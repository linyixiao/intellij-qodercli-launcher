package dev.linzhang.qodercli;

/** The 「重构」 task. Registered in {@code plugin.xml}, which owns the menu label. */
public final class AskRefactorAction extends PresetAskAction {

    public AskRefactorAction() {
        super("给出可落地的重构建议来改进这个文件。",
              "给出可落地的重构建议来改进选中的这段代码。");
    }
}
