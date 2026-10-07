/*
 * 本文件属于 EnderOnline 单元测试。
 *
 * 职责：固化动画时钟钳制与序列循环开关的行为。
 *
 * 关键约束：这些用例只驱动 Animation / AnimationManager / AnimationSequence 的纯逻辑，
 * 不触碰 Minecraft 类型，也不依赖真实帧率——时间增量全部由用例显式给出。
 */
package com.multiplayer.ender.client.ui.animation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动画时钟与循环开关的行为固化测试。
 *
 * 这组测试守护什么：
 * 1. 负时间增量不得让动画倒退——墙上时钟回拨曾把 elapsedTime 拉成负值，缓动函数收到越界输入。
 * 2. {@code AnimationSequence.setLoop} 必须写到父类字段上。此处原有一个遮蔽父类字段的私有 loop，
 *    导致父类完成判定恒看到 false、序列首轮结束就停，{@code createAndStartLoop} 形同虚设。
 *
 * 为什么不用真实时间推进 AnimationManager.update：那会让用例依赖帧率与调度，
 * 而 {@code manualUpdate} 接受调用方给出的增量，正好把时间source 变成可控的。
 *
 * @see Animation
 * @see AnimationSequence
 */
class AnimationClockTest {

    /** 用例结束时清空管理器，避免动画残留影响后续用例。 */
    @AfterEach
    void clearAnimations() {
        AnimationManager.getInstance().clearAllAnimations();
        AnimationManager.getInstance().resumeAll();
    }

    @Test
    @DisplayName("Animation.update：负时间增量不推进时间，也不让进度变成负数")
    void negativeDeltaDoesNotRewind() {
        ValueAnimation animation = new ValueAnimation("negative-delta", 1.0f, 0.0f, 1.0f);
        animation.start();
        animation.update(0.25f, 0.0f);
        float afterForward = animation.getElapsedTime();

        animation.update(-5.0f, 0.0f);

        assertEquals(afterForward, animation.getElapsedTime(), 0.0001f,
                "负增量不得减少已累积时间，实际=" + animation.getElapsedTime());
        assertTrue(animation.getProgress() >= 0.0f, "进度不得为负，实际=" + animation.getProgress());
    }

    @Test
    @DisplayName("Animation.update：进度双向钳制到 [0, 1]")
    void progressIsClampedBothWays() {
        ValueAnimation animation = new ValueAnimation("clamped-progress", 0.5f, 0.0f, 1.0f);
        animation.start();

        animation.update(-1.0f, 0.0f);
        assertEquals(0.0f, animation.getProgress(), 0.0001f, "下界应钳到 0");

        animation.update(10.0f, 0.0f);
        assertTrue(animation.getProgress() <= 1.0f, "上界不得超过 1，实际=" + animation.getProgress());
    }

    @Test
    @DisplayName("AnimationManager.manualUpdate：增量由调用方决定，且越界增量被钳到 [0, 0.1]")
    void manualUpdateUsesCallerDelta() {
        AnimationManager manager = AnimationManager.getInstance();
        ValueAnimation animation = new ValueAnimation("manual-delta", 10.0f, 0.0f, 1.0f);
        animation.setAutoRemove(false);
        // start 会自行注册到管理器（见 Animation.start 的注释），这是文档推荐的注册方式；
        // 直接 addAnimation 注册的动画 active 仍为 false，不会被推进。
        animation.start();

        // 第一次推进允许混入极小量的真实耗时，因此只断言区间而非精确值。
        manager.manualUpdate(0.05f);
        assertTrue(animation.getElapsedTime() >= 0.05f && animation.getElapsedTime() < 0.1f,
                "应推进调用方给出的 0.05 秒（允许微不足道的真实耗时），实际=" + animation.getElapsedTime());

        float beforeLarge = animation.getElapsedTime();
        manager.manualUpdate(5.0f);
        assertTrue(animation.getElapsedTime() - beforeLarge <= 0.1f + 0.001f,
                "超过 0.1 秒的增量必须被钳到 0.1 秒，实际增量=" + (animation.getElapsedTime() - beforeLarge));

        float beforeNegative = animation.getElapsedTime();
        manager.manualUpdate(-1.0f);
        assertFalse(animation.getElapsedTime() < beforeNegative, "负增量不得让动画倒退");
    }

    @Test
    @DisplayName("AnimationSequence.setLoop：开关传播到父类字段（原遮蔽缺陷）")
    void sequenceLoopReachesParentField() {
        AnimationSequence sequence = new AnimationSequence("loop-flag");

        sequence.setLoop(true);
        assertTrue(sequence.isLoop(), "isLoop 应反应已设置的循环开关（父类字段）");
        assertTrue(((Animation) sequence).isLoop(), "经父类视角读取也应为 true——这正是原缺陷失效之处");

        sequence.setLoop(false);
        assertFalse(sequence.isLoop(), "关闭后应为 false");
    }

    @Test
    @DisplayName("AnimationSequence：循环序列播完一轮仍是活动状态")
    void loopingSequenceStaysActive() {
        Animation child = new ValueAnimation("child", 0.2f, 0.0f, 1.0f);
        child.setAutoRemove(false);
        AnimationSequence sequence = new AnimationSequence("loop-run");
        sequence.addAnimation(child);
        sequence.setLoop(true);
        sequence.setAutoRemove(false);
        sequence.start();

        for (int i = 0; i < 40; i++) {
            sequence.update(0.1f, 0.0f);
        }

        assertTrue(sequence.isActive(), "循环序列在子动画播完后应仍处于活动状态");
        assertFalse(sequence.isCompleted(), "循环序列不应被判定为完成");
    }

    @Test
    @DisplayName("AnimationSequence：非循环序列播完一轮后停止")
    void nonLoopingSequenceCompletes() {
        Animation child = new ValueAnimation("child", 0.2f, 0.0f, 1.0f);
        child.setAutoRemove(false);
        AnimationSequence sequence = new AnimationSequence("once");
        sequence.addAnimation(child);
        sequence.setLoop(false);
        sequence.setAutoRemove(false);
        sequence.start();

        for (int i = 0; i < 40; i++) {
            sequence.update(0.1f, 0.0f);
        }

        assertFalse(sequence.isActive(), "非循环序列播完应转为非活动");
    }
}
