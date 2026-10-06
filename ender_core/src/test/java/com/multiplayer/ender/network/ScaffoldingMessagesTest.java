/*
 * 本文件属于 EnderOnline 单元测试。
 *
 * 职责：固化 Scaffolding 对等消息的字节格式，作为从 EnderApiClient 抽出该编解码后的回归基线。
 *
 * 关键约束：这些字节是两端之间的线格式，改动即为协议不兼容变更；端口必须保持大端 2 字节，
 * 否则加入方会解析出错误的 Minecraft 端口。
 */
package com.multiplayer.ender.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScaffoldingMessages} 的行为固化测试。
 *
 * 这组测试守护什么：心跳 JSON 的往返、资料数组的往返、端口的**大端**编码与边界值，
 * 以及畸形载荷返回「缺失」而不是抛异常。
 *
 * 注意：本文件的断言消息可保留中文——测试源码已从 i18n 守卫中排除，中文失败信息更易读。
 *
 * 线程安全性：被测方法是纯静态的，用例之间无共享状态，可并行执行。
 *
 * @see ScaffoldingMessages
 */
class ScaffoldingMessagesTest {

    @Test
    @DisplayName("心跳往返：三个字段原样还原")
    void pingRoundTrip() {
        byte[] payload = ScaffoldingMessages.encodePlayerPing("machine-1", "Alice", "pojav");

        ScaffoldingMessages.PlayerPing ping = ScaffoldingMessages.decodePlayerPing(payload);

        assertNotNull(ping, "合法载荷应能解析");
        assertEquals("machine-1", ping.machineId(), "machine_id 应原样还原");
        assertEquals("Alice", ping.name(), "name 应原样还原");
        assertEquals("pojav", ping.vendor(), "vendor 应原样还原");
        assertFalse(ping.isIncomplete(), "字段齐全时不应判为不完整");
    }

    @Test
    @DisplayName("心跳：缺失字段解析为空串而非 null")
    void pingMissingFieldsBecomeEmpty() {
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);

        ScaffoldingMessages.PlayerPing ping = ScaffoldingMessages.decodePlayerPing(payload);

        assertNotNull(ping, "空对象应能解析");
        assertEquals("", ping.machineId(), "缺失的 machine_id 应为空串");
        assertEquals("", ping.name(), "缺失的 name 应为空串");
        assertEquals("", ping.vendor(), "缺失的 vendor 应为空串");
        assertTrue(ping.isIncomplete(), "缺 machine_id 与 name 时应判为不完整");
    }

    @Test
    @DisplayName("心跳：只有 machine_id 或只有 name 都算不完整")
    void pingIncompleteWhenEitherKeyMissing() {
        assertTrue(ScaffoldingMessages.decodePlayerPing(
                "{\"machine_id\":\"m\",\"name\":\"\"}".getBytes(StandardCharsets.UTF_8)).isIncomplete(),
                "name 为空应判为不完整");
        assertTrue(ScaffoldingMessages.decodePlayerPing(
                "{\"machine_id\":\"\",\"name\":\"n\"}".getBytes(StandardCharsets.UTF_8)).isIncomplete(),
                "machine_id 为空应判为不完整");
        assertFalse(ScaffoldingMessages.decodePlayerPing(
                "{\"machine_id\":\"m\",\"name\":\"n\"}".getBytes(StandardCharsets.UTF_8)).isIncomplete(),
                "两者齐全时不应判为不完整");
    }

    @Test
    @DisplayName("心跳：非法 JSON、非对象与 null 载荷都返回 null")
    void pingRejectsMalformedPayload() {
        assertNull(ScaffoldingMessages.decodePlayerPing(null), "null 载荷应返回 null");
        assertNull(ScaffoldingMessages.decodePlayerPing("not json".getBytes(StandardCharsets.UTF_8)),
                "非法 JSON 应返回 null");
        assertNull(ScaffoldingMessages.decodePlayerPing("[1,2]".getBytes(StandardCharsets.UTF_8)),
                "数组不是对象，应返回 null");
    }

    @Test
    @DisplayName("心跳：中文字段值按 UTF-8 正确往返")
    void pingHandlesUtf8() {
        byte[] payload = ScaffoldingMessages.encodePlayerPing("id", "末影玩家", "ender");

        assertEquals("末影玩家", ScaffoldingMessages.decodePlayerPing(payload).name(),
                "中文玩家名应按 UTF-8 往返");
    }

    @Test
    @DisplayName("资料数组往返：字段与顺序保持")
    void profilesRoundTrip() {
        JsonArray profiles = new JsonArray();
        profiles.add(profileJson("g1", "Alice"));
        profiles.add(profileJson("g2", "Bob"));

        JsonArray decoded = ScaffoldingMessages.decodeProfiles(ScaffoldingMessages.encodeProfiles(profiles));

        assertNotNull(decoded, "合法载荷应能解析");
        assertEquals(2, decoded.size(), "条目数应保持不变");
        assertEquals("Alice", decoded.get(0).getAsJsonObject().get("name").getAsString(), "顺序应保持");
        assertEquals("g2", decoded.get(1).getAsJsonObject().get("machine_id").getAsString(),
                "第二个条目的 machine_id 应保持");
    }

    @Test
    @DisplayName("资料数组：null 编码为空数组，空数组可往返")
    void profilesHandlesNullAndEmpty() {
        assertEquals("[]", new String(ScaffoldingMessages.encodeProfiles(null), StandardCharsets.UTF_8),
                "null 应编码为空数组");

        JsonArray decoded = ScaffoldingMessages.decodeProfiles(ScaffoldingMessages.encodeProfiles(new JsonArray()));
        assertNotNull(decoded, "空数组应能解析");
        assertEquals(0, decoded.size(), "空数组应保持为空");
    }

    @Test
    @DisplayName("资料数组：非法 JSON、非数组与 null 载荷都返回 null")
    void profilesRejectMalformedPayload() {
        assertNull(ScaffoldingMessages.decodeProfiles(null), "null 载荷应返回 null");
        assertNull(ScaffoldingMessages.decodeProfiles("nope".getBytes(StandardCharsets.UTF_8)),
                "非法 JSON 应返回 null");
        assertNull(ScaffoldingMessages.decodeProfiles("{\"a\":1}".getBytes(StandardCharsets.UTF_8)),
                "对象不是数组，应返回 null");
    }

    @Test
    @DisplayName("端口往返：常见取值原样还原")
    void portRoundTrip() {
        int[] ports = {1, 25565, 32768, 65535};
        for (int port : ports) {
            assertEquals(port, ScaffoldingMessages.decodePort(ScaffoldingMessages.encodePort(port)),
                    "端口应原样往返：" + port);
        }
    }

    @Test
    @DisplayName("端口编码：使用大端 2 字节（高位在前）")
    void portIsBigEndian() {
        assertArrayEquals(new byte[]{0x63, (byte) 0xDD}, ScaffoldingMessages.encodePort(25565),
                "25565 应编码为 0x63 0xDD（大端）");
        assertArrayEquals(new byte[]{0x00, 0x01}, ScaffoldingMessages.encodePort(1),
                "端口 1 应编码为 0x00 0x01（大端）");
        assertArrayEquals(new byte[]{(byte) 0xFF, (byte) 0xFF}, ScaffoldingMessages.encodePort(65535),
                "65535 应编码为 0xFF 0xFF");
    }

    @Test
    @DisplayName("端口解码：按无符号处理，0xFFFF 得到 65535")
    void portDecodeIsUnsigned() {
        assertEquals(65535, ScaffoldingMessages.decodePort(new byte[]{(byte) 0xFF, (byte) 0xFF}),
                "无符号短整型上界应为 65535");
        assertEquals(32768, ScaffoldingMessages.decodePort(new byte[]{(byte) 0x80, 0x00}),
                "0x8000 应解析为 32768 而不是负数");
    }

    @Test
    @DisplayName("端口解码：null 与长度不足返回缺失（-1）而不抛异常")
    void portDecodeRejectsShortPayload() {
        assertEquals(-1, ScaffoldingMessages.decodePort(null), "null 应返回 -1");
        assertEquals(-1, ScaffoldingMessages.decodePort(new byte[0]), "空载荷应返回 -1");
        assertEquals(-1, ScaffoldingMessages.decodePort(new byte[]{0x01}), "只有 1 字节应返回 -1");
        assertEquals(258, ScaffoldingMessages.decodePort(new byte[]{0x01, 0x02, 0x03}),
                "多出的字节应被忽略，读出的是前 2 字节 0x0102 = 258");
    }

    /**
     * 构造一条资料 JSON。
     *
     * @param machineId 本机标识
     * @param name 显示名
     * @return 资料对象，永不为 null
     */
    private static JsonObject profileJson(String machineId, String name) {
        JsonObject obj = new JsonObject();
        obj.addProperty("machine_id", machineId);
        obj.addProperty("name", name);
        obj.addProperty("vendor", "pojav");
        obj.addProperty("kind", "GUEST");
        return obj;
    }
}
