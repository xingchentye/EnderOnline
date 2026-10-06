/*
 * 本文件属于 EnderOnline 核心逻辑层。
 *
 * 职责：进程级 WebSocket 连接单例，把 CoreComm 客户端封装为一次 connect 调用。
 */
package com.multiplayer.ender.network;

import com.endercore.core.comm.CoreComm;
import com.endercore.core.comm.EnderExecutors;
import com.endercore.core.comm.client.CoreWebSocketClient;
import com.endercore.core.comm.config.CoreWebSocketConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * 与 EnderCore 后端的 WebSocket 连接入口。
 *
 * 本类只负责「建立与关闭连接」这一件事，不做重连、不做帧分发、不感知房间语义；
 * 协议语义全部下沉到 com.endercore.core.comm 的客户端实现。
 *
 * 设计约束：
 * 1. 全进程至多持有一个 CoreWebSocketClient：每次 connect 都会覆盖 client 字段，
 *    旧连接不会被自动关闭，连续 connect 会泄漏上一个客户端的连接资源。
 * 2. 连接地址固定拼接为 ws://host:port/ws，路径 /ws 是后端约定的固定入口，不可由调用方覆盖。
 * 3. 连接超时固定 10 秒，调用方无法通过本类调整。
 * 4. 实现上直接派生了一条未命名线程完成阻塞连接，未走统一执行器，属于既有的规范偏差；
 *    该线程无所有者、不可被取消，连接超时后才自行结束。
 *
 * 线程安全性：单例的创建受类锁保护，不会重复构造。
 * 但实例字段 client 不是 volatile：connect 的赋值与 close 的读取没有同步关系，
 * 跨线程调用时可能读到 null 或读到已被替换的旧客户端，调用方需在外部串行化 connect 与 close。
 *
 * @since 1.0
 * @see com.endercore.core.comm.client.CoreWebSocketClient
 */
public class NetworkClient {

    /** 本类日志记录器，永不为 null，由 SLF4J 在类初始化时绑定。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkClient.class);
    
    /**
     * 单例实例。
     *
     * 允许为 null，表示尚未创建；所有访问都在 synchronized 的 getInstance 内完成。
     */
    private static NetworkClient instance;
    
    /**
     * 当前 WebSocket 客户端。
     *
     * 允许为 null：为 null 表示尚未连接或连接对象已被替换。
     * 由 connect 赋值、由 close 使用，两者之间没有同步保护。
     */
    private CoreWebSocketClient client;

    /**
     * 私有构造函数，禁止外部实例化。
     *
     * 只能通过 getInstance 获取实例，以保证连接状态集中在一处。
     */
    private NetworkClient() {}

    /**
     * 获取网络客户端单例。
     *
     * @return 单例实例，永不为 null
     */
    public static synchronized NetworkClient getInstance() {
        if (instance == null) {
            instance = new NetworkClient();
        }
        return instance;
    }

    /**
     * 连接到指定的后端服务器。
     *
     * 立即返回，实际的阻塞连接在内部派生线程上执行；
     * 每次调用都会新建一个 CoreWebSocketClient 并覆盖 client 字段，旧连接不会被关闭。
     *
     * 幂等性：本方法不幂等，重复调用会产生多个并发连接并遗留前一个客户端。
     *
     * @param host 服务器主机名或 IP 地址，不能为 null 或空字符串
     * @param port 服务器端口号，取值 1 到 65535
     * @return 连接成功时正常完成的 Future；失败时以原始异常异常完成，调用方需用
     *         handle / exceptionally 处理而不是直接 get
     * @throws IllegalArgumentException 当 host 与 port 拼出的字符串无法被 URI 解析时由 URI.create 抛出
     */
    public CompletableFuture<Void> connect(String host, int port) {
        LOGGER.info("Connecting to {}:{} via EnderCore WebSocket", host, port);
        CompletableFuture<Void> future = new CompletableFuture<>();
        
        try {
            CoreWebSocketConfig config = CoreWebSocketConfig.builder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
                
            client = CoreComm.newClient(config, null, null);
            
            URI uri = URI.create("ws://" + host + ":" + port + "/ws"); 
            
            
            EnderExecutors.daemonThread(() -> {
                try {
                    client.connect(uri).get();
                    future.complete(null);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }, "Ender-Network-Connect").start();
            
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }
    
    /**
     * 关闭当前的网络连接。
     *
     * 关闭超时固定 1 秒。关闭失败只记录警告，不向上抛出，也不把 client 置回 null，
     * 因此关闭后立刻再次 close 会重复关闭同一个客户端。
     *
     * 幂等性：本方法幂等，未连接时是空操作；对同一客户端重复关闭只产生一条警告日志。
     */
    public void close() {
        if (client != null) {
            try {
                client.close(Duration.ofSeconds(1));
            } catch (Exception e) {
                LOGGER.warn("Error closing client", e);
            }
        }
    }
}
