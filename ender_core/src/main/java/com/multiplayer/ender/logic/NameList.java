/*
 * 本文件属于 EnderOnline 逻辑层。
 *
 * 职责：玩家名单数组的增删查——忽略大小写、按首个匹配项操作。
 *
 * 这些方法原先内联在 EnderDashboard 中。它们只操作传入的 JsonArray，不读任何界面状态，
 * 因此抽到纯工具类后可以单独测试，界面类也不再为此承担名单语义。
 */
package com.multiplayer.ender.logic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

/**
 * 玩家名单工具。
 *
 * 名单在状态 JSON 中一律表示为字符串数组（白名单、黑名单、禁言列表）。本类集中处理
 * 这些数组的「是否包含」「添加」「移除」，并统一三项约定：
 *
 * 设计约束：
 * 1. 比较一律**忽略大小写**：玩家名在 Minecraft 中大小写不敏感，拼写差异不应产生重复条目。
 * 2. 允许调用方传入 null 数组或 null 名字，此时一律视为无操作 / 不包含，由本类吸收，
 *    避免每个调用点各写一次判空。
 * 3. 只处理字符串元素：非字符串元素（对象、数组、null）视为不匹配，不会被移除。
 * 4. 移除只删**首个**匹配项；名单本不应有重复，重复时由调用方自行保证。
 *
 * 线程安全性：无状态，全部方法只操作入参，可被任意线程并发调用。
 */
public final class NameList {

    /** 私有构造函数，防止实例化。 */
    private NameList() {
    }

    /**
     * 判断名单中是否存在指定玩家名（忽略大小写）。
     *
     * @param array 名单数组，允许为 null
     * @param name 玩家名，允许为 null
     * @return 存在同名条目时返回 true；数组或名字为 null 时返回 false
     */
    public static boolean contains(JsonArray array, String name) {
        if (array == null || name == null) {
            return false;
        }
        for (JsonElement element : array) {
            if (matches(element, name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 向名单追加玩家名，已存在同名条目时不重复添加。
     *
     * @param array 目标数组，允许为 null（为 null 时不做任何事）
     * @param name 玩家名，允许为 null（为 null 时不做任何事）
     * @return 实际新增了条目时返回 true
     */
    public static boolean add(JsonArray array, String name) {
        if (array == null || name == null) {
            return false;
        }
        if (contains(array, name)) {
            return false;
        }
        array.add(name);
        return true;
    }

    /**
     * 从名单移除首个匹配的玩家名。
     *
     * @param array 目标数组，允许为 null（为 null 时不做任何事）
     * @param name 玩家名，允许为 null（为 null 时不做任何事）
     * @return 实际移除了条目时返回 true
     */
    public static boolean remove(JsonArray array, String name) {
        if (array == null || name == null) {
            return false;
        }
        for (int i = 0; i < array.size(); i++) {
            if (matches(array.get(i), name)) {
                array.remove(i);
                return true;
            }
        }
        return false;
    }

    /**
     * 把名单渲染为逗号分隔的显示文本。
     *
     * 供界面标题或提示直接使用；空名单返回空串，非字符串元素被跳过。
     *
     * @param array 名单数组，允许为 null
     * @return 以 ", " 连接的玩家名，永不为 null
     */
    public static String join(JsonArray array) {
        if (array == null || array.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonElement element : array) {
            if (!isString(element)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(element.getAsString());
        }
        return builder.toString();
    }

    /**
     * 判断元素是否为与给定名字匹配的字符串。
     *
     * @param element 待判定元素，允许为 null
     * @param name 玩家名，不能为 null
     * @return 元素是字符串且与名字忽略大小写相等时返回 true
     */
    private static boolean matches(JsonElement element, String name) {
        if (!isString(element)) {
            return false;
        }
        return name.equalsIgnoreCase(element.getAsString());
    }

    /**
     * 判断元素是否为可比较的字符串。
     *
     * 只接受字符串字面量：名单里的对象、数组与 JSON null 一律不参与比较。
     * 不做「数字转字符串」的兼容——那会让 12 与 "12" 被视为同名，掩盖数据问题。
     *
     * @param element 待判定元素，允许为 null
     * @return 元素为字符串字面量时返回 true
     */
    private static boolean isString(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return false;
        }
        return element.getAsJsonPrimitive().isString();
    }
}
