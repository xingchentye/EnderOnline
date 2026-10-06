/*
 * 本文件属于 EnderOnline 后端进程管理。
 *
 * 职责：包级说明，声明 EasyTier 后端的职责边界与平台限制。
 */

/**
 * EasyTier P2P 后端集成包。
 *
 * 本包负责后端可执行文件的下载、校验、启动与生命周期管理，以及对后端进程输出的解析。
 * 不负责协议通信（归 comm 包），不负责房间语义（归 comm.server 包）。
 *
 * 包内约定：
 * 1. **进程所有权唯一**：任何模块都不得自行启动或停止后端进程，必须经由本包的服务入口。
 * 这是为了让「退出游戏后不留残余进程」有单一的收敛点，见
 * docs/02-performance-plan.md §1.3。
 * 2. 下载必须校验 SHA256，且校验失败一律视为安装失败，不允许「先跑起来再说」。
 * 3. 平台能力必须通过探测而非系统名推断：Android 上能否执行原生二进制取决于设备，
 * 不取决于 os.name，见 docs/05-cross-platform-plan.md §2。
 * 4. 启动的子进程必须隐藏控制台窗口（Windows）并纳入统一的关闭超时流程。
 *
 * 已知限制：
 * Android 平台没有官方 CLI 分发包（只有 APK 与 Magisk 模块），且 Android 10 起禁止从
 * 应用可写目录执行原生二进制。因此 Android 端的后端策略与其他平台不同，详见
 * docs/05-cross-platform-plan.md §3。
 *
 * @since 1.0
 */
package com.endercore.core.easytier;
