package dev.linzhang.qodercli;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.terminal.TerminalToolWindowManager;

import java.util.Arrays;
import java.util.List;

/**
 * Opens a new terminal tab in the current file's directory and launches the Qoder CLI
 * ({@code qodercli}). The command is chosen per OS (login+interactive shell on
 * macOS/Linux, PowerShell on Windows) so any user who has {@code qodercli} on their PATH
 * can use it; after the CLI exits the tab drops back to an interactive shell instead of
 * closing.
 */
public class LaunchQoderCliAction extends AnAction implements DumbAware {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        // Without a project there is no directory to launch in, so don't offer the button.
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }

        // Resolve the directory: selected dir, or the parent of the selected file,
        // falling back to the project base path.
        String workingDir = project.getBasePath();
        VirtualFile vf = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (vf != null) {
            VirtualFile dir = vf.isDirectory() ? vf : vf.getParent();
            if (dir != null) {
                workingDir = dir.getPath();
            }
        }
        if (workingDir == null) {
            return;
        }

        List<String> command = buildCommand();

        TerminalToolWindowManager.getInstance(project)
                .createNewSession(workingDir, "Qoder CLI", command, true, true);
    }

    /**
     * Build the shell invocation that starts {@code qodercli} and then drops back to
     * an interactive shell (so the tab stays open after the CLI exits). The command is
     * chosen per OS so the plugin works for any user who has qodercli on their PATH,
     * not just macOS + zsh.
     */
    private static List<String> buildCommand() {
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("win")) {
            // Windows: run via PowerShell, then hand control back to an interactive shell.
            return Arrays.asList(
                    "powershell.exe",
                    "-NoExit",
                    "-Command",
                    "qodercli"
            );
        }

        // macOS / Linux: use the user's login shell as an interactive login shell so
        // rc files (PATH, and any qodercli function wrapper) are loaded; exec back into
        // the same shell after qodercli exits so the tab stays usable.
        String shell = System.getenv("SHELL");
        if (shell == null || shell.isEmpty()) {
            shell = "/bin/bash";
        }
        return Arrays.asList(
                shell,
                "--login",
                "-i",
                "-c",
                "qodercli; exec \"" + shell + "\" -i"
        );
    }
}
