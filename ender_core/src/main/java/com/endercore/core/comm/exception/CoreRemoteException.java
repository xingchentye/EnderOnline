/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：表示对端返回了非零状态码，并携带定位该请求所需的字段。
 */
package com.endercore.core.comm.exception;

 
/**
 * 远程异常。
 *
 * 当响应帧的状态码非零时构造，携带状态码、请求种类与请求 ID，
 * 便于调用方在没有日志上下文的情况下还原是哪一次请求失败。
 *
 * 线程安全性：字段均为 final，构造完成后不可变，可跨线程传递。
 *
 * @since 1.0
 * @see CoreCommException
 * @see CoreTimeoutException
 */
public final class CoreRemoteException extends CoreCommException {
    /**
     * 对端返回的状态码。
     *
     * 非零表示失败，具体取值由各业务协议自行约定。
     */
    private final int status;

    /**
     * 请求种类，形如 namespace:path。
     *
     * 取自响应帧，可能为空串，不会为 null。
     */
    private final String kind;

    /**
     * 关联的请求 ID，与发起请求时分配的取值一致。
     */
    private final long requestId;

    /**
     * 以对端返回的字段构造异常。
     *
     * 错误消息直接使用响应负载解码出的文本。
     *
     * @param status 对端返回的状态码，非零表示失败
     * @param kind 请求种类，不能为 null，可为空串
     * @param requestId 关联的请求 ID
     * @param message 错误消息，通常为响应负载的 UTF-8 文本，允许为 null
     */
    public CoreRemoteException(int status, String kind, long requestId, String message) {
        super(message);
        this.status = status;
        this.kind = kind;
        this.requestId = requestId;
    }

    /**
     * 获取对端返回的状态码。
     *
     * @return 非零状态码
     */
    public int status() {
        return status;
    }

    /**
     * 获取请求种类。
     *
     * @return 请求种类，形如 namespace:path，可能为空串，永不为 null
     */
    public String kind() {
        return kind;
    }

    /**
     * 获取关联的请求 ID。
     *
     * @return 请求 ID
     */
    public long requestId() {
        return requestId;
    }
}
