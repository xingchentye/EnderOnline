/*
 * 本文件属于 EnderOnline 客户端界面层。
 *
 * 职责：NeoForge 平台的渲染器薄实现，只覆写平台标识与版本判定。
 *
 * NeoForge 的 GUI API 与 Forge 兼容，因此复用 ForgeRenderer 的绘制实现；
 * 真实绘制由 neoforge 模块的 NeoForgeRendererImpl 提供。
 */
package com.multiplayer.ender.client.ui.platform;

/**
 * NeoForge 平台渲染器。
 *
 * NeoForge 与 Forge 的 GUI API 兼容，因此本类继承 ForgeRenderer，只覆写平台标识；
 * 绘制契约见 PlatformRenderer，绘制细节契约见 ForgeRenderer，本类不重复声明。
 *
 * 设计约束：
 * 1. 本类不得复制 ForgeRenderer 的绘制代码；两端的差异只允许出现在版本判定这类小分支上。
 * 2. 本类只作为 NeoForgeRendererImpl 缺失时的降级实现，正常构建下不会被实例化
 *    （PlatformRenderers 优先加载实现类）。
 * 3. 本类位于 ender_core，不得引用 net.neoforged 或任何 MC 类型（ADR-04）；
 *    按 ADR-04 还需随 UI 抽象层一起迁出 core。
 *
 * 线程安全性：本类不持有可变状态；线程安全性完全由父类 ForgeRenderer 决定，
 * 即非线程安全，只允许渲染线程使用。
 *
 * @since 1.0
 * @see ForgeRenderer
 * @see PlatformRenderer
 */
public class NeoForgeRenderer extends ForgeRenderer {

    /**
     * 返回平台标识，契约见 PlatformRenderer#getPlatformName。
     *
     * @return 恒为 "neoforge"
     */
    @Override
    public String getPlatformName() {
        return "neoforge";
    }

    /**
     * 检查当前是否是现代NeoForge版本（1.20.5+）
     *
     * 恒返回 false 的占位实现：只有本降级类被实例化时才会走到这里，
     * 目标平台固定为 NeoForge 1.21.1，NeoForgeRendererImpl 覆写本方法并返回 true。
     *
     * XXX: false 仅是占位值，未在任何真实 NeoForge 环境验证过该分支的可见路径。
     *
     * @return 恒为 false
     */
    public boolean isModernNeoForge() {
        return false;
    }
}
