/*
 * 本文件属于 EnderOnline 单元测试。
 *
 * 职责：固化 RoomCodec 的字节格式，作为房间事件编解码的唯一回归基线。
 *
 * 关键约束：本测试固化的是「现有线上格式」，不是「理想格式」。改动编码格式即为协议破坏性变更，
 * 必须先在本文件中显式更新期望值，而不是让实现悄悄漂移。
 */
package com.endercore.core.comm.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link RoomCodec} 的行为固化测试。
 *
 * 这组测试守护什么：字符串的「2 字节长度 + UTF-8」格式、null 与空串都写成零长度、
 * 成员集合的计数与顺序、以及四个负载构建方法在编码失败时返回空数组而不是抛出异常。
 *
 * 注意：本文件的断言消息可保留中文——测试源码已从 i18n 守卫中排除，中文失败信息更易读。
 *
 * 线程安全性：每个用例各自建立独立的流对象，无共享可变状态，可并行执行。
 *
 * @see RoomCodec
 */
class RoomCodecTest {

    @Test
    @DisplayName("字符串往返：ASCII / 中文 / 空串 / null 都能正确还原")
    void stringRoundTrip() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        RoomCodec.writeString(out, "room-1");
        RoomCodec.writeString(out, "末影联机");
        RoomCodec.writeString(out, "");
        RoomCodec.writeString(out, null);

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        assertEquals("room-1", RoomCodec.readString(in), "ASCII 字符串应原样还原");
        assertEquals("末影联机", RoomCodec.readString(in), "中文应按 UTF-8 正确还原");
        assertEquals("", RoomCodec.readString(in), "空串应还原为空串");
        assertEquals("", RoomCodec.readString(in), "null 写入后应还原为空串（零长度约定）");
    }

    @Test
    @DisplayName("writeString：空串与 null 都写成 2 字节零长度")
    void nullAndEmptyBothWriteZeroLength() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        RoomCodec.writeString(out, null);
        RoomCodec.writeString(out, "");

        byte[] bytes = baos.toByteArray();
        assertEquals(4, bytes.length, "两次零长度写入应恰好产生 4 字节");
        assertArrayEquals(new byte[]{0, 0, 0, 0}, bytes, "零长度编码应为两个 0x0000");
    }

    @Test
    @DisplayName("字符串长度前缀用 unsigned short，最多 65535 字节")
    void lengthPrefixIsUnsignedShort() throws Exception {
        // 65535 字节的 ASCII 串应当可写入（正好用满 2 字节长度前缀）
        String maxAscii = "a".repeat(65535);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        RoomCodec.writeString(out, maxAscii);

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        assertEquals(65535, RoomCodec.readString(in).length(),
                "65535 字节是长度前缀的上限，应可往返");
    }

    @Test
    @DisplayName("writeString：超过 65535 字节时抛 IllegalArgumentException")
    void tooLongStringIsRejected() throws Exception {
        String tooLong = "a".repeat(65536);
        DataOutputStream out = new DataOutputStream(new ByteArrayOutputStream());

        assertThrows(IllegalArgumentException.class,
                () -> RoomCodec.writeString(out, tooLong),
                "超出长度前缀容量时应显式拒绝，而不是写入被截断的长度");
    }

    @Test
    @DisplayName("writeMembers：先写成员数，再按迭代顺序逐个写入")
    void membersAreCountedAndOrdered() throws Exception {
        Set<String> members = new LinkedHashSet<>();
        members.add("m-1");
        members.add("m-2");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        RoomCodec.writeMembers(out, members);

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        assertEquals(2, in.readUnsignedShort(), "成员数应写在最前面");
        assertEquals("m-1", RoomCodec.readString(in), "成员应按迭代顺序写入");
        assertEquals("m-2", RoomCodec.readString(in), "成员应按迭代顺序写入");
    }

    @Test
    @DisplayName("writeMembers：空集合只写成员数 0")
    void emptyMembersWritesZeroCount() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        RoomCodec.writeMembers(out, new LinkedHashSet<>());

        byte[] bytes = baos.toByteArray();
        assertEquals(2, bytes.length, "空集合应只产生成员数这 2 字节");
        assertArrayEquals(new byte[]{0, 0}, bytes, "成员数应为 0");
    }

    @Test
    @DisplayName("payloadRoom：内容等于「房间码」的字符串编码")
    void payloadRoomMatchesStringEncoding() throws Exception {
        ByteArrayOutputStream expectedStream = new ByteArrayOutputStream();
        DataOutputStream expectedOut = new DataOutputStream(expectedStream);
        RoomCodec.writeString(expectedOut, "U/AAAA-BBBB");

        assertArrayEquals(expectedStream.toByteArray(), RoomCodec.payloadRoom("U/AAAA-BBBB"),
                "房间事件负载应等于房间码的字符串编码");
    }

    @Test
    @DisplayName("payloadRoomMember：房间码与成员标识依次写入")
    void payloadRoomMemberHasBothFields() throws Exception {
        DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(RoomCodec.payloadRoomMember("r-1", "m-1")));

        assertEquals("r-1", RoomCodec.readString(in), "第一个字段应为房间码");
        assertEquals("m-1", RoomCodec.readString(in), "第二个字段应为成员标识");
    }

    @Test
    @DisplayName("payloadRoomMeta：元数据为 null 时只写长度 0")
    void payloadRoomMetaHandlesNull() throws Exception {
        DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(RoomCodec.payloadRoomMeta("r-1", null)));

        assertEquals("r-1", RoomCodec.readString(in), "第一个字段应为房间码");
        assertEquals(0, in.readInt(), "null 元数据应写成长度 0");
        assertEquals(0, in.available(), "null 元数据不应残留任何字节");
    }

    @Test
    @DisplayName("payloadRoomMeta：元数据非空时按长度 + 内容写入")
    void payloadRoomMetaWritesBody() throws Exception {
        byte[] meta = "{\"k\":1}".getBytes(StandardCharsets.UTF_8);
        DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(RoomCodec.payloadRoomMeta("r-1", meta)));

        assertEquals("r-1", RoomCodec.readString(in), "第一个字段应为房间码");
        assertEquals(meta.length, in.readInt(), "应写入元数据实际长度");
        byte[] read = new byte[meta.length];
        in.readFully(read);
        assertArrayEquals(meta, read, "元数据内容应原样还原");
    }

    @Test
    @DisplayName("payloadRoomMessage：四个字段依次写入，消息体为 null 时长度为 0")
    void payloadRoomMessageFieldOrder() throws Exception {
        DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(RoomCodec.payloadRoomMessage("r-1", "from-1", "chat", null)));

        assertEquals("r-1", RoomCodec.readString(in), "字段 1 应为房间码");
        assertEquals("from-1", RoomCodec.readString(in), "字段 2 应为发送者");
        assertEquals("chat", RoomCodec.readString(in), "字段 3 应为频道");
        assertEquals(0, in.readInt(), "null 消息体应写成长度 0");
        assertEquals(0, in.available(), "null 消息体不应残留任何字节");
    }

    @Test
    @DisplayName("payloadRoom：编码失败时返回空数组而不是抛出异常（既有契约）")
    void payloadFailureReturnsEmptyArray() {
        // 通过超长房间码触发编码失败，验证失败路径返回空数组
        String tooLong = "x".repeat(70000);
        byte[] payload = RoomCodec.payloadRoom(tooLong);

        assertEquals(0, payload.length,
                "编码失败应返回空数组；事件负载允许为空，不允许中断房间流程");
    }

    @Test
    @DisplayName("readString：长度前缀声明超出剩余数据时抛异常（不做静默截断）")
    void truncatedPayloadIsRejected() {
        // 声明长度 10，但只提供 3 字节内容
        byte[] truncated = new byte[]{0, 10, 'a', 'b', 'c'};
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(truncated));

        assertThrows(Exception.class, () -> RoomCodec.readString(in),
                "剩余数据不足声明长度时必须抛异常，不能返回被截断的字符串");
    }

    @Test
    @DisplayName("readString：零长度返回空串，且不消费后续字节")
    void zeroLengthDoesNotConsumeBody() throws Exception {
        byte[] data = new byte[]{0, 0, 0, 5, 'a', 'b', 'c', 'd', 'e'};
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));

        assertEquals("", RoomCodec.readString(in), "零长度应返回空串");
        assertEquals("abcde", RoomCodec.readString(in), "后续值应为第二段内容");
        assertEquals(0, in.available(), "读取应恰好消费到流末尾");
    }
}
