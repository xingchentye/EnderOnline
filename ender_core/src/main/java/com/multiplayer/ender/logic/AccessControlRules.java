/*
 * 本文件属于 EnderOnline 逻辑层。
 *
 * 职责：房间访问控制规则——判定一名玩家应当被放行、断开还是调整游戏模式。
 *
 * 这段规则此前在 RoomHostLogic 与 EnderDashboard 各有一份实现，且已经漂移：
 * 一份用「白名单非空」触发检查，另一份用「白名单开关」触发。抽出为纯函数后只有一处判定，
 * 并可在不启动服务器的情况下测试。
 */
package com.multiplayer.ender.logic;

import com.google.gson.JsonArray;

/**
 * 房间访问控制规则。
 *
 * 判定顺序固定为「房主豁免 → 黑名单 → 白名单 → 访客权限」，与界面提示的语义一致。
 *
 * 设计约束：
 * 1. 纯函数：只读取传入的名单与取值，不访问服务器、网络或界面状态。
 * 2. 白名单只在**开关打开**时生效。原先的一份实现用「白名单数组非空」代替开关，
 *    会导致「有名单但已关闭白名单」时仍然拦人；本类统一以开关为准。
 * 3. 访客权限取值是界面写入的中文字面量（可交互 / 仅观战 / 仅聊天 / 禁止进入），
 *    与状态 JSON 中的取值一致；未识别的取值按放行处理。
 * 4. 名单比较忽略大小写，且只认字符串条目，具体见 {@link NameList}。
 * 5. {@link #evaluate} 一次算出「决策 + 原因」，调用方不再各自把判定写一遍——
 *    这正是原先两份实现漂移的成因。
 *
 * 线程安全性：无状态，全部方法为静态且只读入参，可被任意线程并发调用。
 */
public final class AccessControlRules {

    /** 访客权限：可交互（默认，不做任何限制）。 */
    public static final String PERMISSION_INTERACTIVE = "可交互";

    /** 访客权限：仅观战，转为旁观者模式。 */
    public static final String PERMISSION_SPECTATOR = "仅观战";

    /** 访客权限：仅聊天，转为冒险模式。 */
    public static final String PERMISSION_CHAT_ONLY = "仅聊天";

    /** 访客权限：禁止进入，直接断开。 */
    public static final String PERMISSION_DENY = "禁止进入";

    private AccessControlRules() {
    }

    /**
     * 对一名玩家的处置类别。
     */
    public enum Decision {
        /** 放行，不做任何改动。 */
        ALLOW,
        /** 断开该玩家，原因见 {@link Outcome#reason()}。 */
        DISCONNECT,
        /** 保持连接，但把游戏模式改为旁观者。 */
        SPECTATOR,
        /** 保持连接，但把游戏模式改为冒险。 */
        ADVENTURE
    }

    /**
     * 判定结果：决策 + 断开原因。
     *
     * @param decision 处置类别，永不为 null
     * @param reason 断开原因文案；决策不是 DISCONNECT 时为空串，永不为 null
     */
    public record Outcome(Decision decision, String reason) {
    }

    /**
     * 判定一名玩家应受到的处置。
     *
     * @param blacklist 黑名单数组，允许为 null（按空名单处理）
     * @param whitelist 白名单数组，允许为 null（按空名单处理）
     * @param whitelistEnabled 白名单开关是否打开
     * @param visitorPermission 访客权限取值，允许为 null（按可交互处理）
     * @param hostName 房主显示名，允许为 null（为 null 时不存在豁免）
     * @param playerName 待判定玩家名，允许为 null
     * @return 判定结果，永不为 null
     */
    public static Outcome evaluate(JsonArray blacklist, JsonArray whitelist, boolean whitelistEnabled,
                                   String visitorPermission, String hostName, String playerName) {
        if (isHost(hostName, playerName)) {
            return new Outcome(Decision.ALLOW, "");
        }
        if (NameList.contains(blacklist, playerName)) {
            return new Outcome(Decision.DISCONNECT, "你已被房主加入黑名单");
        }
        if (whitelistEnabled && !NameList.contains(whitelist, playerName)) {
            return new Outcome(Decision.DISCONNECT, "你不在白名单中");
        }
        if (PERMISSION_DENY.equals(visitorPermission)) {
            return new Outcome(Decision.DISCONNECT, "房间禁止访客进入");
        }
        if (PERMISSION_SPECTATOR.equals(visitorPermission)) {
            return new Outcome(Decision.SPECTATOR, "");
        }
        if (PERMISSION_CHAT_ONLY.equals(visitorPermission)) {
            return new Outcome(Decision.ADVENTURE, "");
        }
        return new Outcome(Decision.ALLOW, "");
    }

    /**
     * 判断给定玩家是否是房主。
     *
     * @param hostName 房主显示名，允许为 null
     * @param playerName 玩家名，允许为 null
     * @return 两者非 null 且忽略大小写相等时返回 true
     */
    private static boolean isHost(String hostName, String playerName) {
        if (hostName == null || playerName == null) {
            return false;
        }
        return hostName.equalsIgnoreCase(playerName);
    }
}
