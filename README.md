# 末影联机 (Minecraft Ender)

![Forge](https://img.shields.io/badge/Forge-1.21.x-DFa86A?style=flat&logo=forge)
![NeoForge](https://img.shields.io/badge/NeoForge-1.21.x-DB6D18?style=flat&logo=neoforge)
![Platform](https://img.shields.io/badge/Platform-Windows%20%7C%20Android-4C8BF5)
![License](https://img.shields.io/badge/License-AGPL_3.0-blue.svg)

**末影联机 (Ender Online)** 是一个轻量级、跨平台的 Minecraft 多人联机模组，旨在为玩家提供简单、零配置的 P2P 联机体验。

本模组集成了高性能的 P2P 后端（基于 [EasyTier](https://github.com/EasyTier/EasyTier)），允许玩家在没有公网 IP 的情况下，通过房间码轻松创建和加入局域网世界。

> ## ⚠️ 已终止 Fabric 支持
>
> 自本版本起，本项目**只支持 Forge 与 NeoForge**，**不再支持 Fabric**。
>
> * **原因**：Fabric 需要独立的映射（Yarn）与 3 个 mixin 注入，导致同一份 UI 与业务逻辑必须在三个模块各维护一份且已产生行为漂移；同时 Forge 与 NeoForge 均提供官方的界面事件钩子，不再需要 mixin。
> * **最后支持 Fabric 的版本**：见 git tag `fabric-eol-v0.1.2`（该 tag 下的 jar 仍可正常使用，但不再接收更新与修复）。
> * **迁移建议**：Fabric 用户请迁移到 Forge 或 NeoForge 版本；存档与世界数据无需转换，配置项名称保持一致。

## ✨ 主要特性

*   **双加载器支持**：同时支持 **Forge** 与 **NeoForge**（当前适配 Minecraft 1.21.1）。
*   **跨平台**：支持 **Windows 桌面** 与 **Android**（PojavLauncher / Amethyst）。
*   **零配置联机**：无需端口映射，无需公网 IP，无需繁琐的服务器搭建。
*   **简单易用**：内置现代化的 GUI 仪表盘，一键创建/加入房间，适配手机竖屏触控。
*   **房间管理**：房主可直接在游戏内管理房间权限（白名单、黑名单、访客权限等）。
*   **自动后端管理**：Windows 端自动下载并管理 P2P 后端进程，开箱即用。

## 📂 项目结构

```
ender_core/      纯 Java，零 Minecraft 依赖（P2P 协议栈、房间服务、后端抽象、工具）
ender_common/    共享源码目录：UI 与业务逻辑（只写 vanilla Mojmap 代码，两端各编译一次）
forge/           Forge 适配层（入口点、事件、渲染桥、配置）
neoforge/        NeoForge 适配层（同上）
```

> `ender_common` **不是 Gradle 子项目**，而是被两端 `sourceSets.main.java.srcDir` 引用的物理源码目录。
> 之所以能这样共享：两端都使用 Mojang 官方映射，但**无法共享字节码**（`net.minecraftforge.*` 与
> `net.neoforged.*` 是两套包）。因此共享代码中禁止出现任何加载器包，详见
> [`claude_docs/00-decisions-and-open-questions.md`](./claude_docs/00-decisions-and-open-questions.md) 的 ADR-13。

## 🛠️ 构建指南

### 环境要求

*   **JDK**: 21
*   **Gradle**: 8.x（项目内置 gradlew）

### 构建命令

**Windows (PowerShell):**
```powershell
# 构建两个版本
.\gradlew.bat build

# 仅构建 Forge 版本
.\gradlew.bat :forge:build

# 仅构建 NeoForge 版本
.\gradlew.bat :neoforge:build
```

**Linux / macOS:**
```bash
# 构建两个版本
./gradlew build

# 仅构建 Forge 版本
./gradlew :forge:build

# 仅构建 NeoForge 版本
./gradlew :neoforge:build
```

构建产物生成在各子模块的 `build/libs/` 目录下。

## 📖 使用说明

1.  **安装模组**：下载对应加载器版本的 `.jar` 文件，放入 Minecraft 的 `mods` 文件夹。
2.  **启动游戏**：进入游戏主界面。
3.  **创建房间（房主）**：
    *   在单人游戏存档内开放到局域网。
    *   打开右上角末影联机开关。
    *   点击"开放到局域网"。
    *   等待后端初始化及连接 P2P 网络。
    *   复制生成的 **房间码** 发送给好友。
4.  **加入房间（玩家）**：
    *   打开多人联机页面。
    *   点击多人联机页面右上角按钮。
    *   点击"加入房间"。
    *   粘贴或输入好友分享的房间码。
    *   点击连接即可加入游戏。

### 📱 Android 说明

Android（PojavLauncher / Amethyst）上的能力边界：

*   作为**加入方**：完整支持，无需额外安装任何东西。
*   作为**房主**：需要设备上已运行 EasyTier（官方 APK 或 Magisk 模块）。未检测到时，创建房间入口会被置灰并说明原因，不会出现"点了没反应"的情况。

## ⚙️ 配置与高级功能

*   **配置文件**：位于 `.minecraft/config/ender_online.json`。
*   **自定义后端**：特殊网络环境下，可在设置中指定自定义的 EasyTier 可执行文件路径或 P2P 节点 URL。

## 🤝 贡献与反馈

欢迎提交 Issue 反馈 Bug 或建议，也欢迎提交 Pull Request 参与开发。参与开发前请先阅读
[`claude_docs/README.md`](./claude_docs/README.md)（文档地图）与
[`claude_docs/10-code-style.md`](./claude_docs/10-code-style.md)（代码规范）。

*   **GitHub**: [EnderOnline](https://github.com/xingchentye/EnderOnline)

## 📄 许可证

本项目代码采用 **GNU Affero General Public License v3.0 (AGPL-3.0)** 许可证。

---
*Powered by EasyTier & EnderOnline Team*
