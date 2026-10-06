/*
 * 本文件属于 EnderOnline 客户端界面层。
 *
 * 职责：定义平台无关的 GUI 绘制契约，屏蔽加载器绘制 API（Forge GuiGraphics 等）的差异。
 *
 * 平台差异只允许落在本接口的实现类中（ADR-03）；UI 代码只依赖本接口，不得出现加载器类型。
 * 本文件位于 ender_core 的 client/ui 包下，按 ADR-04 需随 UI 抽象层一起迁出 core。
 */
package com.multiplayer.ender.client.ui.platform;

/**
 * 平台渲染器接口，UI 层唯一的绘制出口。
 *
 * 契约说明：
 * 1. 所有坐标与尺寸参数单位均为像素（GUI 逻辑像素），原点为当前 GUI 的左上角。
 * 2. 所有颜色参数统一为 0xAARRGGBB 形式的 int，含 alpha 通道；0x00 开头的颜色为全透明。
 * 3. 所有方法都必须在渲染线程（客户端主线程）调用，实现方不做同步，不保证跨线程可见性。
 * 4. 文本参数不允许为 null，实现方可以直接抛 NullPointerException。
 * 5. 渲染上下文未注入时，实现方应当静默跳过绘制而不是抛异常，使屏幕生命周期之外的调用是安全的。
 * 6. radius、blur、border 等修饰参数允许实现方降级处理（例如圆角按直角矩形绘制），
 *    调用方不得依赖其像素级精度。
 * 7. 实现方不得缓存跨帧的可变状态；每帧的绘制结果只由本次调用参数决定。
 *
 * 线程安全性：本接口自身无状态；实现类通常持有渲染线程注入的上下文，属于非线程安全对象，
 * 禁止从异步线程或网络回调线程直接调用。
 *
 * @since 1.0
 * @see PlatformRenderers
 */
public interface PlatformRenderer {

    /**
     * 绘制圆角矩形。
     *
     * 矩形由左上角与宽高确定；宽或高不为正时实现方应当跳过绘制。
     *
     * @param x      左上角X坐标，单位像素
     * @param y      左上角Y坐标，单位像素
     * @param width  宽度，单位像素，非负
     * @param height 高度，单位像素，非负
     * @param color  填充颜色，0xAARRGGBB，含 alpha
     * @param radius 圆角半径，单位像素；实现方允许忽略该参数
     */
    void drawRoundedRect(int x, int y, int width, int height, int color, int radius);

    /**
     * 绘制两色渐变矩形。
     *
     * 渐变方向由 horizontal 决定，颜色沿该方向从 startColor 线性插值到 endColor。
     * 允许多个绘制调用共用同一组矩形参数，实现方不得假设调用顺序。
     *
     * @param x          左上角X坐标，单位像素
     * @param y          左上角Y坐标，单位像素
     * @param width      宽度，单位像素，非负
     * @param height     高度，单位像素，非负
     * @param startColor 起始颜色，0xAARRGGBB，含 alpha
     * @param endColor   结束颜色，0xAARRGGBB，含 alpha
     * @param horizontal true 表示水平渐变，false 表示垂直渐变
     */
    void drawGradientRect(int x, int y, int width, int height, int startColor, int endColor, boolean horizontal);

    /**
     * 绘制矩形阴影。
     *
     * 阴影绘制在矩形之外，不影响矩形自身的填充结果；blur 只表达强度或偏移，
     * 不保证真实的模糊半径（实现方可降级为半透明偏移矩形）。
     *
     * @param x      左上角X坐标，单位像素
     * @param y      左上角Y坐标，单位像素
     * @param width  宽度，单位像素，非负
     * @param height 高度，单位像素，非负
     * @param blur   阴影强度或偏移量，单位像素，非负；实现方允许忽略
     * @param color  阴影颜色，0xAARRGGBB，含 alpha
     */
    void drawShadow(int x, int y, int width, int height, int blur, int color);

    /**
     * 绘制带边框的圆角矩形。
     *
     * 边框绘制在矩形范围内侧；borderWidth 不为正时等价于纯填充矩形。
     * borderWidth 不允许大于等于宽或高的一半，否则填充区退化，调用方需自行保证参数合理。
     *
     * @param x          左上角X坐标，单位像素
     * @param y          左上角Y坐标，单位像素
     * @param width      宽度，单位像素，非负
     * @param height     高度，单位像素，非负
     * @param fillColor  填充颜色，0xAARRGGBB，含 alpha
     * @param borderColor 边框颜色，0xAARRGGBB，含 alpha
     * @param borderWidth 边框宽度，单位像素，非负
     * @param radius     圆角半径，单位像素；实现方允许忽略该参数
     */
    void drawBorderedRoundedRect(int x, int y, int width, int height,
                                 int fillColor, int borderColor, int borderWidth, int radius);

    /**
     * 绘制单行文本。
     *
     * 实现方可跳过绘制的情况：渲染上下文未注入，或字体尚未就绪。
     *
     * @param text   文本内容，不允许为 null；实现方可以静默跳过 null
     * @param x      文本起点X坐标，单位像素
     * @param y      文本顶边Y坐标，单位像素
     * @param color  文本颜色，0xAARRGGBB，含 alpha
     * @param shadow 是否绘制阴影，true 时按实现方的默认阴影色绘制
     */
    void drawText(String text, int x, int y, int color, boolean shadow);

    /**
     * 测量文本渲染宽度。
     *
     * @param text 文本内容，不允许为 null
     * @return 文本宽度，单位像素，非负；text 为 null 时返回 0
     */
    int getTextWidth(String text);

    /**
     * 返回单行文本高度。
     *
     * @return 行高，单位像素，恒为正（Minecraft 默认字体为 9）
     */
    int getTextHeight();

    /**
     * 绘制纹理局部区域。
     *
     * u、v 与 region 参数的坐标系约定由实现方定义，调用方必须与所选实现保持一致。
     *
     * NOTE: 当前 Forge/NeoForge 实现均为空实现的存根，调用不会产生任何绘制输出。
     *
     * @param x      左上角X坐标，单位像素
     * @param y      左上角Y坐标，单位像素
     * @param width  绘制宽度，单位像素，非负
     * @param height 绘制高度，单位像素，非负
     * @param u      纹理U坐标，坐标系由实现方定义
     * @param v      纹理V坐标，坐标系由实现方定义
     * @param regionWidth  纹理区域宽度，单位由实现方定义
     * @param regionHeight 纹理区域高度，单位由实现方定义
     * @param textureWidth  纹理总宽度，单位由实现方定义
     * @param textureHeight 纹理总高度，单位由实现方定义
     */
    void drawTexture(int x, int y, int width, int height,
                     float u, float v, int regionWidth, int regionHeight,
                     int textureWidth, int textureHeight);

    /**
     * 绘制九宫格纹理，用于可缩放的UI元素。
     *
     * 四角按原始尺寸绘制，四边与中心按目标宽高拉伸；border 定义四边各自占用的纹理厚度。
     *
     * @param x      左上角X坐标，单位像素
     * @param y      左上角Y坐标，单位像素
     * @param width  总宽度，单位像素，非负
     * @param height 总高度，单位像素，非负
     * @param border 边框大小，四个方向相同，单位像素；实现方允许忽略
     * @param u      纹理U坐标，坐标系由实现方定义
     * @param v      纹理V坐标，坐标系由实现方定义
     * @param regionWidth  纹理区域宽度，单位由实现方定义
     * @param regionHeight 纹理区域高度，单位由实现方定义
     * @param textureWidth  纹理总宽度，单位由实现方定义
     * @param textureHeight 纹理总高度，单位由实现方定义
     */
    void drawNineSliceTexture(int x, int y, int width, int height, int border,
                             float u, float v, int regionWidth, int regionHeight,
                             int textureWidth, int textureHeight);

    /**
     * 开启矩形裁剪区域。
     *
     * 必须与 disableScissor 成对调用，否则裁剪状态会泄漏到后续绘制。
     * 嵌套调用的叠加语义由实现方决定，调用方不应依赖具体实现。
     *
     * @param x      左上角X坐标，单位像素
     * @param y      左上角Y坐标，单位像素
     * @param width  宽度，单位像素，非负
     * @param height 高度，单位像素，非负
     */
    void enableScissor(int x, int y, int width, int height);

    /**
     * 关闭最近一次开启的裁剪区域。
     *
     * 与 enableScissor 数量不匹配时行为由实现方定义，调用方必须保证配对。
     */
    void disableScissor();

    /**
     * 返回当前平台渲染器的名称。
     *
     * @return 小写平台标识，当前取值为 "fabric"、"forge" 或 "neoforge"；永不返回 null
     */
    String getPlatformName();

    /**
     * 判断当前是否处于可绘制状态。
     *
     * 调用方可以据此跳过整帧绘制；返回 false 时绘制方法必须静默跳过。
     *
     * @return true 表示渲染上下文已注入且可以绘制
     */
    boolean isRenderingGUI();
}
