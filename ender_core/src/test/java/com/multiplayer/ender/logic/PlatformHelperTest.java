/*
 * 本文件属于 EnderOnline 单元测试。
 *
 * 职责：固化平台判定与平台相关命名的现有行为。
 *
 * 端口相关用例已迁至 PortAllocatorTest —— 三处重复实现归并后，
 * 端口契约由 PortAllocator 统一守护，本文件不再重复覆盖。
 */
package com.multiplayer.ender.logic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PlatformHelper} 的行为固化测试。
 *
 * 这组测试守护什么：判定类方法不得返回 null（设计上所有枚举都含 UNKNOWN 兜底），
 * 以及可执行文件名后缀跟随操作系统。
 *
 * 注意：本文件的断言消息可保留中文——测试源码已从 i18n 守卫中排除，中文失败信息更易读。
 *
 * 线程安全性：只读系统属性，无共享可变状态，可并行执行。
 *
 * @see PlatformHelper
 */
class PlatformHelperTest {

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
