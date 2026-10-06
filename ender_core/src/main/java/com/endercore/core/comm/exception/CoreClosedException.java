/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：表示「在已关闭或不可用的连接上执行操作」，用于把链路可用性问题与协议问题区分开。
 */
package com.endercore.core.comm.exception;

 
/**
 * 连接关闭异常。
 *
 * 在连接不可用（尚未连接、正在关闭、已关闭）时执行发送或等待响应会抛出本异常；
 * 关闭流程中被中断的挂起请求也以此异常的形式失败。
 *
 * 线程安全性：继承基类的不可变特性，可跨线程传递，通常作为 CompletableFuture 的失败原因。
 *
 * @since 1.0
 * @see CoreCommException
 */
public final class CoreClosedException extends CoreCommException {
    /**
     * 以错误消息构造异常。
     *
     * @param message 错误消息，用于日志与诊断，允许为 null
     */
    public CoreClosedException(String message) {
        super(message);
    }
}
