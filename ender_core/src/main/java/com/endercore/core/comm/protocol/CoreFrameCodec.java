/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：协议帧的编解码，把 CoreFrame 与字节序列互相转换，不涉及连接管理与业务语义。
 */
package com.endercore.core.comm.protocol;

import com.endercore.core.comm.exception.CoreProtocolException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

 
/**
 * 协议帧编解码器。
 *
 * 字段读写顺序由 CoreProtocol 中的帧头布局固定，是协议格式的唯一实现点；
 * 不持有连接状态、不做重试、不感知业务语义。
 *
 * 设计约束：
 * 1. 编码与解码都执行长度上限校验，超过 maxFrameBytes 的帧一律拒绝。
 * 2. 解码要求入参恰好包含一帧：帧头声明的长度与剩余字节数不一致时抛错；
 *    协议约定一帧对应一条 WebSocket 消息，因此这里刻意不做粘包与分包处理。
 * 3. kind 以 UTF-8 编码，长度上界由帧头 2 字节字段决定，即 65535 字节。
 *
 * 线程安全性：本类无可变状态，encode 可被任意线程并发调用；
 * decode 会推进入参 ByteBuffer 的 position，因此同一个 ByteBuffer 不可被并发使用。
 *
 * @since 1.0
 * @see CoreProtocol
 * @see CoreFrame
 */
public final class CoreFrameCodec {
    /**
     * 单帧最大字节数，含协议头。
     *
     * 由构造器注入并校验，之后不可变。
     */
    private final int maxFrameBytes;

    /**
     * 构造编解码器。
     *
     * @param maxFrameBytes 单帧最大字节数，不得小于 CoreProtocol.HEADER_BYTES
     * @throws IllegalArgumentException 当 maxFrameBytes 小于协议头长度时抛出
     */
    public CoreFrameCodec(int maxFrameBytes) {
        if (maxFrameBytes < CoreProtocol.HEADER_BYTES) {
            throw new IllegalArgumentException("maxFrameBytes 过小: " + maxFrameBytes);
        }
        this.maxFrameBytes = maxFrameBytes;
    }

    /**
     * 编码一帧。
     *
     * 输出长度等于协议头长度加 kind 与 payload 的字节数，不做压缩与对齐。
     *
     * @param frame 待编码的帧，不能为 null
     * @return 编码后的字节数组，长度不超过 maxFrameBytes
     * @throws CoreProtocolException 当 kind 超过 65535 字节或帧总长度超过 maxFrameBytes 时抛出
     */
    public byte[] encode(CoreFrame frame) {
        byte[] kindBytes = frame.kind().getBytes(StandardCharsets.UTF_8);
        byte[] payload = frame.payload();

        if (kindBytes.length > 0xFFFF) {
            throw new CoreProtocolException("kind 过长: " + kindBytes.length);
        }
        if (payload.length < 0) {
            throw new CoreProtocolException("payload 长度非法: " + payload.length);
        }

        int total = CoreProtocol.HEADER_BYTES + kindBytes.length + payload.length;
        if (total > maxFrameBytes) {
            throw new CoreProtocolException("帧大小超过上限: " + total + " > " + maxFrameBytes);
        }

        ByteBuffer buf = ByteBuffer.allocate(total);
        buf.put(CoreProtocol.MAGIC_0);
        buf.put(CoreProtocol.MAGIC_1);
        buf.put(CoreProtocol.VERSION);
        buf.put(frame.type().code());
        buf.put(frame.flags());
        buf.put((byte) (frame.status() & 0xFF));
        buf.putLong(frame.requestId());
        buf.putShort((short) (kindBytes.length & 0xFFFF));
        buf.putInt(payload.length);
        buf.put(kindBytes);
        buf.put(payload);
        return buf.array();
    }

    /**
     * 解码一帧。
     *
     * 解码会消耗入参的剩余字节：成功时 position 推进到帧尾，失败时 position 的位置不确定，
     * 因此调用方在捕获异常后不应继续复用该缓冲区。
     *
     * @param input 恰好包含一帧数据的缓冲区，不能为 null
     * @return 解码出的帧对象，永不为 null
     * @throws CoreProtocolException 当 input 为 null、长度不足、魔数或版本不匹配、消息类型未知、
     *                               帧头声明长度与实际剩余字节不一致或帧长超过 maxFrameBytes 时抛出
     */
    public CoreFrame decode(ByteBuffer input) {
        if (input == null) {
            throw new CoreProtocolException("空帧");
        }
        if (input.remaining() > maxFrameBytes) {
            throw new CoreProtocolException("帧大小超过上限: " + input.remaining() + " > " + maxFrameBytes);
        }
        if (input.remaining() < CoreProtocol.HEADER_BYTES) {
            throw new CoreProtocolException("帧长度不足: " + input.remaining());
        }

        byte magic0 = input.get();
        byte magic1 = input.get();
        if (magic0 != CoreProtocol.MAGIC_0 || magic1 != CoreProtocol.MAGIC_1) {
            throw new CoreProtocolException("magic 不匹配");
        }

        byte version = input.get();
        if (version != CoreProtocol.VERSION) {
            throw new CoreProtocolException("版本不支持: " + (version & 0xFF));
        }

        CoreMessageType type;
        try {
            type = CoreMessageType.from(input.get());
        } catch (IllegalArgumentException e) {
            throw new CoreProtocolException("type 不支持", e);
        }

        byte flags = input.get();
        int status = input.get() & 0xFF;
        long requestId = input.getLong();
        int kindLen = input.getShort() & 0xFFFF;
        int payloadLen = input.getInt();
        if (payloadLen < 0) {
            throw new CoreProtocolException("payloadLen 非法: " + payloadLen);
        }

        int need = CoreProtocol.HEADER_BYTES + kindLen + payloadLen;
        if (need > maxFrameBytes) {
            throw new CoreProtocolException("帧大小超过上限: " + need + " > " + maxFrameBytes);
        }
        if (input.remaining() != kindLen + payloadLen) {
            throw new CoreProtocolException("帧长度不一致: remaining=" + input.remaining() + ", expected=" + (kindLen + payloadLen));
        }

        byte[] kindBytes = new byte[kindLen];
        input.get(kindBytes);
        String kind = new String(kindBytes, StandardCharsets.UTF_8);

        byte[] payload = new byte[payloadLen];
        input.get(payload);

        return new CoreFrame(type, flags, status, requestId, kind, payload);
    }
}
