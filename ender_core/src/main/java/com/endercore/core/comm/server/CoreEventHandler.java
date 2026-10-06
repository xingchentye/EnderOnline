/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：定义服务端处理单向事件的回调契约，与请求处理器并列构成服务端的两个扩展点。
 */
package com.endercore.core.comm.server;

import java.net.InetSocketAddress;

 
/**
 * 事件处理器契约。
 *
 * 用于接收客户端主动上报的单向事件；与 CoreRequestHandler 的区别是不产生响应，
 * 因此没有返回值，也无法把处理结果回传给发送方。
 *
 * 契约说明：
 * 1. 处理器抛出的异常会被服务端静默忽略，既不记录日志也不回传客户端。
 * 2. payload 是解码后的原始字节，永不为 null，可能为空数组。
 * 3. remoteAddress 在连接已断开或套接字地址不可用时可能为 null，使用前需判空。
 *
 * 线程安全性：实现类必须保证 handle 可被并发调用，事件在 handlerExecutor 上分发且顺序不作保证。
 *
 * @since 1.0
 * @see CoreRequestHandler
 * @see CoreRequest
 */
@FunctionalInterface
public interface CoreEventHandler {

    /**
     * 处理事件。
     *
     * @param kind 事件种类，形如 namespace:path，不能为 null
     * @param payload 事件负载的原始字节，不能为 null，可能为空数组
     * @param remoteAddress 事件来源的远端地址，可能为 null
     * @throws Exception 处理失败时抛出；异常由服务端静默忽略
     */
    void handle(String kind, byte[] payload, InetSocketAddress remoteAddress) throws Exception;
}
