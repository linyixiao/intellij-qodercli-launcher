package dev.linzhang.qodercli;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * Opens a new terminal tab in the current file's directory and launches the Qoder CLI
 * ({@code qodercli}) with no extra arguments.
 *
 * <p>The tab is an ordinary interactive shell and the CLI is typed into it as a command line, which
 * is what makes this work for everyone: the user's own rc files are loaded, so {@code qodercli} is
 * resolved from the {@code PATH} (or from a shell function) exactly as in a hand-opened terminal,
 * and when the CLI exits the tab drops back to the prompt instead of closing.
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

        QoderCliLauncher.launch(project, workingDir, QoderCliLauncher.TAB_NAME, QoderCliLauncher.noArgs());
    }
}
