/*
 * 本文件属于 EnderOnline 单元测试。
 *
 * 职责：固化玩家名单数组的增删查语义，作为从 EnderDashboard 拆出该逻辑后的回归基线。
 *
 * 关键约束：名单比较忽略大小写是玩家可见行为（Minecraft 玩家名大小写不敏感），
 * 改动它会让「同名不同大小写」产生重复条目，因此在此显式固定。
 */
package com.multiplayer.ender.logic;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NameList} 的行为固化测试。
 *
 * 这组测试守护什么：忽略大小写的匹配、只处理字符串字面量、添加去重、移除只删首个匹配项，
 * 以及 null 入参一律不抛异常。
 *
 * 注意：本文件的断言消息可保留中文——测试源码已从 i18n 守卫中排除，中文失败信息更易读。
 *
 * 线程安全性：每个用例各自构造数组，无共享状态，可并行执行。
 *
 * @see NameList
 */
class NameListTest {

    /**
     * 构造一个含指定条目的名单数组。
     *
     * @param names 条目，逐个按字符串加入
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
    @DisplayName("contains：命中返回 true，未命中返回 false")
    void containsFindsExactName() {
        JsonArray list = listOf("Alice", "Bob");

        assertTrue(NameList.contains(list, "Alice"), "应命中已有条目");
        assertTrue(NameList.contains(list, "Bob"), "应命中已有条目");
        assertFalse(NameList.contains(list, "Carol"), "不应命中不存在的条目");
    }

    @Test
    @DisplayName("contains：忽略大小写")
    void containsIsCaseInsensitive() {
        JsonArray list = listOf("Alice");

        assertTrue(NameList.contains(list, "alice"), "小写应命中");
        assertTrue(NameList.contains(list, "ALICE"), "大写应命中");
        assertTrue(NameList.contains(list, "aLiCe"), "混合大小写应命中");
    }

    @Test
    @DisplayName("contains：null 数组、null 名字或空名单都返回 false")
    void containsHandlesNullAndEmpty() {
        assertFalse(NameList.contains(null, "Alice"), "null 数组应返回 false");
        assertFalse(NameList.contains(listOf("Alice"), null), "null 名字应返回 false");
        assertFalse(NameList.contains(new JsonArray(), "Alice"), "空名单应返回 false");
    }

    @Test
    @DisplayName("contains：非字符串元素不参与匹配")
    void containsIgnoresNonStringElements() {
        JsonArray list = new JsonArray();
        list.add(123);
        list.add(true);
        list.add(JsonNull.INSTANCE);
        list.add(new JsonObject());

        assertFalse(NameList.contains(list, "123"), "数字不应被当作字符串匹配");
        assertFalse(NameList.contains(list, "true"), "布尔不应被当作字符串匹配");
        assertFalse(NameList.contains(list, "null"), "JSON null 不应被当作字符串匹配");
    }

    @Test
    @DisplayName("add：追加不存在的名字返回 true，重复添加返回 false")
    void addAppendsOnce() {
        JsonArray list = new JsonArray();

        assertTrue(NameList.add(list, "Alice"), "首次添加应成功");
        assertEquals(1, list.size(), "添加后应有 1 个条目");
        assertFalse(NameList.add(list, "Alice"), "重复添加应被拒绝");
        assertEquals(1, list.size(), "重复添加不应改变数组大小");
    }

    @Test
    @DisplayName("add：大小写不同也视为重复")
    void addTreatsDifferentCaseAsDuplicate() {
        JsonArray list = listOf("Alice");

        assertFalse(NameList.add(list, "alice"), "大小写不同应视为重复");
        assertEquals(1, list.size(), "不应产生第二个条目");
    }

    @Test
    @DisplayName("add：null 数组或 null 名字是空操作")
    void addHandlesNull() {
        assertFalse(NameList.add(null, "Alice"), "null 数组应返回 false");
        JsonArray list = new JsonArray();
        assertFalse(NameList.add(list, null), "null 名字应返回 false");
        assertEquals(0, list.size(), "空操作不应改变数组");
    }

    @Test
    @DisplayName("remove：移除首个匹配项，返回 true")
    void removeDeletesFirstMatch() {
        JsonArray list = listOf("Alice", "Bob", "Carol");

        assertTrue(NameList.remove(list, "Bob"), "移除已有条目应成功");
        assertEquals(2, list.size(), "移除后应剩 2 个条目");
        assertEquals("Alice", list.get(0).getAsString(), "顺序应保持不变");
        assertEquals("Carol", list.get(1).getAsString(), "顺序应保持不变");
    }

    @Test
    @DisplayName("remove：忽略大小写")
    void removeIsCaseInsensitive() {
        JsonArray list = listOf("Alice");

        assertTrue(NameList.remove(list, "ALICE"), "大小写不同也应能移除");
        assertEquals(0, list.size(), "移除后应为空");
    }

    @Test
    @DisplayName("remove：不存在时返回 false 且不改变数组")
    void removeReportsMissing() {
        JsonArray list = listOf("Alice");

        assertFalse(NameList.remove(list, "Bob"), "移除不存在的条目应返回 false");
        assertEquals(1, list.size(), "数组不应变化");
    }

    @Test
    @DisplayName("remove：只删除首个重复项")
    void removeDeletesOnlyFirstDuplicate() {
        JsonArray list = listOf("Alice", "Alice");

        assertTrue(NameList.remove(list, "Alice"), "应移除首个匹配项");
        assertEquals(1, list.size(), "重复条目只删一个");
    }

    @Test
    @DisplayName("remove：null 数组或 null 名字是空操作")
    void removeHandlesNull() {
        assertFalse(NameList.remove(null, "Alice"), "null 数组应返回 false");
        assertFalse(NameList.remove(listOf("Alice"), null), "null 名字应返回 false");
    }

    @Test
    @DisplayName("join：以逗号空格连接，空名单返回空串")
    void joinRendersNames() {
        assertEquals("Alice, Bob", NameList.join(listOf("Alice", "Bob")), "应按顺序连接");
        assertEquals("", NameList.join(new JsonArray()), "空名单应返回空串");
        assertEquals("", NameList.join(null), "null 数组应返回空串");
        assertEquals("Alice", NameList.join(listOf("Alice")), "单条目不应带分隔符");
    }

    @Test
    @DisplayName("join：跳过非字符串元素")
    void joinSkipsNonStrings() {
        JsonArray list = new JsonArray();
        list.add("Alice");
        list.add(42);
        list.add("Bob");

        assertEquals("Alice, Bob", NameList.join(list), "非字符串元素应被跳过");
    }

    @Test
    @DisplayName("三法协同：添加后可查到、移除后查不到")
    void addThenRemoveRoundTrip() {
        JsonArray list = new JsonArray();

        assertTrue(NameList.add(list, "Alice"));
        assertTrue(NameList.contains(list, "ALICE"), "添加后应能查到（忽略大小写）");
        assertTrue(NameList.remove(list, "alice"));
        assertFalse(NameList.contains(list, "Alice"), "移除后应查不到");
        assertEquals(0, list.size(), "往返后应回到空名单");
    }
}
