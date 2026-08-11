package dev.linzhang.qodercli;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * Top-level 「问 Qoder CLI」 context-menu group. Its entries — the 「继续当前会话」 toggle, the
 * context-sensitive 「修报错」, the preset tasks (解释 / 审查 / 写测试 / 重构 / 补文档 / 优化) and
 * 「自定义…」 — are declared in {@code plugin.xml}, so each one is an ordinary action that can be
 * found in Find Action and bound to a shortcut in Keymap.
 *
 * <p>All this class still does is decide whether the group is shown at all, and word its title
 * after the current selection so the user can see at a glance whether the selected snippet or the
 * whole file will be sent.
 */
public final class AskQoderCliActionGroup extends DefaultActionGroup implements DumbAware {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        // We inspect the editor's selection model, which must be read on the EDT.
        return ActionUpdateThread.EDT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile vf = e.getData(CommonDataKeys.VIRTUAL_FILE);
        boolean enabled = project != null && vf != null && !vf.isDirectory();
        e.getPresentation().setEnabledAndVisible(enabled);
        boolean hasSelection = AbstractAskQoderCliAction.selectedText(e) != null;
        e.getPresentation().setText(hasSelection ? "问 Qoder CLI（选中代码）" : "问 Qoder CLI（当前文件）");
    }
}
