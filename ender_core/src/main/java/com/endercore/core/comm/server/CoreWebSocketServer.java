/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：WebSocket 服务端的默认实现，负责监听、请求分发、事件分发与事件广播。
 */
package com.endercore.core.comm.server;

import com.endercore.core.comm.exception.CoreProtocolException;
import com.endercore.core.comm.monitor.ConnectionMetrics;
import com.endercore.core.comm.monitor.ConnectionMetricsSnapshot;
import com.endercore.core.comm.protocol.CoreFrame;
import com.endercore.core.comm.protocol.CoreFrameCodec;
import com.endercore.core.comm.protocol.CoreKinds;
import com.endercore.core.comm.protocol.CoreMessageType;
import com.endercore.core.comm.protocol.CoreResponse;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

 
/**
 * WebSocket 服务端实现。
 *
 * 负责监听端口、按 kind 把请求分发给处理器、把事件分发给事件处理器，并支持向单个或全部连接广播事件。
 *
 * 设计约束：
 * 1. 处理器与事件处理器按 kind 唯一注册，重复注册同一 kind 会覆盖旧处理器。
 * 2. 请求在 handlerExecutor 上执行；处理器返回 null 或抛出异常都会回送状态码 255 的响应。
 * 3. 未注册 kind 的请求直接回送状态码 255，不会进入任何处理器；未注册 kind 的事件被静默丢弃。
 * 4. 只接受二进制帧，收到文本帧即以 1003 关闭连接。
 *
 * 线程安全性：处理器表、事件处理器表与连接表均使用 ConcurrentHashMap，支持运行期注册；
 * 广播与发送方法可并发调用。业务处理器的线程安全由实现方负责，本类不保证同一连接的多个请求串行执行。
 *
 * @since 1.0
 * @see CoreRequestHandler
 * @see CoreEventHandler
 * @see CoreFrameCodec
 */
public final class CoreWebSocketServer extends WebSocketServer {
    /** 帧编解码器，由构造器按 maxFrameBytes 创建，不能为 null。 */
    private final CoreFrameCodec codec;
    /** 请求处理器表，键为 kind；同一 kind 后注册者覆盖先注册者。 */
    private final ConcurrentHashMap<String, CoreRequestHandler> handlers = new ConcurrentHashMap<>();
    /** 事件处理器表，键为 kind。 */
    private final ConcurrentHashMap<String, CoreEventHandler> eventHandlers = new ConcurrentHashMap<>();
    /** 已建立的连接表，键为远端地址，断开时移除。 */
    private final ConcurrentHashMap<InetSocketAddress, WebSocket> connectionsByRemote = new ConcurrentHashMap<>();
    /** 连接关闭监听器，写少读多，使用写时复制列表。 */
    private final CopyOnWriteArrayList<Consumer<InetSocketAddress>> closeListeners = new CopyOnWriteArrayList<>();
    /** 请求与事件的处理执行器；构造时若入参为 null 则新建缓存线程池。 */
    private final Executor handlerExecutor;
    /** 启动完成凭证，在 onStart 回调中完成，供 awaitStarted 等待。 */
    private final CompletableFuture<Void> started = new CompletableFuture<>();
    /** 运行指标收集器，内部计数器并发安全。 */
    private final ConnectionMetrics metrics = new ConnectionMetrics();
    /** 当前连接数，onOpen 加一、onClose 减一。 */
    private final AtomicLong connections = new AtomicLong();

    /**
     * 构造服务端。
     *
     * 只完成依赖装配，不绑定端口；需要由调用方显式调用 start 才开始监听。
     *
     * @param address 绑定地址，不能为 null
     * @param maxFrameBytes 单帧最大字节数，不得小于协议头长度
     * @param handlerExecutor 请求与事件的处理执行器，允许为 null，为 null 时新建缓存线程池
     */
    public CoreWebSocketServer(InetSocketAddress address, int maxFrameBytes, Executor handlerExecutor) {
        super(address);
        this.codec = new CoreFrameCodec(maxFrameBytes);
        this.handlerExecutor = handlerExecutor == null ? Executors.newCachedThreadPool() : handlerExecutor;
    }

    /**
     * 注册请求处理器。
     *
     * 幂等性：同一 kind 重复注册会直接覆盖旧处理器，不报错也不返回旧值。
     *
     * @param kind 请求种类，形如 namespace:path，不能为 null
     * @param handler 请求处理器，不能为 null
     * @throws CoreProtocolException 当 kind 格式非法时抛出
     * @throws NullPointerException 当 handler 为 null 时抛出
     */
    public void register(String kind, CoreRequestHandler handler) {
        CoreKinds.validate(kind);
        handlers.put(kind, Objects.requireNonNull(handler, "handler"));
    }

    /**
     * 注册事件处理器。
     *
     * 幂等性：同一 kind 重复注册会直接覆盖旧处理器，不报错也不返回旧值。
     *
     * @param kind 事件种类，形如 namespace:path，不能为 null
     * @param handler 事件处理器，不能为 null
     * @throws CoreProtocolException 当 kind 格式非法时抛出
     * @throws NullPointerException 当 handler 为 null 时抛出
     */
    public void registerEvent(String kind, CoreEventHandler handler) {
        CoreKinds.validate(kind);
        eventHandlers.put(kind, Objects.requireNonNull(handler, "handler"));
    }

    /**
     * 向所有已连接客户端广播事件。
     *
     * 帧只编码一次后复用，广播给调用时刻连接表中的全部连接；无法送达的连接没有回执也不重试。
     *
     * @param kind 事件种类，形如 namespace:path，不能为 null
     * @param payload 事件负载，允许为 null，为 null 时按空数组发送
     * @throws CoreProtocolException 当 kind 格式非法或编码后超过帧长上限时抛出
     */
    public void broadcastEvent(String kind, byte[] payload) {
        CoreKinds.validate(kind);
        byte[] bytes = codec.encode(new CoreFrame(CoreMessageType.EVENT, (byte) 0, 0, 0, kind, payload));
        broadcast(bytes);
    }

    /**
     * 向指定远端发送事件。
     *
     * @param remoteAddress 目标远端地址，不能为 null
     * @param kind 事件种类，形如 namespace:path，不能为 null
     * @param payload 事件负载，允许为 null，为 null 时按空数组发送
     * @return 目标连接仍在连接表中并已投递时返回 true；该远端不在连接表中时返回 false
     * @throws CoreProtocolException 当 kind 格式非法或编码后超过帧长上限时抛出
     * @throws NullPointerException 当 remoteAddress 为 null 时抛出
     */
    public boolean sendEventTo(InetSocketAddress remoteAddress, String kind, byte[] payload) {
        Objects.requireNonNull(remoteAddress, "remoteAddress");
        CoreKinds.validate(kind);
        WebSocket conn = connectionsByRemote.get(remoteAddress);
        if (conn == null) {
            return false;
        }
        byte[] bytes = codec.encode(new CoreFrame(CoreMessageType.EVENT, (byte) 0, 0, 0, kind, payload));
        metrics.onFrameSent(bytes.length);
        conn.send(bytes);
        return true;
    }

    /**
     * 向一组远端发送同一事件。
     *
     * 帧只编码一次后复用；集合中已断开的连接与 null 元素都会被静默跳过，因此本方法不保证全员送达，
     * 也不返回送达数量。
     *
     * @param remoteAddresses 目标远端地址集合，不能为 null，元素允许为 null
     * @param kind 事件种类，形如 namespace:path，不能为 null
     * @param payload 事件负载，允许为 null，为 null 时按空数组发送
     * @throws CoreProtocolException 当 kind 格式非法或编码后超过帧长上限时抛出
     * @throws NullPointerException 当 remoteAddresses 为 null 时抛出
     */
    public void sendEventToMany(Iterable<InetSocketAddress> remoteAddresses, String kind, byte[] payload) {
        Objects.requireNonNull(remoteAddresses, "remoteAddresses");
        CoreKinds.validate(kind);
        byte[] bytes = codec.encode(new CoreFrame(CoreMessageType.EVENT, (byte) 0, 0, 0, kind, payload));
        for (InetSocketAddress remote : remoteAddresses) {
            WebSocket conn = remote == null ? null : connectionsByRemote.get(remote);
            if (conn == null) {
                continue;
            }
            metrics.onFrameSent(bytes.length);
            conn.send(bytes);
        }
    }

    /**
     * 注册连接关闭监听器。
     *
     * 监听器抛出的异常会被静默忽略，不影响其它监听器，也不会中断关闭流程。
     *
     * @param listener 连接关闭监听器，不能为 null，入参为断开连接的远端地址
     * @throws NullPointerException 当 listener 为 null 时抛出
     */
    public void onConnectionClosed(Consumer<InetSocketAddress> listener) {
        closeListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * 连接建立，契约见 WebSocketServer#onOpen。
     *
     * 连接数加一并按远端地址登记连接；远端地址为 null 时不登记，
     * 该连接随后无法被 sendEventTo 定向发送。
     *
     * @param conn 新建的连接，不能为 null
     * @param handshake 客户端握手信息，不能为 null
     */
    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        connections.incrementAndGet();
        InetSocketAddress remote = conn.getRemoteSocketAddress();
        if (remote != null) {
            connectionsByRemote.put(remote, conn);
        }
    }

    /**
     * 连接关闭，契约见 WebSocketServer#onClose。
     *
     * 连接数减一、移除连接登记，并依次通知关闭监听器；远端地址为 null 时只递减计数。
     *
     * @param conn 已关闭的连接，允许为 null
     * @param code WebSocket 关闭码
     * @param reason 关闭原因文本，允许为 null
     * @param remote 是否由对端发起关闭
     */
    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        connections.decrementAndGet();
        InetSocketAddress remoteAddress = conn == null ? null : conn.getRemoteSocketAddress();
        if (remoteAddress != null) {
            connectionsByRemote.remove(remoteAddress);
            for (Consumer<InetSocketAddress> listener : closeListeners) {
                try {
                    listener.accept(remoteAddress);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 收到二进制帧，契约见 WebSocketServer#onMessage。
     *
     * 解码失败时按错误类别关闭连接；解码成功后按消息类型分发到请求、事件或心跳处理。
     *
     * @param conn 来源连接，不能为 null
     * @param message 二进制消息，不能为 null
     */
    @Override
    public void onMessage(WebSocket conn, ByteBuffer message) {
        metrics.onFrameReceived(message.remaining());
        CoreFrame frame;
        try {
            frame = codec.decode(message);
        } catch (CoreProtocolException e) {
            conn.close(1002, e.getMessage());
            return;
        } catch (RuntimeException e) {
            conn.close(1011, "协议解析失败");
            return;
        }

        if (frame.type() == CoreMessageType.REQUEST) {
            handleRequest(conn, frame);
        } else if (frame.type() == CoreMessageType.EVENT) {
            handleEvent(conn, frame);
        } else if (frame.type() == CoreMessageType.HEARTBEAT) {
            handleHeartbeat(conn, frame);
        }
    }

    /**
     * 收到文本帧，契约见 WebSocketServer#onMessage。
     *
     * 本服务端只接受二进制帧，收到文本帧即以 1003 关闭连接。
     *
     * @param conn 来源连接，不能为 null
     * @param message 文本消息，不能为 null
     */
    @Override
    public void onMessage(WebSocket conn, String message) {
        conn.close(1003, "不支持文本帧");
    }

    /**
     * 底层连接错误，契约见 WebSocketServer#onError。
     *
     * 实现为空操作：错误统一由随后触发的 onClose 收敛为断开处理，此处不重复上报。
     *
     * @param conn 出错连接，允许为 null（监听阶段的错误没有关联连接）
     * @param ex 底层错误，不能为 null
     */
    @Override
    public void onError(WebSocket conn, Exception ex) {
    }

    /**
     * 服务端启动完成，契约见 WebSocketServer#onStart。
     *
     * 完成 started 凭证，唤醒所有在 awaitStarted 上等待的线程。
     */
    @Override
    public void onStart() {
        started.complete(null);
    }

    /**
     * 阻塞等待服务端启动完成。
     *
     * @param timeout 等待的最长时间，不能为 null
     * @throws IllegalStateException 当等待超时或线程被中断时抛出，原因异常保留在 cause 中
     */
    public void awaitStarted(java.time.Duration timeout) {
        try {
            started.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("服务端启动超时", e);
        }
    }

    /**
     * 分发请求帧。
     *
     * 未注册 kind 的请求直接回送状态码 255；已注册时把帧字段与远端地址封装为 CoreRequest 并提交到
     * handlerExecutor 执行，处理器返回 null 或抛出异常都会转成状态码 255 的响应。
     *
     * @param conn 请求来源连接，不能为 null
     * @param frame 已解码的请求帧，不能为 null
     */
    private void handleRequest(WebSocket conn, CoreFrame frame) {
        CoreKinds.validate(frame.kind());
        CoreRequestHandler handler = handlers.get(frame.kind());
        if (handler == null) {
            byte[] payload = ("Requested protocol hasn't been implemented: " + frame.kind())
                    .getBytes(StandardCharsets.UTF_8);
            sendResponse(conn, new CoreResponse(255, frame.requestId(), frame.kind(), payload));
            return;
        }

        InetSocketAddress remote = conn.getRemoteSocketAddress();
        CoreRequest request = new CoreRequest(frame.requestId(), frame.kind(), frame.payload(), remote);
        handlerExecutor.execute(() -> {
            try {
                CoreResponse response = handler.handle(request);
                if (response == null) {
                    response = new CoreResponse(255, request.requestId(), request.kind(),
                            "Handler returned null response".getBytes(StandardCharsets.UTF_8));
                }
                sendResponse(conn, response);
            } catch (Exception e) {
                byte[] payload = String.valueOf(e).getBytes(StandardCharsets.UTF_8);
                sendResponse(conn, new CoreResponse(255, request.requestId(), request.kind(), payload));
            }
        });
    }

    /**
     * 把响应编码为响应帧并写回连接。
     *
     * 状态码与 requestId 原样写入帧头，负载不做额外编码。
     *
     * @param conn 目标连接，不能为 null
     * @param response 待发送的响应，不能为 null
     */
    private void sendResponse(WebSocket conn, CoreResponse response) {
        byte[] bytes = codec.encode(new CoreFrame(
                CoreMessageType.RESPONSE,
                (byte) 0,
                response.status(),
                response.requestId(),
                response.kind(),
                response.payload()
        ));
        metrics.onFrameSent(bytes.length);
        conn.send(bytes);
    }

    /**
     * 分发事件帧。
     *
     * 未注册 kind 的事件被静默丢弃；处理器抛出的异常同样被静默忽略，不会回传客户端。
     *
     * @param conn 事件来源连接，不能为 null
     * @param frame 已解码的事件帧，不能为 null
     */
    private void handleEvent(WebSocket conn, CoreFrame frame) {
        CoreKinds.validate(frame.kind());
        CoreEventHandler handler = eventHandlers.get(frame.kind());
        if (handler == null) {
            return;
        }
        InetSocketAddress remote = conn.getRemoteSocketAddress();
        handlerExecutor.execute(() -> {
            try {
                handler.handle(frame.kind(), frame.payload(), remote);
            } catch (Exception ignored) {
            }
        });
    }

    /**
     * 回应心跳帧。
     *
     * 回送一个心跳帧，并携带请求帧的 requestId 以便请求方配对。
     *
     * @param conn 心跳来源连接，不能为 null
     * @param frame 已解码的心跳帧，不能为 null
     */
    private void handleHeartbeat(WebSocket conn, CoreFrame frame) {
        byte[] bytes = codec.encode(new CoreFrame(CoreMessageType.HEARTBEAT, (byte) 0, 0, frame.requestId(), "", new byte[0]));
        metrics.onFrameSent(bytes.length);
        conn.send(bytes);
    }

    /**
     * 获取服务端指标快照。
     *
     * 快照中的挂起请求数固定为 0：服务端不跟踪请求与响应的配对关系。
     *
     * @return 指标快照，永不为 null
     */
    public ConnectionMetricsSnapshot metrics() {
        return metrics.snapshot(0);
    }

    /**
     * 获取当前连接数。
     *
     * @return 已建立且未关闭的连接数量，非负
     */
    public long connections() {
        return connections.get();
    }
}
