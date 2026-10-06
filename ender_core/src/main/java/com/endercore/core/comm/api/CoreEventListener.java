/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：定义事件回调契约，供调用方订阅服务端主动推送的事件帧。
 */
package com.endercore.core.comm.api;

 
/**
 * 事件监听契约。
 *
 * 作为函数式接口只有单一回调点 onEvent；需要按种类过滤时使用 CoreWebSocketClient#onEvent，
 * 需要接收全部事件时使用 CoreWebSocketClient#onAnyEvent。
 *
 * 契约说明：
 * 1. 回调由客户端投递到配置的回调执行器上执行，执行线程不确定，同一监听器可能被并发调用。
 * 2. kind 永不为 null；payload 为解码后的原始字节，永不为 null，可能为空数组。
 * 3. 实现不得在回调中执行阻塞操作，否则会拖慢同一执行器上的其它回调。
 *
 * 线程安全性：实现类必须保证 onEvent 可被并发调用。
 *
 * @since 1.0
 * @see CoreExceptionHandler
 * @see CoreMessageClient
 */
@FunctionalInterface
public interface CoreEventListener {

    /**
     * 接收到事件时回调。
     *
     * @param kind 事件种类，形如 namespace:path，不能为 null
     * @param payload 事件负载的原始字节，不能为 null，可能为空数组
     */
    void onEvent(String kind, byte[] payload);
}
