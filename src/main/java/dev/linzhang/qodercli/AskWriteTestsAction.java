package dev.linzhang.qodercli;

/** The 「写测试」 task. Registered in {@code plugin.xml}, which owns the menu label. */
public final class AskWriteTestsAction extends PresetAskAction {

    public AskWriteTestsAction() {
        super("为这个文件编写单元测试。",
              "为选中的这段代码编写单元测试。");
    }
}
