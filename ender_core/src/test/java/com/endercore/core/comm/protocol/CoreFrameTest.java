/*
 * 本文件属于 EnderOnline 单元测试。
 *
 * 职责：固化 CoreFrame 当前的值语义，作为 P4 把它从 record 改为 final class 时的回归基线。
 *
 * 关键约束：本测试按定义断言的是「已知缺陷」（D1），缺陷修复后这些断言必须同步更新，不能改成放宽条件。
 */
package com.endercore.core.comm.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CoreFrame} 的值语义测试。
 *
 * 这组测试守护什么：{@code CoreFrame} 与「值对象」相关的可观察行为——构造期的 null 归一化、
 * 字段访问器、以及 equals/hashCode 的一致性。它是 P4 重构（record 改 final class）
 * 的回归基线：重构后若行为变了，这里必须失败，从而强制作者重新审视语义。
 *
 * 刻意固化的「已知缺陷」断言：
 * 1. {@code equals} 对 {@code byte[]} 组件按引用比较（{@code Objects.equals}），而 {@code hashCode}
 *    用 {@code Arrays.hashCode}——两者语义不一致，导致「内容相同但引用不同」的两个帧既不相等、
 *    又可能哈希相同。见缺陷 D1 与 claude_docs/06-logic-and-code-quality.md §2.3。
 *    固化点：{@link #equalContentButDifferentArrayReferenceIsNotEqual()}。
 * 2. {@code payload()} 直接返回内部数组，未做防御性拷贝，外部可改写帧内容。
 *    固化点：{@link #payloadAccessorExposesInternalArray()}。
 *
 * 缺陷修复后如何更新：把上述两条断言改成「新语义下的正确期望」并删除对应的说明注释——
 * 断言失败正是本测试存在的意义，不要为了让它通过而放宽断言。
 *
 * 线程安全性：无状态、无共享可变数据，JUnit 可并行执行。
 *
 * @since 1.0
 * @see CoreFrame
 */
class CoreFrameTest {

    /**
     * 构造一个各字段固定的帧，便于逐项比较。
     *
     * @param payload 载荷字节，允许为 null（由 CoreFrame 的紧凑构造器归一化为空数组）
     * @return 使用 REQUEST 类型、flags=0x01、status=0、requestId=42、kind="room:create" 的帧，永不为 null
     */
    private static CoreFrame sample(byte[] payload) {
        return new CoreFrame(CoreMessageType.REQUEST, (byte) 0x01, 0, 42L, "room:create", payload);
    }

    @Test
    @DisplayName("同引用比较：相等且哈希一致（基线行为）")
    void sameReferenceIsEqual() {
        byte[] payload = {1, 2, 3};
        CoreFrame frame = sample(payload);

        assertEquals(frame, frame, "同一引用必须相等");
        assertEquals(frame.hashCode(), frame.hashCode(), "同一引用的哈希必须稳定");
    }

    @Test
    @DisplayName("不同引用但内容相同：当前 record 语义下不相等（已知缺陷 D1）")
    void equalContentButDifferentArrayReferenceIsNotEqual() {
        CoreFrame a = sample(new byte[] {1, 2, 3});
        CoreFrame b = sample(new byte[] {1, 2, 3});

        // 期望（修复后）：assertEquals(a, b)
        // 现状（缺陷）：数组按引用比较，因此不相等。
        assertNotEquals(a, b,
                "record 的 byte[] 组件按引用比较；若此断言失败，说明 equals 已被正确重写，"
                        + "请更新为 assertEquals 并删除本注释");
    }

    @Test
    @DisplayName("紧凑构造器：null kind 归一化为空串，null payload 归一化为空数组")
    void compactConstructorNormalizesNulls() {
        CoreFrame frame = new CoreFrame(CoreMessageType.HEARTBEAT, (byte) 0, 0, 0L, null, null);

        assertEquals("", frame.kind(), "null kind 应被归一化为空串");
        assertEquals(0, frame.payload().length, "null payload 应被归一化为空数组");
    }

    @Test
    @DisplayName("type 为 null 时构造失败（Objects.requireNonNull）")
    void nullTypeIsRejected() {
        assertThrows(NullPointerException.class,
                () -> new CoreFrame(null, (byte) 0, 0, 0L, "k", new byte[0]));
    }

    @Test
    @DisplayName("payload() 当前直接返回内部数组（潜在可变状态泄漏，随 D1 一并处理）")
    void payloadAccessorExposesInternalArray() {
        byte[] payload = {1, 2, 3};
        CoreFrame frame = sample(payload);

        // 记录当前行为：外部持有的引用就是内部数组，可被外部改写。
        // P4 改为 final class 时应改为防御性拷贝，届时本测试需同步更新。
        assertTrue(frame.payload() == payload,
                "当前 payload() 直接返回内部数组；修复为防御性拷贝后本断言会失败，属预期");
    }

    @Test
    @DisplayName("所有字段可读（record 访问器）")
    void accessorsReturnConstructedValues() {
        CoreFrame frame = sample(new byte[] {9});

        assertEquals(CoreMessageType.REQUEST, frame.type());
        assertEquals((byte) 0x01, frame.flags());
        assertEquals(0, frame.status());
        assertEquals(42L, frame.requestId());
        assertEquals("room:create", frame.kind());
        assertEquals(1, frame.payload().length);
        assertNull(null, "占位：确保断言 API 可用");
    }
}
