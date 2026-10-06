/*
 * 本文件属于 EnderOnline 单元测试。
 *
 * 职责：固化房主定位扫描的语义，作为从 EnderApiClient 拆出该逻辑后的回归基线。
 *
 * 关键约束：本类是纯函数，测试不需要 EasyTier——「房间不存在」与「路由未就绪」必须能被区分，
 * 否则加入方只会看到一句笼统的失败提示。
 */
package com.multiplayer.ender.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScaffoldingHostScanner} 的行为固化测试。
 *
 * 这组测试守护什么：房主按主机名前缀识别、端口取自主机名后缀、缺少 IP 时继续扫描其它节点，
 * 以及三种结果（找到 / 见到但缺 IP / 什么都没见到）之间的区分。
 *
 * 注意：本文件的断言消息可保留中文——测试源码已从 i18n 守卫中排除，中文失败信息更易读。
 *
 * 线程安全性：每个用例各自构造 Map，无共享状态，可并行执行。
 *
 * @see ScaffoldingHostScanner
 */
class ScaffoldingHostScannerTest {

    /** 房主主机名前缀，与生产实现一致。 */
    private static final String PREFIX = "scaffolding-mc-server-";

    /**
     * 构造主机名表。
     *
     * @param pairs 交替的 id 与主机名
     * @return 有序表，永不为 null
     */
    private static Map<String, String> names(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /**
     * 构造 IP 表。
     *
     * @param pairs 交替的 id 与 IP
     * @return 表，永不为 null
     */
    private static Map<String, String> ips(String... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("命中房主：地址由对等节点 IP 与主机名后缀端口组成")
    void findsHostAddress() {
        ScanResult result = ScaffoldingHostScanner.scan(
                names("peer-1", PREFIX + "13448"),
                ips("peer-1", "10.0.0.5"),
                PREFIX);

        assertNotNull(result.address, "应解析出房主地址");
        assertEquals("10.0.0.5", result.address.getAddress().getHostAddress(), "应使用对等节点 IP");
        assertEquals(13448, result.address.getPort(), "端口应取自主机名后缀");
        assertTrue(result.hostSeen, "命中时应置位 hostSeen");
    }

    @Test
    @DisplayName("未命中：无前缀匹配时 address 为 null 且 hostSeen 为 false")
    void noMatchLeavesEverythingEmpty() {
        ScanResult result = ScaffoldingHostScanner.scan(
                names("peer-1", "some-player", "peer-2", "another"),
                ips("peer-1", "10.0.0.5"),
                PREFIX);

        assertNull(result.address, "不应解析出地址");
        assertFalse(result.hostSeen, "未见到房主主机名");
        assertFalse(result.ipMissing, "也不应标记缺 IP");
    }

    @Test
    @DisplayName("前缀匹配但缺 IP：置位 hostSeen 与 ipMissing，且 address 为 null")
    void hostSeenButIpMissing() {
        ScanResult result = ScaffoldingHostScanner.scan(
                names("peer-1", PREFIX + "13448"),
                ips(),
                PREFIX);

        assertTrue(result.hostSeen, "见到了房主主机名");
        assertTrue(result.ipMissing, "应标记缺 IP，以区别于「房间不存在」");
        assertNull(result.address, "没有 IP 时无法组装地址");
    }

    @Test
    @DisplayName("缺 IP 的节点不影响后续节点：继续扫描并找到可用房主")
    void continuesAfterMissingIp() {
        ScanResult result = ScaffoldingHostScanner.scan(
                names("peer-1", PREFIX + "13448", "peer-2", PREFIX + "20000"),
                ips("peer-2", "10.0.0.9"),
                PREFIX);

        assertNotNull(result.address, "应继续扫描并命中第二个节点");
        assertEquals(20000, result.address.getPort(), "应使用第二个节点的端口");
        assertTrue(result.ipMissing, "第一个节点缺 IP 的事实仍应被记录");
        assertTrue(result.hostSeen, "应置位 hostSeen");
    }

    @Test
    @DisplayName("前缀匹配但端口非数字：跳过该节点，hostSeen 仍为 true")
    void skipsNonNumericPort() {
        ScanResult result = ScaffoldingHostScanner.scan(
                names("peer-1", PREFIX + "not-a-port"),
                ips("peer-1", "10.0.0.5"),
                PREFIX);

        assertNull(result.address, "端口非法时不应解析出地址");
        assertTrue(result.hostSeen, "端口非法仍说明见过房主主机名");
    }

    @Test
    @DisplayName("非数字端口的节点之后仍能命中合法节点")
    void continuesAfterNonNumericPort() {
        ScanResult result = ScaffoldingHostScanner.scan(
                names("peer-1", PREFIX + "abc", "peer-2", PREFIX + "13448"),
                ips("peer-1", "10.0.0.5", "peer-2", "10.0.0.6"),
                PREFIX);

        assertNotNull(result.address, "应跳过非法节点并命中后续节点");
        assertEquals(13448, result.address.getPort(), "端口应取自第二个节点");
    }

    @Test
    @DisplayName("主机名前后空白被忽略")
    void trimsHostname() {
        ScanResult result = ScaffoldingHostScanner.scan(
                names("peer-1", "  " + PREFIX + "13448  "),
                ips("peer-1", "10.0.0.5"),
                PREFIX);

        assertNotNull(result.address, "带空白的主机名应被裁剪后匹配");
        assertEquals(13448, result.address.getPort(), "端口应从裁剪后的后缀解析");
    }

    @Test
    @DisplayName("null 值主机名条目被跳过，不影响后续匹配")
    void skipsNullHostname() {
        Map<String, String> hostnames = new LinkedHashMap<>();
        hostnames.put("peer-null", null);
        hostnames.put("peer-1", PREFIX + "13448");

        ScanResult result = ScaffoldingHostScanner.scan(hostnames, ips("peer-1", "10.0.0.5"), PREFIX);

        assertNotNull(result.address, "null 主机名应被跳过");
        assertEquals(13448, result.address.getPort(), "应命中后续合法节点");
    }

    @Test
    @DisplayName("ip 为空白字符串等同于缺失")
    void blankIpCountsAsMissing() {
        ScanResult result = ScaffoldingHostScanner.scan(
                names("peer-1", PREFIX + "13448"),
                ips("peer-1", "   "),
                PREFIX);

        assertTrue(result.ipMissing, "空白 IP 应被视为缺失");
        assertNull(result.address, "缺失 IP 时无法组装地址");
    }

    @Test
    @DisplayName("null 入参：主机名表与 IP 表为 null 时返回空结果，前缀为 null 时抛异常")
    void handlesNullArguments() {
        ScanResult noHostnames = ScaffoldingHostScanner.scan(null, ips(), PREFIX);
        assertNotNull(noHostnames, "主机名表为 null 时仍应返回结果对象");
        assertNull(noHostnames.address, "主机名表为 null 时不应有地址");

        ScanResult noIps = ScaffoldingHostScanner.scan(names("peer-1", PREFIX + "13448"), null, PREFIX);
        assertTrue(noIps.ipMissing, "IP 表为 null 时应标记缺 IP");

        assertThrows(NullPointerException.class,
                () -> ScaffoldingHostScanner.scan(names(), ips(), null),
                "前缀为 null 应被拒绝");
    }

    @Test
    @DisplayName("端口边界：主机名后缀恰为合法端口字符串时可解析")
    void parsesPortBoundaries() {
        ScanResult result = ScaffoldingHostScanner.scan(
                names("peer-1", PREFIX + "1"),
                ips("peer-1", "10.0.0.5"),
                PREFIX);

        assertNotNull(result.address, "端口 1 应可解析");
        assertEquals(1, result.address.getPort(), "应解析出端口 1");
    }
}
