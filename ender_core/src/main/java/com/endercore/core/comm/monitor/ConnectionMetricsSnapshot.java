/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：承载某一时刻的连接指标读数，作为监控数据的不可变传输对象。
 */
package com.endercore.core.comm.monitor;

import java.time.Instant;

 
/**
 * 连接指标快照。
 *
 * 由 ConnectionMetrics#snapshot 生成，一次性固化采样时刻的全部读数，供上层展示或上报。
 *
 * 设计约束：所有计数字段都是自连接建立以来的累计值而非区间增量，需要增量时由调用方对两次快照求差。
 *
 * 线程安全性：全部字段为 final，且都是不可变类型，构造完成后可安全跨线程共享。
 *
 * @since 1.0
 * @see ConnectionMetrics
 */
public final class ConnectionMetricsSnapshot {
    /** 采样时刻，由生成快照时的系统时钟给出。 */
    private final Instant createdAt;
    /** 累计发送字节数，单位字节，含协议头。 */
    private final long bytesSent;
    /** 累计接收字节数，单位字节，含协议头。 */
    private final long bytesReceived;
    /** 累计发送帧数，单位帧。 */
    private final long framesSent;
    /** 累计接收帧数，单位帧。 */
    private final long framesReceived;
    /** 累计发出的请求帧数。 */
    private final long requestsSent;
    /** 累计收到的响应帧数。 */
    private final long responsesReceived;
    /** 累计因超时失败的请求数。 */
    private final long requestTimeouts;
    /** 累计协议解析或校验失败次数。 */
    private final long protocolErrors;
    /** 采样时刻挂起的请求数，由调用方统计，不能为负。 */
    private final long pendingRequests;
    /** 最近一次请求的往返时延，单位毫秒；0 表示尚无观测。 */
    private final long lastRttMillis;

    /**
     * 以一次采样的全部读数构造快照。
     *
     * 各参数按依赖顺序一次性注入，读者不应假设参数之间满足算术关系。
     *
     * @param createdAt 采样时刻，不能为 null
     * @param bytesSent 累计发送字节数，非负
     * @param bytesReceived 累计接收字节数，非负
     * @param framesSent 累计发送帧数，非负
     * @param framesReceived 累计接收帧数，非负
     * @param requestsSent 累计发出的请求帧数，非负
     * @param responsesReceived 累计收到的响应帧数，非负
     * @param requestTimeouts 累计超时请求数，非负
     * @param protocolErrors 累计协议错误数，非负
     * @param pendingRequests 采样时刻挂起的请求数，非负
     * @param lastRttMillis 最近一次往返时延毫秒数，非负，0 表示尚无观测
     */
    public ConnectionMetricsSnapshot(
            Instant createdAt,
            long bytesSent,
            long bytesReceived,
            long framesSent,
            long framesReceived,
            long requestsSent,
            long responsesReceived,
            long requestTimeouts,
            long protocolErrors,
            long pendingRequests,
            long lastRttMillis
    ) {
        this.createdAt = createdAt;
        this.bytesSent = bytesSent;
        this.bytesReceived = bytesReceived;
        this.framesSent = framesSent;
        this.framesReceived = framesReceived;
        this.requestsSent = requestsSent;
        this.responsesReceived = responsesReceived;
        this.requestTimeouts = requestTimeouts;
        this.protocolErrors = protocolErrors;
        this.pendingRequests = pendingRequests;
        this.lastRttMillis = lastRttMillis;
    }

    /**
     * 获取采样时刻。
     *
     * @return 采样时刻，永不为 null
     */
    public Instant createdAt() {
        return createdAt;
    }

    /**
     * 获取累计发送字节数。
     *
     * @return 发送字节数，单位字节，非负
     */
    public long bytesSent() {
        return bytesSent;
    }

    /**
     * 获取累计接收字节数。
     *
     * @return 接收字节数，单位字节，非负
     */
    public long bytesReceived() {
        return bytesReceived;
    }

    /**
     * 获取累计发送帧数。
     *
     * @return 发送帧数，单位帧，非负
     */
    public long framesSent() {
        return framesSent;
    }

    /**
     * 获取累计接收帧数。
     *
     * @return 接收帧数，单位帧，非负
     */
    public long framesReceived() {
        return framesReceived;
    }

    /**
     * 获取累计发出的请求帧数。
     *
     * @return 请求帧数，非负
     */
    public long requestsSent() {
        return requestsSent;
    }

    /**
     * 获取累计收到的响应帧数。
     *
     * @return 响应帧数，非负
     */
    public long responsesReceived() {
        return responsesReceived;
    }

    /**
     * 获取累计超时请求数。
     *
     * @return 超时请求数，非负
     */
    public long requestTimeouts() {
        return requestTimeouts;
    }

    /**
     * 获取累计协议错误数。
     *
     * @return 协议错误数，非负
     */
    public long protocolErrors() {
        return protocolErrors;
    }

    /**
     * 获取采样时刻挂起的请求数。
     *
     * @return 挂起请求数，非负
     */
    public long pendingRequests() {
        return pendingRequests;
    }

    /**
     * 获取最近一次往返时延。
     *
     * @return 往返时延，单位毫秒，非负，0 表示尚无观测
     */
    public long lastRttMillis() {
        return lastRttMillis;
    }
}
