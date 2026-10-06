/*
 * 本文件属于 EnderOnline 网络层。
 *
 * 职责：房间管理状态的持有与持久化，独立于房间生命周期与 Scaffolding 传输。
 *
 * 这些字段与锁原先内联在 EnderApiClient 中，使该类同时承担「联机流程编排」与
 * 「配置读写」两件事。抽出后状态只有一处所有者，锁语义也不再与调用方的锁区交错。
 */
package com.multiplayer.ender.network;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 房间管理状态的持有者与持久化入口。
 *
 * 状态是一份 JSON 对象，既包含本模组自有的管理项（白名单、黑名单、静音列表、房间备注等），
 * 也包含一批 Minecraft 原生设置；落盘时后者会被剔除（见 {@link #isMcNativeSetting(String)}）。
 *
 * 设计约束：
 * 1. 状态与持久化路径都是**实例字段**，遵守 ADR-05「可变状态归实例」：静态只保留单例引用与常量。
 * 2. 所有状态读写都必须经过本类，调用方拿到的永远是深拷贝或序列化结果，不能直接持有内部对象。
 * 3. 变更采用「拷贝—修改—整体替换」而不是就地修改：替换引用是原子的，读方要么看到旧值
 *    要么看到新值，不会读到改了一半的对象。
 * 4. 落盘失败只记录警告、不抛异常。因此「方法返回」不代表「已写入磁盘」。
 * 5. 本类不关心联机状态：房间备注变化是否需要重启局域网广播由调用方决定，
 *    {@link #applyUpdate(String)} 只负责汇报备注是否变化。
 *
 * 线程安全性：实例内部持有唯一锁 lock，所有读写与持久化都在锁内完成；单例可被
 * 网络回调线程与主线程并发调用。
 *
 * @see EnderApiClient
 */
final class RoomStateStore {

    /** JSON 解析器，无特殊配置；Gson 本身线程安全。 */
    private static final Gson GSON = new Gson();

    /** 生产环境的持久化路径，相对于进程工作目录。 */
    private static final File DEFAULT_CONFIG_FILE = new File("config/ender_room_config.json");

    /**
     * 进程级单例引用。
     *
     * 引用本身是 final（静态只允许常量引用）；实例可被整体替换，因此无需可变静态字段。
     */
    private static final AtomicReference<RoomStateStore> INSTANCE =
            new AtomicReference<>(new RoomStateStore(DEFAULT_CONFIG_FILE));

    /** 日志列表最多保留的条数。 */
    private static final int MAX_LOG_ENTRIES = 20;

    /** 互斥锁，保护 state 的读写与落盘快照。 */
    private final Object lock = new Object();

    /** 本实例的持久化文件。 */
    private final File configFile;

    /**
     * 当前房间管理状态。
     *
     * 初值为「默认状态合并已落盘配置」；仅允许在持有 lock 时替换或修改。
     */
    private JsonObject state;

    /**
     * 构造存储实例。
     *
     * 构造期间会立刻载入 configFile 中已有的配置；此时实例尚未对外发布，无需加锁。
     *
     * @param configFile 持久化文件，不能为 null
     */
    RoomStateStore(File configFile) {
        this.configFile = configFile;
        this.state = createDefaultState();
        loadConfig();
    }

    /**
     * 获取状态单例。
     *
     * @return 单例，永不为 null
     */
    static RoomStateStore get() {
        return INSTANCE.get();
    }

    /**
     * 替换单例。
     *
     * 仅供测试使用：让测试把自己的实例（指向临时目录）装进来，从而完全不触碰生产路径。
     * 生产代码不应调用。
     *
     * @param instance 新的单例，不能为 null
     */
    static void setInstanceForTest(RoomStateStore instance) {
        INSTANCE.set(instance);
    }

    /**
     * 取当前状态的深拷贝。
     *
     * @return 状态副本，永不为 null；调用方修改副本不影响内部状态
     */
    JsonObject snapshot() {
        synchronized (lock) {
            return state.deepCopy();
        }
    }

    /**
     * 取当前状态的 JSON 字符串。
     *
     * @return 序列化结果，永不为 null
     */
    String toJson() {
        synchronized (lock) {
            return state.toString();
        }
    }

    /**
     * 写入本地房间设置并立即持久化。
     *
     * @param allowCheats 是否允许作弊
     * @param visitorPermission 访客权限取值，允许为 null
     */
    void applyLocalSettings(boolean allowCheats, String visitorPermission) {
        synchronized (lock) {
            state.addProperty("allow_cheats", allowCheats);
            state.addProperty("visitor_permission", visitorPermission);
            state.addProperty("last_updated", System.currentTimeMillis());
            saveConfigLocked();
        }
    }

    /**
     * 增量合并一份新的状态。
     *
     * 语义是「增量合并」而非替换：入参中出现的键覆盖当前值，未出现的键保持原值。
     *
     * @param stateJson 新的状态 JSON，允许为 null，此时不做任何修改
     * @return 房间备注是否因本次合并而改变；调用方据此决定是否需要重启局域网广播
     */
    boolean applyUpdate(String stateJson) {
        if (stateJson == null) {
            return false;
        }
        JsonObject incoming;
        try {
            incoming = GSON.fromJson(stateJson, JsonObject.class);
        } catch (Exception e) {
            // ignore-reason: 入参来自网络，非法 JSON 按「原样返回不做修改」处理（与拆分前一致）；
            // 抛出会把解析错误升级成房间流程中断，而调用方对此无能为力。
            return false;
        }
        if (incoming == null) {
            return false;
        }
        synchronized (lock) {
            JsonObject next = state.deepCopy();
            if (incoming.has("log_entry")) {
                appendLogEntry(next, incoming.get("log_entry").getAsString());
            }

            String oldRemark = readRemark(next);
            mergeJsonObject(next, incoming);
            String newRemark = readRemark(next);
            boolean remarkChanged = !oldRemark.equals(newRemark);

            next.addProperty("last_updated", System.currentTimeMillis());
            state = next;
            saveConfigLocked();
            return remarkChanged;
        }
    }

    /**
     * 追加一条房间管理日志。
     *
     * 只更新内存状态，不落盘。
     *
     * @param entry 日志内容，允许为 null 或空字符串，此时不做任何事
     */
    void appendLog(String entry) {
        if (entry == null || entry.isEmpty()) {
            return;
        }
        synchronized (lock) {
            JsonObject next = state.deepCopy();
            appendLogEntry(next, entry);
            next.addProperty("last_updated", System.currentTimeMillis());
            state = next;
        }
    }

    /**
     * 读取房间备注。
     *
     * @param target 状态对象，不能为 null
     * @return 备注文本；缺失时返回空串
     */
    private static String readRemark(JsonObject target) {
        return target.has("room_remark") ? target.get("room_remark").getAsString() : "";
    }

    /**
     * 把磁盘上的配置合并进当前状态。
     *
     * 文件不存在时直接返回；解析或读取失败只记录警告，不抛出。
     */
    private void loadConfig() {
        if (!configFile.exists()) {
            return;
        }
        try {
            String content = Files.readString(configFile.toPath());
            JsonObject loaded = GSON.fromJson(content, JsonObject.class);
            if (loaded != null) {
                mergeJsonObject(state, loaded);
            }
        } catch (Exception e) {
            EnderApiClient.LOGGER.warn("Failed to load room config", e);
        }
    }

    /**
     * 把当前状态落盘。
     *
     * 写出时会剔除 Minecraft 原生设置键，只保留由本模组自行管理的字段。
     * 失败只记录警告、不抛异常。
     *
     * 调用前提：必须已持有 lock；本方法依赖该锁保证快照期间 state 不被替换。
     */
    private void saveConfigLocked() {
        try {
            File parent = configFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            JsonObject toSave = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : state.entrySet()) {
                String key = entry.getKey();
                if (isMcNativeSetting(key)) {
                    continue;
                }
                toSave.add(key, entry.getValue());
            }

            // 这四个键即使命中原生设置前缀也必须持久化，属显式白名单
            for (String key : new String[]{"whitelist", "blacklist", "mute_list", "whitelist_enabled"}) {
                if (state.has(key)) {
                    toSave.add(key, state.get(key));
                }
            }

            Files.writeString(configFile.toPath(), GSON.toJson(toSave));
        } catch (Exception e) {
            EnderApiClient.LOGGER.warn("Failed to save room config", e);
        }
    }

    /**
     * 判断某个配置键是否属于 Minecraft 原生设置。
     *
     * 原生设置由游戏侧保存，不写入本模组的房间配置文件。
     *
     * NOTE: 例外清单是 allow_cheats 与 allow_pvp——它们以 allow_ 开头但确实由本模组管理。
     *
     * @param key 设置键名，不能为 null
     * @return 属于原生设置返回 true；需要由本模组持久化返回 false
     */
    private static boolean isMcNativeSetting(String key) {
        return (key.startsWith("allow_") && !key.equals("allow_cheats") && !key.equals("allow_pvp")) ||
               key.startsWith("spawn_protection") ||
               key.startsWith("keep_inventory") ||
               key.startsWith("fire_spread") ||
               key.startsWith("mob_spawning") ||
               key.startsWith("time_lock") ||
               key.startsWith("weather_lock") ||
               key.startsWith("respawn_") ||
               key.startsWith("world_border_") ||
               key.equals("last_updated");
    }

    /**
     * 创建默认的房间管理状态。
     *
     * 默认值同时被用作「键集合的基线」与「缺省取值」。状态字段既包含本模组自有的管理项，
     * 也包含一批 Minecraft 原生设置。
     *
     * @return 默认状态对象，永不为 null
     */
    private static JsonObject createDefaultState() {
        JsonObject json = new JsonObject();
        json.addProperty("room_name", "未命名房间");
        json.addProperty("room_remark", "");
        json.addProperty("visitor_permission", "可交互");
        json.addProperty("whitelist_enabled", false);
        json.add("whitelist", new JsonArray());
        json.add("blacklist", new JsonArray());
        json.add("mute_list", new JsonArray());
        json.add("operation_logs", new JsonArray());
        json.addProperty("allow_cheats", false);
        json.addProperty("allow_pvp", true);
        json.addProperty("spawn_protection", 16);
        json.addProperty("keep_inventory", false);
        json.addProperty("fire_spread", true);
        json.addProperty("mob_spawning", true);
        json.addProperty("time_lock", "cycle");
        json.addProperty("weather_lock", false);
        json.addProperty("respawn_x", 0);
        json.addProperty("respawn_y", 0);
        json.addProperty("respawn_z", 0);
        json.addProperty("world_border_center_x", 0);
        json.addProperty("world_border_center_z", 0);
        json.addProperty("world_border_radius", 0);
        json.addProperty("auto_reconnect", true);
        json.addProperty("reconnect_retries", 3);
        json.addProperty("host_migration", false);
        json.addProperty("backend_version", "当前");
        json.addProperty("update_policy", "立即");
        json.addProperty("log_level", "INFO");
        json.addProperty("cpu_limit", 0);
        json.addProperty("memory_limit", 0);
        JsonArray versions = new JsonArray();
        versions.add("当前");
        versions.add("备用");
        json.add("backend_versions", versions);
        json.addProperty("last_updated", System.currentTimeMillis());
        return json;
    }

    /**
     * 把 source 的键值合并进 target。
     *
     * 覆盖语义：同名键以 source 为准。
     * NOTE: log_entry 是控制字段而非状态字段，会被跳过。
     *
     * @param target 目标对象，不能为 null，会被就地修改
     * @param source 源对象，不能为 null
     */
    private static void mergeJsonObject(JsonObject target, JsonObject source) {
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            String key = entry.getKey();
            if ("log_entry".equals(key)) {
                continue;
            }
            target.add(key, entry.getValue());
        }
    }

    /**
     * 向状态对象追加一条带时间戳的操作日志。
     *
     * 日志列表会被裁剪到最近 {@link #MAX_LOG_ENTRIES} 条，因此历史记录不可靠地长期保存。
     *
     * @param target 目标对象，不能为 null，会被就地修改
     * @param entry 日志内容，不能为 null
     */
    private static void appendLogEntry(JsonObject target, String entry) {
        JsonArray logs = target.has("operation_logs") && target.get("operation_logs").isJsonArray()
                ? target.getAsJsonArray("operation_logs")
                : new JsonArray();
        String line = System.currentTimeMillis() + " " + entry;
        logs.add(line);
        JsonArray trimmed = new JsonArray();
        int start = Math.max(0, logs.size() - MAX_LOG_ENTRIES);
        for (int i = start; i < logs.size(); i++) {
            trimmed.add(logs.get(i));
        }
        target.add("operation_logs", trimmed);
    }
}
