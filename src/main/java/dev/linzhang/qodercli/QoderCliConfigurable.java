package dev.linzhang.qodercli;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.util.List;

/**
 * Settings page under <b>Settings | Tools | Qoder CLI Launcher</b>: pick the terminal the CLI is
 * started in, and — for anything we do not support out of the box — supply a command template.
 */
public final class QoderCliConfigurable implements Configurable {

    private ComboBox<QoderCliSettings.Mode> modeBox;
    private JBTextField templateField;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Qoder CLI Launcher";
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return modeBox;
    }

    @Override
    public @Nullable JComponent createComponent() {
        List<QoderCliSettings.Mode> modes = QoderCliSettings.availableModes();
        QoderCliSettings.Mode current = QoderCliSettings.mode();
        // Keep an out-of-place value (e.g. iTerm2 in settings synced from a Mac) selectable rather
        // than silently resetting the user's choice.
        if (!modes.contains(current)) {
            modes.add(current);
        }
        modeBox = new ComboBox<>(modes.toArray(new QoderCliSettings.Mode[0]));
        modeBox.setSelectedItem(current);
        modeBox.addActionListener(e -> syncTemplateEnabled());

        templateField = new JBTextField(QoderCliSettings.customTemplate());
        templateField.getEmptyText().setText("例如：wezterm start --cwd {dir} -- /bin/zsh -ic \"source {script}; exec /bin/zsh -i\"");

        JPanel panel = FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel("启动终端："), modeBox, 1, false)
                .addLabeledComponent(new JBLabel("自定义命令："), templateField, 1, false)
                .addComponentToRightColumn(comment(
                        "占位符：{dir} 工作目录，{script} 临时启动脚本（已含 cd 与 qodercli 及其参数），"
                                + "{cmd} 仅 qodercli 命令行。带空格的参数用英文双引号包成一段。"))
                .addSeparator()
                .addComponent(comment(
                        "「继续当前会话」在 macOS 的 iTerm2 与 Terminal.app 上会回到本插件开的那个标签页继续问；"
                                + "Ghostty、Linux、Windows 与自定义命令新开窗口，并给 CLI 传 --continue 接上上一次的上下文。"
                                + "<br/>macOS 首次使用需在「系统设置 › 隐私与安全性 › 自动化」里允许 IDE 控制终端。"
                                + "<br/>外部终端启动失败时会弹出提示，并自动改用内置终端，操作不会落空。"))
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        panel.setBorder(JBUI.Borders.empty(10));
        syncTemplateEnabled();
        return panel;
    }

    /** The template only matters for the custom mode, so grey it out everywhere else. */
    private void syncTemplateEnabled() {
        templateField.setEnabled(modeBox.getSelectedItem() == QoderCliSettings.Mode.CUSTOM);
    }

    private static @NotNull JBLabel comment(@NotNull String html) {
        JBLabel label = new JBLabel("<html>" + html + "</html>");
        label.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        label.setFontColor(UIUtil.FontColor.BRIGHTER);
        label.setBorder(BorderFactory.createEmptyBorder(2, 0, 6, 0));
        return label;
    }

    @Override
    public boolean isModified() {
        return selectedMode() != QoderCliSettings.mode()
                || !templateField.getText().trim().equals(QoderCliSettings.customTemplate());
    }

    @Override
    public void apply() {
        QoderCliSettings.setMode(selectedMode());
        QoderCliSettings.setCustomTemplate(templateField.getText());
    }

    @Override
    public void reset() {
        modeBox.setSelectedItem(QoderCliSettings.mode());
        templateField.setText(QoderCliSettings.customTemplate());
        syncTemplateEnabled();
    }

    @Override
    public void disposeUIResources() {
        modeBox = null;
        templateField = null;
    }

    private @NotNull QoderCliSettings.Mode selectedMode() {
        Object selected = modeBox.getSelectedItem();
        return selected instanceof QoderCliSettings.Mode
                ? (QoderCliSettings.Mode) selected
                : QoderCliSettings.Mode.BUILT_IN;
    }
}
