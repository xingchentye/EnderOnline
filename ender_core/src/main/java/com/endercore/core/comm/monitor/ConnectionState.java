/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：定义连接生命周期状态枚举，作为状态机与状态变更回调的取值类型。
 */
package com.endercore.core.comm.monitor;

 
/**
 * 连接状态。
 *
 * 合法流转路径：
 * CLOSED 到 CONNECTING（调用 connect），CONNECTING 到 CONNECTED（握手完成），
 * CONNECTING 到 FAILED（握手超时或抛出异常），CONNECTED 到 CLOSING（主动调用 close），
 * CONNECTED 到 FAILED（对端断开且并非本端主动关闭），CLOSING 到 CLOSED（关闭流程结束），
 * FAILED 到 CONNECTING（开启自动重连时由退避调度重新发起）。
 *
 * CLOSED 既是初始状态也是终态：关闭之后再次调用 connect 会重新进入 CONNECTING，
 * 因此不存在「不可再流转」的绝对终态。
 *
 * 线程安全性：枚举常量不可变，可安全地在任意线程间共享与比较。
 *
 * @since 1.0
 * @see com.endercore.core.comm.client.CoreWebSocketClient
 */
public enum ConnectionState {
    /** 连接中，已发起握手但尚未完成，可能流转到 CONNECTED 或 FAILED。 */
    CONNECTING,

    /** 已连接，可以收发请求、响应与事件帧。 */
    CONNECTED,

    /** 关闭中，已停止接受新的业务操作，等待关闭流程结束。 */
    CLOSING,

    /** 已关闭，初始状态，可再次流转到 CONNECTING。 */
    CLOSED,

    /** 连接失败，可能由超时、握手异常或对端断开导致，可经重连回到 CONNECTING。 */
    FAILED
}
