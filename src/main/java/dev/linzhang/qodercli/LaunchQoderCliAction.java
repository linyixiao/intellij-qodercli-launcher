package dev.linzhang.qodercli;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Opens a new Qoder CLI session in the project root and launches the Qoder CLI ({@code qodercli})
 * with no extra arguments. The root, rather than the directory of whichever file happens to be open,
 * is what the CLI expects: it files its session history under the directory it was started in and
 * looks for project-level configuration from there, so starting deeper would fragment both.
 *
 * <p>The session goes to the built-in terminal or to an external one according to the plugin's
 * settings; either way it is an ordinary interactive shell with the CLI typed into it as a command
 * line, so the user's own rc files are loaded and the shell prompt survives the CLI exiting.
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

        String workingDir = project.getBasePath();
        if (workingDir == null) {
            return;
        }

        QoderCliLauncher.launch(project, workingDir, QoderCliLauncher.TAB_NAME, QoderCliLauncher.noArgs());
    }
}
