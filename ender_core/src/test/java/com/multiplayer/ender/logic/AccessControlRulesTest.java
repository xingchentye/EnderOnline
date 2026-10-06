/*
 * 本文件属于 EnderOnline 单元测试。
 *
 * 职责：固化房间访问控制规则，作为合并 RoomHostLogic 与 EnderDashboard 两份重复实现后的唯一基线。
 *
 * 关键约束：本类固定的是「两份实现合并后统一采用的那套语义」——白名单以开关为准而不是以名单是否为空为准。
 * 这条差异是真实的行为变更，已在合并提交中说明；改动它必须同时更新本文件。
 */
package com.multiplayer.ender.logic;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AccessControlRules} 的行为固化测试。
 *
 * 这组测试守护什么：判定优先级（房主豁免 → 黑名单 → 白名单 → 访客权限）、白名单开关的语义、
 * 四种访客权限的处置，以及忽略大小写的名单比较。
 *
 * 注意：本文件的断言消息可保留中文——测试源码已从 i18n 守卫中排除，中文失败信息更易读。
 *
 * 线程安全性：被测方法是纯静态的，用例之间无共享状态，可并行执行。
 *
 * @see AccessControlRules
 */
class AccessControlRulesTest {

    /**
     * 构造名单数组。
     *
     * @param names 条目
     * @return 名单数组，永不为 null
     */
    private static JsonArray listOf(String... names) {
        JsonArray array = new JsonArray();
        for (String name : names) {
            array.add(name);
        }
        return array;
    }

    @Test
    @DisplayName("房主豁免：房主本人始终放行，即使命中黑名单")
    void hostIsAlwaysAllowed() {
        AccessControlRules.Outcome outcome = AccessControlRules.evaluate(
                listOf("HostPlayer"), new JsonArray(), false,
                AccessControlRules.PERMISSION_DENY, "HostPlayer", "HostPlayer");

        assertEquals(AccessControlRules.Decision.ALLOW, outcome.decision(),
                "房主不应被自己设置的黑名单或访客权限拦下");
        assertEquals("", outcome.reason(), "放行时不应带断开原因");
    }

    @Test
    @DisplayName("房主判定忽略大小写")
    void hostComparisonIgnoresCase() {
        AccessControlRules.Outcome outcome = AccessControlRules.evaluate(
                new JsonArray(), new JsonArray(), false,
                AccessControlRules.PERMISSION_DENY, "HostPlayer", "hostplayer");

        assertEquals(AccessControlRules.Decision.ALLOW, outcome.decision(), "大小写不同仍应豁免");
    }

    @Test
    @DisplayName("黑名单优先于白名单：命中黑名单即以黑名单原因断开")
    void blacklistTakesPrecedence() {
        AccessControlRules.Outcome outcome = AccessControlRules.evaluate(
                listOf("Alice"), listOf("Alice"), true,
                AccessControlRules.PERMISSION_INTERACTIVE, "Host", "Alice");

        assertEquals(AccessControlRules.Decision.DISCONNECT, outcome.decision(), "应被断开");
        assertEquals("你已被房主加入黑名单", outcome.reason(), "原因应为黑名单");
    }

    @Test
    @DisplayName("白名单开关打开且不在名单内：断开")
    void whitelistEnabledBlocksOutsider() {
        AccessControlRules.Outcome outcome = AccessControlRules.evaluate(
                new JsonArray(), listOf("Alice"), true,
                AccessControlRules.PERMISSION_INTERACTIVE, "Host", "Bob");

        assertEquals(AccessControlRules.Decision.DISCONNECT, outcome.decision(), "白名单外的玩家应被断开");
        assertEquals("你不在白名单中", outcome.reason(), "原因应为不在白名单");
    }

    @Test
    @DisplayName("白名单开关关闭：名单内外的玩家都放行（以开关为准，不以名单是否为空为准）")
    void whitelistDisabledAllowsEveryone() {
        AccessControlRules.Outcome outsider = AccessControlRules.evaluate(
                new JsonArray(), listOf("Alice"), false,
                AccessControlRules.PERMISSION_INTERACTIVE, "Host", "Bob");
        assertEquals(AccessControlRules.Decision.ALLOW, outsider.decision(),
                "开关关闭时不应因名单非空而拦人");

        AccessControlRules.Outcome insider = AccessControlRules.evaluate(
                new JsonArray(), listOf("Alice"), false,
                AccessControlRules.PERMISSION_INTERACTIVE, "Host", "Alice");
        assertEquals(AccessControlRules.Decision.ALLOW, insider.decision(), "名单内玩家也应放行");
    }

    @Test
    @DisplayName("访客权限：禁止进入 → 断开")
    void denyVisitorsDisconnects() {
        AccessControlRules.Outcome outcome = AccessControlRules.evaluate(
                new JsonArray(), new JsonArray(), false,
                AccessControlRules.PERMISSION_DENY, "Host", "Alice");

        assertEquals(AccessControlRules.Decision.DISCONNECT, outcome.decision(), "应被断开");
        assertEquals("房间禁止访客进入", outcome.reason(), "原因应为禁止访客进入");
    }

    @Test
    @DisplayName("访客权限：仅观战 → 旁观者，仅聊天 → 冒险")
    void visitorPermissionMapsToGameType() {
        AccessControlRules.Outcome spectator = AccessControlRules.evaluate(
                new JsonArray(), new JsonArray(), false,
                AccessControlRules.PERMISSION_SPECTATOR, "Host", "Alice");
        assertEquals(AccessControlRules.Decision.SPECTATOR, spectator.decision(), "仅观战应对应旁观者");
        assertEquals("", spectator.reason(), "非断开时不应带原因");

        AccessControlRules.Outcome adventure = AccessControlRules.evaluate(
                new JsonArray(), new JsonArray(), false,
                AccessControlRules.PERMISSION_CHAT_ONLY, "Host", "Alice");
        assertEquals(AccessControlRules.Decision.ADVENTURE, adventure.decision(), "仅聊天应对应冒险");
    }

    @Test
    @DisplayName("访客权限：可交互或未识别取值 → 放行")
    void interactiveAndUnknownPermissionsAllow() {
        AccessControlRules.Outcome interactive = AccessControlRules.evaluate(
                new JsonArray(), new JsonArray(), false,
                AccessControlRules.PERMISSION_INTERACTIVE, "Host", "Alice");
        assertEquals(AccessControlRules.Decision.ALLOW, interactive.decision(), "可交互应放行");

        AccessControlRules.Outcome unknown = AccessControlRules.evaluate(
                new JsonArray(), new JsonArray(), false, "某个未知取值", "Host", "Alice");
        assertEquals(AccessControlRules.Decision.ALLOW, unknown.decision(), "未识别取值应按放行处理");
    }

    @Test
    @DisplayName("名单比较忽略大小写")
    void nameComparisonIgnoresCase() {
        AccessControlRules.Outcome outcome = AccessControlRules.evaluate(
                listOf("Alice"), new JsonArray(), false,
                AccessControlRules.PERMISSION_INTERACTIVE, "Host", "ALICE");

        assertEquals(AccessControlRules.Decision.DISCONNECT, outcome.decision(),
                "大小写不同的黑名单条目也应命中");
    }

    @Test
    @DisplayName("null 与非法输入：名单为 null、玩家名为 null、房主名为 null 都不抛异常")
    void nullInputsAreTolerated() {
        AccessControlRules.Outcome noLists = AccessControlRules.evaluate(
                null, null, false, AccessControlRules.PERMISSION_INTERACTIVE, "Host", "Alice");
        assertEquals(AccessControlRules.Decision.ALLOW, noLists.decision(), "名单为 null 应按空名单处理");

        AccessControlRules.Outcome nullPlayer = AccessControlRules.evaluate(
                listOf("Alice"), new JsonArray(), false,
                AccessControlRules.PERMISSION_INTERACTIVE, "Host", null);
        assertEquals(AccessControlRules.Decision.ALLOW, nullPlayer.decision(), "玩家名为 null 应放行");

        AccessControlRules.Outcome nullHost = AccessControlRules.evaluate(
                new JsonArray(), new JsonArray(), false,
                AccessControlRules.PERMISSION_INTERACTIVE, null, "Alice");
        assertEquals(AccessControlRules.Decision.ALLOW, nullHost.decision(),
                "房主名为 null 时不存在豁免，但也不应拦住普通玩家");
    }

    @Test
    @DisplayName("名单中的非字符串条目不参与比较")
    void nonStringEntriesAreIgnored() {
        JsonArray list = new JsonArray();
        list.add(JsonNull.INSTANCE);
        list.add(new JsonObject());
        list.add(123);

        AccessControlRules.Outcome outcome = AccessControlRules.evaluate(
                list, new JsonArray(), false,
                AccessControlRules.PERMISSION_INTERACTIVE, "Host", "123");

        assertEquals(AccessControlRules.Decision.ALLOW, outcome.decision(),
                "数字与对象条目不应被当作玩家名匹配");
    }

    @Test
    @DisplayName("Outcome：断开的四种来源都带非空原因，非断开一律空原因")
    void outcomeReasonContract() {
        assertTrue(AccessControlRules.evaluate(listOf("A"), new JsonArray(), false,
                AccessControlRules.PERMISSION_INTERACTIVE, "H", "A").reason().length() > 0,
                "黑名单断开应带原因");
        assertTrue(AccessControlRules.evaluate(new JsonArray(), new JsonArray(), true,
                AccessControlRules.PERMISSION_INTERACTIVE, "H", "A").reason().length() > 0,
                "白名单断开应带原因");
        assertTrue(AccessControlRules.evaluate(new JsonArray(), new JsonArray(), false,
                AccessControlRules.PERMISSION_DENY, "H", "A").reason().length() > 0,
                "禁止进入断开应带原因");

        assertEquals("", AccessControlRules.evaluate(new JsonArray(), new JsonArray(), false,
                AccessControlRules.PERMISSION_SPECTATOR, "H", "A").reason(),
                "旁观者处置不带断开原因");
        assertNotNull(AccessControlRules.evaluate(new JsonArray(), new JsonArray(), false,
                AccessControlRules.PERMISSION_INTERACTIVE, "H", "A").decision(),
                "决策不应为 null");
    }
}
