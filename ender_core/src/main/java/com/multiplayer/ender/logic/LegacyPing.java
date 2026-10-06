/*
 * 本文件属于 EnderOnline 核心逻辑层。
 *
 * 职责：用 0xFE 探测包检测旧版服务端是否在线。
 */
package com.multiplayer.ender.logic;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 传统服务器 Ping 检测。
 *
 * 走的是 Minecraft 1.6 及更早的 0xFE 协议，只能回答「端口上是否有会回 0xFF 的服务端」，
 * 不解析 Motd、版本号与在线人数，因此结果不能用来判断服务端版本或可用性细节。
 *
 * 设计约束：
 * 1. 本类是纯探测工具，不持有连接、不重试、不缓存结果。
 * 2. 探测失败一律以 false 表达，异常在方法内部被吞掉，调用方拿不到失败原因。
 *
 * 线程安全性：本类无实例状态，静态方法可被多线程并发调用；每次调用自带独立 Socket。
 *
 * @since 1.0
 */
public class LegacyPing {

    /**
     * 检测目标服务器是否可连接。
     *
     * 阻塞执行：总耗时上界约为 2 倍 timeout（连接阶段与读取阶段各一次超时）。
     * 任何异常（连接拒绝、超时、读取中断）都返回 false，不向上抛出。
     *
     * @param host 目标主机地址，不能为 null 或空字符串
     * @param port 目标端口号，取值 1 到 65535
     * @param timeout 连接与读取超时，单位毫秒，必须大于 0
     * @return 服务端首字节回 0xFF 时返回 true，其余情况（含各类异常）返回 false
     */
    public static boolean check(String host, int port, int timeout) {
        try (Socket socket = new Socket()) {
            socket.setSoTimeout(timeout);
            socket.connect(new InetSocketAddress(host, port), timeout);
            
            OutputStream out = socket.getOutputStream();
            out.write(0xFE); 
            out.flush();
            
            InputStream in = socket.getInputStream();
            int firstByte = in.read();
            
            
            return firstByte == 0xFF;
        } catch (Exception e) {
            return false;
        }
    }
}
