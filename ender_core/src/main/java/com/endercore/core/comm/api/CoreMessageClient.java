/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：定义请求-响应与单向事件的发送契约。
 */
package com.endercore.core.comm.api;

import com.endercore.core.comm.protocol.CoreResponse;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

 
/**
 * 消息发送契约。
 *
 * 区分两种语义：请求需要等待对端响应并按 requestId 配对；事件是单向投递，不产生响应。
 *
 * 契约说明：
 * 1. kind 必须为 namespace:path 形式，格式非法时立即抛出 CoreProtocolException。
 * 2. payload 允许为 null，实现按空数组处理；入参数组由调用方持有，实现不得修改其内容。
 * 3. 连接不可用时，异步发送返回的 Future 以 CoreClosedException 异常完成，而事件发送直接抛出该异常。
 *
 * 线程安全性：实现类必须保证三个发送方法可被并发调用，且请求 ID 的分配不得重复。
 *
 * @since 1.0
 * @see CoreConnectionManager
 * @see CoreResponse
 */
public interface CoreMessageClient {

    /**
     * 异步发送请求。
     *
     * 本方法立即返回，不等待对端响应；超时由实现按配置的请求超时时间统一计时。
     *
     * @param kind 请求种类，形如 namespace:path，不能为 null
     * @param payload 请求负载，允许为 null，为 null 时按空数组发送
     * @return 响应 Future，永不为 null；对端返回非零状态码时以 CoreRemoteException 异常完成
     * @throws com.endercore.core.comm.exception.CoreProtocolException 当 kind 不是合法的 namespace:path 形式时抛出
     */
    CompletableFuture<CoreResponse> sendAsync(String kind, byte[] payload);

    /**
     * 同步发送请求。
     *
     * 在 sendAsync 返回的 Future 上按 timeout 阻塞等待；若失败原因本身是运行时异常，
     * 则原样抛出该异常，保持与异步路径一致的异常类型。
     *
     * @param kind 请求种类，形如 namespace:path，不能为 null
     * @param payload 请求负载，允许为 null，为 null 时按空数组发送
     * @param timeout 等待响应的最长时间，不能为 null
     * @return 对端响应，永不为 null
     * @throws com.endercore.core.comm.exception.CoreProtocolException 当 kind 格式非法时抛出
     * @throws com.endercore.core.comm.exception.CoreConnectException 当等待超时或线程被中断时抛出
     */
    CoreResponse sendSync(String kind, byte[] payload, Duration timeout);

    /**
     * 发送单向事件。
     *
     * 幂等性：本方法不幂等，每次调用都会向对端投递一条独立的事件帧。
     *
     * @param kind 事件种类，形如 namespace:path，不能为 null
     * @param payload 事件负载，允许为 null，为 null 时按空数组发送
     * @throws com.endercore.core.comm.exception.CoreProtocolException 当 kind 格式非法时抛出
     * @throws com.endercore.core.comm.exception.CoreClosedException 当连接不可用时抛出
     */
    void sendEvent(String kind, byte[] payload);
}
