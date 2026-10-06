/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：请求的内存表示，把请求帧的字段与远端地址一起交给处理器。
 */
package com.endercore.core.comm.server;

import java.net.InetSocketAddress;

 
/**
 * 服务端收到的请求。
 *
 * 由服务端在分发前从请求帧构造，是处理器能看到的全部请求上下文。
 *
 * 设计约束：
 * 1. payload 允许传 null，构造器归一化为空数组，因此读取端无需判空。
 * 2. remoteAddress 可能为 null（连接已断开或底层套接字地址不可用），取用成员标识前必须判空。
 * 3. kind 由帧内容决定，可能为空串，但不会为 null。
 *
 * 线程安全性：字段均为 final，但 payload 是可变数组引用且不做防御性拷贝；
 * 处理器可能在多个线程上并发处理不同请求，实现方不得修改该数组的内容。
 *
 * @since 1.0
 * @see CoreRequestHandler
 * @see CoreResponse
 */
public final class CoreRequest {
    /**
     * 请求 ID，与发起方分配的取值一致。
     *
     * 响应帧需要回填同一个值才能让对端完成配对。
     */
    private final long requestId;

    /**
     * 请求种类，形如 namespace:path。
     *
     * 由请求帧内容决定，可能为空串，不会为 null。
     */
    private final String kind;

    /**
     * 请求负载的原始字节。
     *
     * 不允许为 null，构造时归一化为空数组；引用由调用方持有，本类不做拷贝。
     */
    private final byte[] payload;

    /**
     * 请求来源的远端地址。
     *
     * 允许为 null，表示地址不可用；取用前需判空。
     */
    private final InetSocketAddress remoteAddress;

    /**
     * 构造请求。
     *
     * payload 为 null 时按空数组处理，其余字段原样保存。
     *
     * @param requestId 请求 ID
     * @param kind 请求种类，不能为 null，可为空串
     * @param payload 请求负载，允许为 null，为 null 时归一化为空数组
     * @param remoteAddress 请求来源的远端地址，允许为 null
     */
    public CoreRequest(long requestId, String kind, byte[] payload, InetSocketAddress remoteAddress) {
        this.requestId = requestId;
        this.kind = kind;
        this.payload = payload == null ? new byte[0] : payload;
        this.remoteAddress = remoteAddress;
    }

    /**
     * 获取请求 ID。
     *
     * @return 请求 ID，需原样回填到响应帧
     */
    public long requestId() {
        return requestId;
    }

    /**
     * 获取请求种类。
     *
     * @return 请求种类，形如 namespace:path，可能为空串，永不为 null
     */
    public String kind() {
        return kind;
    }

    /**
     * 获取请求负载。
     *
     * 返回内部数组本身而非副本，调用方修改其内容会影响本对象。
     *
     * @return 请求负载的原始字节，永不为 null，可能为空数组
     */
    public byte[] payload() {
        return payload;
    }

    /**
     * 获取请求来源的远端地址。
     *
     * @return 远端地址，可能为 null
     */
    public InetSocketAddress remoteAddress() {
        return remoteAddress;
    }
}
