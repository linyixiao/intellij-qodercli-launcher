package dev.linzhang.qodercli;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A preset task: a fixed prompt, phrased one way for a whole file and another way for a selected
 * snippet. Subclasses exist so each task can be registered in {@code plugin.xml} with its own id,
 * which is what makes them searchable in Find Action and bindable to a keyboard shortcut.
 */
public abstract class PresetAskAction extends AbstractAskQoderCliAction {

    private final String promptForFile;
    private final String promptForSnippet;

    protected PresetAskAction(@NotNull String promptForFile, @NotNull String promptForSnippet) {
        this.promptForFile = promptForFile;
        this.promptForSnippet = promptForSnippet;
    }

    @Override
    protected @Nullable String buildPrompt(@NotNull AnActionEvent e,
                                           @NotNull Project project,
                                           boolean hasSelection) {
        return hasSelection ? promptForSnippet : promptForFile;
    }
}
