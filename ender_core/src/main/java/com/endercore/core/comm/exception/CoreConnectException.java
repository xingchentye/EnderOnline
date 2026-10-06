/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：表示本地链路侧的失败，包括握手超时、连接被拒绝、连接中断与重连失败。
 */
package com.endercore.core.comm.exception;

 
/**
 * 连接异常。
 *
 * 覆盖「本机到对端的链路没能建立或已中断」这一类失败；对端返回业务错误码请使用 CoreRemoteException。
 *
 * 线程安全性：继承基类的不可变特性，可跨线程传递，通常作为 CompletableFuture 的失败原因。
 *
 * @since 1.0
 * @see CoreCommException
 * @see CoreRemoteException
 */
public final class CoreConnectException extends CoreCommException {
    /**
     * 以错误消息与原因异常构造异常。
     *
     * 本异常总是携带原因异常，便于定位底层 IOException 或超时原因。
     *
     * @param message 错误消息，用于日志与诊断，允许为 null
     * @param cause 底层失败原因，允许为 null
     */
    public CoreConnectException(String message, Throwable cause) {
        super(message, cause);
    }
}
