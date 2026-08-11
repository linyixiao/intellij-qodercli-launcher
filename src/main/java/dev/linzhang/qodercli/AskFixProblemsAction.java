package dev.linzhang.qodercli;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The 「修报错」 task: sends the problems the IDE currently reports for this file (or selection)
 * and asks the CLI to fix them.
 *
 * <p>The entry hides itself when there is nothing to fix, so its presence in the menu is itself a
 * signal that the analyzer has found something.
 */
public final class AskFixProblemsAction extends AbstractAskQoderCliAction {

    @Override
    public void update(@NotNull AnActionEvent e) {
        super.update(e);
        if (!e.getPresentation().isVisible()) {
            return;
        }
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        // Only ask whether there is a first problem: this runs on the EDT every time the menu is
        // built, and a big file can carry thousands of highlighters.
        boolean anyProblem = project != null && editor != null && IdeDiagnostics.hasAny(project, editor);
        e.getPresentation().setEnabledAndVisible(anyProblem);
    }

    @Override
    protected @Nullable String buildPrompt(@NotNull AnActionEvent e,
                                           @NotNull Project project,
                                           boolean hasSelection) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (editor == null) {
            return null;
        }
        List<String> problems = IdeDiagnostics.collect(project, editor);
        if (problems.isEmpty()) {
            return null;
        }
        String scope = hasSelection ? "选中的这段代码" : "这个文件";
        return "IDE 在" + scope + "里报了以下问题，请逐个修复：" + String.join("；", problems);
    }
}
