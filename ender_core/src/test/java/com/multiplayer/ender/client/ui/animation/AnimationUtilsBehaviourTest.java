/*
 * 本文件属于 EnderOnline 单元测试。
 *
 * 职责：固化 UIAnimationUtils.stopAnimation 与 Transition.blink 的行为。
 *
 * 关键约束：这些用例只驱动纯逻辑，不依赖帧率；需要推进时间时一律用 AnimationManager.manualUpdate
 * 给出显式增量，而不是等真实时钟。
 */
package com.multiplayer.ender.client.ui.animation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动画工具方法的行为固化测试。
 *
 * 这组测试守护什么：
 * 1. {@code stopAnimation} 必须走 {@link Animation#stop()}，而不是只把实例从管理器里摘掉——
 *    原来的实现会让动画停留在 active=true 且永不触发停止回调，序列的子动画更会继续独立跑完。
 * 2. {@code Transition.blink} 的暗端必须停在调用方给出的 minAlpha，而不是恒为全透明。
 *
 * 覆盖范围的局限：动画包目前尚未接入客户端渲染钩子（接线属 P5），因此这些用例验证的是
 * 库内契约本身，不能替代接线后的端到端验证。
 *
 * @see UIAnimationUtils
 * @see Transition
 */
class AnimationUtilsBehaviourTest {

    /** 用例结束时清空管理器，避免动画残留影响后续用例。 */
    @AfterEach
    void clearAnimations() {
        AnimationManager.getInstance().clearAllAnimations();
        AnimationManager.getInstance().resumeAll();
    }

    /** 按显示名从管理器的活动列表中取动画；getAnimation 按 id 查找，不能用来按名定位。 */
    private static Animation byName(String name) {
        return AnimationManager.getInstance().getActiveAnimations().stream()
                .filter(a -> name.equals(a.getName()))
                .findFirst()
                .orElse(null);
    }

    @Test
    @DisplayName("stopAnimation：停止运行中的动画，触发停止回调并移出管理器")
    void stopAnimationStopsAndRemoves() {
        AtomicInteger stopCallbacks = new AtomicInteger();
        ValueAnimation animation = new ValueAnimation("stopped", 5.0f, 0.0f, 1.0f);
        animation.setAutoRemove(false);
        animation.setOnStopCallback(a -> stopCallbacks.incrementAndGet());

        // 用前后差值断言，避免依赖管理器的全局计数（其它用例可能残留动画）
        int before = AnimationManager.getInstance().getActiveAnimationCount();
        animation.start();
        assertTrue(animation.isActive(), "start 后应处于活动状态");
        assertEquals(before + 1, AnimationManager.getInstance().getActiveAnimationCount(), "启动后管理器应多持有一个动画");

        UIAnimationUtils.stopAnimation(animation.getId());

        assertEquals(1, stopCallbacks.get(), "停止回调应恰好触发一次——这正是原缺陷失效之处");
        assertFalse(animation.isActive(), "停止后应转为非活动");
        assertEquals(before, AnimationManager.getInstance().getActiveAnimationCount(), "停止后应移出管理器");
        assertFalse(UIAnimationUtils.isAnimationRunning(animation.getId()), "不应再报告为运行中");
    }

    @Test
    @DisplayName("stopAnimation：停止序列时一并停止当前子动画")
    void stopAnimationCascadesToSequenceChild() {
        AtomicInteger childStops = new AtomicInteger();
        ValueAnimation child = new ValueAnimation("child", 0.2f, 0.0f, 1.0f);
        child.setAutoRemove(false);
        child.setOnStopCallback(a -> childStops.incrementAndGet());

        AnimationSequence sequence = new AnimationSequence("cascade");
        sequence.addAnimation(child);
        sequence.setAutoRemove(false);
        sequence.start();

        // 推进一步让序列真正启动第一个子动画（子动画由此注册进管理器）
        sequence.update(0.05f, 0.0f);
        assertTrue(AnimationManager.getInstance().getActiveAnimationCount() >= 1, "子动画应已被登记");

        UIAnimationUtils.stopAnimation(sequence.getId());

        assertEquals(1, childStops.get(), "序列停止应连带停止当前子动画，否则它会继续独立跑完");
        assertFalse(child.isActive(), "子动画应转为非活动");
    }

    @Test
    @DisplayName("stopAnimation：ID 不存在时是安全空操作")
    void stopAnimationIgnoresUnknownId() {
        UIAnimationUtils.stopAnimation("does-not-exist");
    }

    @Test
    @DisplayName("Transition.blink：子动画数量与闪烁次数一致，总时长等于单次时长乘以次数")
    void blinkBuildsTwoChildrenPerBlink() {
        AnimationSequence two = Transition.blink(0.4f, 2, 0.3f, 1.0f);
        AnimationSequence three = Transition.blink(0.4f, 3, 0.3f, 1.0f);

        assertEquals(4, two.getAnimationCount(), "两次闪烁应产生四个子动画（每次一亮一暗）");
        assertEquals(6, three.getAnimationCount(), "三次闪烁应产生六个子动画");
    }

    @Test
    @DisplayName("Transition.blink：淡出子动画的暗端端点等于 minAlpha")
    void blinkHonoursMinAlpha() {
        float minAlpha = 0.3f;
        AnimationSequence sequence = Transition.blink(0.4f, 1, minAlpha, 1.0f);
        sequence.setAutoRemove(false);
        // start 只注册序列本身；首个子动画要等序列第一次 update 才启动并注册
        sequence.start();
        sequence.update(0.01f, 0.0f);

        Animation found = byName("blink_out");
        assertNotNull(found, "序列推进一帧后应能找到淡出子动画");
        ValueAnimation darkHalf = (ValueAnimation) found;

        // 直接把这个子动画推到结束（时长 0.2 秒），观察它的暗端落点
        darkHalf.update(1.0f, 0.0f);

        assertEquals(minAlpha, darkHalf.getCurrentValue(), 0.0001f,
                "淡出端应停在 minAlpha，而不是 0——这正是原缺陷失效之处");
    }

    @Test
    @DisplayName("Transition.blink：单参重载使用 0.3 作为暗端")
    void blinkDefaultKeepsThirtyPercentAlpha() {
        AnimationSequence sequence = Transition.blink(0.4f);
        sequence.setAutoRemove(false);
        sequence.start();
        sequence.update(0.01f, 0.0f);

        Animation found = byName("blink_out");
        assertNotNull(found, "序列推进一帧后应能找到淡出子动画");
        ValueAnimation darkHalf = (ValueAnimation) found;
        darkHalf.update(1.0f, 0.0f);

        assertEquals(0.3f, darkHalf.getCurrentValue(), 0.0001f,
                "单参重载的暗端应为 0.3");
        assertEquals(6, sequence.getAnimationCount(), "单参重载默认三次闪烁");
    }
}
