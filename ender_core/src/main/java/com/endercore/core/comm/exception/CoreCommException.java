/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：通信层所有自定义异常的基类，统一继承 RuntimeException。
 */
package com.endercore.core.comm.exception;

 
/**
 * 通信异常基类。
 *
 * 继承 RuntimeException 而非受检异常，使协议栈内部的错误能直接穿透回调边界，
 * 由业务入口集中处理，无需在每个回调上声明 throws。
 *
 * 设计约束：所有子类都应保持不可变，字段只在构造器中赋值。
 *
 * 线程安全性：实例字段均为 final，构造完成后不可变，可安全地在不同线程间传递。
 *
 * @since 1.0
 * @see CoreClosedException
 * @see CoreConnectException
 * @see CoreProtocolException
 * @see CoreRemoteException
 * @see CoreTimeoutException
 */
public class CoreCommException extends RuntimeException {
    /**
     * 以错误消息构造异常。
     *
     * @param message 错误消息，用于日志与诊断，允许为 null
     */
    public CoreCommException(String message) {
        super(message);
    }

    /**
     * 以错误消息与原因异常构造异常。
     *
     * @param message 错误消息，用于日志与诊断，允许为 null
     * @param cause 导致本次失败的原因异常，允许为 null
     */
    public CoreCommException(String message, Throwable cause) {
        super(message, cause);
    }
}
