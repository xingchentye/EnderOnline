/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：定义服务端处理请求的回调契约，是业务逻辑接入协议栈的唯一入口。
 */
package com.endercore.core.comm.server;

import com.endercore.core.comm.protocol.CoreResponse;

 
/**
 * 请求处理器契约。
 *
 * 由 CoreWebSocketServer 按 kind 注册，收到对应请求帧时在 handlerExecutor 上调用。
 *
 * 契约说明：
 * 1. 返回 null 时服务端会回送状态码 255 的响应，而不会崩溃或静默丢弃请求。
 * 2. 抛出的异常会被服务端捕获并转成状态码 255 的响应，异常文本作为响应负载。
 * 3. 处理器不得长时间阻塞，否则会占用 handlerExecutor 的线程并拖慢同批请求。
 * 4. request 中的 payload 与 remoteAddress 都可能为 null 语义（空数组与 null 地址），需分别处理。
 *
 * 线程安全性：实现类必须线程安全；不同连接乃至同一连接的多个请求都可能并发进入本方法。
 *
 * @since 1.0
 * @see CoreRequest
 * @see CoreResponse
 */
@FunctionalInterface
public interface CoreRequestHandler {
    /**
     * 处理请求。
     *
     * @param request 请求上下文，不能为 null
     * @return 响应对象；返回 null 时服务端以状态码 255 回送
     * @throws Exception 处理失败时抛出；异常会被服务端转换为状态码 255 的响应
     */
    CoreResponse handle(CoreRequest request) throws Exception;
}
