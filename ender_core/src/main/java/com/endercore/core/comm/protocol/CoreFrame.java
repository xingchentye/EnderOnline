/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：协议帧的内存表示，是编解码与业务分发之间的唯一数据载体。
 */
package com.endercore.core.comm.protocol;

import java.util.Objects;

/**
 * 协议帧。
 *
 * 一帧对应一次线上传输的完整消息单元，各字段与 CoreProtocol 定义的帧头字段一一对应。
 *
 * 设计约束：
 * 1. kind 与 payload 允许传 null，紧凑构造器会分别归一化为空串与空数组，因此读取端无需判空。
 * 2. status 只有响应帧有意义，0 表示成功、非零表示对端错误码；flags 当前固定为 0，为后续扩展保留。
 * 3. 事件帧与心跳帧的 requestId 固定为 0，不参与响应配对。
 *
 * 线程安全性：record 的组件均为 final，构造完成后不可变；但 payload 是可变数组引用，
 * 本类不做防御性拷贝，因此只有在调用方不再修改该数组时，帧实例才可跨线程共享。
 *
 * @param type 消息类型，不能为 null
 * @param flags 标志位，当前固定为 0
 * @param status 状态码，0 表示成功
 * @param requestId 请求 ID，事件帧与心跳帧固定为 0
 * @param kind 消息种类，形如 namespace:path，允许为 null，为 null 时归一化为空串
 * @param payload 负载的原始字节，允许为 null，为 null 时归一化为空数组
 * @since 1.0
 * @see CoreFrameCodec
 * @see CoreProtocol
 */
public record CoreFrame(CoreMessageType type, byte flags, int status, long requestId, String kind, byte[] payload) {
    /**
     * 校验并归一化各字段。
     *
     * 只把可空的 kind 与 payload 归一化为空串与空数组，其余字段原样保存。
     *
     * @param type 消息类型，不能为 null
     * @param flags 标志位
     * @param status 状态码
     * @param requestId 请求 ID
     * @param kind 消息种类，允许为 null，为 null 时归一化为空串
     * @param payload 负载，允许为 null，为 null 时归一化为空数组
     * @throws NullPointerException 当 type 为 null 时抛出
     */
    public CoreFrame {
        Objects.requireNonNull(type, "type");
        if (kind == null) kind = "";
        if (payload == null) payload = new byte[0];
    }
}
