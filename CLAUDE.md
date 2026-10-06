# CLAUDE.md — 末影联机 (EnderOnline) 项目指令

## 项目概述

**末影联机 (Ender Online)** 是一个跨平台 Minecraft 多人联机模组，基于 [EasyTier](https://github.com/EasyTier/EasyTier)
提供零配置 P2P 联机：玩家通过房间码创建/加入局域网世界，无需公网 IP。

- 加载器：**Forge / NeoForge**（MC 1.21.1）——**已终止 Fabric 支持**，见下方「范围」
- 语言/构建：Java 21 + Gradle 8.x
- 目标平台：**Windows 桌面** 与 **Android（PojavLauncher / Amethyst）**

## 范围（务必先确认）

| 项 | 状态 |
|---|---|
| Forge | ✅ 支持 |
| NeoForge | ✅ 支持 |
| **Fabric** | ❌ **已终止支持**（[ADR-00](./claude_docs/00-decisions-and-open-questions.md)）。最后一个支持版本见 git tag `fabric-eol-v0.1.2` |
| Android | 加入方完整支持；作房主需设备已运行 EasyTier（APK / Magisk） |

> 终止 Fabric 的依据、删除范围与保留项见
> [`claude_docs/01-architecture-plan.md`](./claude_docs/01-architecture-plan.md) §4 与
> [`claude_docs/baseline-audit.md`](./claude_docs/baseline-audit.md) §6.2。

## 模块结构

```
ender_core/      纯 Java，零 Minecraft 依赖（协议栈、房间服务、后端抽象、工具）
ender_common/    ★ 目标新增：共享源码目录（UI/业务逻辑），被两端各自编译
forge/ neoforge/ 仅加载器适配（入口点、事件、渲染桥、ScreenHooks、配置）
```

> **重要（ADR-13，最容易踩的坑）**：`ender_common` **不是 Gradle 子项目**，而是被两端
> `sourceSets.main.java.srcDir` 引用的物理源码目录。
> 两端**都使用 Mojang 官方映射**，但**无法共享字节码**（`net.minecraftforge.*` 与 `net.neoforged.*` 是两套包）。
> 因此共享代码中**禁止出现任何加载器包**，只能写 vanilla Mojmap 类型。

## 详细文档

详细架构、方案与执行计划全部位于 **[`claude_docs/`](./claude_docs)**。
**每次收到新任务，先读 [`claude_docs/README.md`](./claude_docs/README.md)（含文档地图），再按需展开。**

| 文档 | 内容 |
|---|---|
| [`claude_docs/README.md`](./claude_docs/README.md) | 文档索引与「怎么用这套文档」 |
| [`claude_docs/baseline-audit.md`](./claude_docs/baseline-audit.md) | 实测基线（规模/技术债/真实缺陷/根因/删除清单）——**动手前必读** |
| [`claude_docs/00-decisions-and-open-questions.md`](./claude_docs/00-decisions-and-open-questions.md) | ADR-00/01/03~15（ADR-02 作废）+ 裁决状态 |
| [`claude_docs/01-architecture-plan.md`](./claude_docs/01-architecture-plan.md) | 目标架构、共享源集、**终止 Fabric**、构建收敛 |
| [`claude_docs/02-performance-plan.md`](./claude_docs/02-performance-plan.md) | 线程/生命周期、内存、网络、性能预算 |
| [`claude_docs/03-protocol-plan.md`](./claude_docs/03-protocol-plan.md) | 协议 v2（帧格式、握手、差量；不做 v1 双栈） |
| [`claude_docs/04-uiux-plan.md`](./claude_docs/04-uiux-plan.md) | 设计系统、响应式布局、触控、i18n |
| [`claude_docs/05-cross-platform-plan.md`](./claude_docs/05-cross-platform-plan.md) | Android + Windows 双端适配 |
| [`claude_docs/06-logic-and-code-quality.md`](./claude_docs/06-logic-and-code-quality.md) | 状态所有权、错误模型、God Class 拆分、13 组重复消除 |
| [`claude_docs/07-testing-plan.md`](./claude_docs/07-testing-plan.md) | 测试金字塔、必测清单、基准 |
| [`claude_docs/08-phases.md`](./claude_docs/08-phases.md) | 10 个阶段的顺序、验收命令、工时、风险 |
| [`claude_docs/09-quality-guards.md`](./claude_docs/09-quality-guards.md) | 守卫脚本与 CI 卡口 —— **改代码前必读** |
| [`claude_docs/10-code-style.md`](./claude_docs/10-code-style.md) | **代码规范**：注释/命名/结构/错误处理/日志/提交信息 —— **强制** |

## 硬约束（违反会被守卫拦截）

1. `ender_core` 不得引用 `net.minecraft.*` 或任何加载器 API（ADR-04）。
2. **`ender_common` 不得引用 `net.minecraftforge.*` / `net.neoforged.*`**，只能写 vanilla Mojmap 代码（ADR-13）。
   这是两端能共享源码的唯一前提。
3. 平台差异只允许存在于适配接口实现中；**禁止** `getMethod(` / `Class.forName(` 反射发现（ADR-03）。
4. 禁止裸 `new Thread(`；线程与外部进程必须有生命周期所有者（ADR-06）。
5. 所有可变状态归实例；`private static` 非 `final` 需在白名单且有理由（ADR-05）。
6. UI 不得显示异常 `getMessage()`，只显示错误码对应的本地化文案（ADR-07）。
7. UI 不得使用固定像素定位，一律走 `LayoutEngine` + vp 单位；触控最小命中区 48vp（ADR-09/10）。
8. **注释用中文、字符串用英文、UI 文案一律走语言文件键**（ADR-15）。
   Java 代码中不得出现中文字符串字面量；注释里的中文是允许且鼓励的。
9. **守卫先行**：任何重构阶段开工前，其守卫必须已在 CI 生效（ADR-12）。
10. **不保留 Fabric 相关代码**：不新增、不恢复 `fabric/` 目录或 Fabric 专用分支（ADR-14）。

## 工作方式

1. 开始新任务前读 [`claude_docs/README.md`](./claude_docs/README.md) → 读 [`baseline-audit.md`](./claude_docs/baseline-audit.md)；
2. 按 [`08-phases.md`](./claude_docs/08-phases.md) 确定当前阶段与依赖；
3. 编码前读 [`10-code-style.md`](./claude_docs/10-code-style.md)（注释/命名/结构）；
4. 改动前跑守卫拿基线，改动后跑守卫对比；**基线只降不升**；
5. 完成任务后同步更新对应文档与 `baseline-audit.md` 的数字。

---

*最后更新: 2026-04（文档体系 v3.1；范围收缩为 Forge + NeoForge；旧 `claude_docs/architecture|directory-structure|features|development|refactoring` 已删除并重写）*
