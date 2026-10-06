/*
 * 本文件属于 EnderOnline 核心逻辑层。
 *
 * 职责：玩家侧联机业务的门面，涵盖房间生命周期、Scaffolding 通信、玩家名册与房间配置持久化。
 *
 * NOTE: 本文件是上帝类，正在按 P3 计划拆分，拆分目标见类注释中的拆分计划条目。
 */
package com.multiplayer.ender.network;

import com.endercore.core.comm.CoreComm;
import com.endercore.core.comm.EnderExecutors;
import com.endercore.core.comm.client.CoreWebSocketClient;
import com.endercore.core.comm.config.CoreWebSocketConfig;
import com.endercore.core.comm.protocol.CoreResponse;
import com.endercore.core.comm.server.CoreRequest;
import com.endercore.core.comm.server.CoreWebSocketServer;
import com.endercore.core.easytier.EasyTierManager;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;


import com.multiplayer.ender.logic.LanDiscovery;
import com.multiplayer.ender.logic.PortAllocator;

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
 * 2. 状态字段的可变性没有统一策略：一部分是 volatile，还有相当一部分（currentState、currentRoom、
 *    lastError、dynamicPort）既非 volatile 也无锁。跨线程读写这些字段存在可见性延迟，
 *    不要把 getCurrentState 的返回值当作可靠的实时状态。
 *    房间管理状态已迁至 RoomStateStore，由该类自行加锁保护。
 * 3. lastError 存的是面向开发者的原始异常消息，不得直接展示在 UI 上；UI 只显示错误码对应的
 *    本地化文案（ADR-07）。
 * 4. 端口存在三个互不相同的默认值：Scaffolding 服务端 13448、被托管的 MC 端口 25565、
 *    getPort 在未设置动态端口时返回 25566。它们服务于不同用途，改动前必须确认调用方。
 *
 * 线程安全性：本类不是线程安全的。静态可变字段的写入分散在 ForkJoinPool 线程（joinRoom /
 * startHosting 的异步体）、Scaffolding 调度线程与网络回调线程上，彼此之间没有统一同步。
 * 读多写少的展示类字段（currentState、currentRoom、lastError）尤其容易出现陈旧值。
 *
 * TODO(P3, 2026-10-06): 拆分见 docs/06-logic-and-code-quality.md §3.1——计划拆为 6 个类：
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
     * 玩家名册。
     *
     * 名册本身、最后活跃时间与过期清理都归 ProfileRegistry 所有；本类只负责在合适的时机
     * 调用它，不再直接操作资料列表。
     */
    private static final ProfileRegistry profileRegistry =
            new ProfileRegistry(LOCAL_MACHINE_ID, VENDOR);



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
    static final Logger LOGGER = LoggerFactory.getLogger(EnderApiClient.class);

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
     * 获取当前客户端状态。
     *
     * @return 当前状态枚举值，永不为 null
     */
    public static State getCurrentState() {
        return currentState;
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
        return RoomStateStore.get().snapshot();
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
        RoomStateStore.get().applyLocalSettings(allowCheats, visitorPermission);
    }

    /**
     * 异步获取房间管理状态的 JSON 字符串。
     *
     * @return 已完成、结果为当前状态序列化的 Future，永不为 null
     */
    public static CompletableFuture<String> getRoomManagementState() {
        return CompletableFuture.completedFuture(RoomStateStore.get().toJson());
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
        // 备注变化时重启局域网广播：这是联机行为，不属于状态存储的职责，
        // 因此由这里根据存储层的返回值决定，而不是让存储层反向调用 LanDiscovery。
        boolean remarkChanged = RoomStateStore.get().applyUpdate(stateJson);
        if (remarkChanged && currentState == State.HOSTING && hasDynamicPort()) {
            String remark = RoomStateStore.get().snapshot().has("room_remark")
                    ? RoomStateStore.get().snapshot().get("room_remark").getAsString()
                    : "";
            String broadcast = remark.isEmpty() ? "Ender Online Room" : remark;
            LanDiscovery.startBroadcaster(getPort(), broadcast);
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
        RoomStateStore.get().appendLog(entry);
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
        // 复用 setIdle 的清理序列，仅额外清掉动态端口（因此局域网广播会随之中止）
        setIdle();
        clearDynamicPort();
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
        profileRegistry.reset();
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
        JsonObject json = RoomStateJson.render(
                currentState.name(), currentRoom, lastError, profileRegistry.toProfilesJson());
        return CompletableFuture.completedFuture(json.toString());
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

                JsonObject stateSnapshot = RoomStateStore.get().snapshot();
                if (stateSnapshot.has("log_level")) {
                    cfg.logLevel = stateSnapshot.get("log_level").getAsString();
                }

                manager.start(cfg);


                Thread.sleep(1000);


                if (hasDynamicPort()) {
                     String remark = stateSnapshot.has("room_remark") ? stateSnapshot.get("room_remark").getAsString() : "";
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
        profileRegistry.reset();
        hostedMcPort = mcPort;
        scaffoldingPort = PortAllocator.pickAvailablePort(DEFAULT_SCAFFOLDING_PORT, DEFAULT_SCAFFOLDING_PORT);
        profileRegistry.upsertHost(hostName);
        CoreWebSocketServer server = CoreComm.newServer(new InetSocketAddress("0.0.0.0", scaffoldingPort), 4 * 1024 * 1024, null);
        server.register(ScaffoldingProtocols.PING, EnderApiClient::handlePing);
        server.register(ScaffoldingProtocols.PROTOCOLS, EnderApiClient::handleProtocols);
        server.register(ScaffoldingProtocols.SERVER_PORT, EnderApiClient::handleServerPort);
        server.register(ScaffoldingProtocols.PLAYER_PING, EnderApiClient::handlePlayerPing);
        server.register(ScaffoldingProtocols.PLAYER_PROFILES_LIST, EnderApiClient::handlePlayerProfilesList);
        server.register(ScaffoldingProtocols.ROOM_STATE_SYNC, EnderApiClient::handleRoomStateSync);
        server.start();
        server.awaitStarted(Duration.ofSeconds(3));
        scaffoldingServer = server;
        profileScheduler = Executors.newSingleThreadScheduledExecutor(
                EnderExecutors.daemonFactory("Ender-Scaffolding-Profiles"));
        profileScheduler.scheduleWithFixedDelay(profileRegistry::pruneStaleGuests, 5, 5, TimeUnit.SECONDS);
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
        scaffoldingClientScheduler = Executors.newSingleThreadScheduledExecutor(
                EnderExecutors.daemonFactory("Ender-Scaffolding-Client"));
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


        // 数据来源留在本类：名册只负责合并，不反向依赖 EasyTier。
        profileRegistry.refreshFromPeerHostnames(
                EasyTierManager.getInstance().getPeerHostnames(), SCAFFOLDING_PREFIX);


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
            client.sendSync(ScaffoldingProtocols.PLAYER_PING,
                    ScaffoldingMessages.encodePlayerPing(LOCAL_MACHINE_ID, localPlayerName, VENDOR),
                    Duration.ofSeconds(10));
            CoreResponse resp = client.sendSync(
                    ScaffoldingProtocols.PLAYER_PROFILES_LIST, new byte[0], Duration.ofSeconds(10));
            if (!resp.isOk()) {
                LOGGER.warn("Failed to fetch profiles: status={}", resp.status());
                return;
            }
            JsonArray array = ScaffoldingMessages.decodeProfiles(resp.payload());
            if (array != null) {
                profileRegistry.replaceAllFromArray(array);
            }


            CoreResponse stateResp = client.sendSync(
                    ScaffoldingProtocols.ROOM_STATE_SYNC, new byte[0], Duration.ofSeconds(10));
            if (stateResp.isOk()) {
                String stateJson = new String(stateResp.payload(), StandardCharsets.UTF_8);
                updateRoomManagementState(stateJson);
            } else {
                LOGGER.warn("Failed to sync room state: status={}", stateResp.status());
            }


            CoreResponse portResp = client.sendSync(
                    ScaffoldingProtocols.SERVER_PORT, new byte[0], Duration.ofSeconds(10));
            if (portResp.isOk()) {
                int decodedPort = ScaffoldingMessages.decodePort(portResp.payload());
                if (decodedPort >= 0) {
                    remoteMcPort = decodedPort;
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error polling scaffolding server", e);
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
     * 扫描 EasyTier 的对等节点，定位房主地址。
     *
     * 命中规则是主机名以 SCAFFOLDING_PREFIX 开头，其后缀即房主 Scaffolding 端口。
     * 命中即记录 hostSeen；缺少 IP 时记录 ipMissing 并继续尝试其它节点。
     *
     * @return 扫描结果，永不为 null；未找到房主时 address 为 null
     */
    private static ScanResult scanForScaffoldingRemote() {
        Map<String, String> hostnames = EasyTierManager.getInstance().getPeerHostnames();
        Map<String, String> ips = EasyTierManager.getInstance().getPeerIps();
        ScanResult result = ScaffoldingHostScanner.scan(hostnames, ips, SCAFFOLDING_PREFIX);
        if (result.hostSeen) {
            LOGGER.info("Found scaffolding host, address={}", result.address);
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
        byte[] payload = ScaffoldingProtocols.supportedPayloadUtf8();
        return new CoreResponse(0, req.requestId(), req.kind(), payload);
    }

    /**
     * 处理 c:room_state_sync 请求。
     *
     * 序列化由 RoomStateStore 在其锁内完成，保证返回的是自洽的状态快照。
     *
     * @param req 核心请求对象，不能为 null
     * @return 状态码为 0、负载为房间状态 JSON 的响应对象，永不为 null
     */
    private static CoreResponse handleRoomStateSync(CoreRequest req) {
        String json = RoomStateStore.get().toJson();
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
        return new CoreResponse(0, req.requestId(), req.kind(),
                ScaffoldingMessages.encodePort(hostedMcPort));
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
            ScaffoldingMessages.PlayerPing ping = ScaffoldingMessages.decodePlayerPing(req.payload());
            if (ping == null || ping.isIncomplete()) {
                return new CoreResponse(1, req.requestId(), req.kind(), new byte[0]);
            }
            String machineId = ping.machineId();
            String name = ping.name();
            String vendor = ping.vendor();
            if (machineId.equals(LOCAL_MACHINE_ID)) {
                profileRegistry.upsertHost(name);
                return new CoreResponse(0, req.requestId(), req.kind(), new byte[0]);
            }
            profileRegistry.upsertGuest(machineId, name, vendor);
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
        JsonArray array = profileRegistry.toProfilesJson();
        byte[] payload = array.toString().getBytes(StandardCharsets.UTF_8);
        return new CoreResponse(0, req.requestId(), req.kind(), payload);
    }

}
