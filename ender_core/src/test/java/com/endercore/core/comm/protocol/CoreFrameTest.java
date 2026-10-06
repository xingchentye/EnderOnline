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
 * <p>背景：{@code CoreFrame} 目前是 Java record，组件中含 {@code byte[]}。
 * record 自动生成的 {@code equals} 对数组使用引用比较（{@code Objects.equals}），
 * 而 {@code hashCode} 使用 {@code Arrays.hashCode}。两者语义不一致，
 * 导致「内容相同但引用不同」的两个帧既可能不相等、又可能哈希相同。</p>
 *
 * <p>这组测试用于「固化当前已知缺陷」：断言当前行为，
 * 以便 P4 把 {@code CoreFrame} 改为 final class 并显式实现
 * {@code equals}/{@code hashCode} 时，测试会失败并提醒更新断言。
 * 参见 claude_docs/baseline-audit.md 的缺陷 D1 与 06-logic-and-code-quality.md §2.3。</p>
 */
class CoreFrameTest {

    /** 构造一个各字段固定的帧，便于逐项比较。 */
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
