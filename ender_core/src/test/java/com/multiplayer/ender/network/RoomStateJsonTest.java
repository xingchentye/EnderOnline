/*
 * 本文件属于 EnderOnline 单元测试。
 *
 * 职责：固化对外状态 JSON 的字段组合，作为从 EnderApiClient.getState 抽出该逻辑后的回归基线。
 *
 * 关键约束：这份 JSON 是界面与后端共同消费的契约；字段从有到无属于破坏性变更，
 * 因此「什么状态下带哪些字段」在此逐条固定。
 */
package com.multiplayer.ender.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RoomStateJson} 的行为固化测试。
 *
 * 这组测试守护什么：五种状态的字段组合、名册非空时才附带 profiles 与 players 的约定、
 * 以及房间码与错误文本只在对应状态下出现。
 *
 * 注意：本文件的断言消息可保留中文——测试源码已从 i18n 守卫中排除，中文失败信息更易读。
 *
 * 线程安全性：被测方法是纯静态的，用例之间无共享状态，可并行执行。
 *
 * @see RoomStateJson
 */
class RoomStateJsonTest {

    /**
     * 构造含一名玩家的名册数组。
     *
     * @return 名册数组，永不为 null
     */
    private static JsonArray rosterWith(String name) {
        JsonArray array = new JsonArray();
        JsonObject profile = new JsonObject();
        profile.addProperty("name", name);
        profile.addProperty("machine_id", "m-" + name);
        profile.addProperty("vendor", "pojav");
        profile.addProperty("kind", "GUEST");
        array.add(profile);
        return array;
    }

    @Test
    @DisplayName("每种状态都带 status，且取值为状态名")
    void alwaysIncludesStatus() {
        String[] states = {"HOSTING", "JOINING", "HOSTING_STARTING", "JOINING_STARTING", "ERROR", "IDLE"};
        for (String state : states) {
            JsonObject json = RoomStateJson.render(state, "room", "err", null);
            assertEquals(state, json.get("status").getAsString(),
                    "status 应为状态名：" + state);
        }
    }

    @Test
    @DisplayName("主持中：state=host-ok，带房间码，名册非空时附带 profiles 与 players")
    void hostingIncludesRoster() {
        JsonObject json = RoomStateJson.render("HOSTING", "U/AAAA", null, rosterWith("Alice"));

        assertEquals("host-ok", json.get("state").getAsString(), "state 应为 host-ok");
        assertEquals("U/AAAA", json.get("room").getAsString(), "应带房间码");
        assertTrue(json.has("profiles"), "名册非空时应附带 profiles");
        assertTrue(json.has("players"), "名册非空时应附带 players");
        assertEquals("Alice", json.getAsJsonArray("players").get(0).getAsString(),
                "players 应只含名称");
        assertFalse(json.has("error"), "主持中不应带 error");
    }

    @Test
    @DisplayName("加入中：state=guest-ok，带房间码，名册非空时附带两份名册字段")
    void joiningIncludesRoster() {
        JsonObject json = RoomStateJson.render("JOINING", "U/BBBB", null, rosterWith("Bob"));

        assertEquals("guest-ok", json.get("state").getAsString(), "state 应为 guest-ok");
        assertEquals("U/BBBB", json.get("room").getAsString(), "应带房间码");
        assertTrue(json.has("profiles"), "名册非空时应附带 profiles");
        assertTrue(json.has("players"), "名册非空时应附带 players");
    }

    @Test
    @DisplayName("名册为空或为 null 时不输出 profiles 与 players，而不是输出空数组")
    void emptyRosterOmitsFields() {
        JsonObject emptyArray = RoomStateJson.render("HOSTING", "U/AAAA", null, new JsonArray());
        assertFalse(emptyArray.has("profiles"), "空名册不应输出 profiles");
        assertFalse(emptyArray.has("players"), "空名册不应输出 players");

        JsonObject nullRoster = RoomStateJson.render("JOINING", "U/BBBB", null, null);
        assertFalse(nullRoster.has("profiles"), "null 名册不应输出 profiles");
        assertFalse(nullRoster.has("players"), "null 名册不应输出 players");
    }

    @Test
    @DisplayName("启动中：只给 state，不带房间码与名册")
    void startingStatesAreMinimal() {
        JsonObject hosting = RoomStateJson.render("HOSTING_STARTING", "U/AAAA", null, rosterWith("Alice"));
        assertEquals("host-starting", hosting.get("state").getAsString(), "state 应为 host-starting");
        assertFalse(hosting.has("room"), "启动中不应带房间码");
        assertFalse(hosting.has("profiles"), "启动中不应带名册");

        JsonObject joining = RoomStateJson.render("JOINING_STARTING", "U/BBBB", null, null);
        assertEquals("guest-starting", joining.get("state").getAsString(), "state 应为 guest-starting");
        assertFalse(joining.has("room"), "启动中不应带房间码");
    }

    @Test
    @DisplayName("出错：只给 error，不带 state 与房间码")
    void errorStateCarriesError() {
        JsonObject json = RoomStateJson.render("ERROR", "U/AAAA", "房间不存在", rosterWith("Alice"));

        assertEquals("房间不存在", json.get("error").getAsString(), "应带错误文本");
        assertFalse(json.has("state"), "出错时不应带 state");
        assertFalse(json.has("room"), "出错时不应带房间码");
        assertFalse(json.has("profiles"), "出错时不应带名册");
    }

    @Test
    @DisplayName("未识别状态：只输出 status")
    void unknownStateOnlyHasStatus() {
        JsonObject json = RoomStateJson.render("IDLE", "U/AAAA", "err", rosterWith("Alice"));

        assertEquals("IDLE", json.get("status").getAsString(), "status 仍应输出");
        assertEquals(1, json.size(), "未识别状态不应附加任何其它字段");
    }

    @Test
    @DisplayName("房间码与错误文本为 null 时按 JSON null 输出，不抛异常")
    void nullValuesAreTolerated() {
        JsonObject hosting = RoomStateJson.render("HOSTING", null, null, null);
        assertTrue(hosting.has("room"), "应输出 room 字段");
        assertTrue(hosting.get("room").isJsonNull(), "null 房间码应输出 JSON null");

        JsonObject error = RoomStateJson.render("ERROR", null, null, null);
        assertTrue(error.get("error").isJsonNull(), "null 错误文本应输出 JSON null");
    }

    @Test
    @DisplayName("状态名为 null 时抛异常")
    void nullStateNameIsRejected() {
        assertThrows(NullPointerException.class,
                () -> RoomStateJson.render(null, "U/AAAA", null, null),
                "状态名为 null 应被拒绝");
    }

    @Test
    @DisplayName("players 只含名称，顺序与名册一致")
    void playersFollowRosterOrder() {
        JsonArray roster = new JsonArray();
        for (String name : new String[]{"Alice", "Bob", "Carol"}) {
            roster.add(rosterWith(name).get(0).getAsJsonObject());
        }

        JsonArray players = RoomStateJson.render("HOSTING", "U/AAAA", null, roster)
                .getAsJsonArray("players");

        assertEquals(3, players.size(), "应输出三名玩家");
        assertEquals("Alice", players.get(0).getAsString(), "顺序应与名册一致");
        assertEquals("Carol", players.get(2).getAsString(), "顺序应与名册一致");
    }
}
