package dev.linzhang.qodercli;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class for the 「问 Qoder CLI」 context-menu tasks. It resolves the current file and any
 * editor selection, attaches that content to the Qoder CLI (via {@code --attachment}), sends an
 * initial prompt (via {@code -i}) and drops into interactive mode.
 *
 * <p>Subclasses only supply the prompt: preset tasks return a fixed template, while the custom
 * task pops an input dialog. With a non-empty selection the snippet is written to a temporary
 * file and attached; otherwise the whole file is attached.
 *
 * <p>Every subclass is registered in {@code plugin.xml}, so labels live there (and the tasks show
 * up in Find Action and can be given keyboard shortcuts) rather than being passed in here.
 */
public abstract class AbstractAskQoderCliAction extends AnAction implements DumbAware {

    protected AbstractAskQoderCliAction() {
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        // We inspect the editor's selection model, which must be read on the EDT.
        return ActionUpdateThread.EDT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile vf = e.getData(CommonDataKeys.VIRTUAL_FILE);
        e.getPresentation().setEnabledAndVisible(project != null && vf != null && !vf.isDirectory());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile vf = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || vf == null || vf.isDirectory()) {
            return;
        }

        // Launch in the file's own directory, falling back to the project root.
        String workingDir = project.getBasePath();
        VirtualFile parent = vf.getParent();
        if (parent != null) {
            workingDir = parent.getPath();
        }
        if (workingDir == null) {
            return;
        }

        String selection = selectedText(e);
        boolean hasSelection = selection != null;

        // Ask the subclass for the prompt (a preset template, or the text typed into a dialog).
        // A null / blank result means the user cancelled, so do nothing. This must run on the EDT
        // because the custom task shows an input dialog.
        String rawPrompt = buildPrompt(e, project, hasSelection);
        if (rawPrompt == null || rawPrompt.isBlank()) {
            return;
        }
        // Flatten it here, once, because both ways of delivering the prompt end up being typed into
        // a terminal: as an argument of a `qodercli …` command line for a fresh session, or as a
        // message for a session that is already running. A newline is Enter in both channels — it
        // would submit the command half-written (and cmd.exe would not even keep the quoted string
        // together). The custom task is what makes this real: its dialog accepts several lines.
        String prompt = QoderCliLauncher.singleLine(rawPrompt);

        // Capture everything we need, then do the disk IO (writing the selection snippet) off the
        // EDT, and finally hop back to the EDT to open/reuse the terminal.
        final String filepath = vf.getPath();
        final String ext = vf.getExtension();
        final String selectionText = selection;
        final boolean selectionPresent = hasSelection;
        final String finalPrompt = prompt;
        final String finalWorkingDir = workingDir;

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String attachmentPath = filepath;
            if (selectionPresent) {
                File snippet = writeTempSnippet(selectionText, ext);
                if (snippet != null) {
                    attachmentPath = snippet.getAbsolutePath();
                } else {
                    // Degrading to the whole file changes what the CLI is asked about, so say so
                    // instead of quietly answering a different question.
                    QoderCliNotifications.warn(project, "无法写入选区临时文件",
                            "已改为把整个文件交给 Qoder CLI。");
                }
            }

            // Arguments for a fresh (or --continue) launch: attach the file/snippet and send the prompt.
            List<String> args = new ArrayList<>();
            args.add("--attachment");
            args.add(attachmentPath);
            args.add("-i");
            args.add(finalPrompt);

            // When an existing qodercli session is still running we cannot use --attachment, so we
            // feed the task as one plain line that also asks qodercli to read the file itself. The
            // prompt is already flat; flattening again covers the path we just appended.
            final String injectMessage = QoderCliLauncher.singleLine(
                    finalPrompt + " （请读取文件: " + attachmentPath + "）");

            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) {
                    return;
                }
                QoderCliLauncher.dispatch(project, finalWorkingDir, args, injectMessage);
            });
        });
    }

    /**
     * Produce the prompt to send to the CLI, given the triggering event and whether a selection is
     * present. Runs on the EDT, so implementations may touch the editor or show a dialog. Return
     * {@code null} to cancel (e.g. the user dismissed the custom-prompt dialog, or there is nothing
     * to ask about).
     */
    protected abstract @Nullable String buildPrompt(@NotNull AnActionEvent e,
                                                    @NotNull Project project,
                                                    boolean hasSelection);

    /** The current editor selection, or {@code null} if there is no editor / no selection. */
    static @Nullable String selectedText(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (editor == null) {
            return null;
        }
        String text = editor.getSelectionModel().getSelectedText();
        if (text == null || text.isBlank()) {
            return null;
        }
        return text;
    }

    /**
     * Write the selected text to a temporary file (keeping the source file's extension so the
     * model sees the right language) and mark it for deletion when the IDE exits. Returns
     * {@code null} if the file could not be written.
     */
    private static @Nullable File writeTempSnippet(@NotNull String content, @Nullable String ext) {
        try {
            String suffix = (ext != null && !ext.isEmpty()) ? "." + ext : ".txt";
            File tmp = File.createTempFile("qoder-selection-", suffix);
            tmp.deleteOnExit();
            Files.writeString(tmp.toPath(), content, StandardCharsets.UTF_8);
            return tmp;
        } catch (IOException ex) {
            return null;
        }
    }
}
