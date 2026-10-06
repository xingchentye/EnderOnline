package com.endercore.core.comm.protocol;

import com.endercore.core.comm.exception.CoreProtocolException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link CoreFrameCodec} 编解码测试。
 *
 * <p>覆盖三类：
 * <ol>
 *   <li>往返（往返等价，含边界尺寸）</li>
 *   <li>畸形输入（必须被拒绝，且不得分配大数组或抛非受检异常）</li>
 *   <li>模糊测试（随机字节流不得导致未分类异常或 OOM）</li>
 * </ol>
 *
 * <p>协议版本为 v1（见 {@link CoreProtocol}）。本测试是协议 v2 改造（P4）的安全网：
 * 任何改动都必须先让这组测试通过。</p>
 */
class CoreFrameCodecTest {

    /** 与生产配置一致的上限：1 MiB。 */
    private static final int MAX_FRAME_BYTES = 1024 * 1024;

    private static CoreFrameCodec codec() {
        return new CoreFrameCodec(MAX_FRAME_BYTES);
    }

    /** 走完整的 encode -> decode 往返，返回解码结果。 */
    private static CoreFrame roundTrip(CoreFrameCodec codec, CoreFrame frame) {
        byte[] encoded = codec.encode(frame);
        return codec.decode(ByteBuffer.wrap(encoded));
    }

    private static void assertFrameEquals(CoreFrame expected, CoreFrame actual) {
        assertEquals(expected.type(), actual.type(), "type");
        assertEquals(expected.flags(), actual.flags(), "flags");
        assertEquals(expected.status(), actual.status(), "status");
        assertEquals(expected.requestId(), actual.requestId(), "requestId");
        assertEquals(expected.kind(), actual.kind(), "kind");
        assertArrayEquals(expected.payload(), actual.payload(), "payload");
    }

    // ---------------------------------------------------------------- 往返

    @Test
    @DisplayName("往返：空 payload、空 kind")
    void roundTripEmptyPayloadAndKind() {
        CoreFrame frame = new CoreFrame(CoreMessageType.HEARTBEAT, (byte) 0, 0, 0L, "", new byte[0]);
        assertFrameEquals(frame, roundTrip(codec(), frame));
    }

    @Test
    @DisplayName("往返：四种消息类型")
    void roundTripAllMessageTypes() {
        CoreFrameCodec codec = codec();
        for (CoreMessageType type : CoreMessageType.values()) {
            CoreFrame frame = new CoreFrame(type, (byte) 0x7F, 0, 1L, "room:list", new byte[] {1});
            assertFrameEquals(frame, roundTrip(codec, frame));
        }
    }

    @Test
    @DisplayName("往返：UTF-8 kind（含中文）字节数按 UTF-8 计算")
    void roundTripUtf8Kind() {
        // kind 允许任意 UTF-8；这里用中文验证长度字段按字节而非字符计算
        String kind = "房间:创建";
        CoreFrame frame = new CoreFrame(CoreMessageType.REQUEST, (byte) 0, 0, 7L, kind, new byte[0]);

        CoreFrame decoded = roundTrip(codec(), frame);

        assertEquals(kind, decoded.kind());
        assertEquals(kind.getBytes(StandardCharsets.UTF_8).length, decoded.kind().getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    @DisplayName("往返：status 0..255 全范围（v1 的 status 为无符号字节）")
    void roundTripStatusRange() {
        CoreFrameCodec codec = codec();
        for (int status = 0; status <= 255; status++) {
            CoreFrame frame = new CoreFrame(CoreMessageType.RESPONSE, (byte) 0, status, 0L, "k", new byte[0]);
            assertEquals(status, roundTrip(codec, frame).status(), "status=" + status);
        }
    }

    @Test
    @DisplayName("往返：requestId 边界与负值")
    void roundTripRequestIdBoundaries() {
        CoreFrameCodec codec = codec();
        long[] samples = {0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE};

        for (long id : samples) {
            CoreFrame frame = new CoreFrame(CoreMessageType.REQUEST, (byte) 0, 0, id, "k", new byte[0]);
            assertEquals(id, roundTrip(codec, frame).requestId(), "requestId=" + id);
        }
    }

    @Test
    @DisplayName("往返：最大允许 payload（恰好达到上限）")
    void roundTripMaxPayload() {
        int kindLen = "room:info".length();
        int payloadLen = MAX_FRAME_BYTES - CoreProtocol.HEADER_BYTES - kindLen;

        byte[] payload = new byte[payloadLen];
        new Random(1234).nextBytes(payload);

        CoreFrame frame = new CoreFrame(CoreMessageType.EVENT, (byte) 0, 0, 5L, "room:info", payload);
        CoreFrame decoded = roundTrip(codec(), frame);

        assertArrayEquals(payload, decoded.payload());
    }

    @Test
    @DisplayName("编码：kind 超过 0xFFFF 字节时拒绝")
    void encodeRejectsOversizedKind() {
        StringBuilder sb = new StringBuilder(70000);
        for (int i = 0; i < 70000; i++) {
            sb.append('a');
        }
        CoreFrame frame = new CoreFrame(CoreMessageType.REQUEST, (byte) 0, 0, 0L, sb.toString(), new byte[0]);

        assertThrows(CoreProtocolException.class, () -> codec().encode(frame));
    }

    @Test
    @DisplayName("编码：帧总长超过 maxFrameBytes 时拒绝")
    void encodeRejectsOversizedFrame() {
        byte[] payload = new byte[MAX_FRAME_BYTES];
        CoreFrame frame = new CoreFrame(CoreMessageType.EVENT, (byte) 0, 0, 0L, "k", payload);

        assertThrows(CoreProtocolException.class, () -> codec().encode(frame));
    }

    @Test
    @DisplayName("编码：头部布局与 CoreProtocol 常量一致（防协议悄悄改版）")
    void encodedHeaderLayoutIsStable() {
        CoreFrame frame = new CoreFrame(CoreMessageType.REQUEST, (byte) 0xAB, 0x05, 1L, "k", new byte[] {0x7F});
        byte[] bytes = codec().encode(frame);

        assertEquals(CoreProtocol.HEADER_BYTES + 1 + 1, bytes.length, "总长度 = 头 + kind + payload");
        assertEquals(CoreProtocol.MAGIC_0, bytes[0], "magic0");
        assertEquals(CoreProtocol.MAGIC_1, bytes[1], "magic1");
        assertEquals(CoreProtocol.VERSION, bytes[2], "version");
        assertEquals(CoreMessageType.REQUEST.code(), bytes[3], "type");
        assertEquals((byte) 0xAB, bytes[4], "flags");
        assertEquals((byte) 0x05, bytes[5], "status");
        assertEquals(1L, ByteBuffer.wrap(bytes, 6, 8).getLong(), "requestId");
        assertEquals(1, ByteBuffer.wrap(bytes, 14, 2).getShort(), "kindLen");
        assertEquals(1, ByteBuffer.wrap(bytes, 16, 4).getInt(), "payloadLen");
    }

    // ---------------------------------------------------------------- 畸形输入

    @Test
    @DisplayName("解码：null 输入被拒绝")
    void decodeRejectsNull() {
        assertThrows(CoreProtocolException.class, () -> codec().decode(null));
    }

    @Test
    @DisplayName("解码：长度不足头部时拒绝")
    void decodeRejectsTruncatedHeader() {
        CoreFrameCodec codec = codec();
        byte[] full = codec.encode(new CoreFrame(CoreMessageType.REQUEST, (byte) 0, 0, 0L, "k", new byte[0]));

        for (int len = 0; len < CoreProtocol.HEADER_BYTES; len++) {
            byte[] truncated = new byte[len];
            System.arraycopy(full, 0, truncated, 0, len);
            final int l = len;
            assertThrows(CoreProtocolException.class, () -> codec.decode(ByteBuffer.wrap(truncated)),
                    "长度 " + l + " 应被拒绝");
        }
    }

    @Test
    @DisplayName("解码：magic 错误时拒绝")
    void decodeRejectsBadMagic() {
        CoreFrameCodec codec = codec();
        byte[] bytes = codec.encode(new CoreFrame(CoreMessageType.REQUEST, (byte) 0, 0, 0L, "k", new byte[0]));
        bytes[0] = 0x00;

        assertThrows(CoreProtocolException.class, () -> codec.decode(ByteBuffer.wrap(bytes)));
    }

    @Test
    @DisplayName("解码：version 不匹配时拒绝（v1 是严格相等校验）")
    void decodeRejectsUnsupportedVersion() {
        CoreFrameCodec codec = codec();
        byte[] bytes = codec.encode(new CoreFrame(CoreMessageType.REQUEST, (byte) 0, 0, 0L, "k", new byte[0]));
        bytes[2] = (byte) 0x7F;

        assertThrows(CoreProtocolException.class, () -> codec.decode(ByteBuffer.wrap(bytes)));
    }

    @Test
    @DisplayName("解码：未知 type 时拒绝")
    void decodeRejectsUnknownType() {
        CoreFrameCodec codec = codec();
        byte[] bytes = codec.encode(new CoreFrame(CoreMessageType.REQUEST, (byte) 0, 0, 0L, "k", new byte[0]));
        bytes[3] = (byte) 0x7F;

        assertThrows(CoreProtocolException.class, () -> codec.decode(ByteBuffer.wrap(bytes)));
    }

    @Test
    @DisplayName("解码：payloadLen 为负数时拒绝，且不得尝试分配数组")
    void decodeRejectsNegativePayloadLen() {
        CoreFrameCodec codec = codec();
        byte[] bytes = codec.encode(new CoreFrame(CoreMessageType.REQUEST, (byte) 0, 0, 0L, "k", new byte[0]));
        // payloadLen 位于偏移 16，写入 0x80000000（int 最小负数）
        ByteBuffer.wrap(bytes).putInt(16, Integer.MIN_VALUE);

        assertThrows(CoreProtocolException.class, () -> codec.decode(ByteBuffer.wrap(bytes)));
    }

    @Test
    @DisplayName("解码：payloadLen 超过 maxFrameBytes 时拒绝，且不得分配大数组")
    void decodeRejectsOversizedPayloadLen() {
        CoreFrameCodec codec = codec();
        byte[] bytes = codec.encode(new CoreFrame(CoreMessageType.REQUEST, (byte) 0, 0, 0L, "k", new byte[0]));
        ByteBuffer.wrap(bytes).putInt(16, Integer.MAX_VALUE);

        assertThrows(CoreProtocolException.class, () -> codec.decode(ByteBuffer.wrap(bytes)));
    }

    @Test
    @DisplayName("解码：kindLen 与实际剩余长度不一致时拒绝")
    void decodeRejectsInconsistentKindLen() {
        CoreFrameCodec codec = codec();
        byte[] bytes = codec.encode(new CoreFrame(CoreMessageType.REQUEST, (byte) 0, 0, 0L, "ab", new byte[] {1}));
        // kindLen 位于偏移 14，声明比实际更长
        ByteBuffer.wrap(bytes).putShort(14, (short) 5);

        assertThrows(CoreProtocolException.class, () -> codec.decode(ByteBuffer.wrap(bytes)));
    }

    @Test
    @DisplayName("解码：尾部多余字节时拒绝（v1 假设一帧一 Buffer）")
    void decodeRejectsTrailingBytes() {
        CoreFrameCodec codec = codec();
        byte[] bytes = codec.encode(new CoreFrame(CoreMessageType.REQUEST, (byte) 0, 0, 0L, "k", new byte[0]));
        byte[] padded = new byte[bytes.length + 3];
        System.arraycopy(bytes, 0, padded, 0, bytes.length);

        assertThrows(CoreProtocolException.class, () -> codec.decode(ByteBuffer.wrap(padded)));
    }

    @Test
    @DisplayName("构造：maxFrameBytes 小于头部长度时拒绝")
    void constructorRejectsTooSmallMaxFrameBytes() {
        assertThrows(IllegalArgumentException.class, () -> new CoreFrameCodec(CoreProtocol.HEADER_BYTES - 1));
    }

    // ---------------------------------------------------------------- 模糊测试

    @Test
    @DisplayName("模糊：一万条随机字节流只允许抛 CoreProtocolException")
    void fuzzRandomBytesOnlyThrowsProtocolException() {
        CoreFrameCodec codec = codec();
        Random random = new Random(20260406L);
        int accepted = 0;

        for (int i = 0; i < 10000; i++) {
            int len = random.nextInt(80);
            byte[] data = new byte[len];
            random.nextBytes(data);

            try {
                codec.decode(ByteBuffer.wrap(data));
                accepted++;
            } catch (CoreProtocolException expected) {
                // ignore-reason: 随机字节被协议校验拒绝正是本测试要验证的行为
            } catch (RuntimeException unexpected) {
                throw new AssertionError(
                        "第 " + i + " 条随机输入抛出了未分类异常 " + unexpected.getClass().getName(), unexpected);
            }
        }

        // 随机字节几乎不可能通过 magic+version+type+长度一致性校验；
        // 若 accepted 异常高，说明校验被绕过。
        assertEquals(0, accepted, "随机字节不应被接受为合法帧");
    }

    @Test
    @DisplayName("模糊：合法帧被逐字节破坏后必须被拒绝或等价解码，不得崩溃")
    void fuzzSingleByteCorruption() {
        CoreFrameCodec codec = codec();
        byte[] original = codec.encode(
                new CoreFrame(CoreMessageType.REQUEST, (byte) 0x01, 0, 99L, "room:create", new byte[] {1, 2, 3, 4}));

        for (int pos = 0; pos < original.length; pos++) {
            byte[] mutated = original.clone();
            mutated[pos] = (byte) (mutated[pos] ^ 0xFF);

            try {
                codec.decode(ByteBuffer.wrap(mutated));
                // 有可能恰好仍构成合法帧（例如只改了 payload 内容），可接受
            } catch (CoreProtocolException expected) {
                // ignore-reason: 单字节破坏后被协议校验拒绝，是本测试的预期路径之一
            } catch (RuntimeException unexpected) {
                throw new AssertionError("破坏偏移 " + pos + " 后抛出未分类异常 "
                        + unexpected.getClass().getName(), unexpected);
            }
        }
    }
}
