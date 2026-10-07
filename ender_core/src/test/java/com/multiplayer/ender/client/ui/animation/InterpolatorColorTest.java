/*
 * 本文件属于 EnderOnline 单元测试。
 *
 * 职责：固化 Interpolator.lerpColor 的通道钳制契约。
 *
 * 关键约束：颜色插值的钳制是打包结构正确性的前提，不是行为修饰——本测试用越界进度与
 * 越界端点直接验证「不会发生通道串位」，而不是只验证正常区间。
 */
package com.multiplayer.ender.client.ui.animation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Interpolator#lerpColor(int, int, double)} 的行为固化测试。
 *
 * 这组测试守护什么：ARGB 打包要求每个通道占满一个字节。修复前插值不钳制，
 * 超调缓动（easeOutBack / elastic）会让进度越过 1，通道随即超过 255 并经位运算进位，
 * 例如 red 溢出污染 alpha，产出结构上错误的颜色。本测试固定「四个通道恒在 [0, 255]」。
 *
 * 为什么用越界入参而不是正常区间：正常区间本来就正确，能暴露原缺陷的只有越界路径。
 *
 * @see Interpolator
 */
class InterpolatorColorTest {

    /** 测试用颜色：alpha=0x10、red=0xFA，二者相加在超调时会溢出。 */
    private static final int START = 0x10_FA_20_30;
    private static final int END = 0x80_FF_40_50;

    /** 从打包颜色中取出指定通道。 */
    private static int channel(int color, int shift) {
        return (color >> shift) & 0xFF;
    }

    @Test
    @DisplayName("lerpColor：进度 0 与 1 精确落在端点颜色上")
    void endpointsAreExact() {
        assertEquals(START, Interpolator.lerpColor(START, END, 0.0), "t=0 应返回起始色");
        assertEquals(END, Interpolator.lerpColor(START, END, 1.0), "t=1 应返回结束色");
    }

    @Test
    @DisplayName("lerpColor：进度超过 1 时钳到结束色，通道不溢出")
    void overshootProgressClamps() {
        int overshot = Interpolator.lerpColor(START, END, 1.4);

        assertEquals(END, overshot, "t>1 应被钳到 1，结果等于结束色");
        assertEquals(channel(END, 16), channel(overshot, 16), "red 不得因溢出污染 alpha");
    }

    @Test
    @DisplayName("lerpColor：负数进度钳到起始色，通道不为负")
    void negativeProgressClamps() {
        int undershot = Interpolator.lerpColor(START, END, -0.5);

        assertEquals(START, undershot, "t<0 应被钳到 0，结果等于起始色");
        assertTrue(channel(undershot, 24) >= 0, "alpha 不得为负");
    }

    @Test
    @DisplayName("lerpColor：插值因子越界被钳制，通道不绕回")
    void outOfRangeEndpointsAreClamped() {
        // 通道溢出的唯一来源是插值因子越界（超调缓动），而不是端点：
        // 打包后的单通道恒为一个字节，取值必在 [0, 255]，因此越界端点会被掩码吸收。
        int start = (0x40 << 24) | (0x2C << 16) | (0x21 << 8) | 0x2C;
        int end = (0xCC << 24) | (0x64 << 16) | (0x40 << 8) | 0x64;

        // 远大于 1 的因子：若不钳制，red 会算到 0x7F0 之类的值并挤进相邻字节
        int overshot = Interpolator.lerpColor(start, end, 8.0);
        assertEquals(end, overshot, "t 远大于 1 时必须钳到 t=1，结果等于结束色");

        int undershot = Interpolator.lerpColor(start, end, -4.0);
        assertEquals(start, undershot, "t 远小于 0 时必须钳到 t=0，结果等于起始色");

        // 不变量：任意越界因子下，四个通道都落在 [0, 255]
        for (double t : new double[] { -10.0, -0.001, 0.5, 1.001, 10.0 }) {
            int color = Interpolator.lerpColor(start, end, t);
            for (int shift : new int[] { 24, 16, 8, 0 }) {
                int value = channel(color, shift);
                assertTrue(value >= 0 && value <= 255,
                        "t=" + t + " 时通道位移 " + shift + " 必须在 [0, 255]，实际=" + value);
            }
        }
    }

    @Test
    @DisplayName("lerpColor：正常区间内与独立参考模型逐通道一致")
    void matchesReferenceModelInRange() {
        int start = (0x10 << 24) | (0x20 << 16) | (0x30 << 8) | 0x40;
        int end = (0x80 << 24) | (0xC0 << 16) | (0x50 << 8) | 0x60;

        for (double t : new double[] { 0.0, 0.25, 0.5, 0.75, 1.0 }) {
            int color = Interpolator.lerpColor(start, end, t);
            for (int shift : new int[] { 24, 16, 8, 0 }) {
                assertEquals(referenceChannel(start, end, t, shift), channel(color, shift),
                        "t=" + t + " 位移 " + shift + " 应与参考模型一致");
            }
        }
    }

    /**
     * 独立的通道级参考模型：取出通道、线性插值、钳到 [0, 255]。
     *
     * 刻意不调用 Interpolator 的插值方法，避免与被测实现同源而失去对拍意义。
     */
    private static int referenceChannel(int startColor, int endColor, double t, int shift) {
        int start = (startColor >> shift) & 0xFF;
        int end = (endColor >> shift) & 0xFF;
        double value = start + (end - start) * t;
        return Math.max(0, Math.min(255, (int) value));
    }

    @Test
    @DisplayName("lerpColor：整数中点按通道独立取整")
    void midpointIsChannelWise() {
        int mid = Interpolator.lerpColor(0x00_00_00_00, 0xFF_FF_FF_FF, 0.5);

        assertEquals(127, channel(mid, 24), "alpha 中点应向下取整");
        assertEquals(127, channel(mid, 16), "red 中点应向下取整");
        assertEquals(127, channel(mid, 8), "green 中点应向下取整");
        assertEquals(127, channel(mid, 0), "blue 中点应向下取整");
    }
}
