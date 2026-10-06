/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：定义连接状态与运行指标的只读观测契约。
 */
package com.endercore.core.comm.api;

import com.endercore.core.comm.monitor.ConnectionMetricsSnapshot;
import com.endercore.core.comm.monitor.ConnectionState;

import java.util.function.BiConsumer;

 
/**
 * 状态与指标观测契约。
 *
 * 与 CoreConnectionManager 的分工：后者驱动生命周期变化，本接口只读观测，自身不触发任何状态流转。
 *
 * 契约说明：
 * 1. metrics() 返回某一时刻的不可变快照，不随后续指标变化而更新。
 * 2. 监听器注册是追加语义，重复注册同一监听器会被重复回调，本接口不提供注销方法。
 *
 * 线程安全性：实现类必须保证读取方法与监听器注册方法可被并发调用。
 *
 * @since 1.0
 * @see CoreConnectionManager
 * @see ConnectionState
 */
public interface CoreStateMonitor {

    /**
     * 获取当前连接状态。
     *
     * @return 当前连接状态，永不为 null
     */
    ConnectionState state();

    /**
     * 获取当前连接指标快照。
     *
     * @return 指标快照，永不为 null；其中 pendingRequests 为调用时刻尚未完成的请求数
     */
    ConnectionMetricsSnapshot metrics();

    /**
     * 注册状态变更监听器。
     *
     * 仅在状态真正发生变化时回调，把同一状态重复设置为相同值不会触发通知。
     *
     * @param listener 状态变更监听器，不能为 null；入参依次为旧状态与新状态，均不为 null
     */
    void onStateChanged(BiConsumer<ConnectionState, ConnectionState> listener);
}
