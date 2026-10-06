/*
 * 本文件属于 EnderOnline 网络层。
 *
 * 职责：把当前联机状态渲染成对外的状态 JSON——界面与后端都依赖这份结构。
 *
 * 这段渲染原先内联在 EnderApiClient.getState 中。抽出为纯函数后，字段组合可以在不启动
 * 联机流程的情况下测试，也不必为了断言而把状态机跑起来。
 */
package com.multiplayer.ender.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * 联机状态 JSON 渲染。
 *
 * 输出结构固定为 {@code status} 加上随状态变化的字段：
 * 1. 主持中：{@code state=host-ok} 与 {@code room}，名册非空时附带 {@code profiles} 与 {@code players}
 * 2. 加入中：{@code state=guest-ok} 与 {@code room}，名册非空时同样附带两份名册字段
 * 3. 启动中：只给 {@code state=host-starting} 或 {@code guest-starting}
 * 4. 出错：{@code error}
 * 5. 其余状态：只有 {@code status}
 *
 * 设计约束：
 * 1. 纯函数：不读静态状态、不访问网络，全部输入来自参数。
 * 2. 名册为空时**不输出** {@code profiles} 与 {@code players}，而不是输出空数组——
 *    这是既有约定，消费方据此判断「有没有名册」而不是「名册是不是空」。
 * 3. 状态名以字符串传入而不是枚举：状态枚举定义在 EnderApiClient 内部，
 *    本类不应为了渲染而依赖那个具体类型。
 *
 * 线程安全性：无状态，静态方法只读入参。
 */
public final class RoomStateJson {

    private RoomStateJson() {
    }

    /**
     * 渲染状态 JSON。
     *
     * @param stateName 状态枚举名，不能为 null；未识别的取值只输出 {@code status}
     * @param currentRoom 当前房间码，允许为 null
     * @param lastError 最近一次失败原因，允许为 null
     * @param profiles 玩家资料数组，允许为 null（按空名册处理）
     * @return 状态 JSON 对象，永不为 null
     * @throws NullPointerException 当 stateName 为 null 时抛出
     */
    public static JsonObject render(String stateName, String currentRoom, String lastError, JsonArray profiles) {
        if (stateName == null) {
            throw new NullPointerException("stateName");
        }
        JsonObject json = new JsonObject();
        json.addProperty("status", stateName);

        switch (stateName) {
            case "HOSTING":
                json.addProperty("state", "host-ok");
                json.addProperty("room", currentRoom);
                appendRoster(json, profiles);
                break;
            case "JOINING":
                json.addProperty("state", "guest-ok");
                json.addProperty("room", currentRoom);
                appendRoster(json, profiles);
                break;
            case "HOSTING_STARTING":
                json.addProperty("state", "host-starting");
                break;
            case "JOINING_STARTING":
                json.addProperty("state", "guest-starting");
                break;
            case "ERROR":
                json.addProperty("error", lastError);
                break;
            default:
                break;
        }
        return json;
    }

    /**
     * 在名册非空时附带资料与玩家名两份字段。
     *
     * @param json 目标对象，不能为 null，会被就地修改
     * @param profiles 玩家资料数组，允许为 null
     */
    private static void appendRoster(JsonObject json, JsonArray profiles) {
        if (profiles == null || profiles.isEmpty()) {
            return;
        }
        json.add("profiles", profiles);
        json.add("players", ProfileRegistry.toPlayersJson(profiles));
    }
}
