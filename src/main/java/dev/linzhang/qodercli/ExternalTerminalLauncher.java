package dev.linzhang.qodercli;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.PathEnvironmentVariableUtil;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Launches {@code qodercli} in an <b>external</b> terminal emulator instead of the IDE's terminal
 * tool window, according to {@link QoderCliSettings}.
 *
 * <p>Everything here is plain process spawning, so no terminal-plugin API is involved.
 *
 * <h2>How the command gets there</h2>
 * Prompts contain Chinese text, quotes and, occasionally, whole stack traces, and AppleScript
 * string literals are a poor place to put any of that. So we do the quoting exactly once, into a
 * throwaway script:
 * <ul>
 *   <li>macOS / Linux — a {@code .sh} file holding {@code cd '<dir>' && qodercli '<arg>' …}, which
 *       the terminal's interactive shell <i>sources</i>. Sourcing (rather than running) it means
 *       any {@code qodercli} alias or shell function from the user's rc files still resolves, and
 *       the shell is left sitting in the project directory afterwards.</li>
 *   <li>Windows — a {@code .ps1} file, run with {@code powershell -NoExit -File}.</li>
 * </ul>
 * The AppleScript then only has to mention that path, which we generate and so know contains
 * nothing that needs escaping.
 *
 * <h2>Talking to a session again</h2>
 * On macOS we can go back to a window we opened — except Ghostty, which cannot be scripted:
 * iTerm2 sessions have a stable {@code id} and Terminal.app tabs a stable {@code tty}, and both
 * apps can be told to type into a specific one. We record that identifier per project (see
 * {@link Session}) so follow-up questions land in the same tab, exactly like the embedded
 * terminal does, instead of stacking up new windows.
 *
 * <p>Whether the CLI is still running in there is answered by a <b>marker file</b> that the
 * generated script creates before starting the CLI and deletes after it exits. That beats the
 * alternatives: iTerm2's {@code is at shell prompt} only works if the user installed its shell
 * integration, and inspecting the tty's foreground process group is brittle. A file either exists
 * or it does not.
 *
 * <p>Everywhere else — other platforms, and Ghostty on any of them — there is no comparable
 * scripting interface, so a follow-up question opens a new window and relies on {@code --continue}
 * to restore the conversation. That relaunch runs in the directory the original session started
 * in, because qodercli keeps one session history per launch directory.
 */
final class ExternalTerminalLauncher {

    /** How long to wait for {@code osascript} before assuming something is stuck. */
    private static final int OSASCRIPT_TIMEOUT_MS = 20_000;

    /** Returned by our AppleScripts when the session we were looking for is gone. */
    private static final String MISSING = "MISSING";

    /** Linux emulators we try, in order, for {@link QoderCliSettings.Mode#SYSTEM}. */
    private static final String[] LINUX_TERMINALS = {
            "gnome-terminal", "konsole", "xfce4-terminal", "alacritty", "kitty",
            "x-terminal-emulator", "xterm",
    };

    /** The last session we opened per project, for the reuse flow. */
    private static final Map<Project, Session> SESSIONS = new WeakHashMap<>();

    private ExternalTerminalLauncher() {
    }

    /**
     * A terminal session this plugin opened, tracked so follow-up questions can continue it.
     *
     * <p>Where the terminal can be scripted (iTerm2, Terminal.app) {@code identifier} addresses the
     * very tab again — an iTerm2 session {@code id} or a Terminal.app tab {@code tty}. Where it
     * cannot (Ghostty, custom command, the system terminal off macOS) the identifier is
     * {@code null}: the record still matters, because {@code marker} says whether the CLI is
     * running and {@code workingDir} is where a {@code --continue} relaunch must start.
     *
     * @param mode       which terminal owns it — a session is only reusable while the setting is unchanged
     * @param identifier scriptable handle, or {@code null} when the terminal cannot be addressed
     * @param marker     a file that exists exactly while {@code qodercli} is running in that session
     * @param workingDir the directory the session was launched in; qodercli buckets its session
     *                   history by launch directory, so {@code --continue} only restores this
     *                   conversation when run from here
     */
    private record Session(@NotNull QoderCliSettings.Mode mode,
                           @Nullable String identifier,
                           @NotNull Path marker,
                           @NotNull String workingDir) {
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    // ---------------------------------------------------------------------------------------------
    // Entry points
    // ---------------------------------------------------------------------------------------------

    /**
     * Open a <b>new</b> external terminal in {@code workingDir} running {@code qodercli} with the
     * given arguments. Safe to call from the EDT: the script is written and the process spawned on
     * a pooled thread. If anything goes wrong the user gets a balloon and the session falls back to
     * the IDE's own terminal, so the action never silently does nothing.
     */
    static void launch(@NotNull Project project,
                       @NotNull String workingDir,
                       @NotNull List<String> qoderCliArgs) {
        QoderCliSettings.Mode mode = QoderCliSettings.effectiveMode();
        run(project, workingDir, qoderCliArgs, () -> openNew(project, mode, workingDir, qoderCliArgs));
    }

    /**
     * Continue the conversation. Where the terminal can be scripted (macOS) this behaves like the
     * embedded terminal: the task is typed into the session we already opened — as a plain message
     * while the CLI's UI is up, or as a {@code --continue} relaunch once it has exited back to the
     * shell. Only when that session is gone, or the platform cannot be scripted at all, does a new
     * window appear.
     */
    static void continueSession(@NotNull Project project,
                                @NotNull String workingDir,
                                @NotNull List<String> qoderCliArgs,
                                @NotNull String injectMessage) {
        QoderCliSettings.Mode mode = QoderCliSettings.effectiveMode();
        List<String> continueArgs = new ArrayList<>();
        continueArgs.add("--continue");
        continueArgs.addAll(qoderCliArgs);

        run(project, workingDir, continueArgs, () -> {
            Session session = sessionFor(project, mode);
            if (session == null) {
                // Nothing we opened this IDE session: a fresh window, with --continue so the CLI
                // itself restores whatever conversation belongs to this directory.
                openNew(project, mode, workingDir, continueArgs);
                return;
            }

            if (!supportsReuse(mode)) {
                // No scripting surface to type into (Ghostty, custom command, the system terminal
                // off macOS): the conversation is continued in a new window instead. It must start
                // in the directory the original session ran in — qodercli keeps one session
                // history per launch directory, so only there does --continue find it.
                openNew(project, mode, session.workingDir(), continueArgs);
                return;
            }

            String line;
            if (Files.exists(session.marker())) {
                // The CLI's interactive UI is still up — hand it the task directly. Single line
                // only: a newline would be read as Enter and submit the prompt half-written.
                line = injectMessage;
            } else {
                // It exited back to the shell, so relaunch in that same tab, resuming the context
                // and reusing the marker so the next question can tell the difference again. The
                // relaunch runs in the session's own directory so --continue sees the same history.
                File script = writeLaunchScript(session.workingDir(), continueArgs, session.marker());
                line = sourceCommand(script);
            }

            if (!sendToSession(session, line)) {
                // The user closed that window in the meantime.
                forget(project);
                openNew(project, mode, session.workingDir(), continueArgs);
            }
        });
    }

    /** Only the two AppleScript-driven macOS terminals can be addressed after the fact. */
    private static boolean supportsReuse(@NotNull QoderCliSettings.Mode mode) {
        return mode == QoderCliSettings.Mode.ITERM2
                || (mode == QoderCliSettings.Mode.SYSTEM && QoderCliLauncher.isMac());
    }

    /** Run {@code body} off the EDT, reporting any failure and falling back to the IDE terminal. */
    private static void run(@NotNull Project project,
                            @NotNull String workingDir,
                            @NotNull List<String> qoderCliArgs,
                            @NotNull ThrowingRunnable body) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                fallBackToBuiltIn(project, workingDir, qoderCliArgs, t.getMessage());
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Opening a new session
    // ---------------------------------------------------------------------------------------------

    private static void openNew(@NotNull Project project,
                                @NotNull QoderCliSettings.Mode mode,
                                @NotNull String workingDir,
                                @NotNull List<String> qoderCliArgs) throws Exception {
        Path marker = newMarkerPath();
        File script = writeLaunchScript(workingDir, qoderCliArgs, marker);

        String identifier = null;
        switch (mode) {
            case ITERM2:
                identifier = runAppleScript(newITermTabScript(script));
                break;
            case CUSTOM:
                launchCustom(workingDir, qoderCliArgs, script);
                break;
            case GHOSTTY:
                launchGhostty(workingDir, script);
                break;
            case SYSTEM:
            default:
                if (QoderCliLauncher.isMac()) {
                    identifier = runAppleScript(newMacTerminalTabScript(script));
                } else if (QoderCliLauncher.isWindows()) {
                    launchWindowsTerminal(workingDir, script);
                } else {
                    launchLinuxTerminal(workingDir, script);
                }
                break;
        }
        // Recorded either way: even where there is no handle to script, the record keeps the marker
        // (is the CLI still up?) and the launch directory (where a --continue follow-up must run).
        remember(project, new Session(mode, identifier, marker, workingDir));
    }

    /**
     * Open a tab in the frontmost iTerm2 window (or a new window if there is none), start the CLI
     * there and report back the session id. The first invocation makes macOS ask for permission to
     * control iTerm under System Settings › Privacy &amp; Security › Automation; until that is
     * granted osascript fails, and we surface its message verbatim.
     */
    private static @NotNull String newITermTabScript(@NotNull File script) throws IOException {
        if (findMacApp("iTerm.app") == null) {
            throw new IOException("没有找到 iTerm.app，请先安装 iTerm2 或改用其他终端。");
        }
        return "tell application \"iTerm\"\n"
                + "  activate\n"
                + "  set targetWindow to missing value\n"
                + "  try\n"
                + "    set targetWindow to current window\n"
                + "  end try\n"
                + "  if targetWindow is missing value then\n"
                + "    set targetWindow to (create window with default profile)\n"
                + "  else\n"
                + "    tell targetWindow to create tab with default profile\n"
                + "  end if\n"
                + "  set s to current session of targetWindow\n"
                + "  tell s to write text \"" + escapeAppleScript(sourceCommand(script)) + "\"\n"
                + "  return id of s\n"
                + "end tell\n";
    }

    /** Same for the stock Terminal.app; {@code do script} opens a new window and returns its tab. */
    private static @NotNull String newMacTerminalTabScript(@NotNull File script) {
        return "tell application \"Terminal\"\n"
                + "  activate\n"
                + "  set t to do script \"" + escapeAppleScript(sourceCommand(script)) + "\"\n"
                + "  return tty of t\n"
                + "end tell\n";
    }

    // ---------------------------------------------------------------------------------------------
    // Typing into a session we already opened
    // ---------------------------------------------------------------------------------------------

    /**
     * Bring the recorded session to the front and type {@code line} into it, submitting it as the
     * user would with Enter.
     *
     * @return {@code false} if that session no longer exists, i.e. the window was closed
     */
    private static boolean sendToSession(@NotNull Session session, @NotNull String line) throws Exception {
        String identifier = session.identifier();
        if (identifier == null) {
            // Unreachable in practice: only scriptable terminals get here. Treat as gone.
            return false;
        }
        String applescript = session.mode() == QoderCliSettings.Mode.ITERM2
                ? sendToITermScript(identifier, line)
                : sendToMacTerminalScript(identifier, line);
        return !MISSING.equals(runAppleScript(applescript));
    }

    private static @NotNull String sendToITermScript(@NotNull String sessionId, @NotNull String line) {
        return "set targetID to \"" + escapeAppleScript(sessionId) + "\"\n"
                + "tell application \"iTerm\"\n"
                + "  repeat with w in windows\n"
                + "    repeat with t in tabs of w\n"
                + "      repeat with s in sessions of t\n"
                + "        if id of s is targetID then\n"
                + "          activate\n"
                + "          select w\n"
                + "          select t\n"
                + "          tell s to write text \"" + escapeAppleScript(line) + "\"\n"
                + "          return \"OK\"\n"
                + "        end if\n"
                + "      end repeat\n"
                + "    end repeat\n"
                + "  end repeat\n"
                + "end tell\n"
                + "return \"" + MISSING + "\"\n";
    }

    private static @NotNull String sendToMacTerminalScript(@NotNull String tty, @NotNull String line) {
        return "set targetTTY to \"" + escapeAppleScript(tty) + "\"\n"
                + "tell application \"Terminal\"\n"
                + "  repeat with w in windows\n"
                + "    repeat with t in tabs of w\n"
                + "      if tty of t is targetTTY then\n"
                + "        activate\n"
                + "        set selected of t to true\n"
                + "        set frontmost of w to true\n"
                + "        do script \"" + escapeAppleScript(line) + "\" in t\n"
                + "        return \"OK\"\n"
                + "      end if\n"
                + "    end repeat\n"
                + "  end repeat\n"
                + "end tell\n"
                + "return \"" + MISSING + "\"\n";
    }

    /**
     * Escape a value for an AppleScript string literal. Only backslash and double quote are special
     * there; a line break would end the literal, and since anything typed into the CLI has to be a
     * single line anyway (a newline submits it early), breaks are flattened to spaces.
     */
    static @NotNull String escapeAppleScript(@NotNull String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", " ")
                .replace("\n", " ");
    }

    /**
     * Run an AppleScript from a temporary file rather than {@code osascript -e}, so nothing has to
     * survive an extra round of shell quoting. We wait for the result because the script's output
     * is how the session identifier gets back to us, and its exit code is the only way to learn
     * about a missing automation permission.
     */
    private static @NotNull String runAppleScript(@NotNull String applescript) throws Exception {
        File file = File.createTempFile("qoder-cli-", ".applescript");
        file.deleteOnExit();
        Files.writeString(file.toPath(), applescript, StandardCharsets.UTF_8);

        GeneralCommandLine commandLine =
                new GeneralCommandLine("/usr/bin/osascript", file.getAbsolutePath());
        ProcessOutput output = new CapturingProcessHandler(commandLine).runProcess(OSASCRIPT_TIMEOUT_MS);
        if (output.getExitCode() != 0) {
            String stderr = output.getStderr().trim();
            throw new IOException(stderr.isEmpty() ? "osascript 返回 " + output.getExitCode() : stderr);
        }
        return output.getStdout().trim();
    }

    /** An installed application bundle, looked up in both the system and per-user Applications folders. */
    private static @Nullable File findMacApp(@NotNull String bundleName) {
        File[] candidates = {
                new File("/Applications", bundleName),
                new File(System.getProperty("user.home") + "/Applications", bundleName),
        };
        for (File candidate : candidates) {
            if (candidate.isDirectory()) {
                return candidate;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // Session bookkeeping
    // ---------------------------------------------------------------------------------------------

    private static void remember(@NotNull Project project, @NotNull Session session) {
        synchronized (SESSIONS) {
            SESSIONS.put(project, session);
        }
    }

    private static void forget(@NotNull Project project) {
        synchronized (SESSIONS) {
            SESSIONS.remove(project);
        }
    }

    /** The tracked session, but only while it still belongs to the terminal the user has selected. */
    private static @Nullable Session sessionFor(@NotNull Project project,
                                                @NotNull QoderCliSettings.Mode mode) {
        synchronized (SESSIONS) {
            Session session = SESSIONS.get(project);
            return session != null && session.mode() == mode ? session : null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Ghostty (macOS and Linux)
    // ---------------------------------------------------------------------------------------------

    /**
     * Open a Ghostty window running the generated script. The window runs an interactive shell
     * that sources the script and then {@code exec}s back into an interactive shell, keeping it
     * at a prompt after the CLI exits — the same shape used for the Linux emulators.
     *
     * <p>On macOS the command is passed as a single {@code --initial-command} config flag rather
     * than the usual {@code -e}. The reason is that AppKit treats every bare command-line
     * argument that resolves to a file as "a file to open", and Ghostty (since 1.2.0, the fix for
     * GHSA-q9fg-cpmh-c78x) intercepts every such open with an "Allow Ghostty to execute …?"
     * alert — and on Allow it merely types the filename into a fresh default shell instead of
     * running the command. {@code -e} places the shell path in argv as exactly such a bare file
     * token. {@code --initial-command} keeps the whole command inside one flag-shaped token,
     * which AppKit never treats as a file. Ghostty runs an {@code initial-command} that contains
     * arguments through {@code /bin/sh -c}, and the {@code shell -i -c} wrapper preserves rc-file
     * loading (and therefore the user's PATH) before the script runs.
     * {@code --quit-after-last-window-closed=true} reproduces the other half of what {@code -e}
     * forces, so a finished session does not leave an app behind.
     *
     * <p>On macOS the GUI cannot be launched from the command-line binary directly (Ghostty's own
     * {@code --help} says so); the supported route is {@code open -na Ghostty.app --args …}, which
     * starts a fresh instance per session — exactly the "one window per launch" semantics wanted
     * here. Linux uses the {@code ghostty} binary on the PATH, where plain {@code -e} is fine
     * (no AppKit, and {@code -e} also makes Ghostty quit once the window closes).
     *
     * <p>Ghostty has no scripting interface for addressing a window again (nothing like iTerm's
     * session id). The session is still recorded, so follow-ups at least relaunch
     * {@code --continue} in the directory it started in; but they appear in a new window — see
     * {@link #continueSession}.
     */
    private static void launchGhostty(@NotNull String workingDir, @NotNull File script) throws Exception {
        String shell = loginShell();
        String inner = sourceCommand(script) + "; exec " + shell + " -i";

        if (QoderCliLauncher.isMac()) {
            File app = findMacApp("Ghostty.app");
            if (app == null) {
                throw new IOException("没有找到 Ghostty.app，请先安装 Ghostty（ghostty.org）或在设置里改用其他终端。");
            }
            // One flag-shaped token only: no bare file path in argv, so AppKit has nothing to
            // treat as "a file to open" — the way -e trips Ghostty's execute-confirmation alert.
            // The inner double quotes group the source/exec line into the -c argument once
            // Ghostty hands the value to /bin/sh -c; inner cannot contain double quotes itself,
            // because quotePosix only ever produces single quotes.
            List<String> argv = List.of(
                    "open", "-na", app.getAbsolutePath(), "--args",
                    "--working-directory=" + workingDir,
                    "--quit-after-last-window-closed=true",
                    "--initial-command=" + shell + " -i -c \"" + inner + "\"");
            spawn(argv, workingDir);
            return;
        }

        if (PathEnvironmentVariableUtil.findInPath("ghostty") == null) {
            throw new IOException("没有找到 ghostty 命令，请先安装 Ghostty（ghostty.org）或在设置里改用其他终端。");
        }
        // --working-directory must come before -e: everything after -e belongs to the command.
        List<String> argv = List.of(
                "ghostty", "--working-directory=" + workingDir, "-e", shell, "-i", "-c", inner);
        spawn(argv, workingDir);
    }

    // ---------------------------------------------------------------------------------------------
    // The platform's stock terminal (non-macOS)
    // ---------------------------------------------------------------------------------------------

    /**
     * Prefer Windows Terminal ({@code wt.exe}), which every supported Windows version ships with,
     * and fall back to a detached {@code cmd} window. Either way the actual work is done by
     * PowerShell running our generated script with {@code -NoExit}, so the window stays open at a
     * prompt once the CLI exits.
     */
    private static void launchWindowsTerminal(@NotNull String workingDir, @NotNull File script) throws Exception {
        List<String> powershell = List.of(
                "powershell", "-NoExit", "-ExecutionPolicy", "Bypass", "-File", script.getAbsolutePath());

        if (PathEnvironmentVariableUtil.findInPath("wt.exe") != null) {
            List<String> argv = new ArrayList<>(List.of("wt.exe", "-d", workingDir));
            argv.addAll(powershell);
            spawn(argv, workingDir);
            return;
        }
        List<String> argv = new ArrayList<>(List.of("cmd.exe", "/c", "start", "Qoder CLI"));
        argv.addAll(powershell);
        spawn(argv, workingDir);
    }

    /**
     * Try the common Linux emulators in turn. Each gets the working directory through its own flag
     * and then runs an interactive shell that sources the script and stays alive afterwards, which
     * is the closest equivalent of the embedded terminal's behaviour.
     */
    private static void launchLinuxTerminal(@NotNull String workingDir, @NotNull File script) throws Exception {
        String shell = loginShell();
        String inner = sourceCommand(script) + "; exec " + shell + " -i";

        for (String terminal : LINUX_TERMINALS) {
            if (PathEnvironmentVariableUtil.findInPath(terminal) == null) {
                continue;
            }
            List<String> argv = new ArrayList<>();
            argv.add(terminal);
            switch (terminal) {
                case "gnome-terminal":
                    argv.add("--working-directory=" + workingDir);
                    argv.add("--");
                    break;
                case "konsole":
                    argv.add("--workdir");
                    argv.add(workingDir);
                    argv.add("-e");
                    break;
                case "xfce4-terminal":
                    argv.add("--working-directory=" + workingDir);
                    argv.add("-x");
                    break;
                case "alacritty":
                    argv.add("--working-directory");
                    argv.add(workingDir);
                    argv.add("-e");
                    break;
                case "kitty":
                    argv.add("--directory");
                    argv.add(workingDir);
                    break;
                default:
                    argv.add("-e");
                    break;
            }
            argv.add(shell);
            argv.add("-i");
            argv.add("-c");
            argv.add(inner);
            spawn(argv, workingDir);
            return;
        }
        throw new IOException("没有找到可用的终端程序（已尝试 " + String.join("、", LINUX_TERMINALS)
                + "），请在设置里改用「自定义命令」。");
    }

    // ---------------------------------------------------------------------------------------------
    // User-supplied template
    // ---------------------------------------------------------------------------------------------

    /**
     * Expand the user's template into an argument vector and run it. The template is split on
     * whitespace with double quotes grouping a token, and each placeholder is substituted
     * <i>inside</i> a token, so {@code --working-directory={dir}} stays one argument even when the
     * path contains spaces.
     */
    private static void launchCustom(@NotNull String workingDir,
                                     @NotNull List<String> qoderCliArgs,
                                     @NotNull File script) throws Exception {
        List<String> argv = new ArrayList<>();
        for (String token : tokenize(QoderCliSettings.customTemplate())) {
            argv.add(token
                    .replace("{dir}", workingDir)
                    .replace("{script}", script.getAbsolutePath())
                    .replace("{cmd}", QoderCliLauncher.plainQoderCliLine(qoderCliArgs)));
        }
        if (argv.isEmpty()) {
            throw new IOException("自定义命令为空。");
        }
        spawn(argv, workingDir);
    }

    /** Split a command template into tokens, treating a double-quoted run as a single token. */
    static @NotNull List<String> tokenize(@NotNull String template) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean started = false;
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                started = true;
            } else if (!quoted && Character.isWhitespace(c)) {
                if (started) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    started = false;
                }
            } else {
                current.append(c);
                started = true;
            }
        }
        if (started) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    // ---------------------------------------------------------------------------------------------
    // Shared plumbing
    // ---------------------------------------------------------------------------------------------

    /**
     * Reserve a path for the "the CLI is running" marker. The file itself is created and removed by
     * the generated script, so we start from the absent state.
     */
    private static @NotNull Path newMarkerPath() throws IOException {
        File marker = File.createTempFile("qoder-cli-", ".running");
        marker.deleteOnExit();
        Files.deleteIfExists(marker.toPath());
        return marker.toPath();
    }

    /**
     * Write the throwaway script that actually starts the CLI, bracketed by the marker file so we
     * can later tell whether the CLI is still up. On POSIX the {@code &&} means a failed {@code cd}
     * simply does nothing, which matters because the file is sourced by the user's interactive
     * shell and must never take it down.
     */
    private static @NotNull File writeLaunchScript(@NotNull String workingDir,
                                                   @NotNull List<String> qoderCliArgs,
                                                   @NotNull Path marker) throws IOException {
        String suffix = QoderCliLauncher.isWindows() ? ".ps1" : ".sh";
        File script = File.createTempFile("qoder-cli-", suffix);
        script.deleteOnExit();

        String body;
        if (QoderCliLauncher.isWindows()) {
            String markerLiteral = QoderCliLauncher.quotePowerShell(marker.toString());
            body = "# Generated by the Qoder CLI Launcher IDE plugin. Safe to delete.\r\n"
                    + "New-Item -ItemType File -Force -Path " + markerLiteral + " | Out-Null\r\n"
                    + "try {\r\n"
                    + "  Set-Location -LiteralPath " + QoderCliLauncher.quotePowerShell(workingDir) + "\r\n"
                    + "  " + QoderCliLauncher.plainQoderCliLine(qoderCliArgs) + "\r\n"
                    + "} finally {\r\n"
                    + "  Remove-Item -Force -ErrorAction SilentlyContinue -Path " + markerLiteral + "\r\n"
                    + "}\r\n";
        } else {
            String markerLiteral = QoderCliLauncher.quotePosix(marker.toString());
            body = "# Generated by the Qoder CLI Launcher IDE plugin. Safe to delete.\n"
                    + ": > " + markerLiteral + "\n"
                    + "cd " + QoderCliLauncher.quotePosix(workingDir) + " && "
                    + QoderCliLauncher.plainQoderCliLine(qoderCliArgs) + "\n"
                    + "rm -f " + markerLiteral + "\n";
        }
        Files.writeString(script.toPath(), body, StandardCharsets.UTF_8);
        return script;
    }

    /**
     * The line typed into an interactive shell. {@code source} rather than {@code sh <file>} so the
     * user's aliases, shell functions and PATH tweaks apply, exactly as in the embedded terminal.
     */
    private static @NotNull String sourceCommand(@NotNull File script) {
        return "source " + QoderCliLauncher.quotePosix(script.getAbsolutePath());
    }

    private static @NotNull String loginShell() {
        String shell = System.getenv("SHELL");
        return (shell == null || shell.isBlank()) ? "/bin/bash" : shell;
    }

    /**
     * Start a detached process. We deliberately do not wait for it: a terminal emulator only exits
     * when the user closes the window, and some of them (gnome-terminal) hand off to an already
     * running server and return immediately anyway.
     */
    private static void spawn(@NotNull List<String> argv, @NotNull String workingDir) throws Exception {
        new GeneralCommandLine(argv).withWorkDirectory(workingDir).createProcess();
    }

    /**
     * Tell the user what failed and open the session in the IDE terminal instead, so the action
     * they triggered still happens. Reasons we expect to hit here: the emulator is not installed,
     * macOS automation permission was denied, or a custom template that does not run.
     */
    private static void fallBackToBuiltIn(@NotNull Project project,
                                          @NotNull String workingDir,
                                          @NotNull List<String> qoderCliArgs,
                                          @Nullable String reason) {
        QoderCliNotifications.warn(project, "外部终端启动失败，已改用 IDE 内置终端", reason);
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
                return;
            }
            QoderCliLauncher.launchInBuiltIn(project, workingDir, QoderCliLauncher.TAB_NAME, qoderCliArgs);
        });
    }
}
