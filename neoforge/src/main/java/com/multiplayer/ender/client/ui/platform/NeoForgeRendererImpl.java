/*
 * 本文件属于 EnderOnline NeoForge 适配层。
 *
 * 职责：把共享 UI 层的渲染抽象落到 NeoForge 的 GuiGraphics 上。
 *
 * 关键约束：所有绘制在 guiGraphics 为 null 时必须静默返回，不得抛异常——
 * 渲染器可能在屏幕尚未开始绘制时被调用。
 */
package com.multiplayer.ender.client.ui.platform;

import net.minecraft.client.gui.GuiGraphics;
import com.multiplayer.ender.client.ui.platform.NeoForgeRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

/**
 * NeoForge 平台的渲染器实现。
 *
 * 用 {@link GuiGraphics} 完成实际绘制。NeoForge 与 Forge 的渲染 API 同源，
 * 因此本实现与 {@code ForgeRendererImpl} 结构一致——两边改动需要同步，否则共享 UI 会在两端表现不一致。
 *
 * 设计约束：
 * 1. {@link #setGuiGraphics} 只接受 {@link GuiGraphics}，其它类型抛 {@link IllegalArgumentException}；
 *    这是适配层的类型闸门，避免调用方把错误的上下文送进来。
 * 2. 除 {@code setGuiGraphics} 之外的所有方法在上下文为 null 时都是空操作，渲染失败不应中断游戏循环。
 * 3. 圆角、阴影、九宫格纹理目前是简化实现（矩形代替），属 P6 设计系统的待办项。
 *
 * 线程安全性：{@code guiGraphics} 只在客户端渲染线程读写，不做同步；跨线程使用会读到过期上下文。
 *
 * @since 1.0
 * @see NeoForgeRenderer
 */
public class NeoForgeRendererImpl extends NeoForgeRenderer {

    /** 当前绘图上下文；允许为 null，为 null 时所有绘制方法都是空操作。 */
    private GuiGraphics guiGraphics;

    /**
     * 设置当前绘图上下文。
     *
     * @param guiGraphics 期望为 GuiGraphics 的实例，不能为 null
     * @throws IllegalArgumentException 当入参不是 GuiGraphics 时抛出
     */
    @Override
    public void setGuiGraphics(Object guiGraphics) {
        if (guiGraphics instanceof GuiGraphics) {
            this.guiGraphics = (GuiGraphics) guiGraphics;
        } else {
            throw new IllegalArgumentException("NeoForgeRenderer需要GuiGraphics对象");
        }
    }

    /**
     * 取得当前绘图上下文。
     *
     * @return 当前 GuiGraphics；尚未设置时返回 null
     */
    @Override
    public Object getGuiGraphics() {
        return guiGraphics;
    }

    /**
     * 绘制圆角矩形。
     *
     * 当前实现忽略圆角半径，直接画直角矩形；半径参数保留以便后续替换为实现圆角的版本。
     *
     * @param x 左缘 X 坐标，单位为逻辑像素
     * @param y 上缘 Y 坐标，单位为逻辑像素
     * @param width 宽度，单位为逻辑像素，非负
     * @param height 高度，单位为逻辑像素，非负
     * @param color ARGB 颜色值
     * @param radius 圆角半径，单位为逻辑像素，当前未生效
     */
    @Override
    public void drawRoundedRect(int x, int y, int width, int height, int color, int radius) {
        if (guiGraphics == null) return;

        // 简化实现：绘制矩形，暂时不支持圆角
        guiGraphics.fill(x, y, x + width, y + height, color);
    }

    /**
     * 绘制渐变矩形。
     *
     * {@code horizontal} 参数当前不影响结果：两个分支都调用同一个 fillGradient（GuGraphics 只提供垂直渐变）。
     * TODO(P6, 2026-07-31): 实现真正的水平渐变或移除该参数，见 claude_docs/04-uiux-plan.md 的设计系统章节。
     *
     * @param x 左缘 X 坐标，单位为逻辑像素
     * @param y 上缘 Y 坐标，单位为逻辑像素
     * @param width 宽度，单位为逻辑像素，非负
     * @param height 高度，单位为逻辑像素，非负
     * @param startColor 起始 ARGB 颜色值
     * @param endColor 结束 ARGB 颜色值
     * @param horizontal 期望的方向，当前被忽略
     */
    @Override
    public void drawGradientRect(int x, int y, int width, int height, int startColor, int endColor, boolean horizontal) {
        if (guiGraphics == null) return;

        if (horizontal) {
            // 水平渐变
            guiGraphics.fillGradient(x, y, x + width, y + height, startColor, endColor);
        } else {
            // 垂直渐变
            guiGraphics.fillGradient(x, y, x + width, y + height, startColor, endColor);
        }
    }

    /**
     * 绘制阴影。
     *
     * 用半透明黑色矩形近似：把原色的 alpha 减半后偏移 {@code blur} 像素绘制。模糊半径本身未被模拟。
     *
     * @param x 左缘 X 坐标，单位为逻辑像素
     * @param y 上缘 Y 坐标，单位为逻辑像素
     * @param width 宽度，单位为逻辑像素，非负
     * @param height 高度，单位为逻辑像素，非负
     * @param blur 阴影偏移量，单位为逻辑像素，非负
     * @param color 基准 ARGB 颜色值，仅取 alpha 通道
     */
    @Override
    public void drawShadow(int x, int y, int width, int height, int blur, int color) {
        if (guiGraphics == null) return;

        // 简化阴影实现：绘制半透明黑色矩形
        int shadowColor = (color & 0x00FFFFFF) | ((int)((color >>> 24) * 0.5f) << 24);
        guiGraphics.fill(x + blur, y + blur, x + width, y + height, shadowColor);
    }

    /**
     * 绘制带边框的圆角矩形。
     *
     * 边框由四条边分别填充而成，圆角半径当前未生效；边框宽度大于半宽或半高时填充区会退化为空。
     *
     * @param x 左缘 X 坐标，单位为逻辑像素
     * @param y 上缘 Y 坐标，单位为逻辑像素
     * @param width 宽度，单位为逻辑像素，非负
     * @param height 高度，单位为逻辑像素，非负
     * @param fillColor 填充色 ARGB
     * @param borderColor 边框色 ARGB
     * @param borderWidth 边框宽度，单位为逻辑像素，非负
     * @param radius 圆角半径，单位为逻辑像素，当前未生效
     */
    @Override
    public void drawBorderedRoundedRect(int x, int y, int width, int height,
                                       int fillColor, int borderColor, int borderWidth, int radius) {
        if (guiGraphics == null) return;

        // 绘制填充矩形
        guiGraphics.fill(x + borderWidth, y + borderWidth,
                        x + width - borderWidth, y + height - borderWidth, fillColor);

        // 绘制边框
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
     * 绘制文本。
     *
     * @param text 文本内容，允许为 null；为 null 时空操作
     * @param x 文本左缘 X 坐标，单位为逻辑像素
     * @param y 文本上缘 Y 坐标，单位为逻辑像素
     * @param color RGB 颜色值（不含 alpha）
     * @param shadow 是否绘制阴影
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
     * 计算文本渲染宽度。
     *
     * @param text 文本内容，允许为 null；为 null 时返回 0
     * @return 宽度，单位为逻辑像素；字体不可用时按每字符 6 像素估算
     */
    @Override
    public int getTextWidth(String text) {
        if (text == null) return 0;
        Font font = Minecraft.getInstance().font;
        if (font == null) return text.length() * 6;
        return font.width(text);
    }

    /**
     * 取单行文本高度。
     *
     * @return 行高，单位为逻辑像素；字体不可用时返回 9
     */
    @Override
    public int getTextHeight() {
        Font font = Minecraft.getInstance().font;
        if (font == null) return 9;
        return font.lineHeight;
    }

    /**
     * 绘制纹理区域。
     *
     * WARNING: 当前是空实现——它只做 null 检查，不画任何东西，也没有纹理标识符参数可传。
     * 调用方若依赖纹理绘制，将看不到任何输出；补齐前不要在新界面中使用本方法。
     *
     * @param x 目标左缘 X 坐标，单位为逻辑像素
     * @param y 目标上缘 Y 坐标，单位为逻辑像素
     * @param width 目标宽度，单位为逻辑像素
     * @param height 目标高度，单位为逻辑像素
     * @param u 纹理源 U 坐标，单位为像素
     * @param v 纹理源 V 坐标，单位为像素
     * @param regionWidth 源区域宽度，单位为像素
     * @param regionHeight 源区域高度，单位为像素
     * @param textureWidth 纹理总宽，单位为像素
     * @param textureHeight 纹理总高，单位为像素
     */
    @Override
    public void drawTexture(int x, int y, int width, int height,
                           float u, float v, int regionWidth, int regionHeight,
                           int textureWidth, int textureHeight) {
        if (guiGraphics == null) return;

        // 使用默认纹理标识符（需要调用者提供）
        // 这是一个存根实现，需要在实际使用时完善
        // guiGraphics.blit(textureId, x, y, width, height, u, v, regionWidth, regionHeight, textureWidth, textureHeight);
    }

    /**
     * 绘制九宫格纹理。
     *
     * 当前是简化实现：忽略九宫格与 UV 参数，直接画一个 {@code 0xFF888888} 的实心矩形。
     *
     * @param x 目标左缘 X 坐标，单位为逻辑像素
     * @param y 目标上缘 Y 坐标，单位为逻辑像素
     * @param width 目标宽度，单位为逻辑像素
     * @param height 目标高度，单位为逻辑像素
     * @param border 九宫格边宽，单位为像素，当前未生效
     * @param u 纹理源 U 坐标，单位为像素
     * @param v 纹理源 V 坐标，单位为像素
     * @param regionWidth 源区域宽度，单位为像素
     * @param regionHeight 源区域高度，单位为像素
     * @param textureWidth 纹理总宽，单位为像素
     * @param textureHeight 纹理总高，单位为像素
     */
    @Override
    public void drawNineSliceTexture(int x, int y, int width, int height, int border,
                                    float u, float v, int regionWidth, int regionHeight,
                                    int textureWidth, int textureHeight) {
        if (guiGraphics == null) return;

        // 九宫格纹理绘制实现
        // 这是一个简化版本，需要实际纹理标识符
        // 暂时使用矩形代替
        guiGraphics.fill(x, y, x + width, y + height, 0xFF888888);
    }

    /**
     * 开启裁剪区域。
     *
     * 必须与 {@link #disableScissor()} 成对调用，否则裁剪状态会泄漏到后续绘制。
     *
     * @param x 左缘 X 坐标，单位为逻辑像素
     * @param y 上缘 Y 坐标，单位为逻辑像素
     * @param width 宽度，单位为逻辑像素，非负
     * @param height 高度，单位为逻辑像素，非负
     */
    @Override
    public void enableScissor(int x, int y, int width, int height) {
        if (guiGraphics == null) return;

        guiGraphics.enableScissor(x, y, x + width, y + height);
    }

    /**
     * 关闭最近的裁剪区域。
     *
     * 与 {@link #enableScissor(int, int, int, int)} 配对；无匹配的开启调用时由 GuiGraphics 自身决定行为。
     */
    @Override
    public void disableScissor() {
        if (guiGraphics == null) return;

        guiGraphics.disableScissor();
    }

    /**
     * 判断当前是否处于可绘制状态。
     *
     * @return 已设置绘图上下文时返回 true
     */
    @Override
    public boolean isRenderingGUI() {
        return guiGraphics != null;
    }

    /**
     * 判断是否运行在「现代」NeoForge 渲染路径上。
     *
     * 当前恒为 true：目标平台固定为 NeoForge 1.21.1，已不存在需要区分旧版渲染路径的场景。
     *
     * @return 恒为 true
     */
    @Override
    public boolean isModernNeoForge() {
        // 检查当前是否是现代NeoForge版本（1.20.5+）
        // 这里可以添加版本检测逻辑
        return true;
    }
}