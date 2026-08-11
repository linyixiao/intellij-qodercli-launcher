package dev.linzhang.qodercli;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Ask the Qoder CLI to review everything that is currently uncommitted.
 *
 * <p>The plugin does not build the diff itself — it only tells the agent <i>which</i> files changed
 * and lets it run {@code git diff}. That keeps the plugin thin (no diff rendering, no extra VCS
 * APIs) while still solving the real problem: a terminal agent has no idea what you just edited,
 * and asking it to review "my changes" otherwise makes it re-read the whole repository.
 *
 * <p>Registered from {@code qodercli-vcs.xml}, which is only loaded when the IDE has VCS support.
 */
public final class ReviewChangesAction extends AnAction implements DumbAware {

    /** Cap on how many paths we spell out before falling back to a count. */
    private static final int MAX_LISTED = 40;

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        // Only a yes/no answer is needed here, and this runs on every popup — so ask the change
        // list manager and stop there, instead of building the relative-path list.
        e.getPresentation().setEnabledAndVisible(project != null && !affectedFiles(project).isEmpty());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        // Review from the project root: that is where git lives, and where the agent's own
        // `git diff` will produce the expected paths.
        String workingDir = project.getBasePath();
        if (workingDir == null) {
            return;
        }

        List<String> paths = changedFiles(project);
        if (paths.isEmpty()) {
            return;
        }

        String prompt = buildPrompt(paths);
        List<String> args = new ArrayList<>();
        args.add("-i");
        args.add(prompt);

        QoderCliLauncher.dispatch(project, workingDir, args, prompt);
    }

    /** The locally changed files as the VCS reports them, or an empty list if VCS is not ready. */
    private static @NotNull List<VirtualFile> affectedFiles(@NotNull Project project) {
        try {
            return ChangeListManager.getInstance(project).getAffectedFiles();
        } catch (Throwable t) {
            // VCS may not be initialised yet; treat that as "nothing to review".
            return List.of();
        }
    }

    /** Paths of all locally changed files, relative to the project root where possible. */
    private static @NotNull List<String> changedFiles(@NotNull Project project) {
        List<VirtualFile> files = affectedFiles(project);
        if (files.isEmpty()) {
            return List.of();
        }
        String base = project.getBasePath();
        List<String> paths = new ArrayList<>(files.size());
        for (VirtualFile file : files) {
            String path = file.getPath();
            if (base != null && path.startsWith(base + "/")) {
                path = path.substring(base.length() + 1);
            }
            paths.add(path);
        }
        return paths;
    }

    /** A single-line prompt, so it can also be typed into an already-running session. */
    private static @NotNull String buildPrompt(@NotNull List<String> paths) {
        StringBuilder sb = new StringBuilder(
                "\u8bf7 review \u6211\u5f53\u524d\u672a\u63d0\u4ea4\u7684\u6539\u52a8\uff1a\u5148\u6267\u884c git diff"
                        + "\uff08\u5fc5\u8981\u65f6\u518d\u770b git diff --staged\uff09\u67e5\u770b\u5177\u4f53\u5185\u5bb9\uff0c"
                        + "\u91cd\u70b9\u627e bug\u3001\u8fb9\u754c\u60c5\u51b5\u548c\u98ce\u9669\u70b9\u3002"
                        + "\u5171 " + paths.size() + " \u4e2a\u6587\u4ef6\u53d1\u751f\u53d8\u66f4");
        int listed = Math.min(paths.size(), MAX_LISTED);
        sb.append("\uff1a").append(String.join("\u3001", paths.subList(0, listed)));
        if (listed < paths.size()) {
            sb.append("\u7b49\uff08\u5176\u4f59 ").append(paths.size() - listed).append(" \u4e2a\u7701\u7565\uff09");
        }
        sb.append("\u3002");
        return sb.toString();
    }
}
