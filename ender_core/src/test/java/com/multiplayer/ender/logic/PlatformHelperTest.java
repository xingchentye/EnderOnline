package com.multiplayer.ender.logic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PlatformHelper#findAvailablePort()} 的行为固化测试。
 *
 * <p>背景：{@code findAvailablePort()} 在全库有 3 处重复实现
 * （{@code PlatformHelper} / {@code EnderApiClient} / {@code EasyTierManager}），
 * P3 阶段会合并为唯一的 {@code PortAllocator}。本测试先固化当前行为，
 * 作为合并时的回归基线。</p>
 *
 * <p>已知缺陷（记录，不在本测试中修复）：
 * {@code findAvailablePort()} 采用「打开端口 0 拿号 → 立刻关闭 → 返回号码」的方式，
 * 在返回与调用方真正 bind 之间存在竞态窗口（TOCTOU），
 * 其他进程可能抢先占用该端口。因此它是**建议端口**而不是**已占用端口**。
 * 测试 {@link #returnedPortIsNotCurrentlyBound()} 记录的是「返回时端口空闲」这一事实。</p>
 */
class PlatformHelperTest {

    @Test
    @DisplayName("findAvailablePort：返回合法端口范围（1..65535，排除 0）")
    void returnsPortInValidRange() {
        for (int i = 0; i < 20; i++) {
            int port = PlatformHelper.findAvailablePort();

            assertTrue(port > 0, "端口必须为正数，实际=" + port);
            assertTrue(port <= 65535, "端口必须 ≤65535，实际=" + port);
        }
    }

    @Test
    @DisplayName("findAvailablePort：连续调用不返回 0，且返回值不重复（20 次）")
    void doesNotReturnZeroAndRarelyRepeats() {
        Set<Integer> seen = new HashSet<>();

        for (int i = 0; i < 20; i++) {
            int port = PlatformHelper.findAvailablePort();

            assertNotEquals(0, port, "端口 0 表示系统分配，不应被直接返回");
            seen.add(port);
        }

        // 由内核从临时端口区间分配，20 次里几乎不会重复；
        // 允许极少重复（端口回收很快），因此只要求至少 18 个不同值。
        assertTrue(seen.size() >= 18,
                "20 次调用应得到至少 18 个不同端口，实际不同值=" + seen.size());
    }

    @Test
    @DisplayName("findAvailablePort：返回的端口在返回瞬间是空闲的")
    void returnedPortIsNotCurrentlyBound() throws IOException {
        int port = PlatformHelper.findAvailablePort();
        assertTrue(port > 0, "无法取得可用端口，测试环境异常");

        // 立刻尝试占用：当前实现保证「返回时」空闲（但不保证之后仍空闲）
        try (ServerSocket probe = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
            assertTrue(probe.isBound(), "返回的端口应当可以立即被绑定");
        }
    }

    @Test
    @DisplayName("findAvailablePort：不会返回已被占用的端口")
    void skipsBoundPort() throws IOException {
        // 先占住一个端口，确认返回的不是它
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int occupiedPort = occupied.getLocalPort();

            for (int i = 0; i < 30; i++) {
                int candidate = PlatformHelper.findAvailablePort();
                assertNotEquals(occupiedPort, candidate,
                        "不应返回已被占用的端口 " + occupiedPort);
            }
        }
    }

    @Test
    @DisplayName("getExecutableName：Windows 带 .exe 后缀，其他平台不带")
    void executableNameFollowsOs() {
        String name = PlatformHelper.getExecutableName();

        if (PlatformHelper.getOS() == PlatformHelper.OS.WINDOWS) {
            assertTrue(name.endsWith(".exe"), "Windows 下可执行文件名应带 .exe，实际=" + name);
        } else {
            assertTrue(!name.endsWith(".exe"), "非 Windows 下不应带 .exe，实际=" + name);
        }
    }

    @Test
    @DisplayName("getOS / getArch：返回非 null（探测不得失败）")
    void osAndArchDetectionNeverNull() {
        assertNotEquals(null, PlatformHelper.getOS(), "getOS 不应返回 null");
        assertNotEquals(null, PlatformHelper.getArch(), "getArch 不应返回 null");
    }
}
