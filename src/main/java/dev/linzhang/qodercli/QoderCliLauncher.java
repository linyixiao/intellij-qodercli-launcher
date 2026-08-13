package dev.linzhang.qodercli;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.terminal.ui.TerminalWidget;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.terminal.TerminalToolWindowManager;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Shared helper that opens (or reuses) a terminal tab and runs {@code qodercli}, optionally with
 * extra command-line arguments (e.g. {@code --attachment <file>} / {@code -i <prompt>}).
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #launch} always opens a <b>new</b> "Qoder CLI" tab. This backs the toolbar / Tools
 *       button, i.e. the explicit "start a fresh session" gesture.</li>
 *   <li>{@link #launchAsk} implements the <b>combined reuse flow</b> for the right-click "Ask
 *       Qoder CLI" tasks so repeated questions continue in the same terminal instead of piling up
 *       new tabs. See that method for the exact routing.</li>
 * </ul>
 *
 * <p>Every tab is an ordinary IDE terminal session, so the user's shell rc files (PATH and any
 * {@code qodercli} function wrapper) are loaded and the tab falls back to the shell prompt after
 * the CLI exits. The {@code qodercli} invocation is typed into that shell as a command line, so
 * every argument is quoted defensively for the target platform's shell.
 */
final class QoderCliLauncher {

    /**
     * The tab title used for every session this plugin opens. Reuse only ever targets tabs we
     * created, tracked per project below.
     */
    static final String TAB_NAME = "Qoder CLI";

    /** How long to wait for a freshly opened tab's shell process to connect before giving up. */
    private static final long SHELL_READY_TIMEOUT_MS = 15_000;

    /** Polling interval while waiting for that shell process. */
    private static final long SHELL_READY_POLL_MS = 100;

    /**
     * Widgets this plugin has opened, per project. Used to find a session to reuse.
     *
     * <p>Every tracked widget is untracked from its own {@code Disposer} callback, which matters
     * twice over: a closed tab stops holding its whole Swing component tree, and the entry stops
     * pinning the project. (A {@link WeakHashMap} whose values reach back to the key never expires
     * on its own — the value keeps the key alive.) The map is also the plugin's liveness test:
     * "tracked" means "we opened it and it has not been disposed".
     */
    private static final Map<Project, List<TerminalWidget>> TRACKED = new WeakHashMap<>();

    private QoderCliLauncher() {
    }

    /**
     * Route a task according to the "继续当前会话" toggle: reuse the running session when it is on
     * (see {@link #launchAsk}), otherwise always open a fresh tab. Call on the EDT.
     *
     * @param args          arguments for a fresh / {@code --continue} launch, e.g. {@code --attachment} and {@code -i}
     * @param injectMessage the single-line equivalent typed into an already-running session
     */
    static void dispatch(@NotNull Project project,
                         @NotNull String workingDir,
                         @NotNull List<String> args,
                         @NotNull String injectMessage) {
        boolean continueSession = ContinueSessionToggleAction.isContinueSession();

        if (QoderCliSettings.effectiveMode() != QoderCliSettings.Mode.BUILT_IN) {
            // The external launcher decides for itself how far it can reuse a window — typing into
            // the session it opened where the terminal can be scripted, and falling back to a new
            // window plus --continue where it cannot.
            if (continueSession) {
                ExternalTerminalLauncher.continueSession(project, workingDir, args, injectMessage);
            } else {
                ExternalTerminalLauncher.launch(project, workingDir, args);
            }
            return;
        }

        if (continueSession) {
            launchAsk(project, workingDir, args, injectMessage);
        } else {
            launch(project, workingDir, TAB_NAME, args);
        }
    }

    /**
     * Open a <b>new</b> terminal in {@code workingDir} and launch {@code qodercli} with
     * {@code qoderCliArgs} appended. Pass an empty list to launch the CLI with no extra arguments.
     * Always creates a fresh tab; this is the explicit "new session" gesture.
     */
    static void launch(@NotNull Project project,
                       @NotNull String workingDir,
                       @NotNull String tabName,
                       @NotNull List<String> qoderCliArgs) {
        if (QoderCliSettings.effectiveMode() != QoderCliSettings.Mode.BUILT_IN) {
            ExternalTerminalLauncher.launch(project, workingDir, qoderCliArgs);
            return;
        }
        launchInBuiltIn(project, workingDir, tabName, qoderCliArgs);
    }

    /**
     * Combined-flow launcher for the "Ask Qoder CLI" tasks. Behaviour:
     * <ol>
     *   <li><b>A Qoder CLI tab exists and {@code qodercli} is still running</b> → inject the task
     *       as a plain message ({@code injectMessage}) into that running session, so it is answered
     *       in the same conversation. The message references the file / snippet by absolute path
     *       because {@code --attachment} cannot be applied to an already-running session.</li>
     *   <li><b>A Qoder CLI tab exists but {@code qodercli} has exited to the shell</b> (idle) →
     *       relaunch in that same tab with {@code --continue} prepended so the previous context is
     *       resumed, this time using {@code qoderCliArgs} (including {@code --attachment}).</li>
     *   <li><b>No reusable tab</b> → open a fresh session, exactly like {@link #launch}.</li>
     * </ol>
     * In the reuse cases the terminal tool window is brought to the front and the target tab is
     * selected.
     */
    static void launchAsk(@NotNull Project project,
                          @NotNull String workingDir,
                          @NotNull List<String> qoderCliArgs,
                          @NotNull String injectMessage) {
        TerminalWidget reuse = findReusable(project);
        if (reuse == null) {
            // Route (3): nothing to reuse — fresh session (we are already on the EDT here).
            launchInBuiltIn(project, workingDir, TAB_NAME, qoderCliArgs);
            return;
        }

        // TerminalWidget.isCommandRunning() asserts it is called off the EDT (it inspects the
        // shell's job state on a background thread). We therefore compute it on a pooled thread and
        // then hop back to the EDT to touch the terminal / tool window, which must happen on the EDT.
        final TerminalWidget widget = reuse;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            boolean running;
            try {
                running = widget.isCommandRunning();
            } catch (Throwable t) {
                // If the state can't be determined, prefer the --continue relaunch path.
                running = false;
            }
            final boolean isRunning = running;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) {
                    return;
                }
                // The user may have closed the tab during the background hop; if so, don't touch a
                // dead widget — open a fresh session instead.
                if (!isTracked(project, widget)) {
                    launchInBuiltIn(project, workingDir, TAB_NAME, qoderCliArgs);
                    return;
                }
                activate(project, widget);
                if (isRunning) {
                    // Route (1): qodercli's interactive UI is still up — feed it the task directly.
                    // This is the common path: qodercli's interactive mode does not return to the
                    // shell between questions, so isCommandRunning() stays true.
                    widget.sendCommandToExecute(injectMessage);
                } else {
                    // Route (2, fallback): qodercli has actually exited back to the shell — resume the
                    // previous context with --continue. Rarely hit, since interactive qodercli keeps
                    // the command "running" while its UI is open.
                    List<String> continueArgs = new ArrayList<>();
                    continueArgs.add("--continue");
                    continueArgs.addAll(qoderCliArgs);
                    widget.sendCommandToExecute(plainQoderCliLine(continueArgs));
                }
            });
        });
    }

    /**
     * Open a brand-new tab in the IDE's own terminal running qodercli, and remember it for later
     * reuse. This bypasses the terminal-mode setting on purpose: it is both the built-in path and
     * the fallback used when an external terminal could not be started.
     */
    static void launchInBuiltIn(@NotNull Project project,
                                @NotNull String workingDir,
                                @NotNull String tabName,
                                @NotNull List<String> qoderCliArgs) {
        // createShellWidget opens a regular interactive shell tab (rc files loaded, and the tab
        // drops back to the prompt when qodercli exits). It is deprecated but public and not marked
        // for removal, unlike the internal createNewSession(..., command, ...) overload.
        @SuppressWarnings("deprecation")
        TerminalWidget widget = TerminalToolWindowManager.getInstance(project)
                .createShellWidget(workingDir, tabName, true, true);
        track(project, widget);
        sendWhenReady(project, widget, plainQoderCliLine(qoderCliArgs));
    }

    /**
     * Type {@code commandLine} into a freshly created terminal tab once its shell process is
     * connected. The shell is started asynchronously, so we poll the widget's TTY connector off the
     * EDT and then deliver the command on the EDT. If the shell does not come up within the timeout
     * the command is sent anyway, so it is never dropped because of our waiting logic.
     */
    private static void sendWhenReady(@NotNull Project project,
                                      @NotNull TerminalWidget widget,
                                      @NotNull String commandLine) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            long deadline = System.currentTimeMillis() + SHELL_READY_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                if (widget.getTtyConnector() != null) {
                    break;
                }
                try {
                    Thread.sleep(SHELL_READY_POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!project.isDisposed() && isTracked(project, widget)) {
                    widget.sendCommandToExecute(commandLine);
                }
            });
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Reuse bookkeeping
    // ---------------------------------------------------------------------------------------------

    private static void track(@NotNull Project project, @NotNull TerminalWidget widget) {
        synchronized (TRACKED) {
            TRACKED.computeIfAbsent(project, k -> new ArrayList<>()).add(widget);
        }
        try {
            // Fires when the user closes the tab (or the project goes away), which is what keeps
            // this map from growing into a leak.
            Disposer.register(widget, () -> untrack(project, widget));
        } catch (Throwable t) {
            // Already disposed, or the widget refuses children: better to forget it than to keep a
            // reference we would never clean up. Reuse then simply opens a new tab next time.
            untrack(project, widget);
        }
    }

    private static void untrack(@NotNull Project project, @NotNull TerminalWidget widget) {
        synchronized (TRACKED) {
            List<TerminalWidget> tracked = TRACKED.get(project);
            if (tracked == null) {
                return;
            }
            tracked.remove(widget);
            if (tracked.isEmpty()) {
                TRACKED.remove(project);
            }
        }
    }

    /** Whether {@code widget} is still one of ours and still alive. */
    private static boolean isTracked(@NotNull Project project, @NotNull TerminalWidget widget) {
        synchronized (TRACKED) {
            List<TerminalWidget> tracked = TRACKED.get(project);
            return tracked != null && tracked.contains(widget);
        }
    }

    /**
     * The most-recently-opened Qoder CLI tab for this project that is still alive, or {@code null}
     * if we have none. Closed tabs have already removed themselves via the {@code Disposer}
     * callback registered in {@link #track}, so whatever is left in the list is open.
     */
    private static @Nullable TerminalWidget findReusable(@NotNull Project project) {
        synchronized (TRACKED) {
            List<TerminalWidget> tracked = TRACKED.get(project);
            if (tracked == null || tracked.isEmpty()) {
                return null;
            }
            return tracked.get(tracked.size() - 1);
        }
    }

    /** Bring the Terminal tool window to the front and select the tab hosting {@code widget}. */
    private static void activate(@NotNull Project project, @NotNull TerminalWidget widget) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal");
        if (toolWindow == null) {
            return;
        }
        JComponent target = widget.getComponent();
        ContentManager contentManager = toolWindow.getContentManager();
        if (target != null) {
            for (Content content : contentManager.getContents()) {
                JComponent host = content.getComponent();
                if (host != null && SwingUtilities.isDescendingFrom(target, host)) {
                    contentManager.setSelectedContent(content);
                    break;
                }
            }
        }
        toolWindow.activate(null, true);
    }

    // ---------------------------------------------------------------------------------------------
    // Command construction
    // ---------------------------------------------------------------------------------------------

    /**
     * A single {@code qodercli <args...>} command line with each argument quoted for the current
     * platform's shell. This line is typed into an interactive shell — the freshly opened tab, or
     * an existing one being reused (route 2).
     */
    static String plainQoderCliLine(@NotNull List<String> qoderCliArgs) {
        boolean windows = isWindows();
        StringBuilder sb = new StringBuilder("qodercli");
        for (String arg : qoderCliArgs) {
            String safe = withoutLineBreaks(arg);
            sb.append(' ').append(windows ? quotePowerShell(safe) : quotePosix(safe));
        }
        return sb.toString();
    }

    /**
     * Strip line breaks from one argument. Callers already flatten the prompts they build, but this
     * is the method that owns the "typed into a shell" channel, where a line break is Enter and cuts
     * the command in half whatever the quoting around it (POSIX shells and PowerShell continue on a
     * new line, cmd.exe just breaks). Deliberately not {@link #singleLine}: that also collapses runs
     * of whitespace, which would quietly rewrite a path containing two spaces in a row.
     */
    private static @NotNull String withoutLineBreaks(@NotNull String arg) {
        return (arg.indexOf('\n') < 0 && arg.indexOf('\r') < 0)
                ? arg
                : arg.replace('\r', ' ').replace('\n', ' ');
    }

    static boolean isWindows() {
        return osName().contains("win");
    }

    /** macOS is the only platform where the AppleScript-driven launchers make sense. */
    static boolean isMac() {
        return osName().contains("mac");
    }

    /** Neither macOS nor Windows — where Ghostty and the emulator probe apply. */
    static boolean isLinux() {
        return !isWindows() && !isMac();
    }

    private static String osName() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    }

    /**
     * Wrap a string in POSIX single quotes so it is passed verbatim to the shell, escaping any
     * embedded single quotes with the standard {@code '\''} idiom. This makes arbitrary content
     * (code snippets, file paths with spaces, etc.) safe as a single argument.
     */
    static String quotePosix(@NotNull String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * Quote a string for PowerShell's single-quoted literal syntax, where an embedded single quote
     * is escaped by doubling it.
     */
    static String quotePowerShell(@NotNull String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    /**
     * Collapse {@code s} onto one physical line. Anything typed into an already-running qodercli
     * session must be a single line: a newline is read as Enter and would submit the message
     * half-written, sending the rest as a second question. Runs of whitespace (including tabs and
     * line breaks) become one space.
     */
    static @NotNull String singleLine(@NotNull String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    /** Convenience: no extra arguments. */
    static List<String> noArgs() {
        return new ArrayList<>();
    }
}
