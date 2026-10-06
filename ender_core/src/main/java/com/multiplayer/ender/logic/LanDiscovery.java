/*
 * 本文件属于 EnderOnline 核心逻辑层。
 *
 * 职责：向 224.0.2.60:4445 广播局域网服务器信息，供 Minecraft 客户端在多人游戏列表中看到本房间。
 */
package com.multiplayer.ender.logic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 局域网发现广播服务。
 *
 * 本类只负责「让客户端看得见房间」，不参与连接建立、不校验客户端身份、不感知房间状态；
 * 广播是无条件周期性的，房间已关闭时若未调用 stopBroadcaster，广播会一直持续。
 *
 * 设计约束：
 * 1. 广播内容固定为 [MOTD]...[/MOTD][AD]...[/AD] 格式，即 Minecraft 1.6 及更早的局域网发现报文，
 *    格式不可自由改动，否则客户端不再识别。
 * 2. 全进程至多存在一个广播线程：startBroadcaster 内部先停旧的再启新的，调用方无需自行去重。
 * 3. 广播间隔固定 1500 毫秒，单次发送失败只降低 debug 日志，不重试也不终止调度。
 *
 * 线程安全性：broadcasterExecutor 是本类的静态可变态，start / stop 均声明为 synchronized，
 * 因此状态读写互斥。调度任务本身运行在独立的守护线程 "Ender-Lan-Broadcaster" 上，
 * 与调用线程并发访问静态字段，必须继续依赖 synchronized 而不是裸读写。
 *
 * @since 1.0
 */
public class LanDiscovery {

    /** 本类日志记录器，永不为 null，由 SLF4J 在类初始化时绑定。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(LanDiscovery.class);

    /** 局域网发现组播地址，Minecraft 客户端硬编码监听该地址（IPv4 多播）。 */
    private static final String GROUP_ADDRESS = "224.0.2.60";

    /** 局域网发现组播端口，取值 4445，由 Minecraft 协议规定，不可更改。 */
    private static final int PORT = 4445;

    /**
     * 广播任务调度执行器。
     *
     * 允许为 null：null 表示当前没有正在运行的广播。
     * 由 startBroadcaster 创建（单线程、守护线程）、由 stopBroadcaster 置回 null。
     */
    private static ScheduledExecutorService broadcasterExecutor;

    /**
     * 启动局域网广播服务。
     *
     * 先停止已有广播再启动新的，因此本方法可安全地用于「切换端口或 MOTD」的场景。
     * 首次广播立即执行，之后按固定 1500 毫秒周期重复。
     *
     * 幂等性：本方法幂等——重复调用不会产生第二个广播线程，旧线程会被强制终止。
     *
     * @param port 本地服务器端口号，取值范围 1 到 65535，会被原样写入广播报文的 AD 字段
     * @param motd 服务器描述信息（Message Of The Day），允许为 null，为 null 时会拼接出字面量 null
     */
    public static synchronized void startBroadcaster(int port, String motd) {
        stopBroadcaster();
        
        broadcasterExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Ender-Lan-Broadcaster");
            t.setDaemon(true);
            return t;
        });

        broadcasterExecutor.scheduleAtFixedRate(() -> {
            try {
                
                String msg = String.format("[MOTD]%s[/MOTD][AD]%d[/AD]", motd, port);
                byte[] data = msg.getBytes(StandardCharsets.UTF_8);
                InetAddress group = InetAddress.getByName(GROUP_ADDRESS);
                
                try (DatagramSocket socket = new DatagramSocket()) {
                    DatagramPacket packet = new DatagramPacket(data, data.length, group, PORT);
                    socket.send(packet);
                }
            } catch (Exception e) {
                LOGGER.debug("Failed to broadcast LAN packet: {}", e.getMessage());
            }
        }, 0, 1500, TimeUnit.MILLISECONDS);
        
        LOGGER.info("Started LAN broadcaster for port {} with MOTD: {}", port, motd);
    }

    /**
     * 停止局域网广播服务。
     *
     * 使用 shutdownNow 中断调度线程并丢弃尚未执行的周期任务，因此停止后不会再发出任何报文。
     * 本方法会阻塞到调用点，但不会等待正在执行中的一次发送完成。
     *
     * 幂等性：本方法幂等，未启动或已停止时调用只做一次 null 判断，不产生副作用。
     */
    public static synchronized void stopBroadcaster() {
        if (broadcasterExecutor != null) {
            broadcasterExecutor.shutdownNow();
            broadcasterExecutor = null;
            LOGGER.info("Stopped LAN broadcaster");
        }
    }
}
