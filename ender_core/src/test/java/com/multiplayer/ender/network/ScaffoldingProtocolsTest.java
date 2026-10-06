/*
 * 本文件属于 EnderOnline 单元测试。
 *
 * 职责：固化 Scaffolding 协议名清单与其公布载荷格式，作为把该清单收敛到单一来源后的回归基线。
 *
 * 关键约束：这份清单同时决定「注册了哪些处理器」与「对端能探测到哪些能力」，
 * 因此清单内容与 NUL 分隔的载荷格式都属于对外契约。
 */
package com.multiplayer.ender.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScaffoldingProtocols} 的行为固化测试。
 *
 * 这组测试守护什么：六个协议名的取值、清单与常量一致、公布载荷按 NUL 分隔且数量吻合，
 * 以及 isSupported 的边界。
 *
 * 注意：本文件的断言消息可保留中文——测试源码已从 i18n 守卫中排除，中文失败信息更易读。
 *
 * 线程安全性：被测类只含常量，用例之间无共享状态，可并行执行。
 *
 * @see ScaffoldingProtocols
 */
class ScaffoldingProtocolsTest {

    @Test
    @DisplayName("协议名取值固定")
    void protocolNamesArePinned() {
        assertEquals("c:ping", ScaffoldingProtocols.PING, "连通性探测名应固定");
        assertEquals("c:protocols", ScaffoldingProtocols.PROTOCOLS, "能力探测名应固定");
        assertEquals("c:server_port", ScaffoldingProtocols.SERVER_PORT, "端口查询名应固定");
        assertEquals("c:player_ping", ScaffoldingProtocols.PLAYER_PING, "玩家上报名应固定");
        assertEquals("c:player_profiles_list", ScaffoldingProtocols.PLAYER_PROFILES_LIST,
                "资料列表名应固定");
        assertEquals("c:room_state_sync", ScaffoldingProtocols.ROOM_STATE_SYNC,
                "状态同步名应固定");
    }

    @Test
    @DisplayName("清单包含全部六个协议名且顺序稳定")
    void supportedListHasAllSix() {
        List<String> supported = ScaffoldingProtocols.SUPPORTED;

        assertEquals(6, supported.size(), "应恰好公布六个协议");
        assertEquals(ScaffoldingProtocols.PING, supported.get(0), "顺序应与定义一致");
        assertEquals(ScaffoldingProtocols.PROTOCOLS, supported.get(1), "顺序应与定义一致");
        assertEquals(ScaffoldingProtocols.SERVER_PORT, supported.get(2), "顺序应与定义一致");
        assertEquals(ScaffoldingProtocols.PLAYER_PING, supported.get(3), "顺序应与定义一致");
        assertEquals(ScaffoldingProtocols.PLAYER_PROFILES_LIST, supported.get(4), "顺序应与定义一致");
        assertEquals(ScaffoldingProtocols.ROOM_STATE_SYNC, supported.get(5), "顺序应与定义一致");
    }

    @Test
    @DisplayName("清单无重复项")
    void supportedListHasNoDuplicates() {
        Set<String> seen = new HashSet<>(ScaffoldingProtocols.SUPPORTED);

        assertEquals(ScaffoldingProtocols.SUPPORTED.size(), seen.size(),
                "清单不应出现重复协议名");
    }

    @Test
    @DisplayName("公布载荷以 NUL 分隔，切分后与清单逐项一致")
    void payloadUsesNulSeparator() {
        byte[] payload = ScaffoldingProtocols.supportedPayloadUtf8();

        assertNotNull(payload, "载荷不应为 null");
        String text = new String(payload, StandardCharsets.UTF_8);
        String[] parts = text.split("\0", -1);

        assertEquals(ScaffoldingProtocols.SUPPORTED.size(), parts.length,
                "切分后的段数应与清单长度一致");
        for (int i = 0; i < parts.length; i++) {
            assertEquals(ScaffoldingProtocols.SUPPORTED.get(i), parts[i],
                    "第 " + i + " 段应与清单一致");
        }
    }

    @Test
    @DisplayName("公布载荷确实包含 NUL 分隔符（而不是别的字符）")
    void payloadContainsNulByte() {
        byte[] payload = ScaffoldingProtocols.supportedPayloadUtf8();

        boolean hasNul = false;
        for (byte b : payload) {
            if (b == 0) {
                hasNul = true;
                break;
            }
        }
        assertTrue(hasNul, "载荷应以 NUL 字节分隔协议名");
    }

    @Test
    @DisplayName("公布载荷中不含空格（协议名以 NUL 连接，而非空格或逗号）")
    void payloadHasNoSpaces() {
        String text = new String(ScaffoldingProtocols.supportedPayloadUtf8(), StandardCharsets.UTF_8);

        assertFalse(text.contains(" "), "不应出现空格分隔");
        assertFalse(text.contains(","), "不应出现逗号分隔");
    }

    @Test
    @DisplayName("isSupported：清单内返回 true，清单外与 null 返回 false")
    void isSupportedChecksMembership() {
        for (String name : ScaffoldingProtocols.SUPPORTED) {
            assertTrue(ScaffoldingProtocols.isSupported(name), "清单内应返回 true：" + name);
        }
        assertFalse(ScaffoldingProtocols.isSupported("c:unknown"), "清单外应返回 false");
        assertFalse(ScaffoldingProtocols.isSupported(null), "null 应返回 false");
        assertFalse(ScaffoldingProtocols.isSupported(""), "空串应返回 false");
    }

    @Test
    @DisplayName("清单不可变：尝试修改会抛异常")
    void supportedListIsImmutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> ScaffoldingProtocols.SUPPORTED.add("c:x"),
                "清单应为不可变列表");
    }
}
