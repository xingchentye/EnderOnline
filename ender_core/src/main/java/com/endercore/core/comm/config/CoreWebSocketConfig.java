/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：定义客户端连接超时、请求超时、心跳与自动重连参数的不可变配置对象。
 */
package com.endercore.core.comm.config;

import java.time.Duration;

 
/**
 * WebSocket 客户端配置。
 *
 * 通过内部 Builder 组装，组装完成后不可变；字段本身不做合法性校验，
 * 非法取值（例如负超时）会在客户端真正使用该字段时才暴露。
 *
 * 设计约束：
 * 1. 所有时长均为 Duration，语义由使用处解释：握手超时用 connectTimeout，请求等待用 requestTimeout。
 * 2. heartbeatInterval 为 0 或负值表示关闭心跳。
 * 3. maxFrameBytes 决定客户端编解码器的帧长上限，需与服务端保持一致，否则大帧会被单侧拒收。
 *
 * 线程安全性：所有字段为 final，构造完成后不可变，可安全跨线程共享；
 * 但 Builder 是可变对象且不加同步，只应在单线程内组装后再调用 build。
 *
 * @since 1.0
 * @see com.endercore.core.comm.client.CoreWebSocketClient
 */
public final class CoreWebSocketConfig {
    /**
     * 连接握手超时。
     *
     * 默认 5 秒；由 Builder 提供，客户端在建立链路时消费，允许为 null 但会导致连接阶段抛出 NullPointerException。
     */
    private final Duration connectTimeout;

    /**
     * 单个请求等待响应的超时。
     *
     * 默认 10 秒；超时后请求以 CoreTimeoutException 失败，不会中断连接。
     */
    private final Duration requestTimeout;

    /**
     * 心跳发送间隔。
     *
     * 默认 15 秒；为 0 或负值时客户端不启动心跳任务。
     */
    private final Duration heartbeatInterval;

    /**
     * 是否在非主动断开后自动重连。
     *
     * 默认 true；主动调用 close 触发的断开不会重连。
     */
    private final boolean autoReconnect;

    /**
     * 重连退避的初始时长。
     *
     * 默认 200 毫秒；每次重连后翻倍，直到 reconnectBackoffMax。
     */
    private final Duration reconnectBackoffMin;

    /**
     * 重连退避的上限时长。
     *
     * 默认 5 秒；退避翻倍后按该值封顶。
     */
    private final Duration reconnectBackoffMax;

    /**
     * 单帧最大字节数，含协议头。
     *
     * 默认 4 MiB；需与服务端一致，且不得小于协议头长度，否则客户端构造失败。
     */
    private final int maxFrameBytes;

    /**
     * 由构建器组装配置。
     *
     * 逐字段复制构建器的当前取值，因此构建完成后再修改 Builder 不会影响本实例。
     *
     * @param builder 已填充的构建器，不能为 null
     */
    private CoreWebSocketConfig(Builder builder) {
        this.connectTimeout = builder.connectTimeout;
        this.requestTimeout = builder.requestTimeout;
        this.heartbeatInterval = builder.heartbeatInterval;
        this.autoReconnect = builder.autoReconnect;
        this.reconnectBackoffMin = builder.reconnectBackoffMin;
        this.reconnectBackoffMax = builder.reconnectBackoffMax;
        this.maxFrameBytes = builder.maxFrameBytes;
    }

    /**
     * 获取连接握手超时。
     *
     * @return 连接握手超时，默认 5 秒
     */
    public Duration connectTimeout() {
        return connectTimeout;
    }

    /**
     * 获取单个请求等待响应的超时。
     *
     * @return 请求超时，默认 10 秒
     */
    public Duration requestTimeout() {
        return requestTimeout;
    }

    /**
     * 获取心跳发送间隔。
     *
     * @return 心跳间隔，默认 15 秒，0 或负值表示关闭心跳
     */
    public Duration heartbeatInterval() {
        return heartbeatInterval;
    }

    /**
     * 获取是否自动重连。
     *
     * @return 非主动断开后需要自动重连时返回 true，默认 true
     */
    public boolean autoReconnect() {
        return autoReconnect;
    }

    /**
     * 获取重连退避的初始时长。
     *
     * @return 退避初始值，默认 200 毫秒
     */
    public Duration reconnectBackoffMin() {
        return reconnectBackoffMin;
    }

    /**
     * 获取重连退避的上限时长。
     *
     * @return 退避上限，默认 5 秒
     */
    public Duration reconnectBackoffMax() {
        return reconnectBackoffMax;
    }

    /**
     * 获取单帧最大字节数。
     *
     * @return 单帧最大字节数，单位字节，默认 4194304（4 MiB）
     */
    public int maxFrameBytes() {
        return maxFrameBytes;
    }

    /**
     * 创建配置构建器。
     *
     * 每次调用都返回新实例，各实例之间互不影响。
     *
     * @return 新建的构建器，永不为 null
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 配置构建器。
     *
     * 全部 setter 返回自身以支持链式调用；未显式设置的项沿用字段初始化时的默认值。
     *
     * 线程安全性：本类可变且不加同步，只应在单线程内组装，且不得在 build 之后继续修改。
     */
    public static final class Builder {
        /** 连接握手超时，默认 5 秒。 */
        private Duration connectTimeout = Duration.ofSeconds(5);
        /** 请求超时，默认 10 秒。 */
        private Duration requestTimeout = Duration.ofSeconds(10);
        /** 心跳间隔，默认 15 秒，0 或负值表示关闭心跳。 */
        private Duration heartbeatInterval = Duration.ofSeconds(15);
        /** 是否自动重连，默认 true。 */
        private boolean autoReconnect = true;
        /** 重连退避初始值，默认 200 毫秒。 */
        private Duration reconnectBackoffMin = Duration.ofMillis(200);
        /** 重连退避上限，默认 5 秒。 */
        private Duration reconnectBackoffMax = Duration.ofSeconds(5);
        /** 单帧最大字节数，默认 4 MiB。 */
        private int maxFrameBytes = 4 * 1024 * 1024;

        /**
         * 设置连接握手超时。
         *
         * @param connectTimeout 连接握手超时，不能为 null
         * @return 本构建器，便于链式调用
         */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        /**
         * 设置请求超时。
         *
         * @param requestTimeout 请求超时，不能为 null
         * @return 本构建器，便于链式调用
         */
        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        /**
         * 设置心跳间隔。
         *
         * @param heartbeatInterval 心跳间隔，不能为 null，0 或负值表示关闭心跳
         * @return 本构建器，便于链式调用
         */
        public Builder heartbeatInterval(Duration heartbeatInterval) {
            this.heartbeatInterval = heartbeatInterval;
            return this;
        }

        /**
         * 设置是否自动重连。
         *
         * @param autoReconnect true 表示非主动断开后自动重连
         * @return 本构建器，便于链式调用
         */
        public Builder autoReconnect(boolean autoReconnect) {
            this.autoReconnect = autoReconnect;
            return this;
        }

        /**
         * 设置重连退避初始值。
         *
         * @param reconnectBackoffMin 退避初始值，不能为 null
         * @return 本构建器，便于链式调用
         */
        public Builder reconnectBackoffMin(Duration reconnectBackoffMin) {
            this.reconnectBackoffMin = reconnectBackoffMin;
            return this;
        }

        /**
         * 设置重连退避上限。
         *
         * @param reconnectBackoffMax 退避上限，不能为 null
         * @return 本构建器，便于链式调用
         */
        public Builder reconnectBackoffMax(Duration reconnectBackoffMax) {
            this.reconnectBackoffMax = reconnectBackoffMax;
            return this;
        }

        /**
         * 设置单帧最大字节数。
         *
         * @param maxFrameBytes 单帧最大字节数，单位字节，不得小于协议头长度
         * @return 本构建器，便于链式调用
         */
        public Builder maxFrameBytes(int maxFrameBytes) {
            this.maxFrameBytes = maxFrameBytes;
            return this;
        }

        /**
         * 生成配置对象。
         *
         * 幂等性：可重复调用，每次都会产生一个新的独立配置实例。
         *
         * @return 新建的不可变配置对象，永不为 null
         */
        public CoreWebSocketConfig build() {
            return new CoreWebSocketConfig(this);
        }
    }
}
