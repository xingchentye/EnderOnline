/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：按连接累计收发量、请求量、错误数与最近往返时延，并提供只读快照。
 */
package com.endercore.core.comm.monitor;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

 
/**
 * 连接指标收集器。
 *
 * 只做累加与快照，不负责上报、不持有连接引用；一个实例对应一条连接。
 *
 * 设计约束：
 * 1. 计数器单调递增，两次快照相减即可得到区间增量。
 * 2. lastRttMillis 是「最近一次」而非累计值，会被后续请求覆盖，也可能为 0 表示尚无观测。
 *
 * 线程安全性：全部计数器为 AtomicLong，任意线程可无锁并发更新；
 * 但 snapshot 会逐个读取各计数器，因此同一快照内不同字段不保证取自同一瞬间，
 * 对监控用途足够，不可用于精确对账。
 *
 * @since 1.0
 * @see ConnectionMetricsSnapshot
 */
public final class ConnectionMetrics {
    /** 累计发送字节数，单位字节，含协议头，单调递增。 */
    private final AtomicLong bytesSent = new AtomicLong();
    /** 累计接收字节数，单位字节，含协议头，单调递增。 */
    private final AtomicLong bytesReceived = new AtomicLong();
    /** 累计发送帧数，单位帧，包含心跳帧与事件帧。 */
    private final AtomicLong framesSent = new AtomicLong();
    /** 累计接收帧数，单位帧，包含心跳帧与事件帧。 */
    private final AtomicLong framesReceived = new AtomicLong();
    /** 累计发出的请求帧数，不含事件帧与心跳帧。 */
    private final AtomicLong requestsSent = new AtomicLong();
    /** 累计收到的响应帧数，与 requestsSent 的差值近似反映在途请求。 */
    private final AtomicLong responsesReceived = new AtomicLong();
    /** 累计因超时失败的请求数。 */
    private final AtomicLong requestTimeouts = new AtomicLong();
    /** 累计协议解析或校验失败次数。 */
    private final AtomicLong protocolErrors = new AtomicLong();
    /** 最近一次请求的往返时延，单位毫秒；0 表示尚无观测。 */
    private final AtomicLong lastRttMillis = new AtomicLong();

    /**
     * 记录一次帧发送。
     *
     * @param bytes 本次发送的字节数，含协议头，取值为正
     */
    public void onFrameSent(int bytes) {
        framesSent.incrementAndGet();
        bytesSent.addAndGet(bytes);
    }

    /**
     * 记录一次帧接收。
     *
     * @param bytes 本次接收的字节数，含协议头，取值为正
     */
    public void onFrameReceived(int bytes) {
        framesReceived.incrementAndGet();
        bytesReceived.addAndGet(bytes);
    }

    /** 记录一次请求发送。 */
    public void onRequestSent() {
        requestsSent.incrementAndGet();
    }

    /** 记录一次响应接收。 */
    public void onResponseReceived() {
        responsesReceived.incrementAndGet();
    }

    /** 记录一次请求超时。 */
    public void onRequestTimeout() {
        requestTimeouts.incrementAndGet();
    }

    /** 记录一次协议错误。 */
    public void onProtocolError() {
        protocolErrors.incrementAndGet();
    }

    /**
     * 覆盖最近一次往返时延。
     *
     * 幂等性：本方法不累加，重复调用只保留最后一次取值。
     *
     * @param millis 往返时延毫秒数，非负
     */
    public void setLastRttMillis(long millis) {
        lastRttMillis.set(millis);
    }

    /**
     * 生成当前指标快照。
     *
     * 快照不包含挂起请求的统计能力，该值需由调用方自行统计并传入。
     *
     * @param pendingRequests 调用时刻尚未完成的请求数，由调用方统计，不能为负
     * @return 新建的不可变快照，永不为 null
     */
    public ConnectionMetricsSnapshot snapshot(long pendingRequests) {
        return new ConnectionMetricsSnapshot(
                Instant.now(),
                bytesSent.get(),
                bytesReceived.get(),
                framesSent.get(),
                framesReceived.get(),
                requestsSent.get(),
                responsesReceived.get(),
                requestTimeouts.get(),
                protocolErrors.get(),
                pendingRequests,
                lastRttMillis.get()
        );
    }
}
