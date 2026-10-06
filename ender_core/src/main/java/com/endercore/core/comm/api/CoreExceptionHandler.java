/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：定义分类错误回调契约，让调用方按错误来源分别处理连接、协议、远程与超时失败。
 */
package com.endercore.core.comm.api;

 
/**
 * 错误回调契约。
 *
 * 四类错误的语义边界：
 * 1. 连接类：握手失败、对端断开、重连失败等本地链路问题。
 * 2. 协议类：帧解析失败、魔数或版本不匹配、收到不支持的文本帧。
 * 3. 远程类：对端返回非零状态码。
 * 4. 超时类：请求在配置时限内未收到响应。
 *
 * 契约说明：
 * 1. error 参数永不为 null。
 * 2. 回调在触发错误的线程上同步执行，可能是网络 IO 线程或内部调度线程。
 * 3. 实现不得抛出异常，否则会中断触发点后续的错误分发逻辑。
 *
 * 线程安全性：实现类必须保证四个回调方法可被并发调用。
 *
 * @since 1.0
 * @see CoreEventListener
 */
public interface CoreExceptionHandler {

    /**
     * 处理连接错误。
     *
     * @param error 连接错误异常，不能为 null，通常是 CoreConnectException
     */
    void onConnectionError(Throwable error);

    /**
     * 处理协议错误。
     *
     * @param error 协议错误异常，不能为 null，通常是 CoreProtocolException
     */
    void onProtocolError(Throwable error);

    /**
     * 处理远程错误。
     *
     * @param error 远程错误异常，不能为 null，通常是 CoreRemoteException
     */
    void onRemoteError(Throwable error);

    /**
     * 处理请求超时。
     *
     * @param error 超时异常，不能为 null，通常是 CoreTimeoutException
     */
    void onTimeout(Throwable error);
}
