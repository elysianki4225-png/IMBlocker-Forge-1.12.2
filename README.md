# IMBlocker — Forge 1.12.2（实验版）

[IMBlocker](https://github.com/reserveword/IMBlocker) 的非官方 Forge 1.12.2 移植尝试：在游戏界面关闭输入法，在检测到文字输入框时重新启用。客户端模组，无需服务器安装。

> **重要：尚未完成全部平台移植。** 当前只有 Linux 命令行后端；Windows、macOS 后端会输出警告并保持不活动。实验 jar 仅用替身 API 编译并做过简单模拟测试，**未在真实 Minecraft / Forge 或真实输入法环境中测试**，不应视为稳定版本。尤其不适合需要 Windows 输入法支持的玩家。

## 安装与使用

1. 安装 Minecraft 1.12.2、Java 8 和 Forge 14.23.5.2860 或更高的 1.12.2 版本。
2. 将 [`IMBlocker-Forge-1.12.2-1.0.0-experimental.jar`](releases/IMBlocker-Forge-1.12.2-1.0.0-experimental.jar) 放入客户端 `mods` 文件夹。先备份设置并在测试环境验证。
3. Linux 需安装并运行 Fcitx 5、Fcitx 4 或 IBus，且对应的 `fcitx5-remote`、`fcitx-remote` 或 `ibus` 命令须在 PATH 中。程序按此顺序尝试检测；如果安装了多个框架，请检查日志确认检测结果。
4. 默认按 **F8** 暂停/恢复管理，可在游戏的控制设置中修改。关闭游戏后编辑 `config/imblocker.cfg`，重启游戏生效。

Linux 工具可能更改的是**桌面全局**输入法状态，而非仅游戏窗口；切换窗口和退出游戏时可能需要手动恢复。IBus 默认切换到 `libpinyin`，请根据 `ibus list-engine` 的结果修改 `ibusOnArg`。不同桌面、Wayland 和输入法配置可能不兼容。

## 配置

首次启动生成 UTF-8 属性文件 `config/imblocker.cfg`：

- `enabled`：总开关；`debug`：输出诊断信息。
- `fcitx5OnArg` / `fcitx5OffArg`、`fcitxOnArg` / `fcitxOffArg`：Fcitx 开关参数，默认 `-o` / `-c`。
- `ibusOnArg` / `ibusOffArg`：`ibus engine` 的目标输入法，默认 `libpinyin` / `xkb:us::eng`。
- `extraTypingScreens`：直接处理文字输入的界面完整类名，逗号分隔；会替换默认列表。常规 `GuiTextField` 则通过焦点检测，无需添加。
- `englishForCommands`、`commandPrefix`：预留的命令模式设置；当前 Linux 后端不切换转换模式，Windows 后端未实现。`ibusOnState` 目前未使用。

文字输入检测每客户端 tick 扫描当前界面中直接保存的 `GuiTextField`，以及默认白名单中的告示牌、书等界面。嵌套组件、自定义输入框、复杂模组 GUI 可能无法检测；请使用 F8 暂停或添加适当的界面到白名单。配置文件不是 Forge 配置 GUI，修改后需重启。

## 从源码构建

使用 JDK 8 与 Gradle 4.9（此项目未包含 Gradle wrapper），并允许访问 Forge Maven 和 Maven Central：

```sh
gradle setupDecompWorkspace
gradle build
```

构建产物位于 `build/libs/`。ForgeGradle 2.3 是旧工具链，需要下载历史依赖；此环境无法访问 Forge Maven，因此上述正式构建尚未验证。仓库内的实验 jar 是以 Eclipse Java 编译器和临时 Forge/Minecraft API 替身编译的 Java 7 字节码，仅包含模组类及资源，不包含替身或 Forge。做过模拟界面焦点变化的冒烟测试，但不等于 Forge 兼容性或真实平台测试。

## 已知不足 / 后续工作

- 实现并实机验证 Windows 同线程原生 IME 桥接和 macOS 支持；不要使用外部进程跨线程操作窗口的 IMM 上下文。
- 在 Forge 1.12.2 实际客户端验证加载、混淆名称、按键、各类界面和输入法转换；增加自动化回归测试。
- 改善 Linux 框架检测、输入法原状态恢复及多窗口行为；完善模组 GUI 兼容。
- 当前移植不包含原项目的全部功能（如原生候选框/预编辑界面）。

## English

This is an **experimental, unofficial** Forge 1.12.2 port of [IMBlocker](https://github.com/reserveword/IMBlocker). Linux support invokes Fcitx or IBus command-line tools; Windows and macOS are currently inactive. The supplied jar was compiled against temporary API stubs and smoke-tested with mock screens, **not tested in Minecraft, Forge, or with real input methods**. Linux commands may change the desktop-wide IME. Install the jar in the client `mods` directory, use F8 to suspend/resume, and edit `config/imblocker.cfg` with the game closed. Build from source with JDK 8, Gradle 4.9, and access to Forge Maven using the commands above; the ForgeGradle build has not been verified in this environment.

## 许可 / License

GPL-3.0-or-later；详见 [LICENSE](LICENSE)。原项目：[reserveword/IMBlocker](https://github.com/reserveword/IMBlocker)，感谢原作者及图标作者；本项目为非官方移植。
