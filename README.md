# 末影联机 (Ender Online)

![Forge](https://img.shields.io/badge/Forge-1.21.1-DFa86A?style=flat&logo=forge)
![NeoForge](https://img.shields.io/badge/NeoForge-1.21.1-DB6D18?style=flat&logo=neoforge)
![Java](https://img.shields.io/badge/Java-21-ED8B00?style=flat&logo=openjdk)
![Platform](https://img.shields.io/badge/Platform-Windows%20%7C%20Android-4C8BF5)
![License](https://img.shields.io/badge/License-AGPL_3.0-blue.svg)

**末影联机 (Ender Online)** 是一个面向 Minecraft 的跨平台 P2P 联机模组。它把
[EasyTier](https://github.com/EasyTier/EasyTier) 的组网能力接入游戏，使玩家无需公网 IP、
无需端口映射、也无需搭建独立服务器，即可通过一个**房间码**把局域网世界分享给好友。

---

## 目录

- [特性](#特性)
- [环境要求](#环境要求)
- [快速开始](#快速开始)
- [本地开发与构建](#本地开发与构建)
- [测试与代码质量](#测试与代码质量)
- [配置说明](#配置说明)
- [使用说明](#使用说明)
- [平台支持](#平台支持)
- [项目结构](#项目结构)
- [贡献建议](#贡献建议)
- [许可证](#许可证)

---

## 特性

| 特性 | 说明 |
|---|---|
| **双加载器** | 同时支持 **Forge** 与 **NeoForge**（Minecraft 1.21.1） |
| **零配置联机** | 无需公网 IP、无需端口映射、无需独立服务器 |
| **房间码加入** | 房主生成房间码，好友粘贴即可加入 |
| **游戏内管理** | 房主可在仪表盘中管理白名单、黑名单、禁言列表与访客权限 |
| **后端自动托管** | Windows 端自动下载并启停 P2P 后端进程 |
| **跨平台** | Windows 桌面与 Android（PojavLauncher / Amethyst） |

> ### 关于 Fabric 支持
>
> 本项目**只支持 Forge 与 NeoForge，不再支持 Fabric**。
>
> Fabric 需要独立的 Yarn 映射与多处 Mixin 注入，导致同一份 UI 与业务逻辑必须在三个模块各维护一份，
> 并已出现实际的行为漂移；而 Forge 与 NeoForge 都提供官方的界面事件钩子，不再需要 Mixin。
>
> 最后一个支持 Fabric 的版本见 git tag `fabric-eol-v0.1.2`。该 tag 下的产物仍可正常使用，
> 但不再接收更新与修复。Fabric 用户迁移到 Forge / NeoForge 版本时，存档与世界数据无需转换。

---

## 环境要求

### 运行环境

| 项目 | 要求 |
|---|---|
| Minecraft | **1.21.1**（客户端与服务端均为集成服务器模式） |
| 加载器 | **Forge 52.1.0+** 或 **NeoForge 21.1.218+** |
| Java | **21**（Minecraft 1.21.1 的硬性要求） |
| 操作系统 | Windows 10/11、Linux、macOS；Android 需 PojavLauncher 或 Amethyst |
| 网络 | 能访问公网即可；首次启动需要下载 P2P 后端（约数十 MB） |

### 开发环境

| 项目 | 要求 |
|---|---|
| JDK | **21**（需 `javac`，不接受仅 JRE）。推荐 Temurin / Oracle JDK |
| Gradle | 无需单独安装，使用仓库内置的 **Gradle Wrapper**（8.13） |
| 编辑器 | 任意支持 Gradle 的 Java IDE（IntelliJ IDEA / Eclipse / VS Code） |
| 磁盘 | 首次构建会下载 Minecraft 与加载器依赖，建议预留 3 GB 以上 |

> **务必使用官方映射**：两端都基于 Mojang 官方映射（`mapping_channel=official`）。
> 不要改用 Yarn 或 MCP 映射，否则 `ender_common` 的共享源码将无法同时编译。

---

## 快速开始

### 1. 获取源码

```bash
git clone https://github.com/xingchentye/EnderOnline.git
cd EnderOnline
```

### 2. 构建

Windows（PowerShell / CMD）：

```powershell
.\gradlew.bat build
```

Linux / macOS：

```bash
chmod +x ./gradlew
./gradlew build
```

### 3. 找到产物

构建完成后，两个加载器的 jar 分别位于：

```
forge/build/libs/ender-online-<版本>-forge.jar
neoforge/build/libs/ender-online-<版本>-neoforge.jar
```

### 4. 安装

把与你的加载器匹配的 jar 放入 `.minecraft/mods/`，启动游戏即可。

---

## 本地开发与构建

### 常用任务

```bash
# 构建两端（推荐在提交前执行）
./gradlew build

# 只构建其中一端
./gradlew :forge:build
./gradlew :neoforge:build

# 只做编译校验（比 build 快，适合改动过程中反复执行）
./gradlew :forge:compileJava :neoforge:compileJava

# 启动带模组的客户端（开发调试用）
./gradlew :forge:runClient
./gradlew :neoforge:runClient

# 启动专用服务端
./gradlew :forge:runServer
./gradlew :neoforge:runServer
```

> `ender_core` 是纯 Java 模块，可单独构建与测试：
> `./gradlew :ender_core:build`。它不依赖 Minecraft，因此不会触发加载器的配置阶段。

### 构建缓存

首次构建需要在**配置阶段**解析 Minecraft 与加载器依赖，耗时较长且需要网络。
若遇到依赖解析失败，可先清理再重试：

```bash
./gradlew --stop
./gradlew clean build
```

Windows 上若报缺少 LWJGL native 构件，请确认本地 Maven 仓库或镜像源可访问；
项目在 `settings.gradle` 中配置了镜像源并保留官方源作为回落。

---

## 测试与代码质量

### 单元测试

`ender_core` 的单元测试不依赖 Minecraft，覆盖协议编解码、端口分配、房间码、
重连退避、在途请求表与房间状态存储：

```bash
./gradlew :ender_core:test
```

> 若加载器子项目在配置阶段失败导致整体构建无法启动，可绕过 Gradle 直接运行纯 Java 测试，
> 详见仓库内 `scripts/run-core-tests.ps1`（仅需 JDK 与 Gradle 缓存中的 JUnit）。

### 质量守卫

仓库内置一组「只降不升」的守卫脚本（棘轮基线），覆盖文件体积、注释规范、硬编码文案、
裸线程、静态可变状态、反射调用、加载器渗漏等约束：

```powershell
# 运行全部守卫
./scripts/guards/run-all.ps1

# 只运行其中一项
./scripts/guards/run-all.ps1 -Only format

# 改动使指标下降后，下调基线（需人工确认后再提交）
./scripts/guards/run-all.ps1 -UpdateBaseline
```

CI（`.github/workflows/build.yml`）会在每次 push / PR 时依次执行：
**质量守卫 → 单元测试 → 双端构建矩阵**，任一环节失败即阻断。

### 提交前自检清单

- [ ] `./gradlew :forge:compileJava :neoforge:compileJava` 通过
- [ ] `./gradlew :ender_core:test` 通过
- [ ] `./scripts/guards/run-all.ps1` 无回归
- [ ] 新增/修改的公开方法带中文 Javadoc
- [ ] 新增的 UI 文案走语言文件键，未硬编码

---

## 配置说明

### 玩家配置文件

加载器配置由 Forge / NeoForge 的配置系统管理，文件位于：

```
.minecraft/config/ender-common.toml
```

客户端设置项：

| 配置键 | 默认值 | 说明 |
|---|---|---|
| `client.externalEnderPath` | `""` | 外部后端可执行文件路径。填写后将跳过下载，直接使用该文件 |
| `client.autoUpdate` | `true` | 是否自动更新 P2P 后端 |
| `client.autoStartBackend` | `false` | 进入菜单时是否自动启动后端 |

### 运行时状态文件

房间管理状态（房间名、备注、白名单、黑名单、禁言列表、访客权限与游戏规则）由模组自行持久化：

```
.minecraft/config/ender_room_config.json
```

该文件在房主端写入，用于把房间设置同步给加入方。删除它等同于把房间设置恢复为默认值。

### 网络与后端

- 首次作为房主使用时，模组会下载并启动 EasyTier 后端进程。
- 特殊网络环境下，可通过 `externalEnderPath` 指定自备的后端可执行文件。
- 阶段划分与后端进程信息见本文档末尾的[项目结构](#项目结构)。

---

## 使用说明

### 创建房间（房主）

1. 进入单人存档，按 `Esc` 打开游戏菜单。
2. 选择「对局域网开放」，在右上角把「末影联机」开关打开。
3. 点击「开放到局域网」，等待后端初始化并接入 P2P 网络。
4. 复制生成的**房间码**发送给好友。

### 加入房间（好友）

1. 进入「多人游戏」界面。
2. 点击右上角的末影联机入口。
3. 选择「加入房间」，粘贴或输入房间码。
4. 点击连接，等待进入世界。

### 房间管理（房主）

在仪表盘的房间管理面板中，房主可以：

- 设置房间名称与备注（备注会作为局域网 MOTD 广播）
- 维护白名单、黑名单与禁言列表
- 设置访客权限（可交互 / 仅观战 / 仅聊天 / 禁止进入）
- 调整游戏规则（作弊、PVP、生物生成、天气与时间锁定等）
- 设置重生点与世界边界
- 导入 / 导出房间状态

---

## 平台支持

| 平台 | 作为房主 | 作为加入方 |
|---|---|---|
| **Windows** | ✅ 完整支持（自动下载并托管后端） | ✅ 完整支持 |
| **Linux / macOS** | ✅ 支持（需自备后端或可下载） | ✅ 完整支持 |
| **Android**（PojavLauncher / Amethyst） | ⚠️ 需设备上已运行 EasyTier（官方 APK 或 Magisk 模块）。未检测到时创建入口会置灰并说明原因 | ✅ 完整支持，无需额外安装 |

Android 端界面按触控优化：命中区不小于 48vp，布局按视口单位自适应。

---

## 项目结构

```
EnderOnline/
├── ender_core/      纯 Java，零 Minecraft 依赖
│                    （P2P 协议栈、WebSocket 通信层、后端进程管理、工具）
├── ender_common/    共享源码目录（UI 与业务逻辑，被两端各自编译一次）
├── forge/           Forge 适配层（入口点、事件、渲染桥、配置）
├── neoforge/        NeoForge 适配层（同上）
├── gradle/          版本目录与共享约定
│   ├── libs.versions.toml      依赖与版本的唯一来源
│   └── conventions/            共享源码集接线等约定脚本
└── (本地开发工具)  质量守卫与测试运行器，见「测试与代码质量」
```

### 关于共享源码目录

`ender_common` **不是 Gradle 子项目**，而是被两端 `sourceSets.main.java.srcDir` 引用的物理源码目录。

之所以能共享源码：两端都使用 Mojang 官方映射。之所以**不能**共享字节码：
`net.minecraftforge.*` 与 `net.neoforged.*` 是两套互不兼容的包。

> **因此 `ender_common` 中禁止出现任何加载器包**，只能写 vanilla Mojmap 类型。
> 需要平台差异时，通过适配接口（如 `PlatformConfig`、`UserNotifier`）注入，
> 实现放在各自的加载器模块中。

### 分层依赖方向

```
forge / neoforge  ──▶  ender_common  ──▶  ender_core
     （适配层）            （共享逻辑）        （纯 Java）
```

依赖只能从右向左。`ender_core` 不得引用 Minecraft 或任何加载器 API，
`ender_common` 不得引用加载器 API。

---

## 贡献建议

欢迎 Issue 与 Pull Request。提交前请阅读以下约定。

### 工作流

1. **Fork** 本仓库，从 `1.21` 分支创建特性分支：
   `git checkout -b feat/简短描述`
2. 完成改动后，按[提交前自检清单](#提交前自检清单)在本地验证。
3. 向 `1.21` 分支发起 Pull Request，并在描述中说明：
   - 改动的目的与范围
   - 验证方式（执行的命令与结果）
   - 若涉及行为变更，说明影响面与回归风险

### 提交信息规范

采用 [Conventional Commits](https://www.conventionalcommits.org/)，**英文命令式**主题行：

```
<类型>(<范围>): <简短命令式描述>

<正文：为什么这样改，而不是重复罗列改了什么>
```

常用类型与范围：

| 类型 | 用途 |
|---|---|
| `feat` | 新功能 |
| `fix` | 缺陷修复 |
| `refactor` | 不改变行为的重构 |
| `perf` | 性能优化 |
| `docs` | 文档 |
| `chore` | 构建、依赖、工具链 |

范围示例：`core`、`common`、`forge`、`neoforge`、`ui`、`ci`、`guards`。

> 正文写「为什么」，主题行写「做了什么」。避免在提交信息里堆砌无信息量的改动清单。

### 代码规范

- **注释用中文，字符串用英文，UI 文案一律走语言文件键。**
  Java 代码中不得出现中文字符串字面量（语言文件与测试断言除外）。
- 每个文件带文件头注释说明职责；公开类型与公开方法必须有中文 Javadoc。
- 注释中不得出现 `@author`、日期或 HTML 标签。
- 命名使用能表达意图的完整单词，避免缩写与拼音。

### 架构约束（会被守卫拦截）

| 约束 | 说明 |
|---|---|
| 分层依赖 | `ender_core` 零 Minecraft 依赖；`ender_common` 零加载器依赖 |
| 禁止反射发现 | 不使用 `Class.forName(` / `getMethod(` 做平台探测，改走适配接口 |
| 线程有主 | 禁止裸 `new Thread(`；线程与外部进程必须有明确的生命周期所有者 |
| 状态归实例 | 可变状态归实例；非 `final` 的静态可变字段需有理由 |
| 错误不泄漏 | UI 不得显示异常 `getMessage()`，只显示错误码对应的本地化文案 |
| 布局自适应 | UI 不使用固定像素定位，一律走布局引擎与视口单位 |
| 守卫先行 | 任何重构阶段开工前，其守卫必须已生效；基线**只降不升** |

### 代码审查要点

提交 PR 时请预期以下问题：

- 是否引入了重复实现（同一逻辑在多个模块各写一份）？
- 是否把可变状态散落在多处，而不是收敛到一个所有者？
- 新增的公开 API 是否必要，能否收窄可见性？
- 是否为新拆出的类补了单元测试？
- 是否同步更新了受影响的文档与基线数字？

---

## 许可证

本项目代码采用 **GNU Affero General Public License v3.0 (AGPL-3.0)**，详见 [LICENSE](./LICENSE)。

第三方组件：

| 组件 | 许可证 |
|---|---|
| [EasyTier](https://github.com/EasyTier/EasyTier) | LGPL-3.0 |
| Java-WebSocket | MIT |
| Gson | Apache-2.0 |
| SLF4J | MIT |
| Apache Commons Compress | Apache-2.0 |

---

*Powered by EasyTier & the EnderOnline contributors.*
