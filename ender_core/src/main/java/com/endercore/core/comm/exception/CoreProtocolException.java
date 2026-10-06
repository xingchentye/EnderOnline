/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：表示帧编解码与协议校验失败，是协议层对外暴露的唯一异常类型。
 */
package com.endercore.core.comm.exception;

 
/**
 * 协议异常。
 *
 * 在魔数或版本不匹配、帧长非法与超限、消息类型未知等情况下抛出；
 * 编解码层不抛出其它自定义异常，全部统一包装为本次类型。
 *
 * 线程安全性：继承基类的不可变特性，可跨线程传递，通常作为 CompletableFuture 的失败原因。
 *
 * @since 1.0
 * @see CoreCommException
 */
public final class CoreProtocolException extends CoreCommException {
    /**
     * 以错误消息构造异常。
     *
     * 适用于纯粹的格式校验失败，没有更底层的原因异常。
     *
     * @param message 错误消息，用于日志与诊断，允许为 null
     */
    public CoreProtocolException(String message) {
        super(message);
    }

    /**
     * 以错误消息与原因异常构造异常。
     *
     * 适用于由底层异常（例如未知消息类型）触发的协议失败。
     *
     * @param message 错误消息，用于日志与诊断，允许为 null
     * @param cause 触发本次协议失败的原因异常，允许为 null
     */
    public CoreProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
