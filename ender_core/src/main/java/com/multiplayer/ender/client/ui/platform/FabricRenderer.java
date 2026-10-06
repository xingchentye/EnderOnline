/*
 * 本文件属于 EnderOnline 客户端界面层。
 *
 * 职责：Fabric 平台的渲染器存根，仅保证 PlatformRenderer 契约可用。
 *
 * Fabric 支持已终止（ADR-00/ADR-14），本类不承载绘制逻辑，保留只为兼容旧构建与
 * PlatformRenderers 的降级分支；按 ADR-04 还需随 UI 抽象层迁出 ender_core。
 */
package com.multiplayer.ender.client.ui.platform;

/**
 * Fabric 平台渲染器存根。
 *
 * 本类是 PlatformRenderer 的空实现：除文本度量外所有绘制方法都不产生输出，
 * 仅用于在缺少 Fabric 实现类（FabricRendererImpl）的环境下满足编译与降级需求。
 *
 * 设计约束：
 * 1. Fabric 支持已终止（ADR-00/ADR-14），本类不得新增绘制逻辑，也不得恢复 Fabric 模块。
 * 2. 文本度量返回的是估算值而非真实字体度量，调用方不得用于精确布局。
 * 3. 渲染上下文以 Object 承载且不校验类型，与 ForgeRendererImpl 的强制类型校验语义不同；
 *    改为编译期注入后应换成具体的上下文类型。
 *
 * 线程安全性：非线程安全。drawContext 是普通可变字段且无同步，
 * 只允许渲染线程先通过 setDrawContext 注入，再读取。
 *
 * @since 1.0
 * @see PlatformRenderer
 * @see PlatformRenderers
 */
public class FabricRenderer implements PlatformRenderer {

    /**
     * 当前渲染上下文。
     *
     * 允许为 null（表示尚未注入）；本类不对其做类型校验，也不参与绘制。
     */
    private Object drawContext;

    /**
     * 注入渲染上下文。
     *
     * 存根实现只保存引用，不校验类型；上下文仅用于 isRenderingGUI 的判定。
     *
     * @param drawContext 渲染上下文，允许为 null，为 null 时 isRenderingGUI 返回 false
     */
    public void setDrawContext(Object drawContext) {
        this.drawContext = drawContext;
    }

    /**
     * 返回当前注入的渲染上下文。
     *
     * @return 注入的上下文对象；尚未注入时为 null
     */
    public Object getDrawContext() {
        return drawContext;
    }

    /**
     * 绘制圆角矩形，契约见 PlatformRenderer#drawRoundedRect。
     */
    @Override
    public void drawRoundedRect(int x, int y, int width, int height, int color, int radius) {
        // 存根实现
    }

    /**
     * 绘制渐变矩形，契约见 PlatformRenderer#drawGradientRect。
     */
    @Override
    public void drawGradientRect(int x, int y, int width, int height, int startColor, int endColor, boolean horizontal) {
        // 存根实现
    }

    /**
     * 绘制阴影，契约见 PlatformRenderer#drawShadow。
     */
    @Override
    public void drawShadow(int x, int y, int width, int height, int blur, int color) {
        // 存根实现
    }

    /**
     * 绘制带边框的圆角矩形，契约见 PlatformRenderer#drawBorderedRoundedRect。
     */
    @Override
    public void drawBorderedRoundedRect(int x, int y, int width, int height,
                                       int fillColor, int borderColor, int borderWidth, int radius) {
        // 存根实现
    }

    /**
     * 绘制文本，契约见 PlatformRenderer#drawText。
     */
    @Override
    public void drawText(String text, int x, int y, int color, boolean shadow) {
        // 存根实现
    }

    /**
     * 估算文本宽度，契约见 PlatformRenderer#getTextWidth。
     *
     * 存根实现按每个字符 6 像素估算，不依赖真实字体度量。
     *
     * @param text 文本内容，允许为 null
     * @return 估算宽度，单位像素，非负；text 为 null 时返回 0
     */
    @Override
    public int getTextWidth(String text) {
        return text != null ? text.length() * 6 : 0;
    }

    /**
     * 返回单行文本高度，契约见 PlatformRenderer#getTextHeight。
     *
     * @return 固定返回 Minecraft 默认行高，9 像素
     */
    @Override
    public int getTextHeight() {
        return 9;
    }

    /**
     * 绘制纹理，契约见 PlatformRenderer#drawTexture。
     */
    @Override
    public void drawTexture(int x, int y, int width, int height,
                           float u, float v, int regionWidth, int regionHeight,
                           int textureWidth, int textureHeight) {
        // 存根实现
    }

    /**
     * 绘制九宫格纹理，契约见 PlatformRenderer#drawNineSliceTexture。
     */
    @Override
    public void drawNineSliceTexture(int x, int y, int width, int height, int border,
                                    float u, float v, int regionWidth, int regionHeight,
                                    int textureWidth, int textureHeight) {
        // 存根实现
    }

    /**
     * 开启裁剪区域，契约见 PlatformRenderer#enableScissor。
     */
    @Override
    public void enableScissor(int x, int y, int width, int height) {
        // 存根实现
    }

    /**
     * 关闭裁剪区域，契约见 PlatformRenderer#disableScissor。
     */
    @Override
    public void disableScissor() {
        // 存根实现
    }

    /**
     * 返回平台标识，契约见 PlatformRenderer#getPlatformName。
     *
     * @return 恒为 "fabric"
     */
    @Override
    public String getPlatformName() {
        return "fabric";
    }

    /**
     * 判断渲染上下文是否已注入，契约见 PlatformRenderer#isRenderingGUI。
     *
     * @return drawContext 不为 null 时返回 true
     */
    @Override
    public boolean isRenderingGUI() {
        return drawContext != null;
    }
}
