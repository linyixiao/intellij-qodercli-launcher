package dev.linzhang.qodercli;

/** The 「审查」 task. Registered in {@code plugin.xml}, which owns the menu label. */
public final class AskReviewAction extends PresetAskAction {

    public AskReviewAction() {
        super("审查这个文件，找出 bug、边界情况和潜在隐患。",
              "审查选中的这段代码，找出 bug、边界情况和潜在隐患。");
    }
}
