/*
 * 本文件属于 EnderOnline 客户端界面层。
 *
 * 职责：为 UI 代码提供动画的静态门面，统一转发管理器调度与常用动画的创建。
 *
 * 关键约束：所有方法都作用于进程级单例 AnimationManager，除查询外都要求运行在客户端 UI 线程。
 */
package com.multiplayer.ender.client.ui.animation;

import java.util.function.Consumer;

/**
 * UI 动画静态门面。
 *
 * 把 AnimationManager 的调度能力和 Transition、ValueAnimation、ColorAnimation 的构造能力
 * 收敛成一组静态方法，供界面代码直接调用，避免各处重复取单例。
 *
 * 设计约束：
 * 1. 唯一的时间驱动入口是 updateAnimations，必须由客户端渲染钩子在 UI 线程按帧调用；
 *    调用频率直接决定动画推进步长（见 AnimationManager.update 的说明）。
 * 2. fadeIn、fadeOut、animateValue、animateColor 会立即 start 动画；
 *    而 Transition 的同名方法只构造不启动，两处语义不同，互相替换时需注意。
 * 3. createXxxAnimation 系列返回未启动的序列或动画，由调用方决定何时 start 与如何复用。
 * 4. stopAnimation 系列会先调用 Animation.stop（触发停止回调、并连带停止序列的子动画），再兜底移除。
 * 5. 本类不可实例化，构造器私有。
 *
 * 线程安全性：本类无状态，静态方法可并发调用；但所有调用都会落到单例 AnimationManager 及其持有的
 * 动画实例上，而那些实例没有同步保护。因此除查询类方法外，都必须在客户端 UI 线程调用。
 *
 * @since 1.0
 * @see AnimationManager
 * @see Transition
 */
public final class UIAnimationUtils {

    /** 私有构造函数，工具类禁止实例化。 */
    private UIAnimationUtils() {
        // 工具类，禁止实例化
    }

    /**
     * 推进所有动画一帧。
     *
     * @param partialTick 部分游戏刻，原样透传给每个动画
     */
    public static void updateAnimations(float partialTick) {
        AnimationManager.getInstance().update(partialTick);
    }

    /**
     * 暂停所有动画。
     *
     * 会同时暂停调用方单独启动的动画，恢复时见 resumeAllAnimations 的限制。
     */
    public static void pauseAllAnimations() {
        AnimationManager.getInstance().pauseAll();
    }

    /**
     * 恢复所有动画。
     *
     * 会一并解除调用方此前单独设置的暂停，无法只恢复管理器级暂停。
     */
    public static void resumeAllAnimations() {
        AnimationManager.getInstance().resumeAll();
    }

    /**
     * 停止所有动画。
     *
     * 会触发每个动画的停止回调，并清空管理器的动画列表。
     */
    public static void stopAllAnimations() {
        AnimationManager.getInstance().stopAll();
    }

    /**
     * 清除所有动画。
     *
     * 与 stopAllAnimations 行为一致，保留两个入口是为了兼容既有调用点。
     */
    public static void clearAllAnimations() {
        AnimationManager.getInstance().clearAllAnimations();
    }

    /**
     * 检查是否有动画存在。
     *
     * @return 管理器列表中还有任何动画实例时返回 true，包含已完成但未回收的实例
     */
    public static boolean hasActiveAnimations() {
        return AnimationManager.getInstance().hasActiveAnimations();
    }

    /**
     * 获取管理器列表中的动画数量。
     *
     * @return 列表元素个数，包含已非活动但 autoRemove 为 false 而尚未回收的实例
     */
    public static int getActiveAnimationCount() {
        return AnimationManager.getInstance().getActiveAnimationCount();
    }

    /**
     * 创建并立即开始一个数值动画。
     *
     * @param name 动画名称，用于调试
     * @param duration 持续时间，单位秒；小于 0.001 时按 0.001 处理
     * @param start 起始值
     * @param end 结束值
     * @param easing 缓动函数，允许为 null，为 null 时保持动画的默认缓动
     * @param callback 值更新回调，允许为 null（表示不回调）
     * @return 已启动的动画实例，永不为 null
     */
    public static ValueAnimation animateValue(String name, float duration, float start, float end,
                                             Easing.EasingFunction easing, ValueAnimation.ValueUpdateCallback callback) {
        ValueAnimation animation = new ValueAnimation(name, duration, start, end);
        if (easing != null) {
            animation.setEasingFunction(easing);
        }
        if (callback != null) {
            animation.setValueUpdateCallback(callback);
        }
        animation.start();
        return animation;
    }

    /**
     * 创建并立即开始一个颜色动画。
     *
     * @param name 动画名称，用于调试
     * @param duration 持续时间，单位秒；小于 0.001 时按 0.001 处理
     * @param startColor 起始颜色，0xAARRGGBB
     * @param endColor 结束颜色，0xAARRGGBB
     * @param easing 缓动函数，允许为 null，为 null 时保持动画的默认缓动
     * @param callback 颜色更新回调，允许为 null（表示不回调）
     * @return 已启动的动画实例，永不为 null
     */
    public static ColorAnimation animateColor(String name, float duration, int startColor, int endColor,
                                             Easing.EasingFunction easing, ColorAnimation.ColorUpdateCallback callback) {
        ColorAnimation animation = new ColorAnimation(name, duration, startColor, endColor);
        if (easing != null) {
            animation.setEasingFunction(easing);
        }
        if (callback != null) {
            animation.setColorUpdateCallback(callback);
        }
        animation.start();
        return animation;
    }

    /**
     * 创建并立即开始一个淡入动画。
     *
     * @param name 动画名称，用于调试
     * @param duration 持续时间，单位秒；小于 0.001 时按 0.001 处理
     * @param targetAlpha 目标透明度，0.0 到 1.0
     * @param easing 缓动函数，允许为 null
     * @param callback 值更新回调，允许为 null
     * @return 已启动的动画实例，永不为 null
     */
    public static ValueAnimation fadeIn(String name, float duration, float targetAlpha,
                                       Easing.EasingFunction easing, ValueAnimation.ValueUpdateCallback callback) {
        return animateValue(name, duration, 0.0f, targetAlpha, easing, callback);
    }

    /**
     * 创建并立即开始一个淡出动画。
     *
     * @param name 动画名称，用于调试
     * @param duration 持续时间，单位秒；小于 0.001 时按 0.001 处理
     * @param startAlpha 起始透明度，0.0 到 1.0
     * @param easing 缓动函数，允许为 null
     * @param callback 值更新回调，允许为 null
     * @return 已启动的动画实例，永不为 null
     */
    public static ValueAnimation fadeOut(String name, float duration, float startAlpha,
                                        Easing.EasingFunction easing, ValueAnimation.ValueUpdateCallback callback) {
        return animateValue(name, duration, startAlpha, 0.0f, easing, callback);
    }

    /**
     * 创建按钮点击动画，由缩小、超调回弹与稳定三段组成。
     *
     * 返回的序列未被启动，需要调用方自行 start。
     *
     * @return 包含三个子动画的序列实例，永不为 null
     */
    public static AnimationSequence createButtonClickAnimation() {
        AnimationSequence sequence = new AnimationSequence("button_click");

        // 快速缩小
        ValueAnimation scaleDown = Transition.scaleIn(0.05f, 1.0f, 0.9f);
        scaleDown.setEasingFunction("easeOutQuad");

        // 恢复原状（带轻微超调）
        ValueAnimation scaleUp = Transition.scaleIn(0.1f, 0.9f, 1.05f);
        scaleUp.setEasingFunction("easeOutBack");

        // 最终稳定
        ValueAnimation scaleFinal = Transition.scaleIn(0.08f, 1.05f, 1.0f);
        scaleFinal.setEasingFunction("easeInOutQuad");

        sequence.addAnimation(scaleDown);
        sequence.addAnimation(scaleUp);
        sequence.addAnimation(scaleFinal);

        return sequence;
    }

    /**
     * 创建按钮悬停动画（轻微放大）。
     *
     * 返回的动画未被启动；其 autoRemove 被置为 false，因此不会自动回收。
     *
     * @return 缩放动画实例，永不为 null
     */
    // FIXME(P3, 2026-10-06): autoRemove 被置为 false 后，动画播完仍会永久留在
    // AnimationManager 的活动列表里：每触发一次悬停就多一条永不回收的记录，管理器每帧都要遍历它，
    // 反复悬停会持续增长。修复方向：悬停动画播完后显式 stop，或改用 autoRemove=true 并由调用方持有
    // 最终的缩放值。
    public static ValueAnimation createButtonHoverAnimation() {
        ValueAnimation animation = Transition.scaleIn(0.15f, 1.0f, 1.05f);
        animation.setEasingFunction("easeOutQuad");
        animation.setAutoRemove(false); // 悬停动画不自动移除
        return animation;
    }

    /**
     * 创建按钮悬停退出动画（恢复原状）。
     *
     * 返回的动画未被启动，需要调用方自行 start。
     *
     * @return 缩放动画实例，永不为 null
     */
    public static ValueAnimation createButtonHoverExitAnimation() {
        ValueAnimation animation = Transition.scaleOut(0.15f, 1.05f, 1.0f);
        animation.setEasingFunction("easeInOutQuad");
        return animation;
    }

    /**
     * 创建加载旋转动画。
     *
     * 返回的动画未被启动；它是循环且 autoRemove 为 false 的常驻动画，
     * 必须由加载态结束的一方显式 stop，否则会一直留在管理器中。
     *
     * @return 数值动画实例，值域为 0 到 360，按线性缓动循环
     */
    public static ValueAnimation createLoadingSpinnerAnimation() {
        ValueAnimation animation = new ValueAnimation("loading_spinner", 1.0f, 0.0f, 360.0f);
        animation.setEasingFunction("linear");
        animation.setAutoRemove(false);
        animation.setLoop(true);
        return animation;
    }

    /**
     * 创建成功反馈动画（绿色闪烁与轻微放大）。
     *
     * 颜色按 0xAARRGGBB 打包；返回的序列未被启动。
     *
     * @return 包含颜色与缩放两段的序列实例，永不为 null
     */
    public static AnimationSequence createSuccessAnimation() {
        AnimationSequence sequence = new AnimationSequence("success_feedback");

        // 快速变绿
        ColorAnimation colorToGreen = Transition.colorTransition(0.1f, 0xFFFFFFFF, 0xFF00FF00);
        colorToGreen.setEasingFunction("easeOutQuad");

        // 恢复原色
        ColorAnimation colorBack = Transition.colorTransition(0.2f, 0xFF00FF00, 0xFFFFFFFF);
        colorBack.setEasingFunction("easeInOutQuad");

        // 轻微缩放
        ValueAnimation scaleUp = Transition.scaleIn(0.15f, 1.0f, 1.1f);
        scaleUp.setEasingFunction("easeOutBack");

        ValueAnimation scaleDown = Transition.scaleOut(0.15f, 1.1f, 1.0f);
        scaleDown.setEasingFunction("easeInOutQuad");

        sequence.addAnimation(colorToGreen);
        sequence.addAnimation(colorBack);
        sequence.addAnimation(scaleUp);
        sequence.addAnimation(scaleDown);

        return sequence;
    }

    /**
     * 创建错误反馈动画（变红与水平抖动）。
     *
     * 抖动幅度是像素偏移量，正负号表示左右方向；返回的序列未被启动。
     *
     * @return 包含颜色与三次抖动位移的序列实例，永不为 null
     */
    public static AnimationSequence createErrorAnimation() {
        AnimationSequence sequence = new AnimationSequence("error_feedback");

        // 快速变红
        ColorAnimation colorToRed = Transition.colorTransition(0.1f, 0xFFFFFFFF, 0xFFFF0000);
        colorToRed.setEasingFunction("easeOutQuad");

        // 水平抖动
        ValueAnimation shakeRight = new ValueAnimation("shake_right", 0.05f, 0.0f, 5.0f);
        shakeRight.setEasingFunction("easeOutQuad");

        ValueAnimation shakeLeft = new ValueAnimation("shake_left", 0.05f, 5.0f, -5.0f);
        shakeLeft.setEasingFunction("easeInOutQuad");

        ValueAnimation shakeCenter = new ValueAnimation("shake_center", 0.05f, -5.0f, 0.0f);
        shakeCenter.setEasingFunction("easeInQuad");

        // 恢复原色
        ColorAnimation colorBack = Transition.colorTransition(0.2f, 0xFFFF0000, 0xFFFFFFFF);
        colorBack.setEasingFunction("easeInOutQuad");

        sequence.addAnimation(colorToRed);
        sequence.addAnimation(shakeRight);
        sequence.addAnimation(shakeLeft);
        sequence.addAnimation(shakeCenter);
        sequence.addAnimation(colorBack);

        return sequence;
    }

    /**
     * 创建通知弹出动画（滑入、停留、淡出）。
     *
     * 停留段用的是值域恒为 0 的数值动画，只用于占位计时；返回的序列未被启动。
     *
     * @return 包含三段子动画的序列实例，永不为 null
     */
    public static AnimationSequence createNotificationPopupAnimation() {
        AnimationSequence sequence = new AnimationSequence("notification_popup");

        // 从上方滑入
        ValueAnimation slideIn = Transition.slideInFromTop(0.25f, -50.0f, 0.0f);
        slideIn.setEasingFunction("easeOutBack");

        // 短暂停留
        ValueAnimation stay = new ValueAnimation("stay", 2.5f, 0.0f, 0.0f);
        stay.setEasingFunction("linear");

        // 淡出
        ValueAnimation fadeOut = Transition.fadeOut(0.3f, 1.0f);
        fadeOut.setEasingFunction("easeInQuad");

        sequence.addAnimation(slideIn);
        sequence.addAnimation(stay);
        sequence.addAnimation(fadeOut);

        return sequence;
    }

    /**
     * 创建页面切换动画（当前页滑出、新页滑入）。
     *
     * 两个子动画在序列里是先后播放而不是同时播放，因此页面之间不会交叉滑动。
     * 返回的序列未被启动。
     *
     * @return 包含两段子动画的序列实例，永不为 null
     */
    // XXX: 方法名与注释指向「向右」，但两个子动画实际都向左移动（当前页 0 到 -100，新页 100 到 0）。
    // 命名可能按手势方向而非画面位移方向约定，也可能左右写反；上线前需要与交互设计确认。
    public static AnimationSequence createPageTransitionRight() {
        AnimationSequence sequence = new AnimationSequence("page_transition_right");

        // 当前页面向右滑出
        ValueAnimation currentPageOut = Transition.slideOutToLeft(0.3f, 0.0f, -100.0f);
        currentPageOut.setEasingFunction("easeInQuad");

        // 新页面从右侧滑入
        ValueAnimation newPageIn = Transition.slideInFromRight(0.3f, 100.0f, 0.0f);
        newPageIn.setEasingFunction("easeOutQuad");

        sequence.addAnimation(currentPageOut);
        sequence.addAnimation(newPageIn);

        return sequence;
    }

    /**
     * 创建页面切换动画（当前页滑出、新页滑入）。
     *
     * 同 createPageTransitionRight，两段子动画串行播放；返回的序列未被启动。
     *
     * @return 包含两段子动画的序列实例，永不为 null
     */
    // XXX: 与 createPageTransitionRight 对称的问题：方法名与注释指向「向左」，但两个子动画实际都向右移动。
    public static AnimationSequence createPageTransitionLeft() {
        AnimationSequence sequence = new AnimationSequence("page_transition_left");

        // 当前页面向左滑出
        ValueAnimation currentPageOut = Transition.slideOutToRight(0.3f, 0.0f, 100.0f);
        currentPageOut.setEasingFunction("easeInQuad");

        // 新页面从左侧滑入
        ValueAnimation newPageIn = Transition.slideInFromLeft(0.3f, -100.0f, 0.0f);
        newPageIn.setEasingFunction("easeOutQuad");

        sequence.addAnimation(currentPageOut);
        sequence.addAnimation(newPageIn);

        return sequence;
    }

    /**
     * 创建进度条填充动画。
     *
     * 返回的动画未被启动；进度值不限定单位，由调用方与进度条的取值范围对齐。
     *
     * @param duration 持续时间，单位秒；小于 0.001 时按 0.001 处理
     * @param startProgress 起始进度值
     * @param endProgress 结束进度值
     * @return 数值动画实例，使用 easeInOutQuad 缓动
     */
    public static ValueAnimation createProgressBarAnimation(float duration, float startProgress, float endProgress) {
        ValueAnimation animation = new ValueAnimation("progress_bar", duration, startProgress, endProgress);
        animation.setEasingFunction("easeInOutQuad");
        return animation;
    }

    /**
     * 检查指定动画是否正在运行。
     *
     * 依据管理器列表中的实例判断，已被移除的动画一律返回 false。
     *
     * @param animationId 目标动画 ID
     * @return 存在且处于活动、未完成状态时返回 true，否则返回 false
     */
    public static boolean isAnimationRunning(String animationId) {
        Animation animation = AnimationManager.getInstance().getAnimation(animationId);
        return animation != null && animation.isActive() && !animation.isCompleted();
    }

    /**
     * 停止指定动画。
     *
     * 先按 ID 取出实例并调用 {@link Animation#stop()}：stop 会触发停止回调、把动画置为非活动，
     * 并对序列一并停止其子动画（子动画是各自独立注册进管理器的，只移除序列会留下它们继续跑）。
     * 随后再兜底按 ID 移除一次，覆盖「取不到实例但仍残留在列表中」的边界。
     *
     * @param animationId 目标动画 ID；不存在时静默忽略
     */
    public static void stopAnimation(String animationId) {
        Animation animation = AnimationManager.getInstance().getAnimation(animationId);
        if (animation != null) {
            animation.stop();
        }
        AnimationManager.getInstance().removeAnimation(animationId);
    }

    /**
     * 若指定动画正在运行则停止它。
     *
     * 与先调用 isAnimationRunning 再调用 stopAnimation 等价，同样会触发停止回调。
     *
     * @param animationId 目标动画 ID；不存在时静默忽略
     */
    public static void stopAnimationIfRunning(String animationId) {
        if (isAnimationRunning(animationId)) {
            stopAnimation(animationId);
        }
    }

    // 回调接口定义（为了向后兼容）

    // NOTE: ValueUpdateCallback 与 ColorUpdateCallback 在本文件、ValueAnimation、ColorAnimation 中
    // 各自定义了一份同名嵌套类型，三者不可互换，import 时极易混用；
    // 新增调用点请使用带外部类限定的全名，或直接使用 Consumer 重载。
    /**
     * 数值更新回调，旧接口，仅为向后兼容保留。
     *
     * 与 ValueAnimation.ValueUpdateCallback 结构相同但类型不同，二者不能相互赋值。
     *
     * @since 1.0
     */
    @FunctionalInterface
    public interface ValueUpdateCallback {
        void onValueUpdate(float value);
    }

    /**
     * 颜色更新回调，旧接口，仅为向后兼容保留。
     *
     * 与 ColorAnimation.ColorUpdateCallback 结构相同但类型不同，二者不能相互赋值。
     *
     * @since 1.0
     */
    @FunctionalInterface
    public interface ColorUpdateCallback {
        void onColorUpdate(int color);
    }

    // 适配器方法，将旧的回调接口转换为新的Consumer接口
    /**
     * 把旧的数值回调适配成 Consumer。
     *
     * 适配后的 Consumer 只在收到 Float 实例时转发，其他类型静默忽略，既不转换也不报错。
     *
     * @param callback 旧回调，允许为 null
     * @param <T> 消费类型；运行时只有 Float 会被真正转发
     * @return 适配后的 Consumer；callback 为 null 时返回 null，调用方需判空
     */
    public static <T> Consumer<T> adaptCallback(ValueUpdateCallback callback) {
        return callback == null ? null : value -> {
            if (value instanceof Float) {
                callback.onValueUpdate((Float) value);
            }
        };
    }

    /**
     * 把旧的颜色回调适配成 Consumer。
     *
     * 适配后的 Consumer 只在收到 Integer 实例时转发，其他类型静默忽略。
     *
     * @param callback 旧回调，允许为 null
     * @param <T> 消费类型；运行时只有 Integer 会被真正转发
     * @return 适配后的 Consumer；callback 为 null 时返回 null，调用方需判空
     */
    public static <T> Consumer<T> adaptCallback(ColorUpdateCallback callback) {
        return callback == null ? null : value -> {
            if (value instanceof Integer) {
                callback.onColorUpdate((Integer) value);
            }
        };
    }
}
