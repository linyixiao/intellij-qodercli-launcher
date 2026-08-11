# Qoder CLI Launcher

An IntelliJ Platform plugin that adds a toolbar button to launch the
[Qoder CLI](https://qoder.com) (`qodercli`) in the IDE's embedded terminal.

Clicking the button opens a terminal tab named **Qoder CLI** in the current
file's directory and runs `qodercli`. After the CLI exits, the tab drops back
to an interactive shell so it stays open for further use. Works on macOS, Linux
and Windows.

The same command is also available as **Tools → 启动 Qoder CLI**, which is the
entry point to use if you are on the Classic UI (where the New UI main toolbar
does not exist).

Menu labels are in Chinese, matching the Qoder CLI itself; the English names
below are given only for orientation.

You can also **right-click a file** in the editor or the Project view and open the
**问 Qoder CLI** submenu to pick a task — 解释 (explain), 审查 (review), 写测试
(write tests), 重构 (refactor), 补文档 (add docs), 优化 (optimize), or **自定义…**
(type your own prompt). The chosen file is attached as context; if you have code
selected in the editor, only that snippet is sent (written to a temporary file and
passed via `--attachment`). The CLI opens with the task's prompt and stays
interactive so you can keep asking follow-up questions. A **继续当前会话**
("continue current session") toggle at the top of the submenu controls whether
tasks continue in the existing Qoder CLI terminal or always open a new one. Every
entry is a normal action, so you can also reach it from Find Action or bind it to
a shortcut in Keymap.

Three entries hand the CLI something it cannot see from a terminal:

- **修报错** appears in the same submenu whenever the IDE reports errors or
  warnings in the current file (or selection). It sends that list — the very
  squiggles you are looking at — and asks the CLI to fix them. When the file is
  clean the entry is hidden, so its presence is itself a signal.
- **用 Qoder CLI 解释报错** is available when you right-click inside a Run or Debug
  console. It sends the selected output, or the tail of the console when nothing
  is selected, as an attached file, and names the `Foo.java:42` locations it found
  so the CLI starts from the right sources.
- **让 Qoder CLI review 未提交的改动** sits in the Tools menu and in the Local
  Changes view. It tells the CLI which files you touched and lets it run
  `git diff` itself, so no diff is ever pasted around.

This is an independent helper that only *launches* a separately installed Qoder
CLI — it does not bundle the CLI itself.

## Prerequisites

- The Qoder CLI (`qodercli`) must be installed and available on your `PATH`.
- An IntelliJ-based IDE with the bundled **Terminal** plugin enabled.
- IDE version **2025.1 (build 251)** or newer — the terminal API used to launch a
  custom command is not available in earlier builds.

## Build

The project compiles against a locally installed IntelliJ IDEA (using that IDE's
own jars and bundled JBR), so no IDE download is needed:

```bash
./gradlew clean buildPlugin
```

Common install locations are detected automatically. If your IDE lives elsewhere,
point the build at it explicitly:

```bash
./gradlew clean buildPlugin -PideaHome="/path/to/IntelliJ IDEA.app/Contents"
# or: export IDEA_HOME="/path/to/idea"
```

The installable plugin zip is produced at
`build/distributions/qodercli-launcher-1.1.0.zip`.

On macOS/Linux, if `./gradlew` fails with *Permission denied*, restore the
executable bit once with `chmod +x gradlew`.

## Install

In your IDE: **Settings → Plugins → ⚙ → Install Plugin from Disk…** and pick the
zip above.

## License

Released under the [MIT License](LICENSE).

---

# Qoder CLI Launcher（中文）

一个基于 IntelliJ 平台的插件，在工具栏添加一个按钮，用于在 IDE 内置终端中启动
[Qoder CLI](https://qoder.com)（`qodercli`）。

点击按钮会在当前文件所在目录打开一个名为 **Qoder CLI** 的终端标签页并运行
`qodercli`；命令结束后回落到交互式 shell，标签页保持打开以便继续使用。支持
macOS、Linux 和 Windows。

同一条命令也可从 **Tools → 启动 Qoder CLI** 调用；如果你使用的是 Classic UI（旧界面，
没有新版主工具栏），请从这里进入。

你还可以在编辑器或项目视图中**右键某个文件**，打开 **问 Qoder CLI** 子菜单挑选任务
——解释、审查、写测试、重构、补文档、优化，或 **自定义…**（自己输入提问）。
选中的文件会作为上下文附件带入；若在编辑器中选中了代码，则只发送该片段
（写入临时文件，通过 `--attachment` 传入）。CLI 会带着该任务的提问打开并停留在交互模式，
便于你继续追问。子菜单顶部的 **继续当前会话** 开关用于控制任务是在已有的 Qoder CLI
终端里接着问，还是每次都新开一个会话。每个任务都是独立 action，也可以在 Find Action
里搜索，或在 Keymap 里绑定快捷键。

还有三个入口，负责把「终端里看不到」的信息交给 CLI：

- **修报错**：当 IDE 在当前文件（或选区）里报出 error / warning 时，子菜单里会多出这一项，
  把你正看着的那些波浪线整理成清单交给 CLI 修复；文件没问题时该项自动隐藏，出现与否本身
  就是个信号。
- **用 Qoder CLI 解释报错**：在运行 / 调试控制台里右键即可使用。它会把选中的输出（没选就取
  末尾若干行）作为附件交给 CLI，并在提示语里点名从文本里识别出的 `Foo.java:42` 位置，
  省去它满仓库找文件。
- **让 Qoder CLI review 未提交的改动**：位于 Tools 菜单和 Local Changes 面板。插件只告诉 CLI
  哪些文件改了，具体内容由它自己执行 `git diff` 获取，无需把 diff 粘来粘去。

本插件是一个独立的辅助工具，只负责**启动**你单独安装好的 Qoder CLI，不内置 CLI 本体。

## 使用前提

- 已安装 Qoder CLI（`qodercli`），且其在系统 `PATH` 中可直接调用。
- 基于 IntelliJ 的 IDE，并已启用内置的 **Terminal** 插件。
- IDE 版本为 **2025.1（build 251）** 或更高——用于启动自定义命令的终端 API 在更早版本中不存在。

## 构建

项目直接针对本地安装的 IntelliJ IDEA 编译（复用该 IDE 自带的 jar 与内置 JBR），
无需下载 IDE：

```bash
./gradlew clean buildPlugin
```

常见安装路径会自动识别。若你的 IDE 装在别处，显式指定即可：

```bash
./gradlew clean buildPlugin -PideaHome="/path/to/IntelliJ IDEA.app/Contents"
# 或：export IDEA_HOME="/path/to/idea"
```

产物 zip 位于 `build/distributions/qodercli-launcher-1.1.0.zip`。

在 macOS/Linux 上，若 `./gradlew` 报 *Permission denied*，执行一次
`chmod +x gradlew` 补上可执行权限即可。

## 安装

在 IDE 中依次打开 **Settings → Plugins → ⚙ → Install Plugin from Disk…**，选择上面的 zip。

## 许可

基于 [MIT 许可证](LICENSE) 发布。
