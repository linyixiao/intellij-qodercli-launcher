# Qoder CLI Launcher

An IntelliJ Platform plugin that adds a toolbar button to launch the
[Qoder CLI](https://qoder.com) (`qodercli`) in the IDE's embedded terminal.

Clicking the button opens a terminal tab named **Qoder CLI** in the current
file's directory and runs `qodercli`. After the CLI exits, the tab drops back
to an interactive shell so it stays open for further use. Works on macOS, Linux
and Windows.

The same command is also available as **Tools → Launch Qoder CLI**, which is the
entry point to use if you are on the Classic UI (where the New UI main toolbar
does not exist).

This is an independent helper that only *launches* a separately installed Qoder
CLI — it does not bundle the CLI itself.

## Prerequisites

- The Qoder CLI (`qodercli`) must be installed and available on your `PATH`.
- An IntelliJ-based IDE with the bundled **Terminal** plugin enabled.
- IDE version **2024.2 (build 242)** or newer.

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
`build/distributions/qodercli-launcher-1.0.0.zip`.

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

同一条命令也可从 **Tools → Launch Qoder CLI** 调用；如果你使用的是 Classic UI（旧界面，
没有新版主工具栏），请从这里进入。

本插件是一个独立的辅助工具，只负责**启动**你单独安装好的 Qoder CLI，不内置 CLI 本体。

## 使用前提

- 已安装 Qoder CLI（`qodercli`），且其在系统 `PATH` 中可直接调用。
- 基于 IntelliJ 的 IDE，并已启用内置的 **Terminal** 插件。
- IDE 版本为 **2024.2（build 242）** 或更高。

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

产物 zip 位于 `build/distributions/qodercli-launcher-1.0.0.zip`。

在 macOS/Linux 上，若 `./gradlew` 报 *Permission denied*，执行一次
`chmod +x gradlew` 补上可执行权限即可。

## 安装

在 IDE 中依次打开 **Settings → Plugins → ⚙ → Install Plugin from Disk…**，选择上面的 zip。

## 许可

基于 [MIT 许可证](LICENSE) 发布。
