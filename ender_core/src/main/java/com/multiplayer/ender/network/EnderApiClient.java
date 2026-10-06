/*
 * 本文件属于 EnderOnline 核心逻辑层。
 *
 * 职责：玩家侧联机业务的门面，涵盖房间生命周期、Scaffolding 通信、玩家名册与房间配置持久化。
 *
 * NOTE: 本文件是上帝类，正在按 P3 计划拆分，拆分目标见类注释中的拆分计划条目。
 */
package com.multiplayer.ender.network;

import com.endercore.core.comm.CoreComm;
import com.endercore.core.comm.client.CoreWebSocketClient;
import com.endercore.core.comm.config.CoreWebSocketConfig;
import com.endercore.core.comm.protocol.CoreResponse;
import com.endercore.core.comm.server.CoreRequest;
import com.endercore.core.comm.server.CoreWebSocketServer;
import com.endercore.core.easytier.EasyTierManager;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;


import com.multiplayer.ender.logic.LanDiscovery;

/**
 * 房间联机业务的门面（上帝类）。
 *
 * 本类是全模块的静态状态中枢，把四件本应互相独立的事情压在同一个类里：
 * 房间生命周期（开房/加入/退出）、Scaffolding WebSocket 服务端与客户端、玩家资料名册、
 * 房间管理配置的持久化。任何一处改动都可能影响其余三处，阅读时需意识到这是一个整体。
 *
 * 设计约束：
 * 1. 全部状态都是静态的，因此本类在单个 JVM 内只能表达一个房间；多房间或同时主持与加入
 *    在结构上不可表达。
 * 2. 状态字段的可变性没有统一策略：一部分是 volatile、一部分被 ROOM_STATE_LOCK 保护、
 *    还有相当一部分（currentState、currentRoom、lastError、dynamicPort、roomManagementState）
 *    既非 volatile 也无锁。跨线程读写这些字段存在可见性延迟，不要把 getCurrentState 的返回值
 *    当作可靠的实时状态。
 * 3. lastError 存的是面向开发者的原始异常消息，不得直接展示在 UI 上；UI 只显示错误码对应的
 *    本地化文案（ADR-07）。
 * 4. 端口存在三个互不相同的默认值：Scaffolding 服务端 13448、被托管的 MC 端口 25565、
 *    getPort 在未设置动态端口时返回 25566。它们服务于不同用途，改动前必须确认调用方。
 *
 * 线程安全性：本类不是线程安全的。静态可变字段的写入分散在 ForkJoinPool 线程（joinRoom /
 * startHosting 的异步体）、Scaffolding 调度线程与网络回调线程上，彼此之间没有统一同步。
 * 读多写少的展示类字段（currentState、currentRoom、lastError）尤其容易出现陈旧值。
 *
 * TODO(P3, 2026-10-06): 拆分见 claude_docs/06-logic-and-code-quality.md §3.1——计划拆为 6 个类：
 * 房间状态机、Scaffolding 服务端、Scaffolding 客户端、玩家名册、房间配置存储、端口分配。
 *
 * @since 1.0
 * @see EasyTierManager
 */
public class EnderApiClient {

    /** Gson 实例，无特殊配置；Gson 本身线程安全，可被多线程共享。 */
    private static final Gson GSON = new Gson();

    /** 房主在 EasyTier 网络中的主机名前缀，其后拼接 Scaffolding 端口号，供加入方识别房主。 */
    private static final String SCAFFOLDING_PREFIX = "scaffolding-mc-server-";

    /** Scaffolding 服务端首选端口，取值 13448；被占用时会退化为系统随机分配的端口。 */
    private static final int DEFAULT_SCAFFOLDING_PORT = 13448;

    /** 本实现的 vendor 标识，随玩家资料上报，用于区分不同客户端的来源。 */
    private static final String VENDOR = "ender";

    /**
     * 本机标识。
     *
     * 进程启动时随机生成，因此**每次重启都会变化**，不能用作持久身份。
     * 房主与访客的身份判定、资料去重都依赖它。
     */
    private static final String LOCAL_MACHINE_ID = UUID.randomUUID().toString();

    /**
     * 当前已知的玩家资料列表。
     *
     * 永不为 null；元素可被多个线程就地修改（Profile 的字段是 volatile），
     * 列表自身是并发安全的，但「遍历 + 修改元素」不是原子操作。
     */
    private static final CopyOnWriteArrayList<Profile> profiles = new CopyOnWriteArrayList<>();

    /**
     * 玩家资料的最后活跃时间戳（毫秒）。
     *
     * 永不为 null；访客超过 10 秒未上报即被 pruneGuestProfiles 清理，房主不受此限制。
     */
    private static final ConcurrentHashMap<String, Long> profileLastSeen = new ConcurrentHashMap<>();

    /**
     * Scaffolding 服务端，仅房主持有。
     *
     * 允许为 null（未主持或已停止）；由 stopScaffoldingServer 置回 null。
     */
    private static volatile CoreWebSocketServer scaffoldingServer;

    /**
     * 指向房主 Scaffolding 服务端的客户端连接，仅加入方持有。
     *
     * 允许为 null（未连接或已断开）；由 connectScaffolding 赋值、stopScaffoldingClient 置回 null。
     */
    private static volatile CoreWebSocketClient scaffoldingClient;

    /**
     * 玩家资料清理调度器，仅房主使用。
     *
     * 允许为 null；守护线程 "Ender-Scaffolding-Profiles"，每 5 秒清理一次过期访客。
     */
    private static volatile ScheduledExecutorService profileScheduler;

    /**
     * Scaffolding 轮询调度器，仅加入方使用。
     *
     * 允许为 null；守护线程 "Ender-Scaffolding-Client"，每 5 秒轮询一次房主。
     */
    private static volatile ScheduledExecutorService scaffoldingClientScheduler;

    /**
     * 加入方当前连接的房主地址。
     *
     * 允许为 null（尚未定位到房主）；用于判断是否需要重新建连。
     */
    private static volatile InetSocketAddress scaffoldingRemote;

    /**
     * Scaffolding 服务端实际绑定的端口。
     *
     * 取值范围 1 到 65535，默认 DEFAULT_SCAFFOLDING_PORT；会作为房主主机名的一部分对外公布。
     */
    private static volatile int scaffoldingPort = DEFAULT_SCAFFOLDING_PORT;

    /**
     * 房主本机的 Minecraft 服务器端口。
     *
     * 取值范围 1 到 65535，默认 25565；由 startHosting 的入参覆盖，并通过 c:server_port 下发。
     */
    private static volatile int hostedMcPort = 25565;

    /**
     * 加入方解析出的房主 Minecraft 服务器端口。
     *
     * 取值范围 1 到 65535，默认 25565；同步失败时保留上一次的取值。
     */
    private static volatile int remoteMcPort = 25565;

    /**
     * 本地玩家名称。
     *
     * 允许为空字符串；上报给房主用于显示。
     */
    private static volatile String localPlayerName = "";

    /**
     * 最近一次成功加入的房间代码。
     *
     * 允许为空字符串；仅用于「重连上一次房间」这类交互，不参与协议。
     */
    private static volatile String lastRoomCode = "";

    /** 本类日志记录器，永不为 null，由 SLF4J 在类初始化时绑定。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(EnderApiClient.class);

    /**
     * 局域网广播使用的动态端口。
     *
     * 取值大于 0 表示已设置，-1 表示未设置；setPort 与 clearDynamicPort 是唯二的写入点。
     */
    private static int dynamicPort = -1;
    
    /**
     * 客户端状态。
     *
     * 合法流转路径：
     * IDLE 到 HOSTING_STARTING 到 HOSTING，或 IDLE 到 JOINING_STARTING 到 JOINING。
     * HOSTING_STARTING / JOINING_STARTING 均可因失败流转到 ERROR。
     * HOSTING、JOINING、ERROR 均可由 setIdle 或 panic 退回 IDLE，IDLE 是唯一的复位点。
     * 不存在 HOSTING 与 JOINING 之间的直接流转。
     */
    public enum State {

        /** 空闲，初始状态，也是所有清理流程的终点。 */
        IDLE,

        /** 正在创建房间，尚未确认 EasyTier 与 Scaffolding 服务端就绪，失败则流转到 ERROR。 */
        HOSTING_STARTING,

        /** 正在加入房间，处于扫描房主阶段，失败则流转到 ERROR。 */
        JOINING_STARTING,

        /** 已成功主持房间，Scaffolding 服务端在运行。 */
        HOSTING,

        /** 已成功加入房间，Scaffolding 客户端在轮询房主。 */
        JOINING,

        /** 上次操作失败，原因见 getLastError；可被下一次操作覆盖，或由 setIdle 复位到 IDLE。 */
        ERROR
    }

    /**
     * 当前状态。
     *
     * NOTE: 非 volatile，写入发生在异步线程、读取发生在调用线程，存在可见性延迟。
     */
    private static State currentState = State.IDLE;

    /**
     * 当前房间代码。
     *
     * 允许为空字符串；非 volatile，同上存在可见性问题。
     */
    private static String currentRoom = "";

    /**
     * 最近一次失败的原因，取值为原始异常消息。
     *
     * 允许为空字符串；面向开发者，禁止直接展示在 UI 上（ADR-07）。
     */
    private static String lastError = "";

    /**
     * 房间管理状态的互斥锁。
     *
     * 永不为 null；roomManagementState 的读写与 saveRoomConfig 的快照都必须持有它。
     */
    private static final Object ROOM_STATE_LOCK = new Object();

    /**
     * 房间配置的持久化文件。
     *
     * 永不为 null，路径相对于进程工作目录：config/ender_room_config.json。
     */
    private static final java.io.File CONFIG_FILE = new java.io.File("config/ender_room_config.json");

    /**
     * 房间管理状态。
     *
     * 仅允许在持有 ROOM_STATE_LOCK 时替换或修改；初值为「默认状态合并已落盘配置」。
     */
    private static JsonObject roomManagementState = createDefaultRoomManagementState();

    /**
     * 获取当前客户端状态。
     *
     * @return 当前状态枚举值，永不为 null
     */
    public static State getCurrentState() {
        return currentState;
    }

    /**
     * 把磁盘上的房间配置合并进给定状态对象。
     *
     * 文件不存在时直接返回，不修改 state；解析或读失败只记录警告。
     *
     * @param state 要合并到的状态对象，不能为 null，会被就地修改
     */
    private static void loadRoomConfig(JsonObject state) {
        if (!CONFIG_FILE.exists()) {
            return;
        }
        try {
            String content = java.nio.file.Files.readString(CONFIG_FILE.toPath());
            JsonObject loaded = GSON.fromJson(content, JsonObject.class);
            if (loaded != null) {
                mergeJsonObject(state, loaded);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load room config", e);
        }
    }

    /**
     * 把房间管理状态落盘。
     *
     * 写出时会剔除 Minecraft 原生设置键，只保留白名单、黑名单、静音列表与白名单开关等
     * 由本模组自行管理的字段。
     * 失败只记录警告、不抛异常，因此方法返回不代表写入成功。
     *
     * NOTE: 必须在持有 ROOM_STATE_LOCK 的调用路径之外调用；方法内部会自行加锁快照。
     */
    private static void saveRoomConfig() {
        try {
            java.io.File parent = CONFIG_FILE.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            
            JsonObject toSave = new JsonObject();
            synchronized (ROOM_STATE_LOCK) {
                
                for (Map.Entry<String, JsonElement> entry : roomManagementState.entrySet()) {
                    String key = entry.getKey();
                    if (isMcNativeSetting(key)) {
                        continue;
                    }
                    toSave.add(key, entry.getValue());
                }
                
                if (roomManagementState.has("whitelist")) {
                    toSave.add("whitelist", roomManagementState.get("whitelist"));
                }
                if (roomManagementState.has("blacklist")) {
                    toSave.add("blacklist", roomManagementState.get("blacklist"));
                }
                if (roomManagementState.has("mute_list")) {
                    toSave.add("mute_list", roomManagementState.get("mute_list"));
                }
                if (roomManagementState.has("whitelist_enabled")) {
                    toSave.add("whitelist_enabled", roomManagementState.get("whitelist_enabled"));
                }
            }
            
            java.nio.file.Files.writeString(CONFIG_FILE.toPath(), GSON.toJson(toSave));
        } catch (Exception e) {
            LOGGER.warn("Failed to save room config", e);
        }
    }

    /**
     * 判断某个配置键是否属于 Minecraft 原生设置。
     *
     * 原生设置由游戏侧保存，不写入本模组的房间配置文件，因此本方法决定持久化时哪些键被跳过。
     *
     * NOTE: 例外清单是 allow_cheats 与 allow_pvp——它们以 allow_ 开头但确实由本模组管理。
     *
     * @param key 设置键名，不能为 null
     * @return 属于原生设置返回 true，需要由本模组持久化则返回 false
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
     * 设置局域网广播使用的动态端口。
     *
     * @param port 端口号，取值大于 0 视为有效；设为 0 或负数等价于未设置
     */
    public static void setPort(int port) {
        dynamicPort = port;
    }

    /**
     * 清除动态端口并停止局域网广播。
     *
     * 幂等性：本方法幂等，重复调用效果相同。
     */
    public static void clearDynamicPort() {
        dynamicPort = -1;
        LanDiscovery.stopBroadcaster();
    }

    /**
     * 检查是否设置了动态端口。
     *
     * @return 已设置返回 true；未设置或曾被清除返回 false
     */
    public static boolean hasDynamicPort() {
        return dynamicPort > 0;
    }

    /**
     * 获取局域网广播使用的端口。
     *
     * NOTE: 未设置动态端口时返回的是固定兜底值 25566，而不是 hostedMcPort（默认 25565），
     * 两者不是同一个语义，不要互相替代。
     *
     * @return 已设置时返回动态端口，否则返回 25566
     */
    public static int getPort() {
        return dynamicPort > 0 ? dynamicPort : 25566;
    }

    /**
     * 获取同步到的房主 Minecraft 服务器端口。
     *
     * @return 端口号，默认 25565；尚未收到 c:server_port 响应时保持上一次取值
     */
    public static int getRemoteMcPort() {
        return remoteMcPort;
    }

    /**
     * 获取房主在 EasyTier 网络中的 IP 地址。
     *
     * @return 房主 IP 字符串；尚未定位到房主时返回 null，调用方必须判空
     */
    public static String getHostIp() {
        InetSocketAddress remote = scaffoldingRemote;
        if (remote != null && remote.getAddress() != null) {
            return remote.getAddress().getHostAddress();
        }
        return null;
    }

    /**
     * 获取元数据。
     *
     * FIXME(P3, 2026-10-06): 当前为占位实现，返回的是写死的兼容标识，
     * 不反映真实版本，接入真实元数据前调用方不得依赖其内容。
     *
     * @return 已完成、结果恒为 {"version": "ender_core_compat"} 的 Future，永不为 null
     */
    public static CompletableFuture<String> getMeta() {
        return CompletableFuture.completedFuture("{\"version\": \"ender_core_compat\"}");
    }

    /**
     * 同步获取房间管理状态。
     *
     * @return 状态对象的深拷贝，永不为 null；调用方修改副本不会影响内部状态
     */
    public static JsonObject getRoomManagementStateSync() {
        synchronized (ROOM_STATE_LOCK) {
            return roomManagementState.deepCopy();
        }
    }

    /**
     * 设置本地房间设置并立即持久化。
     *
     * 会写入 allow_cheats、visitor_permission 与 last_updated 三个键，并触发一次落盘。
     *
     * 幂等性：本方法幂等，相同入参重复调用得到相同状态（last_updated 除外）。
     *
     * @param allowCheats 是否允许作弊
     * @param visitorPermission 访客权限取值，允许为 null，为 null 时写入 JSON null
     */
    public static void setLocalSettings(boolean allowCheats, String visitorPermission) {
        synchronized (ROOM_STATE_LOCK) {
            roomManagementState.addProperty("allow_cheats", allowCheats);
            roomManagementState.addProperty("visitor_permission", visitorPermission);
            roomManagementState.addProperty("last_updated", System.currentTimeMillis());
        }
        saveRoomConfig();
    }

    /**
     * 异步获取房间管理状态的 JSON 字符串。
     *
     * @return 已完成、结果为当前状态序列化的 Future，永不为 null
     */
    public static CompletableFuture<String> getRoomManagementState() {
        synchronized (ROOM_STATE_LOCK) {
            return CompletableFuture.completedFuture(roomManagementState.toString());
        }
    }

    /**
     * 合并新的房间管理状态。
     *
     * 语义是「增量合并」而非替换：入参中出现的键会覆盖当前值，未出现的键保持原值。
     * 若入参含 log_entry，会先追加一条操作日志再合并。
     * 房间备注发生变化且当前处于 HOSTING 时，会顺带重启局域网广播以更新 MOTD。
     *
     * 幂等性：本方法不幂等，重复调用同一份含 log_entry 的入参会重复追加日志。
     *
     * @param stateJson 新的状态 JSON 字符串，允许为 null；为 null 或无法解析时原样返回，不做修改
     * @return 已完成、无返回值的 Future，永不为 null
     */
    public static CompletableFuture<Void> updateRoomManagementState(String stateJson) {
        if (stateJson == null) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            JsonObject incoming = GSON.fromJson(stateJson, JsonObject.class);
            if (incoming == null) {
                return CompletableFuture.completedFuture(null);
            }
            synchronized (ROOM_STATE_LOCK) {
                JsonObject next = roomManagementState.deepCopy();
                if (incoming.has("log_entry")) {
                    String entry = incoming.get("log_entry").getAsString();
                    appendLogEntry(next, entry);
                }
                
                String oldRemark = next.has("room_remark") ? next.get("room_remark").getAsString() : "";
                mergeJsonObject(next, incoming);
                String newRemark = next.has("room_remark") ? next.get("room_remark").getAsString() : "";
                
                if (currentState == State.HOSTING && hasDynamicPort() && !oldRemark.equals(newRemark)) {
                    String broadcast = newRemark.isEmpty() ? "Ender Online Room" : newRemark;
                    LanDiscovery.startBroadcaster(getPort(), broadcast);
                }
                
                next.addProperty("last_updated", System.currentTimeMillis());
                roomManagementState = next;
                saveRoomConfig();
            }
        } catch (Exception ignored) {
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 追加一条房间管理日志条目。
     *
     * 只更新内存中的状态，不落盘；日志列表最多保留最近 20 条。
     *
     * 幂等性：本方法不幂等，重复调用同一内容会产生多条日志。
     *
     * @param entry 日志内容，允许为 null 或空字符串，此时直接返回不做任何事
     */
    public static void appendRoomManagementLog(String entry) {
        if (entry == null || entry.isEmpty()) {
            return;
        }
        synchronized (ROOM_STATE_LOCK) {
            JsonObject next = roomManagementState.deepCopy();
            appendLogEntry(next, entry);
            next.addProperty("last_updated", System.currentTimeMillis());
            roomManagementState = next;
        }
    }

    /**
     * 检查客户端健康状态。
     *
     * FIXME(P3, 2026-10-06): 当前恒返回 true，不探测任何连接，
     * 健康检查在语义上尚未实现，调用方不能据此判断服务可用。
     *
     * @return 已完成、结果恒为 true 的 Future，永不为 null
     */
    public static CompletableFuture<Boolean> checkHealth() {
        return CompletableFuture.completedFuture(true);
    }

    /**
     * 紧急停止所有服务并回到空闲状态。
     *
     * 依次停止 EasyTier 进程、Scaffolding 客户端与服务端、清空玩家名册、清除动态端口，
     * 最后把状态复位为 IDLE 并清空当前房间。
     *
     * 幂等性：本方法幂等，重复调用不会因已停止的服务而报错。
     *
     * @param peaceful 是否平滑停止，当前实现忽略该参数，一律立即停止
     * @return 已完成、无返回值的 Future，永不为 null
     */
    public static CompletableFuture<Void> panic(boolean peaceful) {
        EasyTierManager.getInstance().stop();
        stopScaffoldingClient();
        stopScaffoldingServer();
        resetProfiles();
        clearDynamicPort();
        currentState = State.IDLE;
        currentRoom = "";
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 获取日志内容。
     *
     * FIXME(P3, 2026-10-06): 当前恒返回空字符串，日志能力未实现，
     * 依赖本方法展示日志的界面会一直显示为空。
     *
     * @param fetch 是否获取最新日志，当前实现忽略该参数
     * @return 已完成、结果恒为空字符串的 Future，永不为 null
     */
    public static CompletableFuture<String> getLog(boolean fetch) {
        return CompletableFuture.completedFuture("");
    }

    /**
     * 设置正在扫描的玩家。
     *
     * FIXME(P3, 2026-10-06): 当前为空实现，入参被丢弃且不产生任何效果，
     * 该能力尚未实现，调用方不应依赖。
     *
     * @param player 玩家名称，当前实现忽略该参数
     * @return 已完成、无返回值的 Future，永不为 null
     */
    public static CompletableFuture<Void> setScanning(String player) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 回到空闲状态并停止相关服务。
     *
     * 与 panic 的区别是不清理动态端口，因此局域网广播会保留。
     *
     * 幂等性：本方法幂等，重复调用效果相同。
     *
     * @return 已完成、无返回值的 Future，永不为 null
     */
    public static CompletableFuture<Void> setIdle() {
        EasyTierManager.getInstance().stop();
        stopScaffoldingClient();
        stopScaffoldingServer();
        resetProfiles();
        currentState = State.IDLE;
        currentRoom = "";
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 加入指定房间。
     *
     * 在 ForkJoinPool 上异步执行，立即返回。执行流程为：确保 EasyTier 已初始化、停止旧进程、
     * 校验并解析房间码、写入网络名与密钥、启动 EasyTier、随后在最多 30 秒内轮询房主主机名。
     *
     * 阻塞语义：整个流程可能持续 30 秒以上（约 1 秒启动等待加最多 30 次每秒一次的扫描），
     * 期间占用一个公共线程池线程。
     * 过程本身会改写 currentState 与 lastError 静态字段。
     *
     * 幂等性：本方法不幂等，重复调用会先停止当前 EasyTier 再重新连接。
     *
     * @param room 房间代码，不能为 null；必须匹配 U/XXXX-XXXX-XXXX-XXXX（大写字母数字）格式，
     *        否则直接置为 ERROR 并返回 false
     * @param player 玩家名称，允许为 null，为 null 时按空字符串上报
     * @return 加入成功返回 true；房间码非法、房主未在超时内出现或过程抛异常时返回 false
     */
    public static CompletableFuture<Boolean> joinRoom(String room, String player) {
        LOGGER.info("Joining room (EasyTier Network): {}", room);
        currentState = State.JOINING_STARTING;
        return CompletableFuture.supplyAsync(() -> {
            try {
                EasyTierManager manager = EasyTierManager.getInstance();
                
                
                if (!manager.isInitialized()) {
                    manager.initialize().join();
                }

                manager.stop();
                
                String name = "";
                String secret = "";
                
                
                if (room.matches("^U/[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$")) {
                    String raw = room.substring(2);
                    String[] parts = raw.split("-");
                    name = "scaffolding-mc-" + parts[0] + "-" + parts[1];
                    secret = parts[2] + "-" + parts[3];
                } else {
                    LOGGER.warn("Invalid room code format: {}", room);
                    currentState = State.ERROR;
                    lastError = "Invalid room code format";
                    return false;
                }
                
                com.endercore.core.easytier.EasyTierConfig cfg = manager.getConfig();
                cfg.networkName = name;
                cfg.networkSecret = secret;
                cfg.hostname = player == null ? "" : player;
                cfg.tcpWhitelist = "0";
                cfg.udpWhitelist = "0";
                if (hasDynamicPort()) {
                    cfg.rpcPort = getPort();
                }
                manager.start(cfg);
                
                
                Thread.sleep(1000);

                
                boolean hostFound = false;
                boolean hostSeenButNoIp = false;
                for (int i = 0; i < 30; i++) {
                    ScanResult result = scanForScaffoldingRemote();
                    if (result.address != null) {
                        hostFound = true;
                        break;
                    }
                    if (result.hostSeen) {
                        hostSeenButNoIp = true;
                    }
                    Thread.sleep(1000);
                }

                if (!hostFound) {
                    LOGGER.warn("Room host not found for room: {}", room);
                    manager.stop();
                    currentState = State.ERROR;
                    if (hostSeenButNoIp) {
                        lastError = "Host found but unreachable (Route Error)";
                    } else {
                        lastError = "Room not found or host offline";
                    }
                    return false;
                }

                
                if (hasDynamicPort()) {
                     LanDiscovery.startBroadcaster(getPort(), "Ender Online Room");
                }
                
                currentState = State.JOINING;
                currentRoom = room;
                localPlayerName = player == null ? "" : player;
                startScaffoldingClient();
                return true;
            } catch (Exception e) {
                LOGGER.error("Failed to start EasyTier for joining", e);
                currentState = State.ERROR;
                lastError = e.getMessage();
                return false;
            }
        });
    }

    /**
     * 记住最近一次加入的房间代码。
     *
     * 仅影响本地记忆，不参与协议交换。
     *
     * @param roomCode 房间代码，允许为 null，为 null 时后续 getLastRoomCode 也返回 null
     */
    public static void rememberRoomCode(String roomCode) {
        lastRoomCode = roomCode;
    }

    /**
     * 获取最近一次加入的房间代码。
     *
     * @return 房间代码；从未记录过或记录值为 null 时返回 null，调用方必须判空
     */
    public static String getLastRoomCode() {
        return lastRoomCode;
    }

    /**
     * 获取最后一次失败的原因。
     *
     * @return 原始异常消息或固定文案；从未失败时返回空字符串，永不为 null。
     * NOTE: 面向开发者，禁止直接展示在 UI 上（ADR-07）。
     */
    public static String getLastError() {
        return lastError;
    }

    /**
     * 获取当前状态详情。
     *
     * 依据 currentState 输出不同的字段组合：主持与加入会附带 profiles 与 players，
     * 启动中只给 state，ERROR 时给出 error。
     *
     * @return 已完成、结果为状态 JSON 字符串的 Future，永不为 null
     */
    public static CompletableFuture<String> getState() {
        JsonObject json = new JsonObject();
        
        json.addProperty("status", currentState.name());
        
        if (currentState == State.HOSTING) {
             json.addProperty("state", "host-ok");
             json.addProperty("room", currentRoom);

             JsonArray profileArray = buildProfilesJson();
             if (profileArray.size() > 0) {
                 json.add("profiles", profileArray);
                 json.add("players", buildPlayersJson(profileArray));
             }
        } else if (currentState == State.JOINING) {
             json.addProperty("state", "guest-ok");
             json.addProperty("room", currentRoom);
             JsonArray profileArray = buildProfilesJson();
             if (profileArray.size() > 0) {
                 json.add("profiles", profileArray);
                 json.add("players", buildPlayersJson(profileArray));
             }
        } else if (currentState == State.HOSTING_STARTING) {
             json.addProperty("state", "host-starting");
        } else if (currentState == State.JOINING_STARTING) {
             json.addProperty("state", "guest-starting");
        } else if (currentState == State.ERROR) {
             json.addProperty("error", lastError);
        }
        
        return CompletableFuture.completedFuture(json.toString());
    }

    /**
     * 创建默认的房间管理状态。
     *
     * 默认值同时被用作「键集合的基线」与「缺省取值」，随后会合并磁盘上已有的配置。
     * 状态字段既包含本模组自有的管理项（白名单、静音列表等），也包含一批 Minecraft 原生设置。
     *
     * @return 默认状态对象，永不为 null；已在其上合并过落盘配置
     */
    private static JsonObject createDefaultRoomManagementState() {
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
        
        
        loadRoomConfig(json);
        
        return json;
    }

    /**
     * 把 source 的键值合并进 target。
     *
     * 覆盖语义：同名键以 source 为准。
     * NOTE: log_entry 是控制字段而非状态字段，会被跳过，避免它被写进状态对象。
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
            JsonElement value = entry.getValue();
            target.add(key, value);
        }
    }

    /**
     * 向状态对象追加一条带时间戳的操作日志。
     *
     * 日志列表会被裁剪到最近 20 条，因此历史记录不可靠地长期保存。
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
        int start = Math.max(0, logs.size() - 20);
        for (int i = start; i < logs.size(); i++) {
            trimmed.add(logs.get(i));
        }
        target.add("operation_logs", trimmed);
    }

    /**
     * 开始主持游戏并生成房间码。
     *
     * 等价于把进度回调传 null 的三参重载。
     *
     * @param port 本机 Minecraft 服务器端口，取值 1 到 65535
     * @param playerName 玩家名称，允许为 null，为 null 时按空字符串处理
     * @return 以房间码完成的 Future；失败时以 CompletionException 异常完成，cause 为原始异常
     */
    public static CompletableFuture<String> startHosting(int port, String playerName) {
        return startHosting(port, playerName, null);
    }

    /**
     * 开始主持游戏并生成房间码，带进度回调。
     *
     * 在 ForkJoinPool 上异步执行，立即返回。执行流程为：确保 EasyTier 已初始化、停止旧进程、
     * 生成四个随机分组拼成房间码、启动 Scaffolding 服务端、把服务端端口写进 EasyTier 主机名、
     * 启动 EasyTier、启动局域网广播。
     *
     * 阻塞语义：内部包含约 1 秒的固定等待，期间占用一个公共线程池线程。
     * 过程会改写当前状态、当前房间与 hostedMcPort 等静态字段。
     *
     * 幂等性：本方法不幂等，重复调用会重新生成房间码并重建服务端。
     *
     * @param port 本机 Minecraft 服务器端口，取值 1 到 65535
     * @param playerName 玩家名称，允许为 null，为 null 时按空字符串处理
     * @param progressCallback 进度回调，允许为 null；非 null 时在下载安装阶段接收 0.0 到 1.0 的值
     * @return 以房间码完成的 Future；失败时先停止 Scaffolding 服务端，再以
     *         CompletionException 异常完成，cause 为原始异常
     */
    public static CompletableFuture<String> startHosting(int port, String playerName, Consumer<Double> progressCallback) {
        LOGGER.info("Starting hosting for {} on port {}", playerName, port);
        currentState = State.HOSTING_STARTING;
        return CompletableFuture.supplyAsync(() -> {
            try {
                EasyTierManager manager = EasyTierManager.getInstance();
                
                // 检查是否初始化
                if (!manager.isInitialized()) {
                    manager.initialize(progressCallback).join();
                }
                
                manager.stop();
                
                
                String p1 = generateRandomString(4);
                String p2 = generateRandomString(4);
                String p3 = generateRandomString(4);
                String p4 = generateRandomString(4);
                
                String roomCode = "U/" + p1 + "-" + p2 + "-" + p3 + "-" + p4;
                String networkName = "scaffolding-mc-" + p1 + "-" + p2;
                String networkSecret = p3 + "-" + p4;

                hostedMcPort = port;
                localPlayerName = playerName == null ? "" : playerName;
                int serverPort = startScaffoldingServer(port, localPlayerName);
                
                
                com.endercore.core.easytier.EasyTierConfig cfg = manager.getConfig();
                cfg.networkName = networkName;
                cfg.networkSecret = networkSecret;
                cfg.hostname = SCAFFOLDING_PREFIX + serverPort;
                cfg.tcpWhitelist = serverPort + "," + port;
                cfg.udpWhitelist = String.valueOf(port);
                if (hasDynamicPort()) {
                    cfg.rpcPort = getPort();
                }
                
                if (roomManagementState.has("log_level")) {
                    cfg.logLevel = roomManagementState.get("log_level").getAsString();
                }
                
                manager.start(cfg);
                
                
                Thread.sleep(1000);

                
                if (hasDynamicPort()) {
                     String remark = roomManagementState.has("room_remark") ? roomManagementState.get("room_remark").getAsString() : "";
                     if (remark == null || remark.isEmpty()) {
                         remark = "Ender Online Room";
                     }
                     LanDiscovery.startBroadcaster(getPort(), remark);
                }
                
                currentState = State.HOSTING;
                currentRoom = roomCode;
                
                return roomCode;
            } catch (Exception e) {
                LOGGER.error("Failed to start hosting", e);
                currentState = State.ERROR;
                lastError = e.getMessage();
                stopScaffoldingServer();
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 生成指定长度的大写字母数字随机串。
     *
     * 字符表为 A-Z 与 0-9（不含易混淆字符的剔除策略），每次调用都新建 Random 实例。
     *
     * NOTE: 使用 java.util.Random 而非密码学安全随机源，房间码的不可猜测性有限，
     * 安全性由网络密钥与 EasyTier 网络名共同承担。
     *
     * @param length 生成长度，必须大于 0；小于等于 0 时返回空字符串
     * @return 长度为 length 的随机串，永不为 null
     */
    private static String generateRandomString(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder();
        java.util.Random random = new java.util.Random();
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    /**
     * 启动 Scaffolding 服务端并注册全部处理函数。
     *
     * 会先停止既有服务端并清空玩家名册，因此本方法同时承担「重置」职责。
     * 监听地址固定为 0.0.0.0，端口优先使用 DEFAULT_SCAFFOLDING_PORT，被占用时退化为随机端口。
     * 注册的协议为 c:ping、c:protocols、c:server_port、c:player_ping、
     * c:player_profiles_list 与 c:room_state_sync。
     *
     * 幂等性：本方法不幂等，重复调用会重建服务端并重置名册。
     *
     * @param mcPort 本机 Minecraft 服务器端口，取值 1 到 65535，用于响应 c:server_port
     * @param hostName 房主显示名，允许为 null，为 null 时房主资料姓名为 null 并在构建 JSON 时被跳过
     * @return 服务端实际绑定的端口，取值范围 1 到 65535，永不为非正值
     */
    private static int startScaffoldingServer(int mcPort, String hostName) {
        stopScaffoldingServer();
        resetProfiles();
        hostedMcPort = mcPort;
        scaffoldingPort = pickAvailablePort(DEFAULT_SCAFFOLDING_PORT);
        Profile hostProfile = new Profile(LOCAL_MACHINE_ID, hostName, VENDOR, "HOST");
        profiles.add(hostProfile);
        profileLastSeen.put(LOCAL_MACHINE_ID, System.currentTimeMillis());
        CoreWebSocketServer server = CoreComm.newServer(new InetSocketAddress("0.0.0.0", scaffoldingPort), 4 * 1024 * 1024, null);
        server.register("c:ping", EnderApiClient::handlePing);
        server.register("c:protocols", EnderApiClient::handleProtocols);
        server.register("c:server_port", EnderApiClient::handleServerPort);
        server.register("c:player_ping", EnderApiClient::handlePlayerPing);
        server.register("c:player_profiles_list", EnderApiClient::handlePlayerProfilesList);
        server.register("c:room_state_sync", EnderApiClient::handleRoomStateSync);
        server.start();
        server.awaitStarted(Duration.ofSeconds(3));
        scaffoldingServer = server;
        profileScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Ender-Scaffolding-Profiles");
            t.setDaemon(true);
            return t;
        });
        profileScheduler.scheduleWithFixedDelay(EnderApiClient::pruneGuestProfiles, 5, 5, TimeUnit.SECONDS);
        return scaffoldingPort;
    }

    /**
     * 停止 Scaffolding 服务端及其资料清理调度器。
     *
     * 先把静态引用置为 null 再关闭，避免关闭过程中被其它线程重新取用到半关闭的对象。
     * 关闭失败被静默忽略。
     *
     * 幂等性：本方法幂等，未启动时是空操作。
     */
    private static void stopScaffoldingServer() {
        if (profileScheduler != null) {
            profileScheduler.shutdownNow();
            profileScheduler = null;
        }
        CoreWebSocketServer server = scaffoldingServer;
        scaffoldingServer = null;
        if (server != null) {
            try {
                server.stop(1000);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 启动 Scaffolding 轮询调度器。
     *
     * 调度器为守护线程 "Ender-Scaffolding-Client"，首次立即执行，之后每 5 秒一次。
     * 会先停止既有调度器，因此不会出现两个轮询线程。
     *
     * 幂等性：本方法幂等，重复调用只会替换调度器而不会累积线程。
     */
    private static void startScaffoldingClient() {
        stopScaffoldingClient();
        scaffoldingClientScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Ender-Scaffolding-Client");
            t.setDaemon(true);
            return t;
        });
        scaffoldingClientScheduler.scheduleWithFixedDelay(EnderApiClient::pollScaffoldingServer, 0, 5, TimeUnit.SECONDS);
    }

    /**
     * 停止 Scaffolding 客户端轮询与连接。
     *
     * 同时把 scaffoldingClient 与 scaffoldingRemote 置回 null，使下一次轮询必然重新定位房主。
     * 关闭连接失败被静默忽略。
     *
     * 幂等性：本方法幂等，未连接时是空操作。
     */
    private static void stopScaffoldingClient() {
        if (scaffoldingClientScheduler != null) {
            scaffoldingClientScheduler.shutdownNow();
            scaffoldingClientScheduler = null;
        }
        CoreWebSocketClient client = scaffoldingClient;
        scaffoldingClient = null;
        scaffoldingRemote = null;
        if (client != null) {
            try {
                client.close(Duration.ofSeconds(1)).join();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 轮询房主并同步玩家名册、房间状态与端口。
     *
     * 只有处于 JOINING 状态才会真正发包，其余状态直接返回。
     * 每轮依次：从 EasyTier 更新名册、定位房主地址（必要时重建连接）、发送 c:player_ping、
     * 拉取 c:player_profiles_list 刷新名册、拉取 c:room_state_sync 合并房间状态、
     * 拉取 c:server_port 更新 remoteMcPort。每个请求的超时均为 10 秒。
     *
     * 幂等性：本方法幂等——除 c:player_ping 会刷新房主侧的最后活跃时间外，重复执行不改变状态。
     */
    private static void pollScaffoldingServer() {
        if (currentState != State.JOINING) {
            return;
        }
        
        
        updateProfilesFromEasyTier();

        
        InetSocketAddress remote = findScaffoldingRemote();
        if (remote == null) {
            LOGGER.debug("Scaffolding remote not found during poll");
            return;
        }
        if (scaffoldingRemote == null || !scaffoldingRemote.equals(remote) || scaffoldingClient == null || !scaffoldingClient.isConnected()) {
            LOGGER.info("Connecting to scaffolding remote: {}", remote);
            connectScaffolding(remote);
        }
        CoreWebSocketClient client = scaffoldingClient;
        if (client == null || !client.isConnected()) {
            LOGGER.warn("Scaffolding client not connected after attempt");
            return;
        }
        try {
            JsonObject ping = new JsonObject();
            ping.addProperty("machine_id", LOCAL_MACHINE_ID);
            ping.addProperty("name", localPlayerName);
            ping.addProperty("vendor", VENDOR);
            
            client.sendSync("c:player_ping", ping.toString().getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(10));
            CoreResponse resp = client.sendSync("c:player_profiles_list", new byte[0], Duration.ofSeconds(10));
            if (!resp.isOk()) {
                LOGGER.warn("Failed to fetch profiles: status={}", resp.status());
                return;
            }
            String json = new String(resp.payload(), StandardCharsets.UTF_8);
            
            JsonArray array = GSON.fromJson(json, JsonArray.class);
            if (array != null) {
                updateProfilesFromArray(array);
            }

            
            CoreResponse stateResp = client.sendSync("c:room_state_sync", new byte[0], Duration.ofSeconds(10));
            if (stateResp.isOk()) {
                String stateJson = new String(stateResp.payload(), StandardCharsets.UTF_8);
                updateRoomManagementState(stateJson);
            } else {
                LOGGER.warn("Failed to sync room state: status={}", stateResp.status());
            }

            
            CoreResponse portResp = client.sendSync("c:server_port", new byte[0], Duration.ofSeconds(10));
            if (portResp.isOk()) {
                try {
                    java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(portResp.payload()));
                    remoteMcPort = in.readUnsignedShort();
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            LOGGER.error("Error polling scaffolding server", e);
        }
    }

    /**
     * 从 EasyTier 的对等节点主机名补全玩家名册。
     *
     * 主机名以宿主名前缀开头的节点被判定为房主，其余判定为访客；
     * 公共中转节点与含 .easytier. 的基础设施节点会被跳过。
     * 已存在的资料只刷新最后活跃时间，不覆盖姓名（房主除外）。
     */
    private static void updateProfilesFromEasyTier() {
        try {
            Map<String, String> hostnames = EasyTierManager.getInstance().getPeerHostnames();
            for (Map.Entry<String, String> entry : hostnames.entrySet()) {
                String id = entry.getKey();
                String hostname = entry.getValue();
                if (hostname == null || hostname.isBlank()) continue;
                
                
                
                
                
                if (hostname.startsWith("PublicServer_") || hostname.contains(".easytier.")) {
                    continue;
                }

                String kind = "GUEST";
                String name = hostname;
                if (hostname.startsWith(SCAFFOLDING_PREFIX)) {
                    kind = "HOST";
                }
                
                boolean found = false;
                for (Profile p : profiles) {
                    if (p.machineId.equals(id)) {
                        if ("GUEST".equals(p.kind)) {
                            p.name = name;
                            profileLastSeen.put(id, System.currentTimeMillis());
                        } else if ("HOST".equals(p.kind)) {
                             profileLastSeen.put(id, System.currentTimeMillis());
                        }
                        found = true;
                        break;
                    }
                }
                
                if (!found) {
                    
                    String displayName = name;
                    
                    profiles.add(new Profile(id, displayName, "EasyTier", kind));
                    profileLastSeen.put(id, System.currentTimeMillis());
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to update profiles from EasyTier", e);
        }
    }

    /**
     * 建立到房主 Scaffolding 服务端的 WebSocket 连接。
     *
     * 会先关闭既有连接；连接超时 10 秒、请求超时 15 秒，心跳被显式关闭（固定时长 ZERO），
     * 活口检测依赖轮询而不是心跳。
     * 连接失败只记录日志并关闭临时客户端，不会替换 scaffoldingClient，因此调用方需要靠
     * 返回值之外的 isConnected 判断结果。
     *
     * @param remote 房主地址，不能为 null；连接串固定为 ws://host:port/ws
     */
    private static void connectScaffolding(InetSocketAddress remote) {
        try {
            if (scaffoldingClient != null) {
                scaffoldingClient.close(Duration.ofSeconds(1)).join();
            }
        } catch (Exception ignored) {
        }
        CoreWebSocketClient client = CoreComm.newClient(CoreWebSocketConfig.builder()
                .connectTimeout(Duration.ofSeconds(10))
                .requestTimeout(Duration.ofSeconds(15))
                .heartbeatInterval(Duration.ZERO)
                .build(), null, null);
        try {
            URI uri = URI.create("ws://" + remote.getHostString() + ":" + remote.getPort() + "/ws");
            LOGGER.info("Attempting WebSocket connection to: {}", uri);
            client.connect(uri).get(10, TimeUnit.SECONDS);
            scaffoldingClient = client;
            scaffoldingRemote = remote;
            LOGGER.info("WebSocket connected successfully");
        } catch (Exception e) {
            LOGGER.error("Failed to connect to scaffolding server: " + remote, e);
            try {
                client.close(Duration.ofSeconds(1)).join();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 扫描结果，用于在「找到房主」「见到主机名但缺 IP」「什么都没找到」之间传递中间态。
     *
     * 仅供 EnderApiClient 内部使用，禁止跨包引用。
     */
    private static class ScanResult {

        /** 已解析出的房主地址；为 null 表示本轮未解析成功，此时 hostSeen 与 ipMissing 仍有意义。 */
        InetSocketAddress address;

        /** 是否至少见到过一个符合房主主机名格式的节点。 */
        boolean hostSeen;

        /** 是否出现过「主机名匹配但缺少 IP」的情形，用于区分「房间不存在」与「路由未就绪」。 */
        boolean ipMissing;
    }

    /**
     * 扫描 EasyTier 的对等节点，定位房主地址。
     *
     * 命中规则是主机名以 SCAFFOLDING_PREFIX 开头，其后缀即房主 Scaffolding 端口。
     * 命中即记录 hostSeen；缺少 IP 时记录 ipMissing 并继续尝试其它节点。
     *
     * @return 扫描结果，永不为 null；未找到房主时 address 为 null
     */
    private static ScanResult scanForScaffoldingRemote() {
        ScanResult result = new ScanResult();
        Map<String, String> hostnames = EasyTierManager.getInstance().getPeerHostnames();
        Map<String, String> ips = EasyTierManager.getInstance().getPeerIps();
        for (Map.Entry<String, String> entry : hostnames.entrySet()) {
            String hostname = entry.getValue();
            if (hostname == null) {
                continue;
            }
            String trimmed = hostname.trim();
            LOGGER.info("Scanning host: {} -> {}", entry.getKey(), trimmed);
            if (!trimmed.startsWith(SCAFFOLDING_PREFIX)) {
                continue;
            }
            
            result.hostSeen = true;
            
            LOGGER.info("Found scaffolding host: {} -> {}", entry.getKey(), trimmed);
            
            String portStr = trimmed.substring(SCAFFOLDING_PREFIX.length());
            int port;
            try {
                port = Integer.parseInt(portStr);
            } catch (Exception e) {
                continue;
            }
            String ip = ips.get(entry.getKey());
            if (ip == null || ip.isBlank()) {
                
                
                
                result.ipMissing = true;
                LOGGER.warn("Host found but no IP for peer: {}", entry.getKey());
                continue;
            }
            result.address = new InetSocketAddress(ip, port);
            return result;
        }
        return result;
    }

    /**
     * 查找房主地址。
     *
     * @return 房主地址；未找到时返回 null，调用方必须判空
     */
    private static InetSocketAddress findScaffoldingRemote() {
        return scanForScaffoldingRemote().address;
    }

    /**
     * 处理 c:ping 请求。
     *
     * 原样回显请求负载，用作连通性与协议可用性探测。
     *
     * @param req 核心请求对象，不能为 null
     * @return 状态码为 0、负载与请求一致的响应对象，永不为 null
     */
    private static CoreResponse handlePing(CoreRequest req) {
        return new CoreResponse(0, req.requestId(), req.kind(), req.payload());
    }

    /**
     * 处理 c:protocols 请求。
     *
     * 返回本实现支持的协议名列表，以 NUL 字节分隔，供对端探测能力交集。
     *
     * @param req 核心请求对象，不能为 null
     * @return 状态码为 0、负载为 NUL 分隔协议名的响应对象，永不为 null
     */
    private static CoreResponse handleProtocols(CoreRequest req) {
        byte[] payload = String.join("\0",
                "c:ping",
                "c:protocols",
                "c:server_port",
                "c:player_ping",
                "c:player_profiles_list",
                "c:room_state_sync").getBytes(StandardCharsets.UTF_8);
        return new CoreResponse(0, req.requestId(), req.kind(), payload);
    }

    /**
     * 处理 c:room_state_sync 请求。
     *
     * 在锁内完成序列化，保证返回的是自洽的状态快照。
     *
     * @param req 核心请求对象，不能为 null
     * @return 状态码为 0、负载为房间状态 JSON 的响应对象，永不为 null
     */
    private static CoreResponse handleRoomStateSync(CoreRequest req) {
        String json;
        synchronized (ROOM_STATE_LOCK) {
            json = roomManagementState.toString();
        }
        return new CoreResponse(0, req.requestId(), req.kind(), json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 处理 c:server_port 请求。
     *
     * 负载为大端 2 字节无符号短整型，即房主的 Minecraft 服务器端口。
     *
     * @param req 核心请求对象，不能为 null
     * @return 状态码为 0、负载为 2 字节端口号的响应对象；序列化失败时返回状态码 1 与空负载
     */
    private static CoreResponse handleServerPort(CoreRequest req) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            out.writeShort((short) hostedMcPort);
            return new CoreResponse(0, req.requestId(), req.kind(), baos.toByteArray());
        } catch (Exception e) {
            return new CoreResponse(1, req.requestId(), req.kind(), new byte[0]);
        }
    }

    /**
     * 处理 c:player_ping 请求，刷新玩家在线状态。
     *
     * 负载为含 machine_id、name、vendor 的 JSON。本机上报会被识别为房主资料更新，
     * 其它来源则按访客插入或更新。
     *
     * 幂等性：本方法幂等——重复上报只刷新最后活跃时间，不产生重复资料。
     *
     * @param req 核心请求对象，不能为 null
     * @return 状态码为 0 表示已登记；负载为空 JSON、无法解析或 machine_id / name 为空时返回状态码 1
     */
    private static CoreResponse handlePlayerPing(CoreRequest req) {
        try {
            String body = new String(req.payload(), StandardCharsets.UTF_8);
            JsonObject json = GSON.fromJson(body, JsonObject.class);
            if (json == null) {
                return new CoreResponse(1, req.requestId(), req.kind(), new byte[0]);
            }
            String machineId = json.has("machine_id") ? json.get("machine_id").getAsString() : "";
            String name = json.has("name") ? json.get("name").getAsString() : "";
            String vendor = json.has("vendor") ? json.get("vendor").getAsString() : "";
            if (machineId.isBlank() || name.isBlank()) {
                return new CoreResponse(1, req.requestId(), req.kind(), new byte[0]);
            }
            if (machineId.equals(LOCAL_MACHINE_ID)) {
                updateHostProfile(name);
                return new CoreResponse(0, req.requestId(), req.kind(), new byte[0]);
            }
            upsertGuestProfile(machineId, name, vendor);
            return new CoreResponse(0, req.requestId(), req.kind(), new byte[0]);
        } catch (Exception e) {
            return new CoreResponse(1, req.requestId(), req.kind(), new byte[0]);
        }
    }

    /**
     * 处理 c:player_profiles_list 请求。
     *
     * @param req 核心请求对象，不能为 null
     * @return 状态码为 0、负载为玩家资料 JSON 数组的响应对象，永不为 null
     */
    private static CoreResponse handlePlayerProfilesList(CoreRequest req) {
        JsonArray array = buildProfilesJson();
        byte[] payload = array.toString().getBytes(StandardCharsets.UTF_8);
        return new CoreResponse(0, req.requestId(), req.kind(), payload);
    }

    /**
     * 更新或创建房主资料。
     *
     * 只匹配本机标识且 kind 为 HOST 的条目；房主不存在时直接补建。
     *
     * @param name 房主显示名，不能为 null
     */
    private static void updateHostProfile(String name) {
        for (Profile profile : profiles) {
            if (profile.machineId.equals(LOCAL_MACHINE_ID) && "HOST".equals(profile.kind)) {
                profile.name = name;
                profileLastSeen.put(LOCAL_MACHINE_ID, System.currentTimeMillis());
                return;
            }
        }
        Profile host = new Profile(LOCAL_MACHINE_ID, name, VENDOR, "HOST");
        profiles.add(host);
        profileLastSeen.put(LOCAL_MACHINE_ID, System.currentTimeMillis());
    }

    /**
     * 更新或插入访客资料。
     *
     * 以本机标识之外的 machineId 为键；已存在则覆盖姓名、vendor 并把 kind 归正为 GUEST。
     *
     * @param machineId 访客的本机标识，不能为 null
     * @param name 访客显示名，不能为 null
     * @param vendor 访客客户端标识，允许为 null
     */
    private static void upsertGuestProfile(String machineId, String name, String vendor) {
        for (Profile profile : profiles) {
            if (profile.machineId.equals(machineId)) {
                profile.name = name;
                profile.vendor = vendor;
                profile.kind = "GUEST";
                profileLastSeen.put(machineId, System.currentTimeMillis());
                return;
            }
        }
        profiles.add(new Profile(machineId, name, vendor, "GUEST"));
        profileLastSeen.put(machineId, System.currentTimeMillis());
    }

    /**
     * 清理超时未上报的访客资料。
     *
     * 心跳窗口为 10 秒，超过即视为离线并移除；房主资料永不清理。
     * 遍历的是名册副本，因此遍历期间的并发修改不会抛并发修改异常。
     */
    private static void pruneGuestProfiles() {
        long now = System.currentTimeMillis();
        for (Profile profile : new ArrayList<>(profiles)) {
            if ("HOST".equals(profile.kind)) {
                continue;
            }
            Long last = profileLastSeen.get(profile.machineId);
            if (last == null || now - last > 10_000) {
                profiles.remove(profile);
                profileLastSeen.remove(profile.machineId);
            }
        }
    }

    /**
     * 用服务端下发的资料数组整体替换本地名册。
     *
     * 会先清空名册与最后活跃时间表，因此这是一次全量覆盖而非合并；
     * 缺少 machine_id 或 name 的条目被丢弃，kind 为空时按 GUEST 处理。
     *
     * 幂等性：本方法幂等，相同数组重复应用得到相同名册。
     *
     * @param array 玩家资料 JSON 数组，不能为 null
     */
    private static void updateProfilesFromArray(JsonArray array) {
        profiles.clear();
        profileLastSeen.clear();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            String name = obj.has("name") ? obj.get("name").getAsString() : "";
            String machineId = obj.has("machine_id") ? obj.get("machine_id").getAsString() : "";
            String vendor = obj.has("vendor") ? obj.get("vendor").getAsString() : "";
            String kind = obj.has("kind") ? obj.get("kind").getAsString() : "";
            if (machineId.isBlank() || name.isBlank()) {
                continue;
            }
            profiles.add(new Profile(machineId, name, vendor, kind.isBlank() ? "GUEST" : kind));
            profileLastSeen.put(machineId, System.currentTimeMillis());
        }
    }

    /**
     * 构建玩家资料的 JSON 数组。
     *
     * 姓名为 null 或空白的资料会被跳过，因此返回值不保证与名册一一对应。
     *
     * @return 玩家资料 JSON 数组，永不为 null，可能为空数组
     */
    private static JsonArray buildProfilesJson() {
        JsonArray array = new JsonArray();
        for (Profile profile : profiles) {
            if (profile.name == null || profile.name.isBlank()) {
                continue;
            }
            JsonObject obj = new JsonObject();
            obj.addProperty("name", profile.name);
            obj.addProperty("machine_id", profile.machineId);
            obj.addProperty("vendor", profile.vendor);
            obj.addProperty("kind", profile.kind);
            array.add(obj);
        }
        return array;
    }

    /**
     * 从资料数组中抽取玩家名称。
     *
     * @param profilesArray 玩家资料 JSON 数组，不能为 null
     * @return 玩家名称 JSON 数组，永不为 null；非对象元素与无 name 字段的元素会被跳过
     */
    private static JsonArray buildPlayersJson(JsonArray profilesArray) {
        JsonArray players = new JsonArray();
        for (JsonElement element : profilesArray) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            if (!obj.has("name")) {
                continue;
            }
            String name = obj.get("name").getAsString();
            if (name != null && !name.isBlank()) {
                players.add(name);
            }
        }
        return players;
    }

    /**
     * 清空玩家名册与最后活跃时间表。
     *
     * 幂等性：本方法幂等，重复调用效果相同。
     */
    private static void resetProfiles() {
        profiles.clear();
        profileLastSeen.clear();
    }

    /**
     * 选择 Scaffolding 服务端端口。
     *
     * 优先复用首选端口，被占用时退化为随机端口。
     *
     * FIXME(P3, 2026-10-06): 存在 TOCTOU 竞态——探测到「可绑定」与实际绑定之间
     * 存在时间窗口，高并发启动或端口竞争激烈时仍可能绑定失败。
     *
     * @param preferred 首选端口，取值 1 到 65535
     * @return 端口号，永不为非正值：优先返回 preferred，被占用时返回随机分配的空闲端口
     */
    private static int pickAvailablePort(int preferred) {
        if (isPortAvailable(preferred)) {
            return preferred;
        }
        return findAvailablePort();
    }

    /**
     * 分配一个空闲端口。
     *
     * 绑定端口 0 让系统分配后立即释放。
     *
     * FIXME(P3, 2026-10-06): 返回的是「曾空闲」而非「已保留」的端口，存在 TOCTOU 竞态。
     *
     * @return 端口号；分配失败等异常情况下返回兜底值 DEFAULT_SCAFFOLDING_PORT
     */
    private static int findAvailablePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            return DEFAULT_SCAFFOLDING_PORT;
        }
    }

    /**
     * 探测指定端口当前是否可绑定。
     *
     * @param port 端口号，取值 1 到 65535
     * @return 可绑定返回 true；被占用、无权限或取值非法返回 false
     */
    private static boolean isPortAvailable(int port) {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(port)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 玩家资料。
     *
     * 供房主侧名册与状态 JSON 使用，跨线程读写：引用本身被并发容器保护，
     * 字段用 volatile 保证可见性。
     *
     * 仅供 EnderApiClient 内部使用，禁止跨包引用。
     */
    private static final class Profile {

        /** 玩家本机标识，构造后不再变化，永不为 null。 */
        private final String machineId;

        /** 玩家显示名，允许为 null（构造时不做校验）。 */
        private volatile String name;

        /** 客户端标识，不允许为 null；构造时把 null 归一化为空字符串。 */
        private volatile String vendor;

        /** 角色，取值为 HOST 或 GUEST；构造时把 null 归一化为空字符串。 */
        private volatile String kind;

        /**
         * 构造玩家资料。
         *
         * @param machineId 本机标识，不能为 null
         * @param name 显示名，允许为 null
         * @param vendor 客户端标识，允许为 null，为 null 时归一化为空字符串
         * @param kind 角色，允许为 null，为 null 时归一化为空字符串
         */
        private Profile(String machineId, String name, String vendor, String kind) {
            this.machineId = machineId;
            this.name = name;
            this.vendor = vendor == null ? "" : vendor;
            this.kind = kind == null ? "" : kind;
        }
    }
}
