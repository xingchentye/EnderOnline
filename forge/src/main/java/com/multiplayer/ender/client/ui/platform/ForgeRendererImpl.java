/*
 * 本文件属于 EnderOnline Forge 适配层。
 *
 * 职责：Forge 侧渲染器实现，把平台无关的绘制指令翻译为 GuiGraphics 调用。
 *
 * 本类只把绘制指令翻译为 GuiGraphics 调用，不持有屏幕状态，也不做布局计算。
 */
package com.multiplayer.ender.client.ui.platform;

import net.minecraft.client.gui.GuiGraphics;
import com.multiplayer.ender.client.ui.platform.ForgeRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

/**
 * Forge 侧渲染器实现，基于 GuiGraphics 完成实际绘制。
 *
 * ForgeRenderer 在 Forge 平台的实现类。所有绘制方法都先判定渲染上下文是否已注入，
 * 未注入时静默跳过而不是抛异常，使其在屏幕生命周期之外被调用也安全。
 *
 * 设计约束：
 * 1. 圆角、阴影与九宫格纹理均为简化实现：圆角按直角矩形绘制，阴影按偏移半透明矩形绘制，
 *    九宫格纹理用固定灰色矩形占位；三者的 radius/border 参数目前都被忽略。
 * 2. 所有坐标与尺寸参数单位均为像素，原点为当前 GUI 的左上角。
 *
 * 线程安全性：只允许客户端渲染线程使用。guiGraphics 由 setGuiGraphics 注入且未做同步，
 * 跨线程调用不安全。
 */
public class ForgeRendererImpl extends ForgeRenderer {

    /**
     * 当前渲染上下文。
     *
     * 允许为 null（尚未注入或屏幕已关闭），所有绘制方法都必须判空。
     */
    private GuiGraphics guiGraphics;

    /**
     * 注入渲染上下文，契约见 ForgeRenderer#setGuiGraphics。
     *
     * @param guiGraphics 必须是 GuiGraphics 实例
     * @throws IllegalArgumentException 当参数不是 GuiGraphics 实例时抛出
     */
    @Override
    public void setGuiGraphics(Object guiGraphics) {
        if (guiGraphics instanceof GuiGraphics) {
            this.guiGraphics = (GuiGraphics) guiGraphics;
        } else {
            throw new IllegalArgumentException("ForgeRenderer需要GuiGraphics对象");
        }
    }

    /**
     * 返回当前渲染上下文，契约见 ForgeRenderer#getGuiGraphics。
     *
     * @return 当前 GuiGraphics；尚未注入时返回 null
     */
    @Override
    public Object getGuiGraphics() {
        return guiGraphics;
    }

    /**
     * 绘制圆角矩形，契约见 ForgeRenderer#drawRoundedRect。
     *
     * 简化实现：radius 被忽略，按直角矩形填充。
     */
    @Override
    public void drawRoundedRect(int x, int y, int width, int height, int color, int radius) {
        if (guiGraphics == null) return;

        guiGraphics.fill(x, y, x + width, y + height, color);
    }

    /**
     * 绘制渐变矩形，契约见 ForgeRenderer#drawGradientRect。
     *
     * 简化实现：horizontal 参数被忽略，两个分支调用的是同一次纵向渐变填充，
     * 因此横向渐变的调用方目前拿不到预期效果。
     */
    @Override
    public void drawGradientRect(int x, int y, int width, int height, int startColor, int endColor, boolean horizontal) {
        if (guiGraphics == null) return;

        if (horizontal) {
            guiGraphics.fillGradient(x, y, x + width, y + height, startColor, endColor);
        } else {
            guiGraphics.fillGradient(x, y, x + width, y + height, startColor, endColor);
        }
    }

    /**
     * 绘制阴影，契约见 ForgeRenderer#drawShadow。
     *
     * 简化实现：保留 color 的 RGB 分量并将 alpha 折半，绘制一个整体偏移 (blur, blur) 的矩形；
     * blur 因此表现为偏移量而不是模糊半径。
     */
    @Override
    public void drawShadow(int x, int y, int width, int height, int blur, int color) {
        if (guiGraphics == null) return;

        int shadowColor = (color & 0x00FFFFFF) | ((int)((color >>> 24) * 0.5f) << 24);
        guiGraphics.fill(x + blur, y + blur, x + width, y + height, shadowColor);
    }

    /**
     * 绘制带边框的矩形，契约见 ForgeRenderer#drawBorderedRoundedRect。
     *
     * 简化实现：radius 被忽略；先填充内缩 borderWidth 的区域，再分上下左右四条边绘制边框。
     * borderWidth 大于等于宽或高的一半时填充区会退化，调用方需自行保证参数合理。
     */
    @Override
    public void drawBorderedRoundedRect(int x, int y, int width, int height,
                                       int fillColor, int borderColor, int borderWidth, int radius) {
        if (guiGraphics == null) return;

        guiGraphics.fill(x + borderWidth, y + borderWidth,
                        x + width - borderWidth, y + height - borderWidth, fillColor);

        // 上边框
        guiGraphics.fill(x, y, x + width, y + borderWidth, borderColor);
        // 下边框
        guiGraphics.fill(x, y + height - borderWidth, x + width, y + height, borderColor);
        // 左边框
        guiGraphics.fill(x, y, x + borderWidth, y + height, borderColor);
        // 右边框
        guiGraphics.fill(x + width - borderWidth, y, x + width, y + height, borderColor);
    }

    /**
     * 绘制文本，契约见 ForgeRenderer#drawText。
     *
     * text 为 null 或字体尚未就绪时静默跳过。
     */
    @Override
    public void drawText(String text, int x, int y, int color, boolean shadow) {
        if (guiGraphics == null || text == null) return;

        Font font = Minecraft.getInstance().font;
        if (font == null) return;

        if (shadow) {
            guiGraphics.drawString(font, text, x, y, color, true);
        } else {
            guiGraphics.drawString(font, text, x, y, color, false);
        }
    }

    /**
     * 测量文本宽度，契约见 ForgeRenderer#getTextWidth。
     *
     * 字体尚未就绪时退化为「字符数 × 6」的估算值。
     *
     * @return 文本宽度，单位像素，非负；text 为 null 时返回 0
     */
    @Override
    public int getTextWidth(String text) {
        if (text == null) return 0;
        Font font = Minecraft.getInstance().font;
        if (font == null) return text.length() * 6;
        return font.width(text);
    }

    /**
     * 返回单行文本高度，契约见 ForgeRenderer#getTextHeight。
     *
     * @return 行高，单位像素；字体尚未就绪时返回 9
     */
    @Override
    public int getTextHeight() {
        Font font = Minecraft.getInstance().font;
        if (font == null) return 9;
        return font.lineHeight;
    }

    /**
     * 绘制纹理，契约见 ForgeRenderer#drawTexture。
     *
     * 当前为存根实现：方法体为空，不产生任何绘制输出。
     *
     * TODO(P3, 2026-09-30): 接入真实纹理标识符后补全 blit 绘制；在此之前调用方不应依赖其视觉效果。
     */
    @Override
    public void drawTexture(int x, int y, int width, int height,
                           float u, float v, int regionWidth, int regionHeight,
                           int textureWidth, int textureHeight) {
        if (guiGraphics == null) return;
    }

    /**
     * 绘制九宫格纹理，契约见 ForgeRenderer#drawNineSliceTexture。
     *
     * 简化实现：忽略 border、u、v 等纹理参数，直接填充固定灰色（0xFF888888）矩形。
     */
    @Override
    public void drawNineSliceTexture(int x, int y, int width, int height, int border,
                                    float u, float v, int regionWidth, int regionHeight,
                                    int textureWidth, int textureHeight) {
        if (guiGraphics == null) return;

        guiGraphics.fill(x, y, x + width, y + height, 0xFF888888);
    }

    /**
     * 开启裁剪区域，契约见 ForgeRenderer#enableScissor。
     *
     * 必须与 disableScissor 成对调用，否则裁剪状态会泄漏到后续绘制。
     */
    @Override
    public void enableScissor(int x, int y, int width, int height) {
        if (guiGraphics == null) return;

        guiGraphics.enableScissor(x, y, x + width, y + height);
    }

    /**
     * 关闭裁剪区域，契约见 ForgeRenderer#disableScissor。
     */
    @Override
    public void disableScissor() {
        if (guiGraphics == null) return;

        guiGraphics.disableScissor();
    }

    /**
     * 当前是否处于可绘制状态，契约见 ForgeRenderer#isRenderingGUI。
     *
     * @return 渲染上下文已注入时返回 true
     */
    @Override
    public boolean isRenderingGUI() {
        return guiGraphics != null;
    }
}