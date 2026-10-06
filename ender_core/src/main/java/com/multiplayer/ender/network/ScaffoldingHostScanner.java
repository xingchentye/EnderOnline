/*
 * 本文件属于 EnderOnline 网络层。
 *
 * 职责：在 EasyTier 对等节点表中定位房主地址——按主机名前缀识别房主，并解析其 Scaffolding 端口。
 *
 * 这段逻辑原先内联在 EnderApiClient 中并直接调用 EasyTierManager；抽成纯函数后只依赖传入的两张表，
 * 因此可以在不启动 EasyTier 的情况下测试。
 */
package com.multiplayer.ender.network;

import java.net.InetSocketAddress;
import java.util.Map;

/**
 * 房主定位扫描。
 *
 * 房主在对等节点表里以「房主主机名前缀 + Scaffolding 端口」的形式出现
 * （例如 {@code scaffolding-mc-server-13448}）。本类在主机名表中找到第一个符合该格式
 * 且能解析出端口的节点，再用其 IP 组装地址。
 *
 * 设计约束：
 * 1. 纯函数：只读取传入的两张表，不访问 EasyTier、不读写任何共享状态。
 * 2. 「前缀匹配但端口非法」的节点会被跳过，但已经置位 hostSeen——说明房间存在、只是这一个节点不可用。
 * 3. 「前缀匹配但缺 IP」会把 ipMissing 置位并继续扫描其它节点，而不是直接失败：
 *    路由表可能尚未就绪，下一个节点或许可用。
 * 4. 返回第一个成功解析的地址即结束，不评估其它节点。
 *
 * 线程安全性：无状态，全部方法为静态且只读入参，可被任意线程并发调用。
 */
final class ScaffoldingHostScanner {

    /** 私有构造函数，防止实例化。 */
    private ScaffoldingHostScanner() {
    }

    /**
     * 在给定的对等节点表中扫描房主地址。
     *
     * @param hostnames 对等节点主机名表，键为节点 id，允许为 null；值为 null 的条目被跳过
     * @param ips 对等节点 IP 表，键为节点 id，允许为 null
     * @param hostPrefix 房主主机名前缀，不能为 null
     * @return 扫描结果，永不为 null；未找到可用的房主时 address 为 null
     * @throws NullPointerException 当 hostPrefix 为 null 时抛出
     */
    static ScanResult scan(Map<String, String> hostnames, Map<String, String> ips, String hostPrefix) {
        if (hostPrefix == null) {
            throw new NullPointerException("hostPrefix");
        }
        ScanResult result = new ScanResult();
        if (hostnames == null) {
            return result;
        }

        for (Map.Entry<String, String> entry : hostnames.entrySet()) {
            String hostname = entry.getValue();
            if (hostname == null) {
                continue;
            }
            String trimmed = hostname.trim();
            if (!trimmed.startsWith(hostPrefix)) {
                continue;
            }

            result.hostSeen = true;

            String portStr = trimmed.substring(hostPrefix.length());
            int port;
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                // 前缀匹配但端口不是数字：仍算见过房主，只是这个节点不可用
                continue;
            }

            String ip = ips == null ? null : ips.get(entry.getKey());
            if (ip == null || ip.isBlank()) {
                result.ipMissing = true;
                continue;
            }

            result.address = new InetSocketAddress(ip, port);
            return result;
        }
        return result;
    }
}
