/*
 * 本文件属于 EnderOnline 客户端界面层。
 *
 * 职责：Forge 平台的渲染器基类，为 forge 模块中的真实实现预留上下文注入位。
 *
 * 本类自身是存根（空绘制）；真实绘制在 ForgeRendererImpl 中完成。
 * 本文件位于 ender_core，按 ADR-04 需随 UI 抽象层一起迁出 core。
 */
package com.multiplayer.ender.client.ui.platform;

/**
 * Forge 平台渲染器基类与存根实现。
 *
 * 职责：提供渲染上下文注入位（guiGraphics）与文本度量兜底实现；
 * ForgeRendererImpl 继承本类并覆写全部绘制方法，把指令翻译为 GuiGraphics 调用。
 * 只有在实现类缺失（反射找不到 ForgeRendererImpl）时，本类才会被当作最终渲染器使用。
 *
 * 设计约束：
 * 1. 上下文参数类型必须是 Object：本类位于 ender_core，不得引用 MC 或加载器类型（ADR-04），
 *    因此类型校验下沉到适配层实现，本类只保存引用。
 * 2. 未注入上下文时所有绘制方法静默返回，不抛异常，使屏幕生命周期之外的调用安全。
 * 3. 文本度量返回估算值，仅用于实现类缺失时的兜底，调用方不得用于精确布局。
 * 4. 本类不得新增平台分支；平台差异只允许存在于实现类的适配代码中（ADR-03）。
 *
 * 线程安全性：非线程安全。guiGraphics 是普通可变字段且无同步，
 * 只允许渲染线程通过 setGuiGraphics 注入，禁止跨线程注入或读取。
 *
 * @since 1.0
 * @see ForgeRendererImpl
 * @see PlatformRenderer
 * @see PlatformRenderers
 */
public class ForgeRenderer implements PlatformRenderer {

    /**
     * 当前渲染上下文，实际类型为 GuiGraphics。
     *
     * 允许为 null（表示尚未注入或屏幕已关闭）；本类不做类型校验，也不使用该字段绘制。
     */
    private Object guiGraphics;

    /**
     * 注入渲染上下文。
     *
     * 实现方可校验上下文类型，类型不符时抛 IllegalArgumentException；
     * 本存根实现只保存引用。
     *
     * @param guiGraphics 渲染上下文，允许为 null，为 null 时 isRenderingGUI 返回 false
     */
    public void setGuiGraphics(Object guiGraphics) {
        this.guiGraphics = guiGraphics;
    }

    /**
     * 返回当前注入的渲染上下文。
     *
     * @return 注入的上下文对象（实现类为 GuiGraphics）；尚未注入时为 null
     */
    public Object getGuiGraphics() {
        return guiGraphics;
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
     * @return 恒为 "forge"
     */
    @Override
    public String getPlatformName() {
        return "forge";
    }

    /**
     * 判断渲染上下文是否已注入，契约见 PlatformRenderer#isRenderingGUI。
     *
     * @return guiGraphics 不为 null 时返回 true
     */
    @Override
    public boolean isRenderingGUI() {
        return guiGraphics != null;
    }
}
