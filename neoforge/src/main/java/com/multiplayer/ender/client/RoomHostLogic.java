/*
 * 本文件属于 EnderOnline NeoForge 适配层。
 *
 * 职责：房主端的托管逻辑核心，把 Minecraft 世界状态同步到后端并执行后端下发的控制指令。
 *
 * 关键约束：只在客户端逻辑服务器（集成服务器）上运行，由 ServerTickHandler 每 tick 调用；
 * 本类不做线程切换，「当前是否托管」的判断依赖 EnderApiClient 的状态机。
 */
package com.multiplayer.ender.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.multiplayer.ender.network.EnderApiClient;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;

import java.util.List;

/**
 * 房间托管逻辑核心。
 *
 * 双向同步的一端全在这里：上行把 PVP、作弊、刷怪、火势蔓延、昼夜等世界规则推给后端，
 * 下行把后端的白名单/黑名单/访客权限与规则开关应用到当前世界。
 *
 * 设计约束：
 * 1. 节流：{@link #onServerTick} 只每 20 tick（约 1 秒）执行一次同步，不要在每 tick 都跑。
 * 2. 状态归属：只有 {@link EnderApiClient.State#HOSTING} 时才工作；离开该状态会把 initialized 复位，
 *    使得下次进入托管时重新做一次全量同步。
 * 3. 幂等性：{@link #applyState} 每轮都会重放全部规则与权限，重复执行不会累积副作用；
 *    因此它可以直接按当前状态覆盖，而无需 diff。
 * 4. 反射回退链的成因：本类的 getMethod/getField 调用曾是跨 Yarn（Fabric）与 Mojmap（Forge/NeoForge）
 *    两套映射的兼容层——同一语义在两套映射下方法名/字段名不同，只能靠反射按名字探测。
 *    Fabric 支持已终止（ADR-00 / ADR-14），两端统一使用 Mojang 官方映射，
 *    这些回退分支已无存在理由，应按 ADR-03 全部删除、改为直接调用 Mojmap 方法。
 *    TODO(P4, 2026-07-31): 删除本类的反射回退链，见 claude_docs/00-decisions-and-open-questions.md 的 ADR-03
 *
 * 线程安全性：全部方法与字段都只在服务器主线程（事件回调链）上执行，无同步措施，也不允许跨线程调用。
 *
 * @since 1.0
 * @see ServerTickHandler
 * @see EnderApiClient
 */
public class RoomHostLogic {
    /** tick 计数器，累计到 20 的倍数时才执行一轮同步（约 1 秒一次）。 */
    private static int tickCounter = 0;

    /** 是否已完成本轮托管的首帧全量同步；进入托管时置 false，同步完成后置 true。 */
    private static boolean initialized = false;

    /**
     * 服务器 Tick 回调。
     *
     * 每 20 tick 执行一轮：先在首次进入托管时做一次全量上行同步，再拉取后端状态并应用下行规则。
     *
     * 幂等性：重复调用是安全的，规则应用本身是覆盖式的。
     *
     * @param server 当前逻辑服务器实例，不能为 null
     */
    public static void onServerTick(MinecraftServer server) {
        if (tickCounter++ % 20 != 0) return; 

        if (EnderApiClient.getCurrentState() != EnderApiClient.State.HOSTING) {
            initialized = false;
            return;
        }

        if (!initialized) {
            syncFromMinecraft(server);
            initialized = true;
        }

        JsonObject state = EnderApiClient.getRoomManagementStateSync();
        if (state == null) return;

        applyState(server, state);
    }

    /**
     * 把当前世界的规则与设置上行同步到后端。
     *
     * 推送的字段包括：allow_pvp、allow_cheats、spawn_protection、keep_inventory、weather_lock、
     * mob_spawning、fire_spread、time_lock（cycle/fixed）。
     *
     * 失败容忍：读取本机设置时使用反射回退链（见类注释约束 4），探测失败按默认值处理而不是中断同步。
     *
     * @param server 当前逻辑服务器实例，不能为 null
     */
    private static void syncFromMinecraft(MinecraftServer server) {
        JsonObject update = new JsonObject();
        
        
        update.addProperty("allow_pvp", server.isPvpAllowed());
        
        
        boolean allowCheats = false;
        try {
             Object playerList = server.getPlayerList();
             java.lang.reflect.Method m = playerList.getClass().getMethod("isAllowCheatsForAllPlayers");
             allowCheats = (boolean) m.invoke(playerList);
        } catch (Exception ignored) {
            try {
                 Object playerList = server.getPlayerList();
                 java.lang.reflect.Method m = playerList.getClass().getMethod("isAllowCommandsForAllPlayers");
                 allowCheats = (boolean) m.invoke(playerList);
            } catch (Exception ignored2) {}
        }
        update.addProperty("allow_cheats", allowCheats);
        
        
        int spawnProtection = 0;
        try {
             java.lang.reflect.Method m = server.getClass().getMethod("getSpawnProtectionRadius");
             spawnProtection = (int) m.invoke(server);
        } catch (Exception ignored) {
             try {
                 Object playerList = server.getPlayerList();
                 java.lang.reflect.Method m = playerList.getClass().getMethod("getSpawnProtectionRadius");
                 spawnProtection = (int) m.invoke(playerList);
             } catch (Exception ignored2) {}
        }
        update.addProperty("spawn_protection", spawnProtection);
        
        
        GameRules rules = server.getGameRules();
        update.addProperty("keep_inventory", rules.getBoolean(GameRules.RULE_KEEPINVENTORY));
        update.addProperty("weather_lock", !rules.getBoolean(GameRules.RULE_WEATHER_CYCLE));
        
        update.addProperty("mob_spawning", getBooleanGameRule(server, "RULE_DOMOBSPAWNING", "RULE_DO_MOB_SPAWNING"));
        update.addProperty("fire_spread", getBooleanGameRule(server, "RULE_DOFIRETICK", "RULE_DO_FIRE_TICK"));
        
        boolean cycle = getBooleanGameRule(server, "RULE_DAYLIGHT", "RULE_DAYLIGHT_CYCLE", "RULE_DO_DAYLIGHT_CYCLE");
        update.addProperty("time_lock", cycle ? "cycle" : "fixed");

        EnderApiClient.updateRoomManagementState(update.toString());
    }

    /**
     * 按候选字段名读取一个布尔游戏规则。
     *
     * 依次尝试每个字段名，命中第一个可解析的 {@code GameRules.Key} 即返回。字段名差异源于历史映射（见类注释约束 4）。
     *
     * @param server 当前逻辑服务器实例，不能为 null
     * @param fieldNames 候选字段名，按优先级排列，至少一个
     * @return 规则当前值；全部探测失败时返回 true（保守默认：不关闭玩法）
     */
    private static boolean getBooleanGameRule(MinecraftServer server, String... fieldNames) {
        for (String fieldName : fieldNames) {
            try {
                java.lang.reflect.Field f = GameRules.class.getField(fieldName);
                Object key = f.get(null);
                if (key instanceof GameRules.Key) {
                    return server.getGameRules().getBoolean((GameRules.Key<GameRules.BooleanValue>) key);
                }
            } catch (Exception ignored) {}
        }
        return true;
    }

    /**
     * 把后端状态应用到服务器。
     *
     * 先执行访问控制（白名单/黑名单/访客权限），再执行游戏规则同步。顺序不可颠倒：
     * 被踢出的玩家不应再被改动游戏模式。
     *
     * 幂等性：每轮都会重放，重复调用不会产生累积影响。
     *
     * @param server 当前逻辑服务器实例，不能为 null
     * @param state 后端房间管理状态，不能为 null
     */
    public static void applyState(MinecraftServer server, JsonObject state) {
        enforceAccessControl(server, state);
        enforceGameRules(server, state);
    }

    /**
     * 执行访问控制。
     *
     * 对每个非房主玩家依次判断：黑名单命中则踢出，白名单启用且未命中则踢出，
     * 「禁止进入」踢出，「仅观战」转为旁观者，「仅聊天」转为冒险模式。
     *
     * 设计约束：单机托管时房主自身始终被跳过（按玩家名忽略大小写比较），不会被自己的规则踢掉。
     *
     * @param server 当前逻辑服务器实例，不能为 null
     * @param state 后端房间管理状态，不能为 null；缺字段时按「不限制」处理
     */
    private static void enforceAccessControl(MinecraftServer server, JsonObject state) {
        String hostName = "";
        if (server.isSingleplayer()) {
            hostName = server.getSingleplayerProfile() != null ? server.getSingleplayerProfile().getName() : null;
        }

        JsonArray blacklist = state.has("blacklist") ? state.getAsJsonArray("blacklist") : new JsonArray();
        JsonArray whitelist = state.has("whitelist") ? state.getAsJsonArray("whitelist") : new JsonArray();
        String visitorPermission = state.has("visitor_permission") ? state.get("visitor_permission").getAsString() : "可交互";

        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        for (ServerPlayer player : players) {
            String name = player.getGameProfile().getName();
            if (name != null && hostName != null && name.equalsIgnoreCase(hostName)) {
                continue;
            }
            
            if (containsName(blacklist, name)) {
                disconnectPlayer(player, Component.literal("你已被房主加入黑名单"));
                continue;
            }
            if (whitelist.size() > 0 && !containsName(whitelist, name)) {
                disconnectPlayer(player, Component.literal("你不在白名单中"));
                continue;
            }
            
            if ("禁止进入".equals(visitorPermission)) {
                disconnectPlayer(player, Component.literal("房间禁止访客进入"));
                continue;
            }
            
            if ("仅观战".equals(visitorPermission)) {
                setPlayerGameType(player, GameType.SPECTATOR);
            } else if ("仅聊天".equals(visitorPermission)) {
                setPlayerGameType(player, GameType.ADVENTURE);
            }
        }
    }

    /**
     * 执行游戏规则同步。
     *
     * 只处理后端状态里出现的字段；缺字段表示「本轮不调整」。天气与时间锁的语义是反的：
     * {@code weather_lock=true} 对应关闭 {@code RULE_WEATHER_CYCLE}。
     *
     * 设计约束：时间锁为固定时会把主世界时间直接设为目标值，但仅在偏差超过 1000 tick 时才写入，
     * 避免每轮都触发一次时间变更事件。
     *
     * @param server 当前逻辑服务器实例，不能为 null
     * @param state 后端房间管理状态，不能为 null
     */
    private static void enforceGameRules(MinecraftServer server, JsonObject state) {
        if (state.has("allow_pvp")) {
            boolean pvp = state.get("allow_pvp").getAsBoolean();
            if (server.isPvpAllowed() != pvp) {
                server.setPvpAllowed(pvp);
            }
        }
        
        if (state.has("allow_cheats")) {
            setCheatsAllowed(server, state.get("allow_cheats").getAsBoolean());
        }
        
        if (state.has("spawn_protection")) {
             setSpawnProtection(server, state.get("spawn_protection").getAsInt());
        }

        if (state.has("keep_inventory")) {
            boolean val = state.get("keep_inventory").getAsBoolean();
            setBooleanGameRule(server, val, GameRules.RULE_KEEPINVENTORY);
        }
        
        if (state.has("mob_spawning")) {
            boolean val = state.get("mob_spawning").getAsBoolean();
            setBooleanGameRule(server, val, "RULE_DOMOBSPAWNING", "RULE_DO_MOB_SPAWNING");
        }
        
        if (state.has("fire_spread")) {
            boolean val = state.get("fire_spread").getAsBoolean();
            setBooleanGameRule(server, val, "RULE_DOFIRETICK", "RULE_DO_FIRE_TICK");
        }
        
        if (state.has("weather_lock")) {
             boolean val = state.get("weather_lock").getAsBoolean();
             setBooleanGameRule(server, !val, GameRules.RULE_WEATHER_CYCLE);
        }

        if (state.has("time_lock")) {
            String mode = state.get("time_lock").getAsString();
            boolean cycle = "cycle".equals(mode);
            setBooleanGameRule(server, cycle, "RULE_DAYLIGHT", "RULE_DAYLIGHT_CYCLE", "RULE_DO_DAYLIGHT_CYCLE");
            if (!cycle) {
                ServerLevel level = server.overworld();
                if (level != null) {
                    long time = "night".equals(mode) ? 13000L : 1000L;
                    if (Math.abs(level.getDayTime() % 24000 - time) > 1000) {
                        level.setDayTime(time);
                    }
                }
            }
        }
    }
    
    /**
     * 判断名单数组中是否存在指定玩家名。
     *
     * @param array 名单数组，允许为 null
     * @param name 玩家名，允许为 null；为 null 时返回 false
     * @return 存在同名（忽略大小写）条目时返回 true
     */
    private static boolean containsName(JsonArray array, String name) {
        if (array == null || name == null) return false;
        for (JsonElement el : array) {
            if (el.getAsString().equalsIgnoreCase(name)) return true;
        }
        return false;
    }
    
    /**
     * 把玩家踢出服务器。
     *
     * 失败被静默忽略：玩家可能已经断开，此时踢出是空操作。
     *
     * @param player 目标玩家，不能为 null
     * @param reason 断开原因，不能为 null
     */
    private static void disconnectPlayer(ServerPlayer player, Component reason) {
        try {
            player.connection.disconnect(reason);
        } catch (Exception ignored) {}
    }
    
    /**
     * 设置玩家游戏模式。
     *
     * 已是目标模式时直接返回，避免重复广播模式变更。设置走反射回退链（见类注释约束 4）。
     *
     * @param player 目标玩家，不能为 null
     * @param type 目标游戏模式，不能为 null
     */
    private static void setPlayerGameType(ServerPlayer player, GameType type) {
        if (player.gameMode.getGameModeForPlayer() == type) return;
        try {
            java.lang.reflect.Method m = player.getClass().getMethod("setGameMode", GameType.class);
            m.invoke(player, type);
        } catch (Exception ignored) {}
    }
    
    /**
     * 设置「允许所有玩家作弊」。
     *
     * 走反射回退链（见类注释约束 4）：先试 setAllowCheatsForAllPlayers，失败再试 setAllowCommandsForAllPlayers。
     * 两者都失败时静默放弃，不中断其余规则同步。
     *
     * @param server 当前逻辑服务器实例，不能为 null
     * @param value 是否允许
     */
    private static void setCheatsAllowed(MinecraftServer server, boolean value) {
        try {
            Object playerList = server.getPlayerList();
            java.lang.reflect.Method m = playerList.getClass().getMethod("setAllowCheatsForAllPlayers", boolean.class);
            m.invoke(playerList, value);
            return;
        } catch (Exception ignored) {}
        try {
            Object playerList = server.getPlayerList();
            java.lang.reflect.Method m = playerList.getClass().getMethod("setAllowCommandsForAllPlayers", boolean.class);
            m.invoke(playerList, value);
        } catch (Exception ignored) {}
    }
    
    /**
     * 设置出生点保护半径。
     *
     * 负数会被归零；具体写入走反射回退链（见类注释约束 4）。
     *
     * @param server 当前逻辑服务器实例，不能为 null
     * @param value 半径，单位为方块；负值按 0 处理
     */
    private static void setSpawnProtection(MinecraftServer server, int value) {
        int radius = Math.max(0, value);
        try {
            Object playerList = server.getPlayerList();
            java.lang.reflect.Method m = playerList.getClass().getMethod("setSpawnProtectionRadius", int.class);
            m.invoke(playerList, radius);
            return;
        } catch (Exception ignored) {}
        try {
            Object playerList = server.getPlayerList();
            java.lang.reflect.Method m = playerList.getClass().getMethod("setSpawnProtection", int.class);
            m.invoke(playerList, radius);
        } catch (Exception ignored) {}
    }
    
    /**
     * 用已知的规则键设置布尔游戏规则。
     *
     * @param server 当前逻辑服务器实例，不能为 null
     * @param value 目标值
     * @param key 规则键，不能为 null
     */
    private static void setBooleanGameRule(MinecraftServer server, boolean value, GameRules.Key<GameRules.BooleanValue> key) {
        server.getGameRules().getRule(key).set(value, server);
    }

    /**
     * 按候选字段名设置布尔游戏规则。
     *
     * 逐个字段名探测，命中第一个可解析的键后写入并返回；全部失败时静默放弃。
     *
     * @param server 当前逻辑服务器实例，允许为 null；为 null 时直接返回
     * @param value 目标值
     * @param fieldNames 候选字段名，按优先级排列
     */
    private static void setBooleanGameRule(MinecraftServer server, boolean value, String... fieldNames) {
        if (server == null || fieldNames == null) return;
        for (String fieldName : fieldNames) {
            if (fieldName == null || fieldName.isBlank()) continue;
            try {
                java.lang.reflect.Field f = GameRules.class.getField(fieldName);
                Object key = f.get(null);
                if (key instanceof GameRules.Key) {
                    server.getGameRules().getRule((GameRules.Key<GameRules.BooleanValue>) key).set(value, server);
                    return;
                }
            } catch (Exception ignored) {}
        }
    }
}
