/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：表示请求在约定时限内未收到响应，并携带定位该请求所需的字段。
 */
package com.endercore.core.comm.exception;

import java.time.Duration;

 
/**
 * 请求超时异常。
 *
 * 由客户端在请求超时任务触发时构造，表示连接本身可能仍然可用，只是本次请求没有得到响应。
 *
 * 线程安全性：字段均为 final，构造完成后不可变，可跨线程传递。
 *
 * @since 1.0
 * @see CoreCommException
 * @see CoreRemoteException
 */
public final class CoreTimeoutException extends CoreCommException {
    /**
     * 请求种类，形如 namespace:path。
     *
     * 取自发起请求时的入参，不会为 null。
     */
    private final String kind;

    /**
     * 关联的请求 ID，与发起请求时分配的取值一致。
     */
    private final long requestId;

    /**
     * 本次请求实际使用的超时时长。
     *
     * 取值来自客户端配置的请求超时，非负。
     */
    private final Duration timeout;

    /**
     * 以请求上下文构造异常。
     *
     * 错误消息由本构造器拼接产生，调用方无需另行传入。
     *
     * @param kind 请求种类，不能为 null
     * @param requestId 关联的请求 ID
     * @param timeout 本次请求使用的超时时长，不能为 null，非负
     */
    public CoreTimeoutException(String kind, long requestId, Duration timeout) {
        super("请求超时: kind=" + kind + ", requestId=" + requestId + ", timeout=" + timeout);
        this.kind = kind;
        this.requestId = requestId;
        this.timeout = timeout;
    }

    /**
     * 获取请求种类。
     *
     * @return 请求种类，形如 namespace:path，永不为 null
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

    /**
     * 获取本次请求使用的超时时长。
     *
     * @return 超时时长，永不为 null，非负
     */
    public Duration timeout() {
        return timeout;
    }
}
