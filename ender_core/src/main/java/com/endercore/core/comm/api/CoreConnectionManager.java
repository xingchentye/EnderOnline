/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：定义连接生命周期的契约，包括连接、关闭与状态查询，不涉及任何收发报文的语义。
 */
package com.endercore.core.comm.api;

import com.endercore.core.comm.monitor.ConnectionState;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

 
/**
 * 连接生命周期管理契约。
 *
 * 本接口只回答「能否连上、何时断开、当前处于什么状态」；报文收发由 CoreMessageClient 负责，
 * 状态与指标观测由 CoreStateMonitor 负责，三者在 CoreWebSocketClient 上由同一实例一并实现。
 *
 * 契约说明：
 * 1. 所有方法都不接受 null 参数，否则抛出 NullPointerException。
 * 2. connect 返回的 Future 在连接成功或失败时必定完成，不会永久挂起。
 * 3. close 返回的 Future 在关闭流程结束后完成，超出 timeout 则以超时异常完成。
 *
 * 线程安全性：实现类必须保证 connect、close、state、isConnected 可被并发调用，
 * 且状态读取必须返回某一确定时刻的值，不得返回中间态。
 *
 * @since 1.0
 * @see CoreMessageClient
 * @see CoreStateMonitor
 */
public interface CoreConnectionManager {

    /**
     * 连接到指定端点。
     *
     * 幂等性：已处于已连接状态时直接返回已完成的 Future；连接仍在进行中时返回同一个 Future，
     * 不会重复发起握手。
     *
     * @param endpoint 连接端点 URI，不能为 null
     * @return 连接完成的 Future，永不为 null；失败时以 CoreConnectException 异常完成
     */
    CompletableFuture<Void> connect(URI endpoint);

    /**
     * 关闭连接。
     *
     * 幂等性：重复调用是安全的；在从未连接过的实例上调用会立即以完成态返回。
     * 关闭过程中所有挂起的请求都会以 CoreClosedException 异常完成。
     *
     * @param timeout 等待关闭完成的最长时间，不能为 null
     * @return 关闭完成的 Future，永不为 null；超出 timeout 时以超时异常完成
     */
    CompletableFuture<Void> close(Duration timeout);

    /**
     * 获取当前连接状态。
     *
     * @return 当前连接状态，永不为 null
     */
    ConnectionState state();

    /**
     * 检查连接是否可用。
     *
     * 语义等价于 state() == ConnectionState.CONNECTED。
     *
     * @return 状态为 CONNECTED 时返回 true，否则返回 false
     */
    boolean isConnected();
}
