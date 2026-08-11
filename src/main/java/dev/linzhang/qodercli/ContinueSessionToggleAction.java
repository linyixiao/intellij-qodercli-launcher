package dev.linzhang.qodercli;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.project.DumbAware;
import org.jetbrains.annotations.NotNull;

/**
 * A checkbox menu item that controls whether the 「问 Qoder CLI」 tasks continue in the current
 * terminal session (checked, the default) or always open a brand-new session (unchecked).
 *
 * <p>The choice is persisted application-wide via {@link PropertiesComponent}, so it survives IDE
 * restarts and is shared across projects. The menu label lives in {@code plugin.xml}.
 */
public final class ContinueSessionToggleAction extends ToggleAction implements DumbAware {

    private static final String KEY = "dev.linzhang.qodercli.ask.continueSession";

    /**
     * Whether 「问 Qoder CLI」 tasks should reuse the current session. Defaults to {@code true}
     * (reuse via the combined flow) when the user has never toggled it.
     */
    static boolean isContinueSession() {
        return PropertiesComponent.getInstance().getBoolean(KEY, true);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public boolean isSelected(@NotNull AnActionEvent e) {
        return isContinueSession();
    }

    @Override
    public void setSelected(@NotNull AnActionEvent e, boolean state) {
        // Store only when it differs from the default so the properties file stays clean.
        PropertiesComponent.getInstance().setValue(KEY, state, true);
    }
}
