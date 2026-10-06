/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：通信层的对外入口与静态工厂，统一创建 WebSocket 客户端和服务端，并提供一个用于本地联调的命令行入口。
 *
 * 本文件不持有任何连接状态，创建出的实例其生命周期归调用方所有。
 */
package com.endercore.core.comm;

import com.endercore.core.comm.api.CoreExceptionHandler;
import com.endercore.core.comm.client.CoreWebSocketClient;
import com.endercore.core.comm.config.CoreWebSocketConfig;
import com.endercore.core.comm.protocol.CoreResponse;
import com.endercore.core.comm.server.CoreRequest;
import com.endercore.core.comm.server.CoreWebSocketServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;

 
/**
 * 通信层入口。
 *
 * 对外只暴露静态工厂与命令行入口，调用方无需依赖 client / server 包的构造细节。
 *
 * 设计约束：
 * 1. 本类只做创建与转发，不缓存任何客户端或服务端实例，因此不负责关闭它们。
 * 2. 工厂签名需与 CoreWebSocketClient / CoreWebSocketServer 的构造器保持一致，便于测试替换为桩实现。
 *
 * 线程安全性：本类无状态，全部方法为静态且不读写共享变量，可被任意线程并发调用。
 *
 * @since 1.0
 * @see CoreWebSocketClient
 * @see CoreWebSocketServer
 */
public final class CoreComm {
    /** 私有构造函数，防止实例化。 */
    private CoreComm() {
    }

    /**
     * 创建 WebSocket 客户端。
     *
     * 返回的实例尚未发起连接，需由调用方调用 connect 才会建立链路。
     *
     * @param config 客户端配置，不能为 null
     * @param exceptionHandler 异常处理器，允许为 null，为 null 时丢弃全部错误回调
     * @param callbackExecutor 回调执行器，允许为 null，为 null 时由客户端内部创建缓存线程池
     * @return 新建的客户端实例，永不为 null
     */
    public static CoreWebSocketClient newClient(CoreWebSocketConfig config, CoreExceptionHandler exceptionHandler, Executor callbackExecutor) {
        return new CoreWebSocketClient(config, exceptionHandler, callbackExecutor);
    }

    /**
     * 创建 WebSocket 服务端。
     *
     * 返回的实例尚未开始监听，需由调用方调用 start 才会绑定端口。
     *
     * @param address 绑定地址，不能为 null
     * @param maxFrameBytes 单帧最大字节数，不得小于协议头长度，否则构造失败
     * @param handlerExecutor 请求与事件处理器的执行器，允许为 null，为 null 时由服务端内部创建缓存线程池
     * @return 新建的服务端实例，永不为 null
     */
    public static CoreWebSocketServer newServer(InetSocketAddress address, int maxFrameBytes, Executor handlerExecutor) {
        return new CoreWebSocketServer(address, maxFrameBytes, handlerExecutor);
    }

    /**
     * 命令行自检入口。
     *
     * server 模式启动服务端、注册 c:ping 处理器后阻塞进程；client 模式连接指定地址并发送一次 c:ping 请求后关闭连接。
     * 该入口仅用于本地联调，不进入模组运行时路径。
     *
     * @param args 命令行参数，第 0 个元素为模式名，其余元素的含义随模式而定
     * @throws Exception 当启动、连接或收发失败时抛出
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage:");
            System.err.println("  server [host] [port]");
            System.err.println("  client <wsUrl> [kind] [payloadUtf8]");
            return;
        }

        String mode = args[0];
        if ("server".equalsIgnoreCase(mode)) {
            String host = args.length >= 2 ? args[1] : "0.0.0.0";
            int port = args.length >= 3 ? Integer.parseInt(args[2]) : 18080;

            CoreWebSocketServer server = newServer(new InetSocketAddress(host, port), 4 * 1024 * 1024, null);
            server.register("c:ping", (CoreRequest req) -> new CoreResponse(0, req.requestId(), req.kind(), req.payload()));
            server.start();
            server.awaitStarted(Duration.ofSeconds(5));
            System.out.println("CoreWebSocketServer started on ws://" + host + ":" + port);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    server.stop(1000);
                } catch (Exception ignored) {
                }
            }));

            new CountDownLatch(1).await();
            return;
        }

        if ("client".equalsIgnoreCase(mode)) {
            if (args.length < 2) {
                System.err.println("client mode requires wsUrl");
                return;
            }
            URI uri = URI.create(args[1]);
            String kind = args.length >= 3 ? args[2] : "c:ping";
            String payloadUtf8 = args.length >= 4 ? args[3] : "hello";

            CoreWebSocketClient client = newClient(CoreWebSocketConfig.builder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .requestTimeout(Duration.ofSeconds(5))
                    .heartbeatInterval(Duration.ZERO)
                    .build(), null, null);
            client.connect(uri).get();
            CoreResponse resp = client.sendSync(kind, payloadUtf8.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(5));
            System.out.println("status=" + resp.status() + ", ok=" + resp.isOk() + ", payloadUtf8=" + resp.payloadUtf8());
            client.close(Duration.ofSeconds(2)).get();
            return;
        }

        System.err.println("Unknown mode: " + mode);
        System.err.println("Usage:");
        System.err.println("  server [host] [port]");
        System.err.println("  client <wsUrl> [kind] [payloadUtf8]");
    }
}
