package dev.linzhang.qodercli;

/** The 「补文档」 task. Registered in {@code plugin.xml}, which owns the menu label. */
public final class AskAddDocsAction extends PresetAskAction {

    public AskAddDocsAction() {
        super("为这个文件补充文档注释。",
              "为选中的这段代码补充文档注释。");
    }
}
