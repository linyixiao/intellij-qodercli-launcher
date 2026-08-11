package dev.linzhang.qodercli;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The 「自定义…」 task: pops a multiline input dialog and sends whatever the user types.
 *
 * <p>The dialog deliberately accepts several lines — questions about code often need them. The
 * base class flattens the text before typing it into an already-running session, where a newline
 * would be read as Enter.
 */
public final class AskCustomAction extends AbstractAskQoderCliAction {

    @Override
    protected @Nullable String buildPrompt(@NotNull AnActionEvent e,
                                           @NotNull Project project,
                                           boolean hasSelection) {
        String message = hasSelection
                ? "你想针对选中的这段代码问什么？"
                : "你想针对这个文件问什么？";
        return Messages.showMultilineInputDialog(
                project, message, "问 Qoder CLI", "", Messages.getQuestionIcon(), null);
    }
}
