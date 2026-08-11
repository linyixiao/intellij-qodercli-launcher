package dev.linzhang.qodercli;

/** The 「解释」 task. Registered in {@code plugin.xml}, which owns the menu label. */
public final class AskExplainAction extends PresetAskAction {

    public AskExplainAction() {
        super("逐步解释这个文件的作用。",
              "逐步解释选中的这段代码的作用。");
    }
}
