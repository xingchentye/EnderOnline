/*
 * 本文件属于 EnderOnline Forge 适配层。
 *
 * 职责：房主端状态同步器，在 Minecraft 服务器与后端之间双向同步房间配置。
 *
 * 本类只处理「读服务器 → 上报」与「收后端 → 写服务器」两件事，不做网络传输与界面。
 */
package com.multiplayer.ender.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.multiplayer.ender.network.EnderApiClient;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;

/**
 * 房主端状态同步器。
 *
 * 负责在房主侧建立 Minecraft 服务器与后端之间的双向同步：每 20 tick（约 1 秒）
 * 从服务器读取一次权威配置并上报（PvP、作弊权限、出生点保护、游戏规则），
 * 同时把后端下发的房间管理状态写回服务器（名单、访客权限、规则、时间）。
 *
 * 设计约束：
 * 1. 只在 EnderApiClient 状态为 HOSTING 时工作。一旦离开 HOSTING 就把 initialized 复位，
 *    使下次进入托管时重新完整上报一次服务器状态。
 * 2. syncFromMinecraft 仅在 initialized 由 false 翻转为 true 的那一个周期执行一次；
 *    此后每个周期只做「拉取后端状态并应用」。因此运行期直接改动服务器规则不会被自动回传，
 *    除非重新进入托管状态。
 * 3. 规则写入、玩家操作与访问控制执行已分别上移到 ServerRuleWriter、ServerPlayerActions
 *    与 ServerAccessControl，与 EnderDashboard 的界面生效路径共用一份实现；
 *    本类只负责「从状态 JSON 取值」与「键是否存在」这两件判定工作。
 *    那三处的反射回退链成因是 Fabric 时期需要同时支持 Yarn 与 Mojmap 两套映射，
 *    同一语义在两套映射下方法名不同：作弊权限（Yarn areCheatsAllowed /
 *    Mojmap isAllowCheatsForAllPlayers）、出生点保护（getSpawnProtectionRadius
 *    在不同版本分别挂在服务器与玩家列表上）、游戏规则字段名与时间设置。
 *    本类中 PvP 已直接调用 Mojmap 的 isPvpAllowed。Fabric 终止后两端已统一使用官方映射，
 *    这些回退链已无存在必要，属 ADR-03 的待消除目标。
 * 4. 所有反射失败都被静默吞掉，以保证 tick 不中断；代价是映射不匹配时表现为
 *    「设置点了不生效」而不是报错。
 *
 * TODO(ADR-03, 2026-09-30): 删除 ServerRuleWriter 与 ServerPlayerActions 中的反射回退链，
 * 改为直接调用 Mojmap 方法并显式上报失败。
 *
 * 线程安全性：onServerTick 由 ServerTickHandler 在服务器主线程调用；applyState 会改写
 * 游戏规则、踢出玩家、切换游戏模式，因此同样只允许在服务器线程调用，本类不做内部加锁。
 * tickCounter 与 initialized 是静态可变状态，同样只在服务器线程访问。
 *
 * @see ServerTickHandler
 */
public class RoomHostLogic {
    /** tick 计数器，非负递增，每 20 取模判定一次执行；跨世界保留，不随托管状态复位。 */
    private static int tickCounter = 0;

    /** 本次托管周期是否已做过初始上报；离开 HOSTING 状态时复位为 false。 */
    private static boolean initialized = false;

    /**
     * 服务器 tick 回调，每 20 tick 真正执行一次。
     *
     * 非 HOSTING 状态下复位 initialized 并直接返回；进入 HOSTING 后的第一个周期先做一次
     * 完整上报，之后每个周期拉取后端状态并应用到服务器。
     *
     * 幂等性：本方法可被高频调用，未命中 20 tick 周期时立即返回，无副作用。
     *
     * @param server 当前 Minecraft 服务器实例，不能为 null（由 ServerTickHandler 保证）
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
     * 把服务器当前的权威配置一次性上报给后端。
     *
     * 上报字段：allow_pvp、allow_cheats、spawn_protection、keep_inventory、weather_lock、
     * mob_spawning、fire_spread、time_lock。取值失败的字段退回兜底值
     * （作弊权限 false、出生点保护 0、布尔游戏规则 true），不会中断本次上报。
     *
     * time_lock 的取值只有两种：周期为 "cycle"，锁定为 "fixed"（不区分白天或夜晚）。
     *
     * @param server 当前 Minecraft 服务器实例，不能为 null
     */
    private static void syncFromMinecraft(MinecraftServer server) {
        JsonObject update = new JsonObject();
        
        
        update.addProperty("allow_pvp", server.isPvpAllowed());
        
        
        // NOTE: 兼容分支来自 Fabric 时期的 Yarn / Mojmap 双映射（Yarn areCheatsAllowed
        // 对应 Mojmap isAllowCheatsForAllPlayers）。两端映射统一后回退链已无必要。
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
     * 反射读取布尔型游戏规则的当前值，按候选字段名依次尝试。
     *
     * 用于兼容多套映射下的规则字段命名（例如 RULE_DOMOBSPAWNING 与 RULE_DO_MOB_SPAWNING）。
     *
     * @param server 当前 Minecraft 服务器实例，不能为 null
     * @param fieldNames 候选的 GameRules 静态字段名，按优先级排列；为空数组时直接返回兜底值
     * @return 规则当前值；所有候选都取不到时返回 true（与多数规则的默认值一致）
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
     * 应用后端下发的房间管理状态。
     *
     * 依次执行访问控制（名单与访客权限）与游戏规则写回；两个子步骤各自容忍缺字段，
     * state 中不存在的键表示「本次不下发该项」，对应设置保持当前值不变。
     *
     * 副作用：可能踢出在线玩家、切换其游戏模式、改写世界游戏规则与世界时间。
     * 因此只允许在服务器主线程调用。
     *
     * @param server 当前 Minecraft 服务器实例，不能为 null
     * @param state 后端下发的房间管理状态 JSON，不能为 null
     */
    public static void applyState(MinecraftServer server, JsonObject state) {
        enforceAccessControl(server, state);
        enforceGameRules(server, state);
    }

    /**
     * 按名单与访客权限清理在线玩家。
     *
     * 房主本人始终跳过；黑名单命中即踢出；白名单非空且未命中则踢出；
     * 访客权限为「禁止进入」时踢出全部非房主玩家；「仅观战」与「仅聊天」分别把
     * 玩家切换为旁观者与冒险模式。其余权限值不改变玩家状态。
     *
     * @param server 当前 Minecraft 服务器实例，不能为 null
     * @param state 房间管理状态 JSON，不能为 null；缺少 blacklist/whitelist 时按空名单处理
     */
    private static void enforceAccessControl(MinecraftServer server, JsonObject state) {
        JsonArray blacklist = state.has("blacklist") ? state.getAsJsonArray("blacklist") : new JsonArray();
        JsonArray whitelist = state.has("whitelist") ? state.getAsJsonArray("whitelist") : new JsonArray();
        String visitorPermission = state.has("visitor_permission") ? state.get("visitor_permission").getAsString() : "可交互";

        // 白名单是否生效沿用历史推导：名单非空即视为启用（状态 JSON 未携带独立开关）。
        boolean whitelistEnabled = whitelist.size() > 0;
        // 判定与执行都在 ServerAccessControl，与 EnderDashboard 的界面生效路径共用一份实现。
        ServerAccessControl.apply(server, ServerAccessControl.resolveHostName(server),
                blacklist, whitelist, whitelistEnabled, visitorPermission);
    }

    /**
     * 把后端下发的游戏规则写回服务器。
     *
     * 逐项判定 state 中是否存在对应键，存在才写入。time_lock 非 "cycle" 时还会把世界时间
     * 对齐到白天（1000 刻）或夜晚（13000 刻）；偏差不超过 1000 刻时不动，避免每周期反复设值。
     *
     * @param server 当前 Minecraft 服务器实例，不能为 null
     * @param state 房间管理状态 JSON，不能为 null
     */
    private static void enforceGameRules(MinecraftServer server, JsonObject state) {
        // 规则写入统一交给 ServerRuleWriter，与 EnderDashboard 的界面生效路径共用一份实现，
        // 本方法只负责「键是否存在」的判定：缺失的键必须跳过而不是补默认值，
        // 否则后端局部下发时会把未提及的配置一并覆盖掉。
        if (state.has("keep_inventory")) {
            ServerRuleWriter.setBooleanGameRule(server, state.get("keep_inventory").getAsBoolean(),
                    GameRules.RULE_KEEPINVENTORY);
        }

        if (state.has("mob_spawning")) {
            ServerRuleWriter.setBooleanGameRule(server, state.get("mob_spawning").getAsBoolean(),
                    "RULE_DOMOBSPAWNING", "RULE_DO_MOB_SPAWNING");
        }

        if (state.has("fire_spread")) {
            ServerRuleWriter.setBooleanGameRule(server, state.get("fire_spread").getAsBoolean(),
                    "RULE_DOFIRETICK", "RULE_DO_FIRE_TICK");
        }

        if (state.has("weather_lock")) {
            ServerRuleWriter.setBooleanGameRule(server, !state.get("weather_lock").getAsBoolean(),
                    GameRules.RULE_WEATHER_CYCLE);
        }

        if (state.has("allow_pvp")) {
            server.setPvpAllowed(state.get("allow_pvp").getAsBoolean());
        }

        if (state.has("allow_cheats")) {
            ServerRuleWriter.setCheatsAllowed(server, state.get("allow_cheats").getAsBoolean());
        }

        if (state.has("spawn_protection")) {
            ServerRuleWriter.setSpawnProtection(server, state.get("spawn_protection").getAsInt());
        }

        if (state.has("time_lock")) {
            String mode = state.get("time_lock").getAsString();
            boolean cycle = "cycle".equals(mode);
            ServerRuleWriter.setBooleanGameRule(server, cycle,
                    "RULE_DAYLIGHT", "RULE_DAYLIGHT_CYCLE", "RULE_DO_DAYLIGHT_CYCLE");
            if (!cycle) {
                ServerLevel level = server.overworld();
                if (level != null) {
                    // 偏差不超过 1000 刻时不动，避免每个轮询周期都设置一次时间。
                    long time = "night".equals(mode) ? 13000L : 1000L;
                    if (Math.abs(level.getDayTime() % 24000 - time) > 1000) {
                        ServerRuleWriter.applyTimeMode(server, mode);
                    }
                }
            }
        }
    }
}
