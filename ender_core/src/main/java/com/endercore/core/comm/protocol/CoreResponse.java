/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：请求响应的内存表示，把响应帧还原为状态码、请求 ID、种类与负载。
 */
package com.endercore.core.comm.protocol;

import java.nio.charset.StandardCharsets;

 
/**
 * 请求响应。
 *
 * 由客户端在收到响应帧时构造，或由服务端处理器构造回包；requestId 用于把响应与请求配对。
 *
 * 设计约束：
 * 1. status 为 0 表示成功，非零表示对端定义的错误码，具体语义由各业务协议约定。
 * 2. payload 允许传 null，构造器归一化为空数组，因此读取端无需判空。
 * 3. kind 由帧内容决定，可能为空串，但不会为 null。
 *
 * 线程安全性：字段均为 final，但 payload 是可变数组引用且不做防御性拷贝，
 * 只有在调用方不再修改该数组时，本对象才可跨线程共享。
 *
 * @since 1.0
 * @see CoreFrame
 * @see CoreMessageType
 */
public final class CoreResponse {
    /**
     * 响应状态码。
     *
     * 0 表示成功，非零为对端定义的错误码。
     */
    private final int status;

    /**
     * 关联的请求 ID，与发起请求时分配的取值一致。
     */
    private final long requestId;

    /**
     * 响应种类，形如 namespace:path。
     *
     * 由响应帧内容决定，可能为空串，不会为 null。
     */
    private final String kind;

    /**
     * 响应负载的原始字节。
     *
     * 不允许为 null，构造时归一化为空数组；引用由调用方持有，本类不做拷贝。
     */
    private final byte[] payload;

    /**
     * 构造响应。
     *
     * payload 为 null 时按空数组处理，其它字段原样保存。
     *
     * @param status 响应状态码，0 表示成功
     * @param requestId 关联的请求 ID
     * @param kind 响应种类，不能为 null，可为空串
     * @param payload 响应负载，允许为 null，为 null 时归一化为空数组
     */
    public CoreResponse(int status, long requestId, String kind, byte[] payload) {
        this.status = status;
        this.requestId = requestId;
        this.kind = kind;
        this.payload = payload == null ? new byte[0] : payload;
    }

    /**
     * 获取响应状态码。
     *
     * @return 响应状态码，0 表示成功
     */
    public int status() {
        return status;
    }

    /**
     * 获取关联的请求 ID。
     *
     * @return 请求 ID
     */
    public long requestId() {
        return requestId;
    }

    /**
     * 获取响应种类。
     *
     * @return 响应种类，形如 namespace:path，可能为空串，永不为 null
     */
    public String kind() {
        return kind;
    }

    /**
     * 获取响应负载。
     *
     * 返回内部数组本身而非副本，调用方修改其内容会影响本对象。
     *
     * @return 响应负载的原始字节，永不为 null，可能为空数组
     */
    public byte[] payload() {
        return payload;
    }

    /**
     * 判断本次响应是否成功。
     *
     * @return 状态码为 0 时返回 true，否则返回 false
     */
    public boolean isOk() {
        return status == 0;
    }

    /**
     * 按 UTF-8 解码响应负载。
     *
     * 负载不是合法 UTF-8 序列时按 Java 的替换字符语义处理，不抛出异常。
     *
     * @return 负载解码后的字符串，永不为 null，负载为空时返回空串
     */
    public String payloadUtf8() {
        return new String(payload, StandardCharsets.UTF_8);
    }
}
