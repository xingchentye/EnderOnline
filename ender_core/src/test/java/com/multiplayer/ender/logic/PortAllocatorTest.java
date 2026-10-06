/*
 * 本文件属于 EnderOnline 单元测试。
 *
 * 职责：固化 PortAllocator 的行为，取代原先写在 PlatformHelperTest 中的端口用例。
 *
 * 关键约束：本测试固化的是「建议端口」语义，不是「已占用端口」语义；
 * 已知的 TOCTOU 竞态窗口按缺陷记录，不在测试里绕过或掩盖。
 */
package com.multiplayer.ender.logic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link PortAllocator} 的行为固化测试。
 *
 * 这组测试守护什么：三处重复实现归并为唯一 PortAllocator 后的四条契约——
 * 返回值落在 1..65535（排除 0）、连续调用几乎不重复、返回瞬间端口确实可绑定、
 * 失败语义按调用方选择（-1 或兜底值）而不被悄悄改变。
 *
 * 已知缺陷（记录，不在本测试中修复）：
 * {@code findAvailablePort()} 采用「打开端口 0 拿号 → 立刻关闭 → 返回号码」的方式，
 * 在返回与调用方真正 bind 之间存在竞态窗口（TOCTOU），其他进程可能抢先占用该端口。
 * 因此它是**建议端口**而不是**已占用端口**。
 * 测试 {@link #returnedPortIsNotCurrentlyBound()} 记录的是「返回时端口空闲」这一事实，
 * 而不是「返回后可安全使用」——不要把该断言当成更强保证。
 *
 * 注意：本文件的断言消息可保留中文——测试源码已从 i18n 守卫中排除，中文失败信息更易读。
 *
 * 线程安全性：每个用例各自建立本地 ServerSocket 探针，无共享可变状态，可并行执行。
 *
 * @see PortAllocator
 */
class PortAllocatorTest {

    @Test
    @DisplayName("findAvailablePort：返回合法端口范围（1..65535，排除 0）")
    void returnsPortInValidRange() {
        for (int i = 0; i < 20; i++) {
            int port = PortAllocator.findAvailablePort();

            assertTrue(port > 0, "端口必须为正数，实际=" + port);
            assertTrue(port <= 65535, "端口必须 ≤65535，实际=" + port);
        }
    }

    @Test
    @DisplayName("findAvailablePort：连续调用不返回 0，且返回值不重复（20 次）")
    void doesNotReturnZeroAndRarelyRepeats() {
        Set<Integer> seen = new HashSet<>();

        for (int i = 0; i < 20; i++) {
            int port = PortAllocator.findAvailablePort();

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
        int port = PortAllocator.findAvailablePort();
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
                int candidate = PortAllocator.findAvailablePort();
                assertNotEquals(occupiedPort, candidate,
                        "不应返回已被占用的端口 " + occupiedPort);
            }
        }
    }

    @Test
    @DisplayName("findAvailablePortOr：成功时返回可用端口，不使用兜底值")
    void findAvailablePortOrIgnoresFallbackOnSuccess() {
        int fallback = 13448;
        int port = PortAllocator.findAvailablePortOr(fallback);

        assertTrue(port > 0, "端口必须为正数，实际=" + port);
        assertTrue(port <= 65535, "端口必须 ≤65535，实际=" + port);
        // 正常情况下内核必然分配成功，因此不应落到兜底值
        assertNotEquals(fallback, port,
                "内核可分配端口时不应返回兜底值 " + fallback + "（重复命中说明实现退化了）");
    }

    @Test
    @DisplayName("isPortAvailable：已绑定的端口返回 false")
    void boundPortIsNotAvailable() throws IOException {
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int occupiedPort = occupied.getLocalPort();
            assertTrue(!PortAllocator.isPortAvailable(occupiedPort),
                    "已被占用的端口不应报告为可用：" + occupiedPort);
        }
    }

    @Test
    @DisplayName("isPortAvailable：非法端口返回 false（不抛异常）")
    void invalidPortIsNotAvailable() {
        // 端口 0 会被 ServerSocket 接受（内核分配临时端口），但按契约它不是「可用端口」；
        // -1/65536/70000 则会让 ServerSocket 抛 IllegalArgumentException。两者都必须挡在绑定之前。
        assertTrue(!PortAllocator.isPortAvailable(0), "端口 0 不应报告为可用");
        assertTrue(!PortAllocator.isPortAvailable(-1), "负数端口不应报告为可用");
        assertTrue(!PortAllocator.isPortAvailable(65536), "65536 超出范围，不应报告为可用");
        assertTrue(!PortAllocator.isPortAvailable(70000), "超范围端口不应报告为可用");
    }

    @Test
    @DisplayName("isPortAvailable：合法边界值 65535 可被探测，且不抛异常")
    void boundaryPortIsProbed() {
        try {
            // 65535 是合法上界。修复前这里会抛 IllegalArgumentException；
            // 返回值本身取决于该端口此刻是否空闲，因此不断言具体取值。
            PortAllocator.isPortAvailable(65535);
        } catch (RuntimeException e) {
            fail("合法端口 65535 的探测不应抛出异常，实际抛出：" + e);
        }
    }

    @Test
    @DisplayName("pickAvailablePort：首选值非法时不给回非法端口")
    void pickRejectsInvalidPreferred() {
        int picked = PortAllocator.pickAvailablePort(0, 13448);

        assertNotEquals(0, picked, "首选值为 0 时不得原样返回 0");
        assertTrue(picked > 0 && picked <= 65535,
                "退化结果必须落在合法范围，实际=" + picked);
    }

    @Test
    @DisplayName("pickAvailablePort：首选端口空闲时原样返回")
    void pickReturnsPreferredWhenFree() throws IOException {
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int freePort = probe.getLocalPort();
            probe.close();

            int picked = PortAllocator.pickAvailablePort(freePort, 13448);
            assertEquals(freePort, picked,
                    "首选端口空闲时应原样返回，实际=" + picked);
        }
    }

    @Test
    @DisplayName("pickAvailablePort：首选端口被占用时改为分配其他端口")
    void pickFallsBackWhenPreferredBusy() throws IOException {
        int fallback = 13448;
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int occupiedPort = occupied.getLocalPort();

            int picked = PortAllocator.pickAvailablePort(occupiedPort, fallback);
            assertNotEquals(occupiedPort, picked,
                    "首选端口被占用时不应返回它：" + occupiedPort);
            assertTrue(picked > 0 && picked <= 65535,
                    "退化的端口必须落在合法范围，实际=" + picked);
        }
    }
}
