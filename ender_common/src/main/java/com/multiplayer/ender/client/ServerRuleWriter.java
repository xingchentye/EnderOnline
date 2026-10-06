/*
 * 本文件属于 EnderOnline 共享层（ender_common）。
 *
 * 职责：把房间配置写入 Minecraft 服务端的游戏规则与玩家列表设置。
 *
 * 本类由房主轮询路径与界面生效路径共用，不含界面、不做网络传输。
 */
package com.multiplayer.ender.client;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;

/**
 * 服务端规则写入器：把房间配置写入 Minecraft 服务端的游戏规则与玩家列表设置。
 *
 * 本类由 {@link RoomHostLogic}（轮询后端状态）与 {@code EnderDashboard}（界面立即生效）
 * 共用，合并了此前两边各写一份的同名实现。两条路径的参数语义一致：{@code mode} 为
 * {@code "cycle"} 时保持昼夜循环，为 {@code "night"} 时固定到夜晚，其余值固定到白天。
 * 差异只在于来源和调用时机——界面路径每轮全量覆盖，轮询路径只在对应键出现时写入。
 *
 * 反射回退链（ADR-03 待消除的目标）：作弊权限、出生点保护、游戏规则字段名与时间设置
 * 在本类中均按候选名依次尝试，失败静默忽略。代价是映射不匹配时表现为「设置点了不生效」，
 * 而不是报错；收益是调用方的 tick 不会被中断。
 *
 * 线程约束：本类只做同步写入，不加锁。调用方必须运行在服务端主线程
 * （{@link RoomHostLogic} 由 {@code ServerTickHandler} 保证），否则会造成规则写入竞态。
 *
 * 本类不含任何加载器类型，可被 Forge 与 NeoForge 共享编译（ADR-13）。
 *
 * @see RoomHostLogic
 */
public final class ServerRuleWriter {


    /**
     * 昼夜循环规则的候选字段名；doDaylightCycle 的三种历史拼写都要试。
     */
    private static final String[] DAYLIGHT_FIELDS =
            { "RULE_DAYLIGHT", "RULE_DAYLIGHT_CYCLE", "RULE_DO_DAYLIGHT_CYCLE" };

    /** 固定到白天时写入的时间刻度（清晨）。 */
    private static final long DAY_TIME_TICK = 1000L;

    /** 固定到夜晚时写入的时间刻度。 */
    private static final long NIGHT_TIME_TICK = 13000L;

    /** 工具类不应被实例化。 */
    private ServerRuleWriter() {
    }

    /**
     * 按时间模式写入昼夜循环，并在固定模式下对齐世界时间。
     *
     * {@code mode} 为 {@code "cycle"} 时打开昼夜循环；否则关闭循环，并把主世界时间设到
     * {@link #NIGHT_TIME_TICK}（{@code "night"}）或 {@link #DAY_TIME_TICK}（其余值）。
     *
     * 调用方若希望避免每个轮询周期都写一次时间，应在自身侧先比较时间偏差再调用
     * （{@link RoomHostLogic} 即如此，界面路径则每轮全量覆盖）。
     *
     * @param server 目标服务端，允许为 null（为 null 时直接返回）
     * @param mode 时间模式，{@code "cycle"} 表示保持循环，不能为 null
     */
    public static void applyTimeMode(MinecraftServer server, String mode) {
        if (server == null) {
            return;
        }
        boolean cycle = "cycle".equals(mode);
        setBooleanGameRule(server, cycle, DAYLIGHT_FIELDS);
        if (!cycle) {
            setWorldTime(server.overworld(), "night".equals(mode) ? NIGHT_TIME_TICK : DAY_TIME_TICK);
        }
    }

    /**
     * 设置主世界时间。
     *
     * 依次尝试 {@code setDayTime} 与 Yarn 遗留的 {@code setTimeOfDay}，都失败时静默忽略。
     * 仅供 {@link #applyTimeMode} 使用——公开入口是模式而非刻度，避免调用方自行换算。
     *
     * @param level 目标世界，允许为 null（为 null 时直接返回）
     * @param time 目标时间，单位为 tick；0..23999 为一个完整昼夜周期
     */
    private static void setWorldTime(ServerLevel level, long time) {
        if (level == null) {
            return;
        }
        try {
            java.lang.reflect.Method m = level.getClass().getMethod("setDayTime", long.class);
            m.invoke(level, time);
            return;
        } catch (Exception ignored) {}
        try {
            java.lang.reflect.Method m = level.getClass().getMethod("setTimeOfDay", long.class);
            m.invoke(level, time);
        } catch (Exception ignored) {}
    }

    /**
     * 写回「允许所有玩家使用命令」开关。
     *
     * 依次尝试 {@code setAllowCheatsForAllPlayers} 与 Yarn 遗留的
     * {@code setAllowCommandsForAllPlayers}；两者都失败时静默忽略，开关维持原状。
     *
     * @param server 目标服务端，不能为 null
     * @param value 目标开关值
     */
    public static void setCheatsAllowed(MinecraftServer server, boolean value) {
        if (invokeOnPlayerList(server, "setAllowCheatsForAllPlayers", boolean.class, value)) {
            return;
        }
        invokeOnPlayerList(server, "setAllowCommandsForAllPlayers", boolean.class, value);
    }

    /**
     * 写回出生点保护半径。
     *
     * 半径先做 {@code Math.max(0, value)} 归一化；随后依次尝试
     * {@code setSpawnProtectionRadius} 与 Yarn 遗留的 {@code setSpawnProtection}，
     * 都失败时静默忽略。
     *
     * @param server 目标服务端，不能为 null
     * @param value 目标半径，单位为方块；负值会被归一化为 0
     */
    public static void setSpawnProtection(MinecraftServer server, int value) {
        int radius = Math.max(0, value);
        if (invokeOnPlayerList(server, "setSpawnProtectionRadius", int.class, radius)) {
            return;
        }
        invokeOnPlayerList(server, "setSpawnProtection", int.class, radius);
    }


    /**
     * 按已知的游戏规则键写入布尔值。
     *
     * 已知键无需走候选字段名的反射回退链，直接写入即可；未知键请用
     * {@link #setBooleanGameRule(MinecraftServer, boolean, String...)}。
     *
     * @param server 目标服务端，不能为 null
     * @param value 目标规则值
     * @param key 游戏规则键，不能为 null
     */
    public static void setBooleanGameRule(MinecraftServer server, boolean value,
            GameRules.Key<GameRules.BooleanValue> key) {
        server.getGameRules().getRule(key).set(value, server);
    }

    /**
     * 按候选字段名反射定位布尔游戏规则并写入。
     *
     * 命中第一个可用字段后立即返回；全部候选失败时静默忽略，规则维持原值。
     * 这是本文件里最重的一处回退链：先按字段名取静态 Key，再尝试
     * {@code getRule(Class)}，失败则遍历规则对象上首参为 boolean 的双参 {@code set} 方法。
     *
     * @param server 目标服务端，允许为 null（为 null 时直接返回）
     * @param value 目标规则值
     * @param fieldNames 候选的 GameRules 静态字段名，按优先级排列；允许为 null
     */
    public static void setBooleanGameRule(MinecraftServer server, boolean value, String... fieldNames) {
        if (server == null || fieldNames == null) {
            return;
        }
        GameRules rules = server.getGameRules();
        for (String fieldName : fieldNames) {
            if (fieldName == null || fieldName.isBlank()) {
                continue;
            }
            try {
                java.lang.reflect.Field f = GameRules.class.getField(fieldName);
                Object key = f.get(null);
                if (!(key instanceof GameRules.Key<?> typedKey)) {
                    continue;
                }
                Object rule = invokeGetRule(rules, typedKey);
                if (rule == null) {
                    continue;
                }
                if (invokeBooleanSetter(rule, value, server)) {
                    return;
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * 在玩家列表上反射调用单参方法，整型重载。
     *
     * 方法名或签名在目标映射下不存在时静默返回 false，由调用方决定是否继续回退。
     *
     * @param server 目标服务端，不能为 null
     * @param methodName 候选方法名
     * @param type 参数类型
     * @param value 参数值
     * @return 调用成功返回 true；取玩家列表或调用失败返回 false
     */
    private static boolean invokeOnPlayerList(MinecraftServer server, String methodName, Class<?> type, int value) {
        return invokeOnPlayerList(server, methodName, type, (Object) value);
    }

    /**
     * 在玩家列表上反射调用单参方法。
     *
     * @param server 目标服务端，不能为 null
     * @param methodName 候选方法名
     * @param type 参数类型
     * @param value 参数值，包装类型以兼容整型与布尔两种调用
     * @return 调用成功返回 true；取玩家列表或调用失败返回 false
     */
    private static boolean invokeOnPlayerList(MinecraftServer server, String methodName, Class<?> type, Object value) {
        try {
            Object playerList = server.getPlayerList();
            java.lang.reflect.Method m = playerList.getClass().getMethod(methodName, type);
            m.invoke(playerList, value);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 用一次单参 {@code getRule} 调用取规则对象。
     *
     * 优先按传入键的实际类型取，失败后遍历规则对象上所有单参 {@code getRule} 方法——
     * 后者用于键类型在编译期不可见（候选字段名反射得来）的场合。
     *
     * @param rules 规则容器，不能为 null
     * @param key 规则键，不能为 null
     * @return 取到的规则对象；全部失败返回 null
     */
    private static Object invokeGetRule(GameRules rules, GameRules.Key<?> key) {
        try {
            java.lang.reflect.Method m = rules.getClass().getMethod("getRule", key.getClass());
            return m.invoke(rules, key);
        } catch (Exception ignored) {}
        for (java.lang.reflect.Method m : rules.getClass().getMethods()) {
            if (!"getRule".equals(m.getName()) || m.getParameterCount() != 1) {
                continue;
            }
            try {
                return m.invoke(rules, key);
            } catch (Exception ignored) {
                continue;
            }
        }
        return null;
    }

    /**
     * 用规则对象上首参为 boolean 的双参 {@code set} 方法写入值。
     *
     * @param rule 规则对象，不能为 null
     * @param value 目标值
     * @param server 作为第二参数传入的服务端
     * @return 成功写入返回 true；没有匹配方法或调用失败返回 false
     */
    private static boolean invokeBooleanSetter(Object rule, boolean value, MinecraftServer server) {
        for (java.lang.reflect.Method m : rule.getClass().getMethods()) {
            if (!"set".equals(m.getName()) || m.getParameterCount() != 2) {
                continue;
            }
            Class<?>[] params = m.getParameterTypes();
            if (params[0] != boolean.class) {
                continue;
            }
            try {
                m.invoke(rule, value, server);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }

}
