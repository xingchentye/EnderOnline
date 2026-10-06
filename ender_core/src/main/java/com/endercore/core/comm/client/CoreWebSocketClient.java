/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：WebSocket 客户端的默认实现，负责连接维护、请求-响应配对、事件分发、心跳与自动重连。
 */
package com.endercore.core.comm.client;

import com.endercore.core.comm.api.CoreConnectionManager;
import com.endercore.core.comm.api.CoreExceptionHandler;
import com.endercore.core.comm.api.CoreEventListener;
import com.endercore.core.comm.api.CoreMessageClient;
import com.endercore.core.comm.api.CoreStateMonitor;
import com.endercore.core.comm.config.CoreWebSocketConfig;
import com.endercore.core.comm.exception.CoreClosedException;
import com.endercore.core.comm.exception.CoreConnectException;
import com.endercore.core.comm.exception.CoreProtocolException;
import com.endercore.core.comm.exception.CoreRemoteException;
import com.endercore.core.comm.exception.CoreTimeoutException;
import com.endercore.core.comm.monitor.ConnectionMetrics;
import com.endercore.core.comm.monitor.ConnectionMetricsSnapshot;
import com.endercore.core.comm.monitor.ConnectionState;
import com.endercore.core.comm.protocol.CoreFrame;
import com.endercore.core.comm.protocol.CoreFrameCodec;
import com.endercore.core.comm.protocol.CoreKinds;
import com.endercore.core.comm.protocol.CoreMessageType;
import com.endercore.core.comm.protocol.CoreResponse;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

 
/**
 * WebSocket 客户端实现。
 *
 * 一个实例对应一条逻辑链路，同时实现连接管理、消息发送与状态观测三套契约；
 * 协议解析下沉到 CoreFrameCodec，房间等业务语义下沉到调用方注册的监听器。
 *
 * 设计约束：
 * 1. connect 与 close 由 lifecycleLock 串行化，避免并发建立或拆除同一条链路。
 * 2. 关闭动作与重连退避、请求超时、心跳都提交到内部单线程调度器执行，不在调用者线程上阻塞。
 * 3. 未显式提供回调执行器时会新建缓存线程池，回调顺序不作保证，实现方不得假设串行。
 * 4. 关闭时所有挂起请求统一以 CoreClosedException 失败，不会永久挂起。
 *
 * 线程安全性：连接状态为 AtomicReference，挂起请求表与事件监听器表为并发容器，
 * 监听器列表使用写时复制；状态与事件回调投递到 callbackExecutor，因此回调可能并发执行。
 *
 * @since 1.0
 * @see CoreWebSocketConfig
 * @see CoreFrameCodec
 * @see CoreConnectionManager
 */
public final class CoreWebSocketClient implements CoreConnectionManager, CoreMessageClient, CoreStateMonitor {
    /** 保护 connect 与 close 的互斥锁，避免并发建立或拆除链路。 */
    private final Object lifecycleLock = new Object();
    /** 当前连接状态，初始为 CLOSED，所有变更都经 setState 写入。 */
    private final AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.CLOSED);
    /** 状态变更监听器，写少读多，使用写时复制列表。 */
    private final CopyOnWriteArrayList<BiConsumer<ConnectionState, ConnectionState>> stateListeners = new CopyOnWriteArrayList<>();
    /** 按种类索引的事件监听器表。 */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<CoreEventListener>> eventListeners = new ConcurrentHashMap<>();
    /** 不区分种类的全局事件监听器，写少读多。 */
    private final CopyOnWriteArrayList<CoreEventListener> anyEventListeners = new CopyOnWriteArrayList<>();
    /** 尚未完成的请求，键为 requestId；响应、超时与连接关闭都会将条目移除。 */
    private final ConcurrentHashMap<Long, PendingRequest> pending = new ConcurrentHashMap<>();
    /** 请求 ID 分配器，从 1 开始单调递增，同一实例内不会重复。 */
    private final AtomicLong requestIdSeq = new AtomicLong(1);

    /**
     * 客户端配置。
     *
     * 不允许为 null，由构造器注入；实例本身不可变，可跨线程读取。
     */
    private final CoreWebSocketConfig config;

    /**
     * 异常处理器。
     *
     * 不允许为 null：构造器入参为 null 时替换为丢弃全部回调的空实现。
     */
    private final CoreExceptionHandler exceptionHandler;

    /**
     * 回调执行器。
     *
     * 不允许为 null：构造器入参为 null 时新建缓存线程池；承载状态变更与事件回调。
     */
    private final Executor callbackExecutor;

    /**
     * 内部调度器。
     *
     * 单线程守护线程，承载重连退避、请求超时与心跳任务；由构造器创建，随实例长期存活。
     */
    private final ScheduledExecutorService scheduler;

    /**
     * 帧编解码器。
     *
     * 不允许为 null，由构造器按配置的 maxFrameBytes 创建。
     */
    private final CoreFrameCodec codec;

    /** 运行指标收集器，内部计数器并发安全。 */
    private final ConnectionMetrics metrics = new ConnectionMetrics();

    /** 当前连接端点，仅由 connect 赋值；尚未连接时为 null。 */
    private volatile URI endpoint;
    /** 底层 WebSocket 客户端，握手开始前为 null。 */
    private volatile WebSocketClient client;
    /** 最近一次 connect 的完成凭证；尚未调用过 connect 时为 null，连接进行中会被复用。 */
    private volatile CompletableFuture<Void> connectFuture;
    /** 是否由本实例主动发起关闭，用于区分正常关闭与对端断开。 */
    private volatile boolean closing;
    /** 当前重连退避时长，初始为配置的最小值，每次重连后翻倍并封顶。 */
    private volatile Duration dynamicBackoff;

    /**
     * 构造客户端。
     *
     * 只完成依赖装配与调度器创建，不发起任何网络动作。
     *
     * @param config 客户端配置，不能为 null
     * @param exceptionHandler 异常处理器，允许为 null，为 null 时使用丢弃全部回调的空实现
     * @param callbackExecutor 回调执行器，允许为 null，为 null 时新建缓存线程池
     * @throws NullPointerException 当 config 为 null 时抛出
     */
    public CoreWebSocketClient(CoreWebSocketConfig config, CoreExceptionHandler exceptionHandler, Executor callbackExecutor) {
        this.config = Objects.requireNonNull(config, "config");
        this.exceptionHandler = exceptionHandler == null ? new NoopExceptionHandler() : exceptionHandler;
        this.callbackExecutor = callbackExecutor == null ? Executors.newCachedThreadPool() : callbackExecutor;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "endercore-core-comm-scheduler");
            t.setDaemon(true);
            return t;
        });
        this.codec = new CoreFrameCodec(config.maxFrameBytes());
        this.dynamicBackoff = config.reconnectBackoffMin();
    }

    /**
     * 连接到指定端点，契约见 CoreConnectionManager#connect。
     *
     * 全程持有 lifecycleLock：已连接时直接返回已完成的 Future；上一次连接仍在进行中时复用同一个
     * connectFuture，不会重复发起握手。握手超时或抛出异常时会先把状态置为 FAILED，
     * 再让 Future 以 CoreConnectException 异常完成。
     *
     * @param endpoint 连接端点 URI，不能为 null
     * @return 连接完成的 Future，永不为 null
     * @throws NullPointerException 当 endpoint 为 null 时抛出
     */
    @Override
    public CompletableFuture<Void> connect(URI endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        synchronized (lifecycleLock) {
            if (state.get() == ConnectionState.CONNECTED) {
                return CompletableFuture.completedFuture(null);
            }
            if (connectFuture != null && !connectFuture.isDone()) {
                return connectFuture;
            }
            this.endpoint = endpoint;
            this.closing = false;
            setState(ConnectionState.CONNECTING);
            this.connectFuture = new CompletableFuture<>();
            this.client = newClient(endpoint);
            try {
                boolean started = this.client.connectBlocking(config.connectTimeout().toMillis(), TimeUnit.MILLISECONDS);
                if (!started) {
                    CoreConnectException e = new CoreConnectException("连接超时: " + endpoint, null);
                    exceptionHandler.onConnectionError(e);
                    setState(ConnectionState.FAILED);
                    connectFuture.completeExceptionally(e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                CoreConnectException ce = new CoreConnectException("连接被中断: " + endpoint, e);
                exceptionHandler.onConnectionError(ce);
                setState(ConnectionState.FAILED);
                connectFuture.completeExceptionally(ce);
            } catch (Exception e) {
                CoreConnectException ce = new CoreConnectException("连接失败: " + endpoint, e);
                exceptionHandler.onConnectionError(ce);
                setState(ConnectionState.FAILED);
                connectFuture.completeExceptionally(ce);
            }
            return connectFuture;
        }
    }

    /**
     * 关闭连接，契约见 CoreConnectionManager#close。
     *
     * 真正的关闭动作提交到 scheduler 执行，因此本方法不会在调用者线程上阻塞等待；
     * 从未连接过时立即以完成态返回。关闭过程中所有挂起请求都会以 CoreClosedException 失败。
     *
     * @param timeout 等待关闭完成的最长时间，不能为 null
     * @return 关闭完成的 Future，永不为 null；超出 timeout 时以超时异常完成
     * @throws NullPointerException 当 timeout 为 null 时抛出
     */
    @Override
    public CompletableFuture<Void> close(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        synchronized (lifecycleLock) {
            closing = true;
            setState(ConnectionState.CLOSING);
            WebSocketClient c = this.client;
            if (c == null) {
                setState(ConnectionState.CLOSED);
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<Void> f = new CompletableFuture<>();
            scheduler.execute(() -> {
                try {
                    c.closeBlocking();
                    failPending(new CoreClosedException("连接已关闭"));
                    setState(ConnectionState.CLOSED);
                    f.complete(null);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failPending(new CoreClosedException("连接关闭被中断"));
                    setState(ConnectionState.CLOSED);
                    f.completeExceptionally(e);
                } catch (Exception e) {
                    failPending(new CoreClosedException("连接关闭失败"));
                    setState(ConnectionState.CLOSED);
                    f.completeExceptionally(e);
                }
            });
            return f.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 获取当前连接状态，契约见 CoreStateMonitor#state。
     *
     * @return 当前连接状态，永不为 null
     */
    @Override
    public ConnectionState state() {
        return state.get();
    }

    /**
     * 检查连接是否可用，契约见 CoreConnectionManager#isConnected。
     *
     * @return 状态为 CONNECTED 时返回 true，否则返回 false
     */
    @Override
    public boolean isConnected() {
        return state.get() == ConnectionState.CONNECTED;
    }

    /**
     * 异步发送请求，契约见 CoreMessageClient#sendAsync。
     *
     * 先校验 kind 并检查连接状态，再分配 requestId、登记超时任务并投递帧；
     * 投递失败时会撤销超时任务并让 Future 以 CoreConnectException 异常完成。
     *
     * @param kind 请求种类，形如 namespace:path，不能为 null
     * @param payload 请求负载，允许为 null，为 null 时按空数组发送
     * @return 响应 Future，永不为 null；连接不可用时以 CoreClosedException 异常完成
     * @throws CoreProtocolException 当 kind 格式非法时抛出
     */
    @Override
    public CompletableFuture<CoreResponse> sendAsync(String kind, byte[] payload) {
        CoreKinds.validate(kind);
        if (!isConnected()) {
            CompletableFuture<CoreResponse> f = new CompletableFuture<>();
            f.completeExceptionally(new CoreClosedException("连接不可用: state=" + state.get()));
            return f;
        }

        long requestId = requestIdSeq.getAndIncrement();
        CoreFrame requestFrame = new CoreFrame(CoreMessageType.REQUEST, (byte) 0, 0, requestId, kind, payload);
        byte[] bytes = codec.encode(requestFrame);

        CompletableFuture<CoreResponse> future = new CompletableFuture<>();
        Duration timeout = config.requestTimeout();
        ScheduledFuture<?> timeoutTask = scheduler.schedule(() -> {
            PendingRequest removed = pending.remove(requestId);
            if (removed != null && removed.future.completeExceptionally(new CoreTimeoutException(kind, requestId, timeout))) {
                metrics.onRequestTimeout();
                exceptionHandler.onTimeout(new CoreTimeoutException(kind, requestId, timeout));
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);

        pending.put(requestId, new PendingRequest(kind, System.nanoTime(), future, timeoutTask));

        try {
            metrics.onRequestSent();
            metrics.onFrameSent(bytes.length);
            client.send(bytes);
        } catch (Exception e) {
            PendingRequest removed = pending.remove(requestId);
            if (removed != null) {
                removed.timeoutTask.cancel(false);
            }
            future.completeExceptionally(new CoreConnectException("发送失败: " + kind, e));
        }

        return future;
    }

    /**
     * 同步发送请求，契约见 CoreMessageClient#sendSync。
     *
     * 在 sendAsync 返回的 Future 上按 timeout 阻塞等待；若失败原因本身是运行时异常
     * （例如对端错误码或连接不可用），则原样抛出该异常，否则包装为 CoreConnectException。
     *
     * @param kind 请求种类，形如 namespace:path，不能为 null
     * @param payload 请求负载，允许为 null，为 null 时按空数组发送
     * @param timeout 等待响应的最长时间，不能为 null
     * @return 对端响应，永不为 null
     * @throws CoreProtocolException 当 kind 格式非法时抛出
     * @throws CoreConnectException 当等待超时、线程被中断或链路不可用时抛出
     * @throws NullPointerException 当 timeout 为 null 时抛出
     */
    @Override
    public CoreResponse sendSync(String kind, byte[] payload, Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        try {
            return sendAsync(kind, payload).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            if (e.getCause() instanceof RuntimeException) {
                throw (RuntimeException) e.getCause();
            }
            throw new CoreConnectException("同步请求失败: " + kind, e);
        }
    }

    /**
     * 发送单向事件，契约见 CoreMessageClient#sendEvent。
     *
     * 帧的 requestId 固定为 0，不登记挂起表，因此不会产生超时或响应回调。
     *
     * @param kind 事件种类，形如 namespace:path，不能为 null
     * @param payload 事件负载，允许为 null，为 null 时按空数组发送
     * @throws CoreProtocolException 当 kind 格式非法时抛出
     * @throws CoreClosedException 当连接不可用时抛出
     */
    @Override
    public void sendEvent(String kind, byte[] payload) {
        CoreKinds.validate(kind);
        if (!isConnected()) {
            throw new CoreClosedException("连接不可用: state=" + state.get());
        }
        CoreFrame frame = new CoreFrame(CoreMessageType.EVENT, (byte) 0, 0, 0, kind, payload);
        byte[] bytes = codec.encode(frame);
        metrics.onFrameSent(bytes.length);
        client.send(bytes);
    }

    /**
     * 获取连接指标快照，契约见 CoreStateMonitor#metrics。
     *
     * @return 指标快照，永不为 null；挂起请求数取自快照生成时刻的挂起表大小
     */
    @Override
    public ConnectionMetricsSnapshot metrics() {
        return metrics.snapshot(pending.size());
    }

    /**
     * 注册状态变更监听器，契约见 CoreStateMonitor#onStateChanged。
     *
     * 追加语义：同一实例可重复注册，重复注册会被重复回调。
     *
     * @param listener 状态变更监听器，不能为 null，入参依次为旧状态与新状态
     * @throws NullPointerException 当 listener 为 null 时抛出
     */
    @Override
    public void onStateChanged(BiConsumer<ConnectionState, ConnectionState> listener) {
        stateListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * 注册按种类过滤的事件监听器。
     *
     * 同一 kind 可注册多个监听器，全部会被回调；回调在 callbackExecutor 上执行，顺序不作保证。
     *
     * @param kind 事件种类，形如 namespace:path，不能为 null
     * @param listener 事件监听器，不能为 null
     * @throws CoreProtocolException 当 kind 格式非法时抛出
     * @throws NullPointerException 当 listener 为 null 时抛出
     */
    public void onEvent(String kind, CoreEventListener listener) {
        CoreKinds.validate(kind);
        Objects.requireNonNull(listener, "listener");
        eventListeners.computeIfAbsent(kind, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /**
     * 注册不区分种类的全局事件监听器。
     *
     * 所有事件都会触发该监听器，与按种类注册的监听器彼此独立、互不影响。
     *
     * @param listener 事件监听器，不能为 null
     * @throws NullPointerException 当 listener 为 null 时抛出
     */
    public void onAnyEvent(CoreEventListener listener) {
        anyEventListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * 创建底层的 WebSocket 客户端并绑定生命周期回调。
     *
     * 每个连接尝试都会新建一个底层实例，因此连接回调闭包捕获的是本次尝试所需的上下文。
     *
     * @param endpoint 连接端点
     * @return 已配置好回调的底层客户端实例，永不为 null
     */
    private WebSocketClient newClient(URI endpoint) {
        return new WebSocketClient(endpoint) {
            /**
             * 握手完成，契约见 WebSocketClient#onOpen。
             *
             * 实现上把重连退避重置为配置的最小值、置状态为 CONNECTED、完成 connectFuture 并启动心跳。
             *
             * @param handshakedata 服务端握手信息，不能为 null
             */
            @Override
            public void onOpen(ServerHandshake handshakedata) {
                dynamicBackoff = config.reconnectBackoffMin();
                setState(ConnectionState.CONNECTED);
                if (connectFuture != null && !connectFuture.isDone()) {
                    connectFuture.complete(null);
                }
                scheduleHeartbeat();
            }

            /**
             * 收到文本帧，契约见 WebSocketClient#onMessage。
             *
             * 本客户端只接受二进制帧，因此上报协议错误并主动以 1003 关闭连接。
             *
             * @param message 文本消息，不能为 null
             */
            @Override
            public void onMessage(String message) {
                metrics.onProtocolError();
                CoreProtocolException e = new CoreProtocolException("不支持文本帧");
                exceptionHandler.onProtocolError(e);
                close(1003, e.getMessage());
            }

            /**
             * 收到二进制帧，契约见 WebSocketClient#onMessage。
             *
             * 解码失败时按错误类别以 1002 或 1011 关闭连接；解码成功后按消息类型分发到响应、事件或心跳处理。
             *
             * @param bytes 二进制消息，不能为 null
             */
            @Override
            public void onMessage(ByteBuffer bytes) {
                metrics.onFrameReceived(bytes.remaining());
                CoreFrame frame;
                try {
                    frame = codec.decode(bytes);
                } catch (CoreProtocolException e) {
                    metrics.onProtocolError();
                    exceptionHandler.onProtocolError(e);
                    close(1002, e.getMessage());
                    return;
                } catch (RuntimeException e) {
                    metrics.onProtocolError();
                    exceptionHandler.onProtocolError(e);
                    close(1011, "协议解析失败");
                    return;
                }

                if (frame.type() == CoreMessageType.RESPONSE) {
                    onResponseFrame(frame);
                } else if (frame.type() == CoreMessageType.EVENT) {
                    onEventFrame(frame);
                } else if (frame.type() == CoreMessageType.HEARTBEAT) {
                    onHeartbeatFrame(frame);
                }
            }

            /**
             * 连接关闭，契约见 WebSocketClient#onClose。
             *
             * 非主动关闭时置状态为 FAILED、上报异常处理器，并按配置决定是否安排自动重连；
             * 无论何种原因，都会让所有挂起请求以 CoreClosedException 失败。
             *
             * @param code WebSocket 关闭码
             * @param reason 关闭原因文本，允许为 null
             * @param remote 是否由对端发起关闭
             */
            @Override
            public void onClose(int code, String reason, boolean remote) {
                if (closing) {
                    setState(ConnectionState.CLOSED);
                } else {
                    setState(ConnectionState.FAILED);
                }
                failPending(new CoreClosedException("连接已关闭: code=" + code + ", reason=" + reason));
                if (!closing) {
                    exceptionHandler.onConnectionError(new CoreConnectException("连接断开: " + reason, null));
                    if (config.autoReconnect()) {
                        scheduleReconnect();
                    }
                }
            }

            /**
             * 底层连接错误，契约见 WebSocketClient#onError。
             *
             * 只上报异常、不改变连接状态：状态由随后触发的 onClose 统一收敛，避免重复流转。
             *
             * @param ex 底层错误，不能为 null
             */
            @Override
            public void onError(Exception ex) {
                exceptionHandler.onConnectionError(new CoreConnectException("连接错误: " + endpoint, ex));
            }
        };
    }

    /**
     * 处理响应帧。
     *
     * 按 requestId 取出挂起请求并取消其超时任务，计算本次往返时延；
     * 状态码为 0 时正常完成 Future，否则以 CoreRemoteException 失败。
     * 找不到对应挂起请求的响应会被静默忽略。
     *
     * @param frame 已解码的响应帧，不能为 null
     */
    private void onResponseFrame(CoreFrame frame) {
        metrics.onResponseReceived();
        PendingRequest pendingRequest = pending.remove(frame.requestId());
        if (pendingRequest == null) {
            return;
        }
        pendingRequest.timeoutTask.cancel(false);

        long rttMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - pendingRequest.startNanos);
        metrics.setLastRttMillis(rttMillis);

        if (frame.status() == 0) {
            pendingRequest.future.complete(new CoreResponse(frame.status(), frame.requestId(), frame.kind(), frame.payload()));
        } else {
            String msg = new String(frame.payload(), StandardCharsets.UTF_8);
            CoreRemoteException e = new CoreRemoteException(frame.status(), frame.kind(), frame.requestId(), msg);
            exceptionHandler.onRemoteError(e);
            pendingRequest.future.completeExceptionally(e);
        }
    }

    /**
     * 处理事件帧。
     *
     * 先回调该 kind 的专属监听器，再回调全局监听器；两者都通过 callbackExecutor 投递，
     * 因此不阻塞网络 IO 线程，也不保证回调顺序。
     *
     * @param frame 已解码的事件帧，不能为 null
     */
    private void onEventFrame(CoreFrame frame) {
        CopyOnWriteArrayList<CoreEventListener> specific = eventListeners.get(frame.kind());
        if (specific != null) {
            for (CoreEventListener listener : specific) {
                callbackExecutor.execute(() -> listener.onEvent(frame.kind(), frame.payload()));
            }
        }
        for (CoreEventListener listener : anyEventListeners) {
            callbackExecutor.execute(() -> listener.onEvent(frame.kind(), frame.payload()));
        }
    }

    /**
     * 处理心跳帧。
     *
     * 当前只把最近往返时延归零，表示该心跳不携带可用的延迟观测值。
     *
     * @param frame 已解码的心跳帧，不能为 null
     */
    private void onHeartbeatFrame(CoreFrame frame) {
        metrics.setLastRttMillis(0);
    }

    /**
     * 按当前退避时长安排一次重连。
     *
     * 实际延迟为 dynamicBackoff 加上 0 到 99 毫秒的随机抖动；任务执行后把退避翻倍并封顶。
     * 任务触发时若已进入主动关闭流程则直接放弃，不发起连接。
     */
    private void scheduleReconnect() {
        Duration backoff = dynamicBackoff;
        long jitter = ThreadLocalRandom.current().nextLong(0, 100);
        scheduler.schedule(() -> {
            if (closing) {
                return;
            }
            try {
                connect(endpoint);
            } catch (Exception e) {
                exceptionHandler.onConnectionError(new CoreConnectException("重连失败", e));
            }
            dynamicBackoff = nextBackoff(dynamicBackoff);
        }, backoff.toMillis() + jitter, TimeUnit.MILLISECONDS);
    }

    /**
     * 计算下一次重连退避时长。
     *
     * 计算顺序为「当前值翻倍」再「按配置上限封顶」，返回值不小于入参。
     *
     * @param current 当前退避时长，不能为 null
     * @return 下一次退避时长，永不为 null，非负
     */
    private Duration nextBackoff(Duration current) {
        long next = Math.min(current.toMillis() * 2L, config.reconnectBackoffMax().toMillis());
        return Duration.ofMillis(next);
    }

    /**
     * 启动周期性心跳。
     *
     * 心跳间隔为 0 或负值时直接跳过，不创建定时任务；任务内只编码并发送心跳帧，
     * 发送失败仅上报异常处理器，不改变连接状态。
     */
    private void scheduleHeartbeat() {
        Duration interval = config.heartbeatInterval();
        if (interval == null || interval.isZero() || interval.isNegative()) {
            return;
        }
        scheduler.scheduleAtFixedRate(() -> {
            if (!isConnected()) {
                return;
            }
            try {
                byte[] bytes = codec.encode(new CoreFrame(CoreMessageType.HEARTBEAT, (byte) 0, 0, 0, "", new byte[0]));
                metrics.onFrameSent(bytes.length);
                client.send(bytes);
            } catch (Exception e) {
                exceptionHandler.onConnectionError(new CoreConnectException("心跳发送失败", e));
            }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 更新连接状态并通知监听器。
     *
     * 幂等性：新旧状态相同时不触发任何回调。回调通过 callbackExecutor 投递，不阻塞状态推进。
     *
     * @param newState 新状态，不能为 null
     */
    private void setState(ConnectionState newState) {
        ConnectionState old = state.getAndSet(newState);
        if (old == newState) {
            return;
        }
        for (BiConsumer<ConnectionState, ConnectionState> l : stateListeners) {
            callbackExecutor.execute(() -> l.accept(old, newState));
        }
    }

    /**
     * 让所有挂起请求失败并清空挂起表。
     *
     * 每个请求的超时任务会被取消，其 Future 以传入的异常完成。
     *
     * @param error 用于完成各 Future 的异常，不能为 null
     */
    private void failPending(RuntimeException error) {
        for (PendingRequest pr : pending.values()) {
            pr.timeoutTask.cancel(false);
            pr.future.completeExceptionally(error);
        }
        pending.clear();
    }

    /**
     * 挂起中的请求。
     *
     * 仅在客户端内部使用，承载响应配对所需的 Future、发送时刻与超时任务。
     *
     * 线程安全性：字段均为 final，实例写入挂起表后不再修改。
     */
    private static final class PendingRequest {
        /** 请求种类，用于构造超时与远程错误消息。 */
        private final String kind;
        /** 发送时刻，单位纳秒，取自 System.nanoTime()，只用于计算相对耗时。 */
        private final long startNanos;
        /** 等待响应的 Future，永不为 null。 */
        private final CompletableFuture<CoreResponse> future;
        /** 超时定时任务，响应到达或失败时会先取消。 */
        private final ScheduledFuture<?> timeoutTask;

        /**
         * 构造挂起请求记录。
         *
         * @param kind 请求种类，不能为 null
         * @param startNanos 发送时刻的纳秒时间戳
         * @param future 等待响应的 Future，不能为 null
         * @param timeoutTask 超时定时任务，不能为 null
         */
        private PendingRequest(String kind, long startNanos, CompletableFuture<CoreResponse> future, ScheduledFuture<?> timeoutTask) {
            this.kind = kind;
            this.startNanos = startNanos;
            this.future = future;
            this.timeoutTask = timeoutTask;
        }
    }

    /**
     * 丢弃全部错误回调的空实现。
     *
     * 用于未提供异常处理器时保持调用链无需判空：所有回调都只做丢弃，不记录日志也不抛出异常。
     */
    private static final class NoopExceptionHandler implements CoreExceptionHandler {
        /** 处理连接错误，契约见 CoreExceptionHandler#onConnectionError。 */
        @Override
        public void onConnectionError(Throwable error) {
        }

        /** 处理协议错误，契约见 CoreExceptionHandler#onProtocolError。 */
        @Override
        public void onProtocolError(Throwable error) {
        }

        /** 处理远程错误，契约见 CoreExceptionHandler#onRemoteError。 */
        @Override
        public void onRemoteError(Throwable error) {
        }

        /** 处理请求超时，契约见 CoreExceptionHandler#onTimeout。 */
        @Override
        public void onTimeout(Throwable error) {
        }
    }
}
