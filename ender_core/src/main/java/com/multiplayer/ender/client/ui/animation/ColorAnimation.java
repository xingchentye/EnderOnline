/*
 * 本文件属于 EnderOnline 客户端界面层。
 *
 * 职责：在两个 ARGB 颜色之间按缓动进度插值，是颜色过渡与淡入淡出的唯一实现。
 *
 * 关键约束：颜色一律按 0xAARRGGBB 打包，四个通道独立线性插值，不做色彩空间转换。
 */
package com.multiplayer.ender.client.ui.animation;

import java.util.function.Consumer;

/**
 * 颜色动画，在两个 ARGB 颜色之间按缓动进度插值。
 *
 * 颜色一律按 0xAARRGGBB 打包，即最高 8 位是 alpha，其后依次是 red、green、blue，
 * 每个通道取值 0 到 255。插值对四个通道分别进行，alpha 同样参与过渡，因此可以直接做淡入淡出。
 *
 * 设计约束：
 * 1. 越界分量在插值出口被钳制：{@link Interpolator#lerpColor(int, int, double)} 会把进度钳到
 *    [0, 1]、把每个通道钳到 [0, 255]，因此超调缓动与越界端点都不会造成通道串位。
 *    构造器仍不做范围校验——入参越界会先按位运算打包，再由插值出口钳制回来。
 * 2. 起始色与结束色可以在播放中通过 setColors 修改，改动只影响后续帧，不会重算已发出的颜色。
 * 3. 回调是唯一的颜色输出通道：本类不持有任何渲染目标，调用方必须在回调里把颜色写回自己的状态。
 * 4. 播放结束不会自动把颜色定格为 endColor，最后一帧仍取决于 update 的调用时机。
 *
 * 线程安全性：无同步，只允许在客户端 UI 线程创建、修改与推进；
 * 颜色回调在推进线程上同步执行，实现方不得在其中阻塞或回改本动画。
 *
 * @since 1.0
 * @see Interpolator#lerpColor(int, int, double)
 * @see ValueAnimation
 */
public class ColorAnimation extends Animation {

    /**
     * 颜色更新回调。
     *
     * 每个 update 帧回调一次，参数是插值后的 0xAARRGGBB 颜色值。
     *
     * 线程安全性：在推进动画的客户端 UI 线程上同步执行，实现方不得阻塞。
     */
    @FunctionalInterface
    public interface ColorUpdateCallback {
        void onColorUpdate(int color);
    }

    /** 起始颜色，0xAARRGGBB；可由 setStartColor 或 setColors 修改。 */
    private int startColor;

    /** 结束颜色，0xAARRGGBB；可由 setEndColor 或 setColors 修改。 */
    private int endColor;

    /** 最近一帧插值得到的颜色，0xAARRGGBB；构造后初始值等于起始色。 */
    private int currentColor;

    /** 颜色更新回调，允许为 null（表示不回调）。 */
    private ColorUpdateCallback colorUpdateCallback;

    /**
     * 创建颜色动画。
     *
     * 构造器按位组合，不对四个分量做范围校验：传入超出 0 到 255 的取值会向相邻通道进位，
     * 得到的分量本身就已经不是调用方给出的值。插值出口的钳制无法还原这一步的进位，
     * 因此调用方必须自行保证分量合法。
     *
     * @param name 动画名称
     * @param duration 动画持续时间，单位秒；小于 0.001 时按 0.001 处理
     * @param startColor 起始颜色，0xAARRGGBB
     * @param endColor 结束颜色，0xAARRGGBB
     */
    public ColorAnimation(String name, float duration, int startColor, int endColor) {
        super(name, duration);
        this.startColor = startColor;
        this.endColor = endColor;
        this.currentColor = startColor;
    }

    /**
     * 创建颜色动画，用 RGB 分量表示颜色。
     *
     * 两个颜色的 alpha 固定为 0xFF（完全不透明）。分量按位组合而不做范围校验，
     * 越界取值同样会向相邻通道进位，调用方必须自行保证分量合法。
     *
     * @param name 动画名称
     * @param duration 动画持续时间，单位秒；小于 0.001 时按 0.001 处理
     * @param startR 起始红色分量，0 到 255
     * @param startG 起始绿色分量，0 到 255
     * @param startB 起始蓝色分量，0 到 255
     * @param endR 结束红色分量，0 到 255
     * @param endG 结束绿色分量，0 到 255
     * @param endB 结束蓝色分量，0 到 255
     */
    public ColorAnimation(String name, float duration, int startR, int startG, int startB, int endR, int endG, int endB) {
        this(name, duration,
                (0xFF << 24) | (startR << 16) | (startG << 8) | startB,
                (0xFF << 24) | (endR << 16) | (endG << 8) | endB);
    }

    /**
     * 创建颜色动画，用 ARGB 分量表示颜色。
     *
     * 分量不做掩码，传入大于 255 的值会向相邻通道进位（例如 red=300 会把 green 也抬高），
     * 与打包版本构造器同一约束：调用方必须自行保证分量合法。
     *
     * @param name 动画名称
     * @param duration 动画持续时间，单位秒；小于 0.001 时按 0.001 处理
     * @param startA 起始透明度分量，0 到 255
     * @param startR 起始红色分量，0 到 255
     * @param startG 起始绿色分量，0 到 255
     * @param startB 起始蓝色分量，0 到 255
     * @param endA 结束透明度分量，0 到 255
     * @param endR 结束红色分量，0 到 255
     * @param endG 结束绿色分量，0 到 255
     * @param endB 结束蓝色分量，0 到 255
     */
    public ColorAnimation(String name, float duration,
                         int startA, int startR, int startG, int startB,
                         int endA, int endR, int endG, int endB) {
        this(name, duration,
                (startA << 24) | (startR << 16) | (startG << 8) | startB,
                (endA << 24) | (endR << 16) | (endG << 8) | endB);
    }

    /**
     * 按缓动进度刷新当前颜色并触发回调。
     *
     * @param easedProgress 已应用缓动函数的进度；超调类缓动会短暂越出 [0, 1]
     * @param partialTick 部分游戏刻，本实现忽略
     */
    @Override
    protected void onUpdate(float easedProgress, float partialTick) {
        // 插值出口负责钳制：超调缓动在这里被吸收，不会再产生越界的 ARGB。
        currentColor = Interpolator.lerpColor(startColor, endColor, easedProgress);

        // 触发颜色更新回调
        if (colorUpdateCallback != null) {
            colorUpdateCallback.onColorUpdate(currentColor);
        }
    }

    /**
     * 设置颜色更新回调。
     *
     * @param callback 回调函数，接收 0xAARRGGBB 的当前颜色，允许为 null（表示清除回调）
     */
    public void setColorUpdateCallback(ColorUpdateCallback callback) {
        this.colorUpdateCallback = callback;
    }

    /**
     * 设置颜色更新回调，Consumer 适配重载。
     *
     * 与接口重载构成同一方法名的两组签名，传 lambda 时可能产生歧义，新代码请显式转换类型。
     *
     * @param callback 回调函数，允许为 null（表示清除回调）
     */
    public void setColorUpdateCallback(Consumer<Integer> callback) {
        if (callback != null) {
            this.colorUpdateCallback = callback::accept;
        } else {
            this.colorUpdateCallback = null;
        }
    }

    /** 获取最近一帧插值得到的颜色，0xAARRGGBB；未推进前等于起始色。 */
    public int getCurrentColor() {
        return currentColor;
    }

    /**
     * 获取当前颜色的 ARGB 分量。
     *
     * @return 长度为 4 的数组，依次为 alpha、red、green、blue，每个分量 0 到 255；新数组，可安全修改
     */
    public int[] getCurrentColorComponents() {
        return new int[]{
                (currentColor >> 24) & 0xFF,
                (currentColor >> 16) & 0xFF,
                (currentColor >> 8) & 0xFF,
                currentColor & 0xFF
        };
    }

    /**
     * 获取当前颜色的 RGB 分量，忽略透明度。
     *
     * @return 长度为 3 的数组，依次为 red、green、blue，每个分量 0 到 255；新数组，可安全修改
     */
    public int[] getCurrentColorRGB() {
        return new int[]{
                (currentColor >> 16) & 0xFF,
                (currentColor >> 8) & 0xFF,
                currentColor & 0xFF
        };
    }

    /** 获取当前颜色的十六进制字符串，格式为井号加 8 位十六进制，按 ARGB 顺序输出。 */
    public String getCurrentColorHex() {
        return String.format("#%08X", currentColor);
    }

    /** 获取起始颜色，0xAARRGGBB。 */
    public int getStartColor() {
        return startColor;
    }

    /**
     * 设置起始颜色。
     *
     * 只影响后续帧的插值，不改变已发出的颜色，也不会重置播放进度。
     *
     * @param startColor 起始颜色，0xAARRGGBB
     */
    public void setStartColor(int startColor) {
        this.startColor = startColor;
    }

    /** 获取结束颜色，0xAARRGGBB。 */
    public int getEndColor() {
        return endColor;
    }

    /**
     * 设置结束颜色。
     *
     * 只影响后续帧的插值，不改变已发出的颜色，也不会重置播放进度。
     *
     * @param endColor 结束颜色，0xAARRGGBB
     */
    public void setEndColor(int endColor) {
        this.endColor = endColor;
    }

    /**
     * 同时设置起始色与结束色。
     *
     * 等价于依次调用 setStartColor 与 setEndColor，同样不重置进度。
     *
     * @param startColor 起始颜色，0xAARRGGBB
     * @param endColor 结束颜色，0xAARRGGBB
     */
    public void setColors(int startColor, int endColor) {
        this.startColor = startColor;
        this.endColor = endColor;
    }

    /**
     * 创建并立即开始一个颜色动画。
     *
     * @param name 动画名称
     * @param duration 持续时间，单位秒；小于 0.001 时按 0.001 处理
     * @param startColor 起始颜色，0xAARRGGBB
     * @param endColor 结束颜色，0xAARRGGBB
     * @param callback 颜色更新回调，允许为 null（表示不回调）
     * @return 已启动的动画实例，永不为 null
     */
    public static ColorAnimation createAndStart(String name, float duration, int startColor, int endColor, ColorUpdateCallback callback) {
        ColorAnimation animation = new ColorAnimation(name, duration, startColor, endColor);
        if (callback != null) {
            animation.setColorUpdateCallback(callback);
        }
        animation.start();
        return animation;
    }

    /**
     * 创建并立即开始一个颜色动画，Consumer 适配重载。
     *
     * @param name 动画名称
     * @param duration 持续时间，单位秒；小于 0.001 时按 0.001 处理
     * @param startColor 起始颜色，0xAARRGGBB
     * @param endColor 结束颜色，0xAARRGGBB
     * @param callback 颜色更新回调，允许为 null（表示不回调）
     * @return 已启动的动画实例，永不为 null
     */
    public static ColorAnimation createAndStart(String name, float duration, int startColor, int endColor, Consumer<Integer> callback) {
        return createAndStart(name, duration, startColor, endColor,
            callback == null ? null : (ColorUpdateCallback) callback::accept);
    }

    /**
     * 创建并立即开始一个透明度动画，RGB 部分保持不变。
     *
     * 两个 alpha 都经过 0xFF 掩码，RGB 部分经过 0x00FFFFFF 掩码，因此本方法的分量是安全的。
     *
     * @param name 动画名称
     * @param duration 持续时间，单位秒；小于 0.001 时按 0.001 处理
     * @param startAlpha 起始透明度，0 到 255，0 为全透明
     * @param endAlpha 结束透明度，0 到 255，255 为完全不透明
     * @param rgbColor RGB 颜色值，0x00RRGGBB，高位会被忽略
     * @param callback 颜色更新回调，允许为 null（表示不回调）
     * @return 已启动的动画实例，永不为 null
     */
    public static ColorAnimation createAlphaAnimation(String name, float duration, int startAlpha, int endAlpha, int rgbColor, ColorUpdateCallback callback) {
        int startColor = ((startAlpha & 0xFF) << 24) | (rgbColor & 0x00FFFFFF);
        int endColor = ((endAlpha & 0xFF) << 24) | (rgbColor & 0x00FFFFFF);
        return createAndStart(name, duration, startColor, endColor, callback);
    }

    /**
     * 创建并立即开始一个透明度动画，Consumer 适配重载。
     *
     * @param name 动画名称
     * @param duration 持续时间，单位秒；小于 0.001 时按 0.001 处理
     * @param startAlpha 起始透明度，0 到 255
     * @param endAlpha 结束透明度，0 到 255
     * @param rgbColor RGB 颜色值，0x00RRGGBB
     * @param callback 颜色更新回调，允许为 null（表示不回调）
     * @return 已启动的动画实例，永不为 null
     */
    public static ColorAnimation createAlphaAnimation(String name, float duration, int startAlpha, int endAlpha, int rgbColor, Consumer<Integer> callback) {
        return createAlphaAnimation(name, duration, startAlpha, endAlpha, rgbColor,
            callback == null ? null : (ColorUpdateCallback) callback::accept);
    }

    /**
     * 创建淡入动画，alpha 从 0 过渡到 255，RGB 保持不变。
     *
     * @param name 动画名称
     * @param duration 持续时间，单位秒；小于 0.001 时按 0.001 处理
     * @param rgbColor RGB 颜色值，0x00RRGGBB
     * @param callback 颜色更新回调，允许为 null（表示不回调）
     * @return 已启动的动画实例，永不为 null
     */
    public static ColorAnimation createFadeIn(String name, float duration, int rgbColor, ColorUpdateCallback callback) {
        return createAlphaAnimation(name, duration, 0x00, 0xFF, rgbColor, callback);
    }

    /**
     * 创建淡入动画，Consumer 适配重载。
     *
     * @param name 动画名称
     * @param duration 持续时间，单位秒；小于 0.001 时按 0.001 处理
     * @param rgbColor RGB 颜色值，0x00RRGGBB
     * @param callback 颜色更新回调，允许为 null（表示不回调）
     * @return 已启动的动画实例，永不为 null
     */
    public static ColorAnimation createFadeIn(String name, float duration, int rgbColor, Consumer<Integer> callback) {
        return createFadeIn(name, duration, rgbColor,
            callback == null ? null : (ColorUpdateCallback) callback::accept);
    }

    /**
     * 创建淡出动画，alpha 从 255 过渡到 0，RGB 保持不变。
     *
     * @param name 动画名称
     * @param duration 持续时间，单位秒；小于 0.001 时按 0.001 处理
     * @param rgbColor RGB 颜色值，0x00RRGGBB
     * @param callback 颜色更新回调，允许为 null（表示不回调）
     * @return 已启动的动画实例，永不为 null
     */
    public static ColorAnimation createFadeOut(String name, float duration, int rgbColor, ColorUpdateCallback callback) {
        return createAlphaAnimation(name, duration, 0xFF, 0x00, rgbColor, callback);
    }

    /**
     * 创建淡出动画，Consumer 适配重载。
     *
     * @param name 动画名称
     * @param duration 持续时间，单位秒；小于 0.001 时按 0.001 处理
     * @param rgbColor RGB 颜色值，0x00RRGGBB
     * @param callback 颜色更新回调，允许为 null（表示不回调）
     * @return 已启动的动画实例，永不为 null
     */
    public static ColorAnimation createFadeOut(String name, float duration, int rgbColor, Consumer<Integer> callback) {
        return createFadeOut(name, duration, rgbColor,
            callback == null ? null : (ColorUpdateCallback) callback::accept);
    }
}
