/*
 * 本文件属于 EnderOnline 客户端界面层。
 *
 * 职责：以静态工厂集中提供淡入淡出、滑动、缩放、旋转、颜色过渡等预定义过渡效果。
 *
 * 关键约束：本类只构造动画实例，不注册也不启动；命名约定是本类的效果均为客户端 UI 线程上的视觉反馈。
 */
package com.multiplayer.ender.client.ui.animation;

/**
 * 过渡动画工厂。
 *
 * 把常用的 UI 过渡效果收敛成一组带默认缓动的工厂方法，调用方拿到实例后按需注册与启动。
 *
 * 设计约束：
 * 1. 本类只负责构造，不注册也不启动。返回的实例需要调用方自行 start；
 *    与本包 UIAnimationUtils 的同名方法不同，后者会直接启动动画。
 * 2. 滑动与缩放类刻意选用 easeOutBack 或 easeInBack，让数值越出终点后再回弹，这是视觉意图；
 *    调用方若把结果直接当坐标使用，需要容忍短暂越界（见各类缓动函数的取值域说明）。
 * 3. 传给动画的名称字符串（如 slide_in_left）只是调试标签，不参与查找或去重。
 * 4. 本类不可实例化，构造器私有。
 *
 * 线程安全性：本类无任何状态，静态工厂方法可并发调用；但返回的动画实例不是线程安全的，
 * 必须在客户端 UI 线程上启动与推进。
 *
 * @since 1.0
 * @see ValueAnimation
 * @see AnimationSequence
 * @see ColorAnimation
 */
public final class Transition {

    /** 私有构造函数，工具类禁止实例化。 */
    private Transition() {
        // 工具类，禁止实例化
    }

    /**
     * 创建淡入动画。
     *
     * 只构造不启动；透明度取值范围 0.0 到 1.0。
     *
     * @param duration     动画持续时间（秒）
     * @param targetAlpha  目标透明度（0.0-1.0）
     * @return 数值动画实例
     */
    public static ValueAnimation fadeIn(float duration, float targetAlpha) {
        return new ValueAnimation("fade_in", duration, 0.0f, targetAlpha);
    }

    /**
     * 创建淡出动画。
     *
     * 只构造不启动；透明度取值范围 0.0 到 1.0。
     *
     * @param duration     动画持续时间（秒）
     * @param startAlpha   起始透明度（0.0-1.0）
     * @return 数值动画实例
     */
    public static ValueAnimation fadeOut(float duration, float startAlpha) {
        return new ValueAnimation("fade_out", duration, startAlpha, 0.0f);
    }

    /**
     * 创建标准淡入动画（从完全透明到完全不透明）
     *
     * @param duration 动画持续时间（秒）
     * @return 数值动画实例，透明度从 0.0 过渡到 1.0
     */
    public static ValueAnimation fadeIn(float duration) {
        return fadeIn(duration, 1.0f);
    }

    /**
     * 创建标准淡出动画（从完全不透明到完全透明）
     *
     * @param duration 动画持续时间（秒）
     * @return 数值动画实例，透明度从 1.0 过渡到 0.0
     */
    public static ValueAnimation fadeOut(float duration) {
        return fadeOut(duration, 1.0f);
    }

    /**
     * 创建滑动进入动画（从左侧）
     *
     * 使用 easeOutBack，入位末端会有一次轻微超调再回稳。
     *
     * @param duration    动画持续时间（秒）
     * @param startX      起始X坐标（屏幕外左侧）
     * @param endX        结束X坐标（目标位置）
     * @return 数值动画实例
     */
    public static ValueAnimation slideInFromLeft(float duration, float startX, float endX) {
        ValueAnimation animation = new ValueAnimation("slide_in_left", duration, startX, endX);
        animation.setEasingFunction("easeOutBack");
        return animation;
    }

    /**
     * 创建滑动退出动画（到右侧）
     *
     * 使用 easeInBack，起步会先向反方向蓄力再滑出。
     *
     * @param duration    动画持续时间（秒）
     * @param startX      起始X坐标（当前位置）
     * @param endX        结束X坐标（屏幕外右侧）
     * @return 数值动画实例
     */
    public static ValueAnimation slideOutToRight(float duration, float startX, float endX) {
        ValueAnimation animation = new ValueAnimation("slide_out_right", duration, startX, endX);
        animation.setEasingFunction("easeInBack");
        return animation;
    }

    /**
     * 创建滑动进入动画（从右侧）
     *
     * @param duration    动画持续时间（秒）
     * @param startX      起始X坐标（屏幕外右侧）
     * @param endX        结束X坐标（目标位置）
     * @return 数值动画实例
     */
    public static ValueAnimation slideInFromRight(float duration, float startX, float endX) {
        ValueAnimation animation = new ValueAnimation("slide_in_right", duration, startX, endX);
        animation.setEasingFunction("easeOutBack");
        return animation;
    }

    /**
     * 创建滑动退出动画（到左侧）
     *
     * @param duration    动画持续时间（秒）
     * @param startX      起始X坐标（当前位置）
     * @param endX        结束X坐标（屏幕外左侧）
     * @return 数值动画实例
     */
    public static ValueAnimation slideOutToLeft(float duration, float startX, float endX) {
        ValueAnimation animation = new ValueAnimation("slide_out_left", duration, startX, endX);
        animation.setEasingFunction("easeInBack");
        return animation;
    }

    /**
     * 创建滑动进入动画（从上方）
     *
     * @param duration    动画持续时间（秒）
     * @param startY      起始Y坐标（屏幕外上方）
     * @param endY        结束Y坐标（目标位置）
     * @return 数值动画实例
     */
    public static ValueAnimation slideInFromTop(float duration, float startY, float endY) {
        ValueAnimation animation = new ValueAnimation("slide_in_top", duration, startY, endY);
        animation.setEasingFunction("easeOutBack");
        return animation;
    }

    /**
     * 创建滑动退出动画（到下方）
     *
     * @param duration    动画持续时间（秒）
     * @param startY      起始Y坐标（当前位置）
     * @param endY        结束Y坐标（屏幕外下方）
     * @return 数值动画实例
     */
    public static ValueAnimation slideOutToBottom(float duration, float startY, float endY) {
        ValueAnimation animation = new ValueAnimation("slide_out_bottom", duration, startY, endY);
        animation.setEasingFunction("easeInBack");
        return animation;
    }

    /**
     * 创建滑动进入动画（从下方）
     *
     * @param duration    动画持续时间（秒）
     * @param startY      起始Y坐标（屏幕外下方）
     * @param endY        结束Y坐标（目标位置）
     * @return 数值动画实例
     */
    public static ValueAnimation slideInFromBottom(float duration, float startY, float endY) {
        ValueAnimation animation = new ValueAnimation("slide_in_bottom", duration, startY, endY);
        animation.setEasingFunction("easeOutBack");
        return animation;
    }

    /**
     * 创建滑动退出动画（到上方）
     *
     * @param duration    动画持续时间（秒）
     * @param startY      起始Y坐标（当前位置）
     * @param endY        结束Y坐标（屏幕外上方）
     * @return 数值动画实例
     */
    public static ValueAnimation slideOutToTop(float duration, float startY, float endY) {
        ValueAnimation animation = new ValueAnimation("slide_out_top", duration, startY, endY);
        animation.setEasingFunction("easeInBack");
        return animation;
    }

    /**
     * 创建缩放进入动画（从小变大）
     *
     * 使用 easeOutBack，最大缩放会短暂超过 endScale 再回落。
     *
     * @param duration    动画持续时间（秒）
     * @param startScale  起始缩放值（0.0-1.0）
     * @param endScale    结束缩放值（通常为1.0）
     * @return 数值动画实例
     */
    public static ValueAnimation scaleIn(float duration, float startScale, float endScale) {
        ValueAnimation animation = new ValueAnimation("scale_in", duration, startScale, endScale);
        animation.setEasingFunction("easeOutBack");
        return animation;
    }

    /**
     * 创建标准缩放进入动画（从0缩放到1）
     *
     * @param duration 动画持续时间（秒）
     * @return 数值动画实例，缩放从 0.0 过渡到 1.0
     */
    public static ValueAnimation scaleIn(float duration) {
        return scaleIn(duration, 0.0f, 1.0f);
    }

    /**
     * 创建缩放退出动画（从大变小）
     *
     * 使用 easeInBack，起步会先略微放大再收缩。
     *
     * @param duration    动画持续时间（秒）
     * @param startScale  起始缩放值（通常为1.0）
     * @param endScale    结束缩放值（0.0-1.0）
     * @return 数值动画实例
     */
    public static ValueAnimation scaleOut(float duration, float startScale, float endScale) {
        ValueAnimation animation = new ValueAnimation("scale_out", duration, startScale, endScale);
        animation.setEasingFunction("easeInBack");
        return animation;
    }

    /**
     * 创建标准缩放退出动画（从1缩放到0）
     *
     * @param duration 动画持续时间（秒）
     * @return 数值动画实例，缩放从 1.0 过渡到 0.0
     */
    public static ValueAnimation scaleOut(float duration) {
        return scaleOut(duration, 1.0f, 0.0f);
    }

    /**
     * 创建弹跳进入动画。
     *
     * 使用 easeOutBounce，值在接近终点时反复反弹，反弹幅度始终落在起止区间内。
     *
     * @param duration 动画持续时间（秒）
     * @param startY 起始Y坐标
     * @param endY 结束Y坐标
     * @return 数值动画实例
     */
    public static ValueAnimation bounceIn(float duration, float startY, float endY) {
        ValueAnimation animation = new ValueAnimation("bounce_in", duration, startY, endY);
        animation.setEasingFunction("easeOutBounce");
        return animation;
    }

    /**
     * 创建弹跳退出动画。
     *
     * 使用 easeInBounce，起步阶段即开始反弹。
     *
     * @param duration 动画持续时间（秒）
     * @param startY 起始Y坐标
     * @param endY 结束Y坐标
     * @return 数值动画实例
     */
    public static ValueAnimation bounceOut(float duration, float startY, float endY) {
        ValueAnimation animation = new ValueAnimation("bounce_out", duration, startY, endY);
        animation.setEasingFunction("easeInBounce");
        return animation;
    }

    /**
     * 创建旋转进入动画。
     *
     * 角度单位为度，输入的起止角度不要求落在 0 到 360 之间；
     * 本方法直接把两端数值线性插值，不做按 360 度取最短路径的环绕处理，
     * 因此 350 度到 10 度会顺时针转 340 度而不是逆时针转 20 度。
     * 需要环绕语义时应改用 Interpolator.lerpAngle。
     *
     * @param duration       动画持续时间（秒）
     * @param startRotation  起始角度（度）
     * @param endRotation    结束角度（度）
     * @return 数值动画实例
     */
    public static ValueAnimation rotateIn(float duration, float startRotation, float endRotation) {
        ValueAnimation animation = new ValueAnimation("rotate_in", duration, startRotation, endRotation);
        animation.setEasingFunction("easeOutBack");
        return animation;
    }

    /**
     * 创建旋转退出动画。
     *
     * 角度单位与环绕限制同 rotateIn。
     *
     * @param duration       动画持续时间（秒）
     * @param startRotation  起始角度（度）
     * @param endRotation    结束角度（度）
     * @return 数值动画实例
     */
    public static ValueAnimation rotateOut(float duration, float startRotation, float endRotation) {
        ValueAnimation animation = new ValueAnimation("rotate_out", duration, startRotation, endRotation);
        animation.setEasingFunction("easeInBack");
        return animation;
    }

    /**
     * 创建颜色过渡动画。
     *
     * 颜色按 0xAARRGGBB 打包，alpha 同样参与过渡。
     *
     * @param duration    动画持续时间（秒）
     * @param startColor  起始颜色（0xAARRGGBB）
     * @param endColor    结束颜色（0xAARRGGBB）
     * @return 颜色动画实例
     */
    public static ColorAnimation colorTransition(float duration, int startColor, int endColor) {
        return new ColorAnimation("color_transition", duration, startColor, endColor);
    }

    /**
     * 创建闪烁动画（多次淡入淡出）
     *
     * 返回的序列未被启动，也未注册进 AnimationManager，需要调用方自行 start。
     *
     * @param duration      单次闪烁持续时间（秒）
     * @param blinkCount    闪烁次数
     * @param minAlpha      最小透明度（0.0-1.0）
     * @param maxAlpha      最大透明度（0.0-1.0）
     * @return 序列动画实例（需要后续添加到AnimationManager）
     */
    // FIXME(P3, 2026-10-06): 参数 minAlpha 完全未被使用：两段子动画都以 maxAlpha 作为
    // 亮端，fadeOut 从 maxAlpha 降到 0、fadeIn 再从 0 升回 maxAlpha，因此闪烁的暗端恒为 0 而不是
    // minAlpha，调用方传 minAlpha=0.3 仍会闪到全透明。修复方向：暗端改用 minAlpha。
    public static AnimationSequence blink(float duration, int blinkCount, float minAlpha, float maxAlpha) {
        AnimationSequence sequence = new AnimationSequence("blink");

        for (int i = 0; i < blinkCount; i++) {
            // 从 maxAlpha 淡出到 0（暗端不是 minAlpha，见上方已知缺陷说明）
            sequence.addAnimation(fadeOut(duration / 2, maxAlpha));
            // 从 0 淡入到 maxAlpha
            sequence.addAnimation(fadeIn(duration / 2, maxAlpha));
        }

        return sequence;
    }

    /**
     * 创建标准闪烁动画（3次闪烁；暗端参数当前不生效，实际在 0 到 1.0 之间闪烁）。
     *
     * 详见 blink 重载处的已知缺陷说明。
     *
     * @param duration 单次闪烁持续时间（秒）
     * @return 序列动画实例，未被启动，需要调用方自行 start
     */
    public static AnimationSequence blink(float duration) {
        return blink(duration, 3, 0.3f, 1.0f);
    }

    /**
     * 创建脉动动画（循环缩放）
     *
     * pulseCount 小于等于 0 时按「无限」处理，此时只生成一轮子动画并置循环标志；
     * 返回的序列未被启动，需要调用方自行 start（循环能否生效见 AnimationSequence 的说明）。
     *
     * @param duration    单次脉动持续时间（秒）
     * @param pulseCount  脉动次数（0表示无限）
     * @param minScale    最小缩放值
     * @param maxScale    最大缩放值
     * @return 序列动画实例
     */
    public static AnimationSequence pulse(float duration, int pulseCount, float minScale, float maxScale) {
        AnimationSequence sequence = new AnimationSequence("pulse");

        int count = pulseCount;
        if (pulseCount <= 0) {
            count = 1; // 无限循环通过循环标志实现
            sequence.setLoop(true);
        }

        for (int i = 0; i < count; i++) {
            // 放大
            sequence.addAnimation(scaleIn(duration / 2, minScale, maxScale));
            // 缩小
            sequence.addAnimation(scaleOut(duration / 2, maxScale, minScale));
        }

        return sequence;
    }

    /**
     * 创建标准脉动动画（无限循环，缩放0.9-1.1）
     *
     * @param duration 单次脉动持续时间（秒）
     * @return 序列动画实例，未被启动，需要调用方自行 start
     */
    public static AnimationSequence pulse(float duration) {
        return pulse(duration, 0, 0.9f, 1.1f);
    }
}
