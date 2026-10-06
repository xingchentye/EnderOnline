/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：房间事件的二进制编解码，独立于房间注册表与业务处理器。
 *
 * 这些方法原先内联在 CoreRooms 的 RoomManager 里，使该类同时承担「房间注册」与
 * 「字节编解码」两件事；抽出后编解码可以单独测试，房间注册逻辑也不再被编解码细节淹没。
 */
package com.endercore.core.comm.server;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 房间事件的二进制编解码工具。
 *
 * 字符串一律采用「2 字节无符号长度 + UTF-8 字节」格式；所有方法都是纯静态、无状态，
 * 可被多线程并发调用。
 *
 * 设计约束：
 * 1. 负载构建方法（{@code payload*}）在编码失败时返回**空数组**而不是抛出异常，
 *    这是既有行为：事件负载允许为空，调用方不应因编码失败而中断房间流程。
 * 2. 字符串长度上限为 65535 字节（受 2 字节长度前缀限制），超出时 writeString 抛出
 *    IllegalArgumentException。
 *
 * 线程安全性：无共享可变状态；每次调用各自创建独立的流对象。
 *
 * @see CoreRooms
 */
final class RoomCodec {

    /** 私有构造函数，防止实例化。 */
    private RoomCodec() {
    }

    /**
     * 读取一个长度前缀字符串。
     *
     * 格式：长度（2 字节无符号短整型）+ UTF-8 字节；长度为 0 时直接返回空串。
     *
     * @param in 输入流，不能为 null
     * @return 解码后的字符串，永不为 null，长度前缀为 0 时返回空串
     * @throws Exception 当流读取失败或剩余数据不足声明长度时抛出
     */
    static String readString(DataInputStream in) throws Exception {
        int len = in.readUnsignedShort();
        if (len == 0) {
            return "";
        }
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 写入一个长度前缀字符串。
     *
     * 格式：长度（2 字节无符号短整型）+ UTF-8 字节；null 与空串都写成零长度。
     *
     * @param out 输出流，不能为 null
     * @param s 待写入字符串，允许为 null
     * @throws IllegalArgumentException 当 UTF-8 编码后长度超过 65535 字节时抛出
     * @throws Exception 当底层流写入失败时抛出
     */
    static void writeString(DataOutputStream out, String s) throws Exception {
        if (s == null || s.isEmpty()) {
            out.writeShort(0);
            return;
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65535) {
            throw new IllegalArgumentException("string too long");
        }
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    /**
     * 写入成员标识集合。
     *
     * 格式：成员数（2 字节无符号短整型）+ 逐个长度前缀字符串；集合的迭代顺序即写入顺序。
     *
     * @param out 输出流，不能为 null
     * @param memberIds 成员标识集合，不能为 null
     * @throws Exception 当底层流写入失败时抛出
     */
    static void writeMembers(DataOutputStream out, Set<String> memberIds) throws Exception {
        out.writeShort(memberIds.size());
        for (String id : memberIds) {
            writeString(out, id);
        }
    }

    /**
     * 构建房间事件负载。
     *
     * @param roomId 房间码，不能为 null
     * @return 仅含房间码的负载；编码失败时返回空数组而不抛出异常
     */
    static byte[] payloadRoom(String roomId) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            writeString(out, roomId);
            return baos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    /**
     * 构建房间成员事件负载。
     *
     * @param roomId 房间码，不能为 null
     * @param memberId 成员标识，不能为 null
     * @return 房间码与成员标识依次写入的负载；编码失败时返回空数组而不抛出异常
     */
    static byte[] payloadRoomMember(String roomId, String memberId) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            writeString(out, roomId);
            writeString(out, memberId);
            return baos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    /**
     * 构建房间元数据事件负载。
     *
     * @param roomId 房间码，不能为 null
     * @param meta 元数据，允许为 null，为 null 时只写入长度 0
     * @return 房间码、元数据长度与元数据依次写入的负载；编码失败时返回空数组而不抛出异常
     */
    static byte[] payloadRoomMeta(String roomId, byte[] meta) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            writeString(out, roomId);
            out.writeInt(meta == null ? 0 : meta.length);
            if (meta != null && meta.length > 0) {
                out.write(meta);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    /**
     * 构建房间消息事件负载。
     *
     * @param roomId 房间码，不能为 null
     * @param fromId 发送者标识，不能为 null
     * @param channel 频道名，不能为 null
     * @param message 消息体，允许为 null，为 null 时只写入长度 0
     * @return 房间码、发送者、频道、消息长度与消息体依次写入的负载；编码失败时返回空数组而不抛出异常
     */
    static byte[] payloadRoomMessage(String roomId, String fromId, String channel, byte[] message) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            writeString(out, roomId);
            writeString(out, fromId);
            writeString(out, channel);
            out.writeInt(message == null ? 0 : message.length);
            if (message != null && message.length > 0) {
                out.write(message);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }
}
