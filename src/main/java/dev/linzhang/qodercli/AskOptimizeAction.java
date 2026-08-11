package dev.linzhang.qodercli;

/** The 「优化」 task. Registered in {@code plugin.xml}, which owns the menu label. */
public final class AskOptimizeAction extends PresetAskAction {

    public AskOptimizeAction() {
        super("给出这个文件的性能优化建议。",
              "给出选中的这段代码的性能优化建议。");
    }
}
