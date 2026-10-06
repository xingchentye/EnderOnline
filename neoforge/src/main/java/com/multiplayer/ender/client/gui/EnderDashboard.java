/*
 * 本文件属于 EnderOnline NeoForge 适配层。
 *
 * 职责：末影联机中心（仪表盘），把后端状态、房间管理、玩家列表与各项设置集中在一个界面里。
 *
 * 关键约束：本文件已超出守卫的行数上限，是 P3 拆分前的过渡态——
 * 新增功能不要继续往这里堆，改动前先确认是否属于 §8 已规划的某个独立页面。
 */
package com.multiplayer.ender.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.multiplayer.ender.Config;
import com.multiplayer.ender.client.ClientSetup;
import com.multiplayer.ender.logic.ProcessLauncher;
import com.multiplayer.ender.network.EnderApiClient;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.AbstractLayout;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.EditGameRulesScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.border.WorldBorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 末影联机中心（仪表盘）。
 *
 * 这是本模组的主要界面：闲置态提供「加入房间 / 设置」，已连接态按房主/访客分叉，
 * 房主额外得到一个六页签的房间管理面板（概览 / 权限与访客 / 规则与玩法 / 世界与边界 / 网络与容灾 / 后端与性能）。
 * 从主菜单、多人菜单或暂停菜单各处跳入都能落到这里。
 *
 * 设计约束：
 * 1. 本类是**上帝类**：约 60 个方法、1700 余行，同时承担视图、状态缓存、后端轮询、
 *    房间管理状态的合并与推送，以及世界规则应用。它是 P3 拆分的首要目标。
 * 2. P3 拆分方案（见 claude_docs/04-uiux-plan.md §8）：拆成 5 个页面 + 5 个 VM——
 *    DashboardScreen 只留骨架/导航/权限门禁，页面为 OverviewPage、PlayersPage、PermissionsPage、
 *    WorldPage、NetworkPage，对应 OverviewVM / PlayersVM / PermissionsVM / WorldVM / NetworkVM；
 *    Screen 内不得再直接调用 EnderApiClient 静态方法，一律经 VM。
 * 3. 内部反射回退链（大量 getMethod/getField/getMethods 探测）属于 ADR-03 的待消除目标：
 *    它们源自历史上跨 Yarn/Mojmap 两套映射的兼容需求，Fabric 终止后映射已统一，应全部删除。
 * 4. 静态可变状态（{@code lastClipboard}、{@code wasConnected}、{@code lastStateJson}）违反 ADR-05 的
 *    「可变状态归实例」，是第 1 条拆分的直接原因；本次只补注释，不改行为。
 * 5. 布局混用两套：房间管理面板走 LinearLayout + 自适应尺寸（{@code percentWidth}/{@code adaptive*}），
 *    访客玩家列表与部分行则按像素坐标手排；改版式时不要假定只有一种机制。
 *
 * TODO(P3, 2026-09-30): 按 claude_docs/04-uiux-plan.md §8 拆分本上帝类，并删除内部反射回退链（ADR-03）
 *
 * 线程安全性：UI 字段只在客户端主线程读写；后端轮询与房间管理状态的异步回调通过
 * {@code minecraft.execute} 回到主线程后才触碰控件。静态状态跨实例共享，无同步保护。
 *
 * @since 1.0
 * @see EnderBaseScreen
 * @see EnderApiClient
 * @see ClientSetup
 */
public class EnderDashboard extends EnderBaseScreen {
    /** 本类日志记录器，非 null。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(EnderDashboard.class);

    /** 后端状态的显示文本，非 null，默认取语言键 {@code ender.dashboard.status.fetching}。 */
    private String backendState = Component.translatable("ender.dashboard.status.fetching").getString();

    /** 上次状态轮询时间戳，单位毫秒（System.currentTimeMillis）。 */
    private long lastStateCheck = 0;

    /** JSON 解析器，非 null，复用同一实例。 */
    private static final Gson GSON = new Gson();

    /** 上次从剪贴板读取过的房间码，非 null，空串表示尚未读取；用于避免重复自动加入。 */
    private static String lastClipboard = "";

    /** 静态的「已连接」标志，跨仪表盘实例共享；用于剪贴板自动加入的去重。 */
    private static boolean wasConnected = false;

    /** 本实例 UI 上呈现的连接态；与静态 {@code wasConnected} 配合判断是否需要重建控件。 */
    private boolean isUiConnected = false;

    /** 最近一次解析到的后端状态；允许为 null，为 null 表示未连接或尚未拉取。 */
    private static JsonObject lastStateJson = null;

    /** 是否显示玩家列表区域，默认 true。 */
    private boolean showPlayerList = true;

    /** 是否显示服务器设置区域，默认 false。 */
    private boolean showServerSettings = false;

    /** 外部核心路径的临时副本，构造时从配置读入，非 null。 */
    private String tempPath;

    /** 自动更新开关的临时副本，构造时从配置读入。 */
    private boolean tempAutoUpdate;

    /** 自动启动开关的临时副本，构造时从配置读入。 */
    private boolean tempAutoStart;

    /** 上次房间管理状态同步时间戳，单位毫秒，用于 500 毫秒节流。 */
    private long lastRoomSync = 0;

    /** 上次连通性探测时间戳，单位毫秒。 */
    private long lastPingCheck = 0;

    /** 最近一次探测耗时，单位毫秒；-1 表示尚无有效样本。 */
    private int lastPingMs = -1;

    /** 网络质量标签对应的颜色，RGB 值，默认绿色。 */
    private int networkQualityColor = 0x00FF00;

    /** 网络质量标签，非 null，取值为优秀/良好/一般/较差。 */
    private String networkQualityLabel = "良好";

    /** 房间名，非 null，默认「未命名房间」。 */
    private String roomName = "未命名房间";

    /** 房间描述（MOTD），非 null，默认空串；会同步为集成服务器的 MOTD。 */
    private String roomRemark = "";

    /** 当前玩家数。 */
    private int currentPlayers = 0;

    /** 房间人数上限。 */
    private int maxPlayers = 0;

    /** 访客权限，非 null，取值为可交互/仅聊天/仅观战/禁止进入。 */
    private String visitorPermission = "可交互";

    /** 是否启用白名单。 */
    private boolean whitelistEnabled = false;

    /** 白名单数组，非 null，元素为玩家名字符串。 */
    private JsonArray whitelist = new JsonArray();

    /** 黑名单数组，非 null，元素为玩家名字符串。 */
    private JsonArray blacklist = new JsonArray();

    /** 禁言列表数组，非 null，元素为玩家名字符串。 */
    private JsonArray muteList = new JsonArray();

    /** 操作日志数组，非 null；后端页只展示最后 5 条。 */
    private JsonArray operationLogs = new JsonArray();

    /** 是否允许所有玩家使用作弊指令。 */
    private boolean allowCheats = false;

    /** 出生点保护半径，单位为方块，默认 16。 */
    private int spawnProtection = 16;

    /** 是否保留物品栏。 */
    private boolean keepInventory = false;

    /** 火势是否蔓延。 */
    private boolean fireSpread = true;

    /** 是否允许怪物生成。 */
    private boolean mobSpawning = true;

    /** 时间锁模式，非 null，取值为 cycle（随昼夜流转）或 night/fixed（锁定）。 */
    private String timeControl = "cycle";

    /** 是否锁定天气（true 表示关闭天气循环）。 */
    private boolean weatherLock = false;

    /** 重生点 X 坐标，单位为方块。 */
    private int respawnX = 0;

    /** 重生点 Y 坐标，单位为方块。 */
    private int respawnY = 0;

    /** 重生点 Z 坐标，单位为方块。 */
    private int respawnZ = 0;

    /** 世界边界中心 X 坐标，单位为方块。 */
    private int worldBorderCenterX = 0;

    /** 世界边界中心 Z 坐标，单位为方块。 */
    private int worldBorderCenterZ = 0;

    /** 世界边界半径，单位为方块；0 表示不应用边界。 */
    private int worldBorderRadius = 0;

    /** 是否启用自动重连。 */
    private boolean autoReconnect = true;

    /** 自动重连的最大重试次数，默认 3。 */
    private int reconnectRetries = 3;

    /** 是否启用房主迁移。 */
    private boolean hostMigration = false;

    /** 后端版本标识，非 null，默认「当前」。 */
    private String backendVersion = "当前";

    /** 更新策略，非 null，取值为立即/延后。 */
    private String updatePolicy = "立即";

    /** 后端日志级别，非 null，取值为 INFO/WARN/DEBUG。 */
    private String logLevel = "INFO";

    /** CPU 限制，取值 >= 0；0 表示不限制。 */
    private int cpuLimit = 0;

    /** 内存限制，单位为 MB，取值 >= 0；0 表示不限制。 */
    private int memoryLimit = 0;

    /** 性能采样序列，非 null；当前仅收集，未参与渲染。 */
    private final List<Integer> performanceSamples = new ArrayList<>();

    /** 可选后端版本列表，非 null，元素为版本字符串；后端页的版本按钮据此循环切换。 */
    private JsonArray backendVersions = new JsonArray();

    /** 房间管理状态是否有未推送的本地改动；为真时下一轮同步会先上行推送而不是拉取。 */
    private boolean roomStateDirty = false;

    /** 上次向后端推送房间管理状态的时间戳，单位毫秒，用于避免频繁拉取。 */
    private long lastPushTime = 0;

    /** 房间管理页当前注册的控件集合，非 null；切页时先移除这些控件再重建。 */
    private final List<AbstractWidget> roomPageWidgets = new ArrayList<>();

    /** 房间管理页的页签按钮，非 null，顺序与 {@link RoomPage#values()} 一致。 */
    private final List<Button> roomPageButtons = new ArrayList<>();

    /** 房间管理页的内容面板；在 {@link #initRoomManagementContent()} 中创建，之前为 null。 */
    private RoomPagePanel roomPagePanel;

    /** 访客视角的玩家列表控件；允许为 null，为 null 时无列表。 */
    private PlayerListScrollWidget playerListWidget;

    /**
     * 仪表盘的展示模式。
     *
     * 三种模式共用同一套内容构建逻辑，仅影响页脚按钮与顶部状态行的呈现：
     * FULL 从主菜单进入，显示完整仪表盘；
     * INGAME_INFO 由暂停菜单以访客身份跳入，只读；
     * INGAME_SETTINGS 由暂停菜单以房主身份跳入，可改房间设置。
     */
    public enum ViewMode {
        /** 完整模式，从主菜单/多人菜单进入，含底部「断开连接」。 */
        FULL,
        /** 游戏内只读模式，从暂停菜单以访客身份进入。 */
        INGAME_INFO,
        /** 游戏内设置模式，从暂停菜单以房主身份进入。 */
        INGAME_SETTINGS
    }

    /**
     * 房主管理面板的页签。
     *
     * 页签顺序即 {@link #roomPageButtons} 的顺序，也是界面上从左到右的顺序；
     * 每个页签对应一个构建方法：OVERVIEW 到 {@code addOverviewPage}，
     * PERMISSIONS 到 {@code addPermissionsPage}，RULES 到 {@code addRulesPage}，
     * WORLD 到 {@code addWorldPage}，NETWORK 到 {@code addNetworkPage}，
     * BACKEND 到 {@code addBackendPage}。默认停在 OVERVIEW，切换时不重置已解析的状态。
     */
    public enum RoomPage {
        /** 房间概览：房间号、玩家数、网络质量、房间描述与复制/关闭入口。 */
        OVERVIEW,
        /** 权限与访客：访客权限、白名单开关、详细名单管理入口。 */
        PERMISSIONS,
        /** 规则与玩法：作弊、保留物品、PVP、天气锁定与原生规则界面入口。 */
        RULES,
        /** 世界与边界：重生点与世界边界坐标。 */
        WORLD,
        /** 网络与容灾：自动重连与重试次数。 */
        NETWORK,
        /** 后端与性能：版本、更新策略、日志级别、CPU/内存限制与导入导出。 */
        BACKEND
    }

    /** 当前展示模式，非 null，默认 FULL。 */
    private ViewMode currentMode = ViewMode.FULL;

    /** 当前选中的房间管理页签，非 null，默认 OVERVIEW。 */
    private RoomPage currentRoomPage = RoomPage.OVERVIEW;

    /**
     * 构造仪表盘，使用完整模式。
     *
     * @param parent 父屏幕，用于返回，允许为 null
     */
    public EnderDashboard(Screen parent) {
        this(parent, ViewMode.FULL);
    }

    /**
     * 构造仪表盘。
     *
     * 构造时把三项客户端配置读入临时副本，之后界面不再回读配置。
     *
     * @param parent 父屏幕，用于返回，允许为 null
     * @param mode 展示模式，不能为 null
     */
    public EnderDashboard(Screen parent, ViewMode mode) {
        super(Component.literal("末影联机中心"), parent);
        this.currentMode = mode;
        this.tempPath = Config.EXTERNAL_ender_PATH.get();
        this.tempAutoUpdate = Config.AUTO_UPDATE.get();
        this.tempAutoStart = Config.AUTO_START_BACKEND.get();
    }

    /**
     * 标记连接状态。
     *
     * 供 {@code HostScreen} 在托管成功后回调，避免本界面仍停留在闲置态。
     * 注意它写的是静态字段，会影响同一进程内其它仪表盘实例。
     *
     * @param connected 是否已连接
     */
    public void setConnected(boolean connected) {
        wasConnected = connected;
    }

    /**
     * 检查剪贴板并自动跳转加入流程。
     *
     * 已连接时直接返回；否则读取剪贴板，仅在内容匹配联机码格式
     * （{@code U/XXXX-XXXX-XXXX-XXXX}，大写字母数字）且与上次处理过的值不同时，
     * 跳到 {@link JoinScreen} 并带上该码。
     *
     * 幂等性：由静态 {@code lastClipboard} 去重，同一段剪贴板内容只触发一次跳转。
     */
    private void checkClipboardAndAutoJoin() {
        if (wasConnected) return;
        try {
            String clipboard = this.minecraft.keyboardHandler.getClipboard();
            if (clipboard != null) {
                clipboard = clipboard.trim();
                if (!clipboard.isEmpty() && !clipboard.equals(lastClipboard)) {
                    
                    if (clipboard.matches("^U/[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$")) {
                        lastClipboard = clipboard;
                        String finalClipboard = clipboard;
                        this.minecraft.execute(() -> {
                             this.minecraft.setScreen(new JoinScreen(this, finalClipboard));
                        });
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 初始化界面内容。
     *
     * 先用后端状态机与缓存的状态 JSON 校准「是否已连接」，必要时补一次立即查询，
     * 然后按结果分叉：已连接走房主/访客两条内容构建路径，否则构建闲置界面。
     *
     * 实现约束：本方法会被反复调用（状态跳变、窗口缩放），因此必须幂等——
     * 只重建控件，不得在此启动新的轮询或产生其它副作用。
     */
    @Override
    protected void initContent() {
        // 顶部信息区：用状态机与缓存校准连接态，避免缓存与真实状态不符
        EnderApiClient.State realState = EnderApiClient.getCurrentState();
        if (realState == EnderApiClient.State.IDLE) {
            wasConnected = false;
            lastStateJson = null;
        } else if (realState == EnderApiClient.State.HOSTING || realState == EnderApiClient.State.JOINING) {
            wasConnected = true;
            
            if (lastStateJson != null && lastStateJson.has("state")) {
                String cachedState = lastStateJson.get("state").getAsString();
                boolean cachedIsHost = "host-ok".equals(cachedState);
                boolean realIsHost = (realState == EnderApiClient.State.HOSTING);
                if (cachedIsHost != realIsHost) {
                    lastStateJson = null;
                }
            } else {
                lastStateJson = null;
            }
        }

        // 玩家列表在每次重建时先丢弃旧实例，随后由各自的内容构建路径重新创建
        this.playerListWidget = null;
        if (lastStateJson != null) {
            if (lastStateJson.has("state")) {
                String s = lastStateJson.get("state").getAsString();
                boolean connected = "host-ok".equals(s) || "guest-ok".equals(s);
                if (wasConnected != connected) {
                    wasConnected = connected;
                }
            } else if (lastStateJson.has("status") && "IDLE".equals(lastStateJson.get("status").getAsString())) {
                wasConnected = false;
            }
        }

        // 缓存缺失或状态存疑时补一次立即查询，避免首帧显示错误的分支
        if (!wasConnected || (wasConnected && lastStateJson == null)) {
             checkStateImmediately();
        }

        // 内容区分叉：房主/访客已连接内容，或闲置入口
        if (wasConnected) {
            initConnectedContent();
        } else {
            initIdleContent();
        }
    }

    /**
     * 立即查询一次后端状态并按需重建界面。
     *
     * 仅在状态跳变（与静态标志或本实例 UI 标志不一致）或首次拿到已连接状态时重建控件，
     * 避免每次轮询都做一次昂贵的全量重建。
     *
     * 设计约束：解析失败只记警告，不清空已有状态——旧状态比空状态更有用。
     */
    private void checkStateImmediately() {
        if (!EnderApiClient.hasDynamicPort()) return;

        EnderApiClient.getState().thenAccept(stateJson -> {
            if (stateJson != null) {
                try {
                    JsonObject json = GSON.fromJson(stateJson, JsonObject.class);
                    if (json.has("state")) {
                        String state = json.get("state").getAsString();
                        boolean isConnected = "host-ok".equals(state) || "guest-ok".equals(state);

                        if (wasConnected != isConnected || this.isUiConnected != isConnected) {
                            wasConnected = isConnected;
                            lastStateJson = json;
                            if (this.minecraft != null) this.minecraft.execute(this::rebuildWidgets);
                        } else if (isConnected && lastStateJson == null) {
                            lastStateJson = json;
                            if (this.minecraft != null) this.minecraft.execute(this::rebuildWidgets);
                        }
                    } else if (json.has("status") && "IDLE".equals(json.get("status").getAsString())) {
                        if (wasConnected) {
                            wasConnected = false;
                            this.isUiConnected = false;
                            lastStateJson = null;
                            if (this.minecraft != null) this.minecraft.execute(this::rebuildWidgets);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("failed to parse backend state json", e);
                }
            }
        });
    }

    /**
     * 用外部拿到的状态对象刷新界面。
     *
     * 供 {@link JoinScreen} 在加入成功后直接把状态推入，省掉一次轮询。
     *
     * @param json 后端状态对象，为 null 时直接返回，不做任何刷新
     */
    public void updateFromState(JsonObject json) {
        if (json == null) return;
        lastStateJson = json;
        if (json.has("state")) {
            String state = json.get("state").getAsString();
            boolean isConnected = "host-ok".equals(state) || "guest-ok".equals(state);
            wasConnected = isConnected;
            this.isUiConnected = isConnected;
        }
        this.rebuildWidgets();
    }

    /**
     * 构建已连接状态的内容区。
     *
     * 按状态分三种落点：正在启动时只显示一行「请求中」；访客交给
     * {@link #initGuestConnectedContent()}；房主交给 {@link #initRoomManagementContent()}，
     * 并在页脚放「断开连接」（完整模式）或「返回」（游戏内模式）。
     */
    private void initConnectedContent() {
        this.isUiConnected = true;

        boolean isHost = false;
        boolean isStarting = false;
        
        if (lastStateJson != null) {
            if (lastStateJson.has("state")) {
                String state = lastStateJson.get("state").getAsString();
                isHost = "host-ok".equals(state);
                isStarting = "host-starting".equals(state) || "guest-starting".equals(state);
            }
        } else {
            EnderApiClient.State state = EnderApiClient.getCurrentState();
            isHost = (state == EnderApiClient.State.HOSTING);
            isStarting = (state == EnderApiClient.State.HOSTING_STARTING || state == EnderApiClient.State.JOINING_STARTING);
        }
        
        // 启动中：内容区只放一行状态提示，不铺任何管理面板
        if (isStarting) {
            LinearLayout loadingLayout = LinearLayout.vertical().spacing(10);
            loadingLayout.defaultCellSetting().alignHorizontallyCenter();
            loadingLayout.addChild(new StringWidget(Component.translatable("ender.host.status.requesting"), this.font));
            this.layout.addToContents(loadingLayout);
            return;
        }

        // 访客：只有一个玩家列表与「加入游戏」按钮，没有房主管理面板
        if (!isHost) {
            initGuestConnectedContent();
            return;
        }

        // 房主：左侧页签 + 右侧内容面板
        initRoomManagementContent();
        // 底栏：完整模式给「断开连接」，游戏内模式只给「返回」
        if (currentMode == ViewMode.FULL) {
            this.layout.addToFooter(Button.builder(Component.literal("断开连接"), button -> {
                EnderApiClient.setIdle();
                new Thread(ProcessLauncher::stop, "Ender-Stopper").start();
                wasConnected = false;
                this.isUiConnected = false;
                this.rebuildWidgets();
            }).width(adaptiveButtonWidth()).build());
        } else {
            this.layout.addToFooter(Button.builder(Component.literal("返回"), button -> {
                this.onClose();
            }).width(adaptiveButtonWidth()).build());
        }
    }

    /**
     * 构建访客视角的已连接内容。
     *
     * 底栏是「断开连接 / 返回」；内容区自上而下依次是标题、玩家列表与「加入游戏」按钮。
     * 列表宽度按屏幕宽度的 60% 取，并限制在 200..400 像素之间。
     */
    private void initGuestConnectedContent() {
        // 底栏：断开连接（会同时停止本地核心进程）与返回
        LinearLayout footerLayout = LinearLayout.horizontal().spacing(10);
        
        footerLayout.addChild(Button.builder(Component.literal("断开连接"), b -> {
            EnderApiClient.setIdle();
            new Thread(ProcessLauncher::stop, "Ender-Stopper").start();
            wasConnected = false;
            this.isUiConnected = false;
            this.onClose();
        }).width(adaptiveSmallButtonWidth()).build());
        
        footerLayout.addChild(Button.builder(Component.literal("返回"), b -> this.onClose()).width(adaptiveSmallButtonWidth()).build());
        
        this.layout.addToFooter(footerLayout);

        // 顶部标题区：居中显示「房间玩家列表」
        int spacing = adaptiveSpacing();
        int headerHeight = this.layout.getHeaderHeight() + spacing;
        int footerHeight = this.layout.getFooterHeight() + spacing;
        
        int titleY = headerHeight;
        StringWidget title = new StringWidget(0, titleY, this.width, this.font.lineHeight + 4, Component.literal("房间玩家列表"), this.font);
        title.alignCenter();
        this.addRenderableWidget(title);
        
        // 中部玩家列表：宽度取屏幕 60% 并夹在 200..400 之间，水平居中
        int listTop = titleY + spacing * 2;
        int listBottom = this.height - footerHeight - spacing * 3;
        int listWidth = Math.min(400, Math.max(200, percentWidth(60)));
        int listX = (this.width - listWidth) / 2;
        
        PlayerListScrollWidget list = new PlayerListScrollWidget(this.minecraft, listWidth, listBottom - listTop, listTop, listBottom);
        list.updateWidgetSize(listWidth, listBottom - listTop, listTop, listBottom, listX);
        list.updateEntries(lastStateJson);
        this.playerListWidget = list;
        this.addRenderableWidget(list);

        // 列表下方：「加入游戏」按钮，用后端下发的远端地址直连
        int buttonWidth = adaptiveButtonWidth();
        Button joinGameBtn = Button.builder(Component.literal("加入游戏"), b -> {
            String ip = EnderApiClient.getHostIp();
            if (ip != null) {
                int port = EnderApiClient.getRemoteMcPort();
                ServerData serverData = new ServerData("Ender Room", ip + ":" + port, ServerData.Type.OTHER);
                ConnectScreen.startConnecting(this, this.minecraft, ServerAddress.parseString(serverData.ip), serverData, false, null);
            }
        }).width(buttonWidth).build();
        joinGameBtn.setPosition((this.width - buttonWidth) / 2, listBottom + spacing);
        this.addRenderableWidget(joinGameBtn);
    }

    /**
     * 把玩家列表以纯文本形式追加到指定布局。
     *
     * 优先读 {@code profiles}（可标出 [房主]），退回读 {@code players} 的字符串元素。
     * NOTE: 当前无调用点，保留为拆分前的备用渲染路径。
     *
     * @param layout 目标布局，不能为 null
     */
    private void addPlayerListToLayout(LinearLayout layout) {
        if (lastStateJson != null) {
            if (lastStateJson.has("profiles")) {
                try {
                    JsonArray profiles = lastStateJson.getAsJsonArray("profiles");
                    if (profiles.size() > 0) {
                        layout.addChild(new StringWidget(Component.literal(" 当前玩家 (" + profiles.size() + ") "), this.font));
                        for (JsonElement p : profiles) {
                            JsonObject profile = p.getAsJsonObject();
                            String name = profile.get("name").getAsString();
                            String kind = profile.has("kind") ? profile.get("kind").getAsString() : "";
                            String display = name;
                            if ("HOST".equals(kind)) {
                                display += " [房主]";
                            }
                            layout.addChild(new StringWidget(Component.literal(display), this.font));
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("failed to parse backend state json", e);
                }
            } else if (lastStateJson.has("players")) {
                try {
                    var players = lastStateJson.getAsJsonArray("players");
                    if (players.size() > 0) {
                        layout.addChild(new StringWidget(Component.literal(" 当前玩家 (" + players.size() + ") "), this.font));
                        for (var p : players) {
                            String pName = p.getAsString();
                            layout.addChild(new StringWidget(Component.literal(pName), this.font));
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("failed to parse backend state json", e);
                }
            }
        }
    }

    /**
     * 把临时副本写回配置并持久化到 {@code ender.toml}。
     *
     * 会把外部核心路径、自动更新与自动启动三项一起写入并 save()。
     * NOTE: 本方法当前无调用点——仪表盘本身不提供这三项的编辑入口，入口在 {@link EnderConfigScreen}。
     */
    private void saveConfig() {
        Config.EXTERNAL_ender_PATH.set(this.tempPath);
        Config.AUTO_UPDATE.set(this.tempAutoUpdate);
        Config.AUTO_START_BACKEND.set(this.tempAutoStart);
        Config.CLIENT_SPEC.save();
    }

    /**
     * 按 {@code host:port} 直接连接服务器。
     *
     * 只解析第一段冒号，缺端口时回落 25565；格式非法时静默放弃（不报错、不跳转）。
     * NOTE: 当前无调用点，保留为拆分前的备用路径。
     *
     * @param connectUrl 连接串，格式为 host 或 host:port，不能为 null
     */
    private void connectToServer(String connectUrl) {
         try {
             String[] parts = connectUrl.split(":");
             String host = parts[0];
             int port = 25565;
             if (parts.length > 1) {
                 port = Integer.parseInt(parts[1]);
             }
             ServerAddress serverAddress = new ServerAddress(host, port);
             ConnectScreen.startConnecting(this.parent, this.minecraft, serverAddress, new ServerData("Ender Server", connectUrl, ServerData.Type.OTHER), false, null);
         } catch (Exception e) {
         }
    }

    /**
     * 构建闲置态内容。
     *
     * 内容区是「加入房间 / 设置」两个按钮，底栏是「退出」。
     * 「加入房间」在后端已就绪时直接进 {@link JoinScreen}，否则先落到 {@link StartupScreen}。
     */
    private void initIdleContent() {
        this.isUiConnected = false;
        // 内容区：加入房间与设置
        LinearLayout contentLayout = LinearLayout.vertical().spacing(adaptiveSpacing() * 2);

        contentLayout.addChild(Button.builder(Component.literal("加入房间"), button -> {
            if (EnderApiClient.hasDynamicPort()) {
                this.minecraft.setScreen(new JoinScreen(this));
            } else {
                this.minecraft.setScreen(new StartupScreen(this.parent));
            }
        }).width(adaptiveButtonWidth()).build());

        contentLayout.addChild(Button.builder(Component.literal("设置"), button -> {
            this.minecraft.setScreen(new EnderConfigScreen(this));
        }).width(adaptiveButtonWidth()).build());

        this.layout.addToContents(contentLayout);

        // 底栏：退出
        this.layout.addToFooter(Button.builder(Component.literal("退出"), button -> {
            this.onClose();
        }).width(adaptiveButtonWidth()).build());
    }

    /**
     * 每帧更新。
     *
     * 两条独立的节流链路：
     * 1. 每 1 秒轮询一次后端状态（{@link #updateBackendState}）；无动态端口时显示「未启动」。
     * 2. 房主已连接时，每 500 毫秒做一轮房间管理状态同步：有本地改动就上行推送并立刻应用到世界，
     *    无改动且距上次推送超过 2 秒才下行拉取一次，避免与本地编辑互相覆盖。
     */
    @Override
    public void tick() {
        super.tick();
        
        // 状态轮询：1 秒节流
        long now = System.currentTimeMillis();
        if (now - lastStateCheck > 1000) {
            lastStateCheck = now;
            
            if (EnderApiClient.hasDynamicPort()) {
                long startedAt = System.currentTimeMillis();
                EnderApiClient.getState().thenAccept(stateJson -> updateBackendState(stateJson, startedAt));
            } else {
                this.backendState = Component.translatable("ender.state.not_started").getString();
            }
        }

        // 房间管理状态同步：500 毫秒节流，优先上行，空闲时才下行
        if (isHostConnected()) {
            long roomInterval = 500;
            if (now - lastRoomSync > roomInterval) {
                lastRoomSync = now;
                if (roomStateDirty) {
                    EnderApiClient.updateRoomManagementState(buildRoomManagementStateJson().toString());
                    roomStateDirty = false;
                    lastPushTime = System.currentTimeMillis();
                    applyRoomManagementStateToServer();
                } else if (System.currentTimeMillis() - lastPushTime > 2000) {
                    EnderApiClient.getRoomManagementState().thenAccept(this::updateRoomManagementStateFromString);
                }
            }
        }
    }

    /**
     * 渲染界面。
     *
     * 在原版渲染之上，于头部下方居中绘制一行后端状态（完整模式与游戏内只读模式才有）；
     * 状态仍是「获取中」时直接显示该占位文案，否则加前缀。
     *
     * @param guiGraphics 绘图上下文，不能为 null
     * @param mouseX 鼠标 X 坐标，单位为逻辑像素
     * @param mouseY 鼠标 Y 坐标，单位为逻辑像素
     * @param partialTick 当前帧的部分刻进度，取值 0.0..1.0
     */
    @Override
    public void render(net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        
        if (this.currentMode == ViewMode.FULL || this.currentMode == ViewMode.INGAME_INFO) {
            int textY = this.layout.getHeaderHeight() + 5;
            guiGraphics.drawCenteredString(this.font, Component.translatable("ender.dashboard.status.fetching").getString().equals(this.backendState) ? 
                this.backendState : Component.translatable("ender.dashboard.status_prefix").append(this.backendState).getString(), this.width / 2, textY, 0xAAAAAA);
        }
    }

    /**
     * 解析并应用后端状态。
     *
     * 兼容两种协议形态：新形态的 {@code state} 字段，与旧形态的 {@code status} 枚举（会在此归一化）。
     * 已连接时把状态写入静态缓存并刷新玩家列表；状态跳变时重建控件。同时用本次调用的往返耗时
     * 估算网络质量档位（<=80 优秀 / <=150 良好 / <=250 一般 / 其余较差）。
     *
     * 幂等性：同一状态重复调用只会重写同样的字段；重建控件由状态跳变判定守住。
     *
     * @param stateJson 后端状态 JSON 字符串，允许为 null（为 null 时不做任何事）
     * @param startedAt 本次请求发起时刻，单位毫秒（System.currentTimeMillis），用于估算耗时；负值会被夹到 0
     */
    private void updateBackendState(String stateJson, long startedAt) {
        if (stateJson != null) {
             try {
                    JsonObject json = GSON.fromJson(stateJson, JsonObject.class);
                    String state = "";
                    if (json.has("state")) {
                        state = json.get("state").getAsString();
                    } else if (json.has("status")) {
                        String status = json.get("status").getAsString();
                        if ("IDLE".equals(status)) {
                            state = "idle";
                        } else if ("ERROR".equals(status)) {
                            state = "error";
                        } else if ("HOSTING_STARTING".equals(status)) {
                            state = "host-starting";
                        } else if ("JOINING_STARTING".equals(status)) {
                            state = "guest-starting";
                        } else if ("HOSTING".equals(status)) {
                            state = "host-ok";
                        } else if ("JOINING".equals(status)) {
                            state = "guest-ok";
                        }
                    }
                    boolean isConnected = "host-ok".equals(state) || "guest-ok".equals(state);

                    boolean needsInit = wasConnected != isConnected || this.isUiConnected != isConnected || (isConnected && lastStateJson == null);
                    if (isConnected) {
                        lastStateJson = json;
                        if (this.minecraft != null) {
                            this.minecraft.execute(() -> {
                                if (this.playerListWidget != null) {
                                    this.playerListWidget.updateEntries(lastStateJson);
                                }
                            });
                        }
                    } else {
                        lastStateJson = null;
                    }
                    if (needsInit) {
                        wasConnected = isConnected;
                        if (this.minecraft != null) {
                            this.minecraft.execute(() -> this.init(this.minecraft, this.width, this.height));
                        }
                    }
                    
                    String displayKey = "ender.state.idle";
                    
                    switch (state) {
                        case "idle": displayKey = "ender.state.idle"; break;
                        case "host-starting": displayKey = "ender.state.host_starting"; break;
                        case "host-scanning": displayKey = "ender.state.host_scanning"; break;
                        case "host-ok": displayKey = "ender.state.connected"; break; 
                        case "guest-starting": displayKey = "ender.state.guest_starting"; break;
                        case "guest-connecting": displayKey = "ender.state.guest_connecting"; break;
                        case "guest-ok": displayKey = "ender.state.connected"; break;
                        case "waiting": displayKey = "ender.state.waiting"; break;
                        case "error":
                            if (json.has("error")) {
                                this.backendState = "错误: " + json.get("error").getAsString();
                                displayKey = null;
                            } else {
                                this.backendState = "错误";
                                displayKey = null;
                            }
                            break;
                        default: displayKey = null; break;
                    }
                    
                    if (displayKey != null) {
                        this.backendState = Component.translatable(displayKey).getString();
                    } else if (!"error".equals(state)) {
                        this.backendState = state;
                    }

                    if (isConnected) {
                        long cost = Math.max(0, System.currentTimeMillis() - startedAt);
                        this.lastPingMs = (int) cost;
                        if (cost <= 80) {
                            this.networkQualityLabel = "优秀";
                            this.networkQualityColor = 0x00FF00;
                        } else if (cost <= 150) {
                            this.networkQualityLabel = "良好";
                            this.networkQualityColor = 0x66FF00;
                        } else if (cost <= 250) {
                            this.networkQualityLabel = "一般";
                            this.networkQualityColor = 0xFFFF00;
                        } else {
                            this.networkQualityLabel = "较差";
                            this.networkQualityColor = 0xFF5555;
                        }
                    }
             } catch (Exception ignored) {}
        }
    }

    /**
     * 判断当前是否处于房主已连接状态。
     *
     * @return 状态缓存为 host-ok 时返回 true；缓存为 null 或无 state 字段时返回 false
     */
    private boolean isHostConnected() {
        if (lastStateJson == null) {
            return false;
        }
        if (lastStateJson.has("state")) {
            return "host-ok".equals(lastStateJson.get("state").getAsString());
        }
        return false;
    }

    /**
     * 从字符串解析并合并房间管理状态。
     *
     * 若本地有未推送的改动（{@code roomStateDirty}）则整轮跳过，防止远端旧值覆盖本地正在编辑的值。
     * 合并出变化时会立刻把状态应用到世界并重建控件。
     *
     * @param stateJson 房间管理状态 JSON 字符串，允许为 null（直接返回）
     */
    private void updateRoomManagementStateFromString(String stateJson) {
        if (stateJson == null || roomStateDirty) {
            return;
        }
        try {
            JsonObject json = GSON.fromJson(stateJson, JsonObject.class);
            if (json == null) {
                return;
            }
            boolean changed = mergeRoomManagementState(json);
            if (changed) {
                applyRoomManagementStateToServer();
            }
            if (changed && this.minecraft != null) {
                this.minecraft.execute(() -> this.init(this.minecraft, this.width, this.height));
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 把远端房间管理状态合并进本地字段。
     *
     * 逐字段处理：只在字段存在且取值确有变化时写入并标记 changed。数组类字段（白名单/黑名单/禁言/
     * 操作日志/后端版本列表）只要字段存在就整体替换并标记变化——数组不做逐元素比较。
     *
     * @param json 远端房间管理状态，不能为 null
     * @return 至少有一个字段发生变化时返回 true
     */
    private boolean mergeRoomManagementState(JsonObject json) {
        boolean changed = false;
        if (json.has("room_name")) {
            String v = json.get("room_name").getAsString();
            if (!v.equals(this.roomName)) {
                this.roomName = v;
                changed = true;
            }
        }
        if (json.has("room_remark")) {
            String v = json.get("room_remark").getAsString();
            if (!v.equals(this.roomRemark)) {
                this.roomRemark = v;
                changed = true;
            }
        }
        if (json.has("visitor_permission")) {
            String v = json.get("visitor_permission").getAsString();
            if (!v.equals(this.visitorPermission)) {
                this.visitorPermission = v;
                changed = true;
            }
        }
        if (json.has("whitelist_enabled")) {
            boolean v = json.get("whitelist_enabled").getAsBoolean();
            if (v != this.whitelistEnabled) {
                this.whitelistEnabled = v;
                changed = true;
            }
        }
        if (json.has("whitelist")) {
            JsonArray v = json.getAsJsonArray("whitelist");
            this.whitelist = v == null ? new JsonArray() : v;
            changed = true;
        }
        if (json.has("blacklist")) {
            JsonArray v = json.getAsJsonArray("blacklist");
            this.blacklist = v == null ? new JsonArray() : v;
            changed = true;
        }
        if (json.has("mute_list")) {
            JsonArray v = json.getAsJsonArray("mute_list");
            this.muteList = v == null ? new JsonArray() : v;
            changed = true;
        }
        if (json.has("operation_logs")) {
            JsonArray v = json.getAsJsonArray("operation_logs");
            this.operationLogs = v == null ? new JsonArray() : v;
            changed = true;
        }
        if (json.has("allow_cheats")) {
            this.allowCheats = json.get("allow_cheats").getAsBoolean();
            changed = true;
        }
        if (json.has("allow_pvp")) {
            this.pvpAllowed = json.get("allow_pvp").getAsBoolean();
            changed = true;
        }
        if (json.has("spawn_protection")) {
            this.spawnProtection = json.get("spawn_protection").getAsInt();
            changed = true;
        }
        if (json.has("keep_inventory")) {
            this.keepInventory = json.get("keep_inventory").getAsBoolean();
            changed = true;
        }
        if (json.has("fire_spread")) {
            this.fireSpread = json.get("fire_spread").getAsBoolean();
            changed = true;
        }
        if (json.has("mob_spawning")) {
            this.mobSpawning = json.get("mob_spawning").getAsBoolean();
            changed = true;
        }
        if (json.has("time_lock")) {
            this.timeControl = json.get("time_lock").getAsString();
            changed = true;
        }
        if (json.has("weather_lock")) {
            this.weatherLock = json.get("weather_lock").getAsBoolean();
            changed = true;
        }
        if (json.has("respawn_x")) {
            this.respawnX = json.get("respawn_x").getAsInt();
            changed = true;
        }
        if (json.has("respawn_y")) {
            this.respawnY = json.get("respawn_y").getAsInt();
            changed = true;
        }
        if (json.has("respawn_z")) {
            this.respawnZ = json.get("respawn_z").getAsInt();
            changed = true;
        }
        if (json.has("world_border_center_x")) {
            this.worldBorderCenterX = json.get("world_border_center_x").getAsInt();
            changed = true;
        }
        if (json.has("world_border_center_z")) {
            this.worldBorderCenterZ = json.get("world_border_center_z").getAsInt();
            changed = true;
        }
        if (json.has("world_border_radius")) {
            this.worldBorderRadius = json.get("world_border_radius").getAsInt();
            changed = true;
        }
        if (json.has("auto_reconnect")) {
            this.autoReconnect = json.get("auto_reconnect").getAsBoolean();
            changed = true;
        }
        if (json.has("reconnect_retries")) {
            this.reconnectRetries = json.get("reconnect_retries").getAsInt();
            changed = true;
        }
        if (json.has("host_migration")) {
            this.hostMigration = json.get("host_migration").getAsBoolean();
            changed = true;
        }
        if (json.has("backend_version")) {
            this.backendVersion = json.get("backend_version").getAsString();
            changed = true;
        }
        if (json.has("update_policy")) {
            this.updatePolicy = json.get("update_policy").getAsString();
            changed = true;
        }
        if (json.has("log_level")) {
            this.logLevel = json.get("log_level").getAsString();
            changed = true;
        }
        if (json.has("cpu_limit")) {
            this.cpuLimit = json.get("cpu_limit").getAsInt();
            changed = true;
        }
        if (json.has("memory_limit")) {
            this.memoryLimit = json.get("memory_limit").getAsInt();
            changed = true;
        }
        if (json.has("backend_versions")) {
            JsonArray v = json.getAsJsonArray("backend_versions");
            this.backendVersions = v == null ? new JsonArray() : v;
            changed = true;
        }
        return changed;
    }

    /**
     * 把当前本地房间管理状态序列化成后端接受的对象。
     *
     * 字段名与后端约定的 JSON 键一一对应；数组字段为 null 时会替换为空数组而不是写 null。
     *
     * @return 房间管理状态对象，永不为 null；每次调用都新建
     */
    private JsonObject buildRoomManagementStateJson() {
        JsonObject json = new JsonObject();
        json.addProperty("room_name", roomName);
        json.addProperty("room_remark", roomRemark);
        json.addProperty("visitor_permission", visitorPermission);
        json.addProperty("whitelist_enabled", whitelistEnabled);
        json.add("whitelist", whitelist == null ? new JsonArray() : whitelist);
        json.add("blacklist", blacklist == null ? new JsonArray() : blacklist);
        json.add("mute_list", muteList == null ? new JsonArray() : muteList);
        json.add("operation_logs", operationLogs == null ? new JsonArray() : operationLogs);
        json.addProperty("allow_cheats", allowCheats);
        json.addProperty("allow_pvp", pvpAllowed);
        json.addProperty("spawn_protection", spawnProtection);
        json.addProperty("keep_inventory", keepInventory);
        json.addProperty("fire_spread", fireSpread);
        json.addProperty("mob_spawning", mobSpawning);
        json.addProperty("time_lock", timeControl);
        json.addProperty("weather_lock", weatherLock);
        json.addProperty("respawn_x", respawnX);
        json.addProperty("respawn_y", respawnY);
        json.addProperty("respawn_z", respawnZ);
        json.addProperty("world_border_center_x", worldBorderCenterX);
        json.addProperty("world_border_center_z", worldBorderCenterZ);
        json.addProperty("world_border_radius", worldBorderRadius);
        json.addProperty("auto_reconnect", autoReconnect);
        json.addProperty("reconnect_retries", reconnectRetries);
        json.addProperty("host_migration", hostMigration);
        json.addProperty("backend_version", backendVersion);
        json.addProperty("update_policy", updatePolicy);
        json.addProperty("log_level", logLevel);
        json.addProperty("cpu_limit", cpuLimit);
        json.addProperty("memory_limit", memoryLimit);
        json.add("backend_versions", backendVersions == null ? new JsonArray() : backendVersions);
        return json;
    }

    /**
     * 构建房主管理面板。
     *
     * 布局为「左侧页签竖排 + 右侧内容面板」：页签宽度取屏幕宽度的 15%（夹在 120..200），
     * 内容面板占剩余宽度与整个可用高度。房间描述为空时会先同步拉一次远端值再渲染。
     *
     * 设计约束：本方法会清空并重建 {@code roomPageButtons}，因此每次重建都要重新
     * {@code visitWidgets} 注册控件，不能只创建不注册。
     */
    private void initRoomManagementContent() {
        // 房间描述为空时补一次同步拉取，避免标题位置长期空白
        if (roomRemark == null || roomRemark.isEmpty()) {
            JsonObject state = EnderApiClient.getRoomManagementStateSync();
            if (state != null && state.has("room_remark")) {
                roomRemark = state.get("room_remark").getAsString();
            }
        }

        // 左侧页签栏：宽度随屏幕自适应
        int menuWidth = Math.min(200, Math.max(120, percentWidth(15)));
        int spacing = adaptiveSpacing() * 2;
        int margin = adaptiveMargin();
        int contentX = menuWidth + spacing;
        int contentWidth = this.width - contentX - margin;

        LinearLayout menu = LinearLayout.vertical().spacing(6);
        menu.defaultCellSetting().alignHorizontallyLeft();
        roomPageButtons.clear();
        for (RoomPage page : RoomPage.values()) {
            addRoomPageMenuButton(menu, page, menuWidth);
        }
        updateRoomPageMenuButtons();
        
        // 页签栏按内容排布后固定到左上角
        menu.arrangeElements();
        menu.setPosition(margin, this.layout.getHeaderHeight() + margin);

        // 右侧内容面板：先建空面板，再填充当前页签的内容
        int panelHeight = this.height - this.layout.getHeaderHeight() - this.layout.getFooterHeight() - margin * 2;
        this.roomPagePanel = new RoomPagePanel(contentWidth, panelHeight);
        this.roomPagePanel.setPosition(contentX, this.layout.getHeaderHeight() + margin);
        
        rebuildRoomPageContent(false);
        
        // 页签按钮最后注册，保证它们压在内容之上、可点击
        menu.visitWidgets(this::addRenderableWidget);
        
    }

    /**
     * 创建一个页签按钮并加入页签栏。
     *
     * 按钮文案取自 {@link #getRoomPageTitle}，点击后调用 {@link #switchToPage}；
     * 按钮同时被记录进 {@code roomPageButtons} 以便后续统一更新启用态。
     *
     * @param menu 目标页签栏布局，不能为 null
     * @param page 该按钮对应的页签，不能为 null
     * @param width 按钮宽度，单位为逻辑像素
     */
    private void addRoomPageMenuButton(LinearLayout menu, RoomPage page, int width) {
        Button button = Button.builder(Component.literal(getRoomPageTitle(page)), b -> switchToPage(page)).width(width).build();
        roomPageButtons.add(button);
        menu.addChild(button);
    }

    /**
     * 切换房间管理页签。
     *
     * 目标与当前页签相同时直接返回（避免无谓重建）；否则更新当前页、刷新页签启用态并重建内容。
     *
     * @param page 目标页签，不能为 null
     */
    private void switchToPage(RoomPage page) {
        if (page == this.currentRoomPage) {
            return;
        }
        this.currentRoomPage = page;
        updateRoomPageMenuButtons();
        rebuildRoomPageContent(true);
    }

    /**
     * 刷新页签按钮的启用态。
     *
     * 当前页签的按钮置为不可用（{@code active = false}）以示选中；其它页签置为可用。
     * 按索引对齐 {@link RoomPage#values()} 与 {@code roomPageButtons}，长度取两者较小值。
     */
    private void updateRoomPageMenuButtons() {
        RoomPage[] pages = RoomPage.values();
        for (int i = 0; i < pages.length && i < roomPageButtons.size(); i++) {
            roomPageButtons.get(i).active = pages[i] != currentRoomPage;
        }
    }

    /**
     * 重建当前页签的内容区。
     *
     * 先移除上一轮的控件，再按当前页签分发到对应的构建方法，最后注册新控件。
     *
     * 设计约束：{@code registerWidgets} 参数控制注册时机——切页时传 true 立即注册；
     * 首次构建面板时传 false，控件暂时只记录在 {@code roomPageWidgets} 里，
     * 由方法末尾的统一注册循环处理，避免控件注册早于面板定位。
     *
     * @param registerWidgets 是否在遍历控件时就立即注册为可渲染控件
     */
    private void rebuildRoomPageContent(boolean registerWidgets) {
        if (roomPagePanel == null) {
            return;
        }
        
        // 先摘掉上一轮的控件，避免切页后旧控件残留并响应点击
        for (AbstractWidget widget : roomPageWidgets) {
            this.removeWidget(widget);
        }
        
        roomPageWidgets.clear();
        LinearLayout pageContent = LinearLayout.vertical().spacing(12);
        pageContent.defaultCellSetting().alignHorizontallyCenter(); 
        switch (currentRoomPage) {
            case OVERVIEW -> addOverviewPage(pageContent);
            case PERMISSIONS -> addPermissionsPage(pageContent);
            case RULES -> addRulesPage(pageContent);
            case WORLD -> addWorldPage(pageContent);
            case NETWORK -> addNetworkPage(pageContent);
            case BACKEND -> addBackendPage(pageContent);
        }
        roomPagePanel.setContent(pageContent);
        
        // 面板先排布定位，控件才有正确的绝对坐标
        roomPagePanel.arrangeElements();
        
        pageContent.visitWidgets(widget -> {
            roomPageWidgets.add(widget);
            if (registerWidgets) {
                this.addRenderableWidget(widget);
            }
        });
        
        
        
        
        
        
        
        
        
        
        
        if (!registerWidgets) {
             
             for (AbstractWidget widget : roomPageWidgets) {
                 this.addRenderableWidget(widget);
             }
        }
    }

    /**
     * 取页签的显示标题。
     *
     * @param page 页签，不能为 null
     * @return 显示标题，永不为 null
     */
    private String getRoomPageTitle(RoomPage page) {
        return switch (page) {
            case OVERVIEW -> "房间概览";
            case PERMISSIONS -> "权限与访客";
            case RULES -> "规则与玩法";
            case WORLD -> "世界与边界";
            case NETWORK -> "网络与容灾";
            case BACKEND -> "后端与性能";
        };
    }

    /**
     * 房间管理页的内容面板。
     *
     * 把单个内容布局包装成一个固定宽高的 {@link Layout}，并在排布时把内容水平居中。
     * 之所以自己实现而不是直接用 LinearLayout，是因为需要「固定面板尺寸 + 内容居中」的组合语义。
     *
     * 设计约束：{@code arrangeElements()} 末尾会把自身宽高重置回构造时的固定值，
     * 因此内容再宽也不会撑大面板；但内容超宽时会被裁切而不是换行。
     */
    private static class RoomPagePanel extends AbstractLayout implements Layout {
        /** 面板内容；允许为 null，为 null 时面板为空。 */
        private LayoutElement content;

        /** 构造时确定的固定宽度，单位为逻辑像素。 */
        private final int fixedWidth;

        /** 构造时确定的固定高度，单位为逻辑像素。 */
        private final int fixedHeight;

        private RoomPagePanel(int width, int height) {
            super(0, 0, width, height);
            this.fixedWidth = width;
            this.fixedHeight = height;
        }

        private void setContent(LayoutElement content) {
            this.content = content;
        }

        /**
         * 遍历子元素，把自己包装的内容作为唯一子节点。
         *
         * @param consumer 子元素消费器，不能为 null
         */
        @Override
        public void visitChildren(Consumer<LayoutElement> consumer) {
            if (content != null) {
                consumer.accept(content);
            }
        }

        /**
         * 排布内容并把自身尺寸还原为固定值。
         *
         * 内容会先自行排布，然后按面板宽度居中，纵向贴面板上缘。
         */
        @Override
        public void arrangeElements() {
            if (content instanceof Layout layout) {
                layout.arrangeElements();
            }
            if (content != null) {
                // 内容在面板内水平居中
                int contentW = content.getWidth();
                int offset = (this.fixedWidth - contentW) / 2;
                content.setPosition(this.getX() + offset, this.getY());
            }
            this.width = fixedWidth;
            this.height = fixedHeight;
        }
    }

    /**
     * 访客/概览共用的玩家列表控件。
     *
     * 固定行高 24、行宽 300，滚动条贴右缘内侧 6 像素；条目为三列「名字 / 身份 / 连接状态」。
     * 本类是非静态内部类（需要访问外层字体与布局），故与 {@link EnderDashboard} 同生命周期。
     */
    private class PlayerListScrollWidget extends net.minecraft.client.gui.components.ObjectSelectionList<PlayerListScrollWidget.Entry> {
        /**
         * 构造玩家列表。
         *
         * @param minecraft Minecraft 实例，不能为 null
         * @param width 列表宽度，单位为逻辑像素
         * @param height 列表可见高度，单位为逻辑像素
         * @param top 列表顶部 Y 坐标，单位为逻辑像素
         * @param bottom 列表底部 Y 坐标，单位为逻辑像素
         */
        public PlayerListScrollWidget(net.minecraft.client.Minecraft minecraft, int width, int height, int top, int bottom) {
            super(minecraft, width, height, top, 24);
        }

        /**
         * 更新控件的位置与尺寸。
         *
         * 供外层在自适应布局算完后再校正一次；{@code bottom} 参数当前未参与计算，保留以对齐调用点。
         *
         * @param width 新宽度，单位为逻辑像素
         * @param height 新高度，单位为逻辑像素
         * @param top 新顶部 Y 坐标，单位为逻辑像素
         * @param bottom 新底部 Y 坐标，单位为逻辑像素
         * @param left 新左缘 X 坐标，单位为逻辑像素
         */
        public void updateWidgetSize(int width, int height, int top, int bottom, int left) {
            this.width = width;
            this.height = height;
            this.setX(left);
            this.setY(top);
        }
        
        /**
         * 按状态重建条目。
         *
         * 优先读 {@code profiles}（用 kind 标出房主/成员、用 vendor=EasyTier 标为「连接中」），
         * 退回读 {@code players} 的字符串元素。状态为 null 时列表为空。
         *
         * @param state 后端状态，允许为 null
         */
        public void updateEntries(JsonObject state) {
            this.clearEntries();
            if (state == null) return;
            
            if (state.has("profiles")) {
                try {
                    JsonArray profiles = state.getAsJsonArray("profiles");
                    for (JsonElement p : profiles) {
                        JsonObject profile = p.getAsJsonObject();
                        String name = profile.get("name").getAsString();
                        String kind = profile.has("kind") ? profile.get("kind").getAsString() : "";
                        String vendor = profile.has("vendor") ? profile.get("vendor").getAsString() : "";
                        String type = "HOST".equals(kind) ? "[房主]" : "[成员]";
                        
                        String status = "[已连接]";
                        if ("EasyTier".equals(vendor)) {
                            status = "[连接中]";
                        }
                        
                        this.addEntry(new Entry(name, type, status));
                    }
                } catch (Exception e) {
                    LOGGER.warn("failed to parse backend state json", e);
                }
            } else if (state.has("players")) {
                try {
                    JsonArray players = state.getAsJsonArray("players");
                    for (JsonElement p : players) {
                        this.addEntry(new Entry(p.getAsString(), "[成员]", "[已连接]"));
                    }
                } catch (Exception e) {
                    LOGGER.warn("failed to parse backend state json", e);
                }
            }
        }
        
        /**
         * 行宽，固定 300 逻辑像素。
         *
         * @return 行宽
         */
        @Override
        public int getRowWidth() {
            return 300;
        }

        /**
         * 滚动条位置，贴列表右缘内侧 6 像素。
         *
         * @return 滚动条 X 坐标，单位为逻辑像素
         */
        @Override
        protected int getScrollbarPosition() {
            return this.getX() + this.width - 6;
        }

        /**
         * 玩家列表条目，三列布局：名字（超宽截断加省略号）、身份、连接状态。
         *
         * 不响应点击（{@code mouseClicked} 恒返回 false）——本条列表只做展示，操作入口在房间管理页。
         */
        public class Entry extends net.minecraft.client.gui.components.ObjectSelectionList.Entry<Entry> {
             /** 玩家名，非 null。 */
             private final String name;

             /** 身份标签，非 null，取值为 [房主] 或 [成员]；决定名字的颜色。 */
             private final String type;

             /** 连接状态标签，非 null，取值为 [已连接] / [连接中] / [未连接]；决定状态列颜色。 */
             private final String status;

             public Entry(String name, String type, String status) {
                 this.name = name;
                 this.type = type;
                 this.status = status;
             }

             /**
              * 渲染条目。
              *
              * 名字列宽上限 125 像素，超出则截断并追加省略号；身份列在 x+140，状态列在 x+200。
              *
              * @param guiGraphics 绘图上下文，不能为 null
              * @param index 条目索引
              * @param y 行顶部 Y 坐标，单位为逻辑像素
              * @param x 行左缘 X 坐标，单位为逻辑像素
              * @param entryWidth 行宽，单位为逻辑像素
              * @param entryHeight 行高，单位为逻辑像素
              * @param mouseX 鼠标 X 坐标，单位为逻辑像素
              * @param mouseY 鼠标 Y 坐标，单位为逻辑像素
              * @param hovered 鼠标是否悬停
              * @param partialTick 当前帧的部分刻进度，取值 0.0..1.0
              */
             @Override
             public void render(net.minecraft.client.gui.GuiGraphics guiGraphics, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float partialTick) {
                 int color = 0xFFFFFF;
                 if ("[房主]".equals(type)) {
                     color = 0xFFFF55;
                 }
                 
                 String displayName = name;
                 int maxNameWidth = 125;
                 if (EnderDashboard.this.font.width(displayName) > maxNameWidth) {
                     displayName = EnderDashboard.this.font.plainSubstrByWidth(displayName, maxNameWidth - 10) + "...";
                 }
                 
                 guiGraphics.drawString(EnderDashboard.this.font, displayName, x + 10, y + (entryHeight - 8) / 2, color);
                 guiGraphics.drawString(EnderDashboard.this.font, Component.literal(type).withStyle(net.minecraft.ChatFormatting.GRAY), x + 140, y + (entryHeight - 8) / 2, 0xFFFFFF);
                 
                 int statusColor = 0x55FF55; 
                 if ("[未连接]".equals(status)) {
                     statusColor = 0xFF5555; 
                 } else if ("[连接中]".equals(status)) {
                     statusColor = 0xFFFF55; 
                 }
                 guiGraphics.drawString(EnderDashboard.this.font, Component.literal(status).withStyle(net.minecraft.ChatFormatting.GRAY), x + 200, y + (entryHeight - 8) / 2, statusColor);
             }

             /**
              * 鼠标点击处理。
              *
              * 恒返回 false：条目不可点击，事件继续向下传递。
              *
              * @param mouseX 鼠标 X 坐标，单位为逻辑像素
              * @param mouseY 鼠标 Y 坐标，单位为逻辑像素
              * @param button 鼠标按键编号
              * @return 恒为 false
              */
             @Override
             public boolean mouseClicked(double mouseX, double mouseY, int button) {
                 return false;
             }
             
             @Override
             public Component getNarration() {
                 return Component.literal(name);
             }
        }
    }

    /**
     * 构建「房间概览」页。
     *
     * 垂直排布：房间号、玩家数、网络质量三行信息，随后是房间描述（MOTD）编辑行、复制房间号按钮
     * 与关闭房间按钮。
     *
     * 设计约束：描述输入框与保存按钮的可用性都取决于 {@link #isHostConnected()}——
     * 访客只能看，不能改（但此处仍会显示可编辑控件的外观，属既存交互问题）。
     *
     * @param content 目标内容布局，不能为 null
     */
    private void addOverviewPage(LinearLayout content) {
        // 顶部信息区：房间号 / 玩家数 / 网络质量
        LinearLayout roomInfo = LinearLayout.vertical().spacing(6);
        roomInfo.defaultCellSetting().alignHorizontallyCenter();
        String roomCode = "未知";
        if (lastStateJson != null && lastStateJson.has("room")) {
            roomCode = lastStateJson.get("room").getAsString();
        }
        String finalRoomCode = roomCode;
        int playerCount = 0;
        if (lastStateJson != null) {
            if (lastStateJson.has("profiles")) {
                playerCount = lastStateJson.getAsJsonArray("profiles").size();
            } else if (lastStateJson.has("players")) {
                playerCount = lastStateJson.getAsJsonArray("players").size();
            }
        }
        roomInfo.addChild(new StringWidget(Component.literal("房间号: " + finalRoomCode), this.font));
        roomInfo.addChild(new StringWidget(Component.literal("玩家数: " + playerCount), this.font));
        roomInfo.addChild(new StringWidget(Component.literal("网络质量: " + networkQualityLabel + " " + (lastPingMs < 0 ? "--" : lastPingMs + "ms")), this.font));

        LinearLayout remarkLayout = LinearLayout.horizontal().spacing(6);
        EditBox remarkBox = new EditBox(this.font, 0, 0, 150, 20, Component.literal("房间描述"));
        remarkBox.setValue(roomRemark);
        remarkBox.setMaxLength(64);
        remarkBox.setHint(Component.literal("房间描述 (MOTD)"));
        remarkBox.setResponder(val -> {
            roomRemark = val;
            
        });
        remarkBox.setEditable(isHostConnected());
        remarkLayout.addChild(remarkBox);
        Button saveBtn = Button.builder(Component.literal("保存"), b -> {
            
            roomRemark = remarkBox.getValue();
            roomStateDirty = true;
            ClientSetup.showToast(Component.literal("提示"), Component.literal("房间描述已保存"));
        }).width(44).build();
        saveBtn.active = isHostConnected();
        remarkLayout.addChild(saveBtn);
        roomInfo.addChild(remarkLayout);

        roomInfo.addChild(Button.builder(Component.literal("复制房间号"), button -> {
            try {
                this.minecraft.keyboardHandler.setClipboard(finalRoomCode);
                ClientSetup.showToast(Component.literal("提示"), Component.literal("房间号已复制"));
            } catch (Exception e) {
                ClientSetup.showToast(Component.literal("提示"), Component.literal("复制失败，请手动复制房间号"));
            }
        }).width(200).build());

        roomInfo.addChild(Button.builder(Component.literal("关闭房间"), button -> {
            EnderApiClient.setIdle();
            new Thread(ProcessLauncher::stop, "Ender-Stopper").start();
            wasConnected = false;
            this.isUiConnected = false;
            this.onClose();
        }).width(200).build());
        content.addChild(roomInfo);
    }

    /**
     * 构建「权限与访客」页。
     *
     * 访客权限按钮按「可交互 / 仅聊天 / 仅观战 / 禁止进入」四态循环，白名单开关为二态，
     * 其后是名单管理入口与当前三个名单的条目数概况。
     *
     * 设计约束：每次改动都会置 {@code roomStateDirty}，由 {@code tick()} 的上行链路推给后端，
     * 本页不直接发请求。
     *
     * @param content 目标内容布局，不能为 null
     */
    private void addPermissionsPage(LinearLayout content) {
        // 访客权限区：四态循环
        LinearLayout permission = LinearLayout.vertical().spacing(6);
        permission.defaultCellSetting().alignHorizontallyCenter();
        permission.addChild(new StringWidget(Component.literal(" 权限与访客 "), this.font));

        String[] permissionCycle = new String[]{"可交互", "仅聊天", "仅观战", "禁止进入"};
        Button permissionBtn = Button.builder(Component.literal("访客权限: " + visitorPermission), button -> {
            int idx = 0;
            for (int i = 0; i < permissionCycle.length; i++) {
                if (permissionCycle[i].equals(visitorPermission)) {
                    idx = i;
                    break;
                }
            }
            visitorPermission = permissionCycle[(idx + 1) % permissionCycle.length];
            button.setMessage(Component.literal("访客权限: " + visitorPermission));
            roomStateDirty = true;
            ClientSetup.showToast(Component.literal("提示"), Component.literal("访客权限已更新"));
        }).width(200).build();
        permission.addChild(permissionBtn);

        permission.addChild(Button.builder(Component.literal("白名单启用: " + (whitelistEnabled ? "开" : "关")), button -> {
            whitelistEnabled = !whitelistEnabled;
            button.setMessage(Component.literal("白名单启用: " + (whitelistEnabled ? "开" : "关")));
            roomStateDirty = true;
            ClientSetup.showToast(Component.literal("提示"), Component.literal("白名单设置已更新"));
        }).width(200).build());

        permission.addChild(Button.builder(Component.literal("详细名单管理 (白名单/黑名单/禁言)"), button -> {
            if (this.minecraft != null) {
                this.minecraft.setScreen(new RoomListsScreen(this));
            }
        }).width(240).build());

        permission.addChild(new StringWidget(Component.literal("当前列表概况:"), this.font));
        com.google.gson.JsonObject state = EnderApiClient.getRoomManagementStateSync();
        int wlCount = 0;
        int blCount = 0;
        int muteCount = 0;
        if (state != null) {
            if (state.has("whitelist") && state.get("whitelist").isJsonArray()) {
                wlCount = state.getAsJsonArray("whitelist").size();
            }
            if (state.has("blacklist") && state.get("blacklist").isJsonArray()) {
                blCount = state.getAsJsonArray("blacklist").size();
            }
            if (state.has("mute_list") && state.get("mute_list").isJsonArray()) {
                muteCount = state.getAsJsonArray("mute_list").size();
            }
        }
        permission.addChild(new StringWidget(Component.literal("白名单: " + wlCount + " | 黑名单: " + blCount + " | 禁言: " + muteCount), this.font));

        content.addChild(permission);
    }
    
    /** 是否允许 PVP。声明在方法区是为了贴近使用点，值随房间管理状态同步。 */
    private boolean pvpAllowed = true;

    /**
     * 构建「规则与玩法」页。
     *
     * 依次是可切换的作弊 / 保留物品 / PVP / 天气锁定四个开关，随后是原生规则界面入口
     * 与「应用到当前世界」按钮。
     *
     * 设计约束：这里的开关只改本地字段并置脏；真正生效要等「应用到当前世界」或在
     * {@code tick()} 的上行链路里触发 {@code applyRulesToServer()}。
     *
     * @param content 目标内容布局，不能为 null
     */
    private void addRulesPage(LinearLayout content) {
        // 规则开关区：四个可切换按钮
        LinearLayout rules = LinearLayout.vertical().spacing(6);
        rules.defaultCellSetting().alignHorizontallyCenter();

        rules.addChild(Button.builder(Component.literal("允许作弊: " + (allowCheats ? "开" : "关")), b -> {
            allowCheats = !allowCheats;
            b.setMessage(Component.literal("允许作弊: " + (allowCheats ? "开" : "关")));
            roomStateDirty = true;
            ClientSetup.showToast(Component.literal("提示"), Component.literal("规则已更新"));
        }).width(200).build());
        rules.addChild(Button.builder(Component.literal("保留物品: " + (keepInventory ? "开" : "关")), b -> {
            keepInventory = !keepInventory;
            b.setMessage(Component.literal("保留物品: " + (keepInventory ? "开" : "关")));
            roomStateDirty = true;
            ClientSetup.showToast(Component.literal("提示"), Component.literal("规则已更新"));
        }).width(200).build());
        rules.addChild(Button.builder(Component.literal("允许PVP: " + (pvpAllowed ? "开" : "关")), b -> {
            pvpAllowed = !pvpAllowed;
            b.setMessage(Component.literal("允许PVP: " + (pvpAllowed ? "开" : "关")));
            roomStateDirty = true;
            ClientSetup.showToast(Component.literal("提示"), Component.literal("规则已更新"));
        }).width(200).build());
        rules.addChild(Button.builder(Component.literal("天气锁定: " + (weatherLock ? "开" : "关")), b -> {
            weatherLock = !weatherLock;
            b.setMessage(Component.literal("天气锁定: " + (weatherLock ? "开" : "关")));
            roomStateDirty = true;
            ClientSetup.showToast(Component.literal("提示"), Component.literal("规则已更新"));
        }).width(200).build());

        rules.addChild(Button.builder(Component.literal("更多游戏规则设置..."), b -> {
            IntegratedServer server = this.minecraft.getSingleplayerServer();
            if (server == null) {
                return;
            }
            this.minecraft.setScreen(new EditGameRulesScreen(server.getGameRules().copy(), (rulesOpt) -> {
                this.minecraft.setScreen(this);
                rulesOpt.ifPresent(r -> server.getGameRules().assignFrom(r, server));
            }));
        }).width(200).build());

        rules.addChild(Button.builder(Component.literal("应用到当前世界"), b -> applyRulesToServer()).width(200).build());
        content.addChild(rules);
    }

    /**
     * 构建「世界与边界」页。
     *
     * 两组三输入框：重生点 (X Y Z) 与 世界边界 (中心X 中心Z 半径)，各自带一个应用按钮。
     *
     * 设计约束：输入框初值会优先取玩家当前坐标（房主正在现场），仅在拿不到玩家时回落到已保存值；
     * 解析失败的输入不会覆盖字段（{@link #parseIntSafe} 返回旧值）。
     *
     * @param content 目标内容布局，不能为 null
     */
    private void addWorldPage(LinearLayout content) {
        // 重生点区：输入框初值优先用玩家当前位置，便于「就地设为重生点」
        LinearLayout world = LinearLayout.vertical().spacing(6);
        world.defaultCellSetting().alignHorizontallyCenter();

        int fillX = respawnX;
        int fillY = respawnY;
        int fillZ = respawnZ;
        if (this.minecraft != null && this.minecraft.player != null) {
            fillX = (int) this.minecraft.player.getX();
            fillY = (int) this.minecraft.player.getY();
            fillZ = (int) this.minecraft.player.getZ();
        }

        EditBox respawnXBox = new EditBox(this.font, 0, 0, 64, 20, Component.literal("X"));
        respawnXBox.setValue(String.valueOf(fillX));
        respawnXBox.setResponder(val -> {
            respawnX = parseIntSafe(val, respawnX);
            roomStateDirty = true;
        });
        EditBox respawnYBox = new EditBox(this.font, 0, 0, 64, 20, Component.literal("Y"));
        respawnYBox.setValue(String.valueOf(fillY));
        respawnYBox.setResponder(val -> {
            respawnY = parseIntSafe(val, respawnY);
            roomStateDirty = true;
        });
        EditBox respawnZBox = new EditBox(this.font, 0, 0, 64, 20, Component.literal("Z"));
        respawnZBox.setValue(String.valueOf(fillZ));
        respawnZBox.setResponder(val -> {
            respawnZ = parseIntSafe(val, respawnZ);
            roomStateDirty = true;
        });
        LinearLayout respawnRow = LinearLayout.horizontal().spacing(6);
        respawnRow.addChild(respawnXBox);
        respawnRow.addChild(respawnYBox);
        respawnRow.addChild(respawnZBox);
        world.addChild(new StringWidget(Component.literal("重生点 (X Y Z)"), this.font));
        world.addChild(respawnRow);
        world.addChild(Button.builder(Component.literal("应用重生点"), b -> {
            applyRespawn();
            ClientSetup.showToast(Component.literal("提示"), Component.literal("重生点已应用"));
        }).width(200).build());

        EditBox borderXBox = new EditBox(this.font, 0, 0, 64, 20, Component.literal("中心X"));
        borderXBox.setValue(String.valueOf(worldBorderCenterX));
        borderXBox.setResponder(val -> {
            worldBorderCenterX = parseIntSafe(val, worldBorderCenterX);
            roomStateDirty = true;
        });
        EditBox borderZBox = new EditBox(this.font, 0, 0, 64, 20, Component.literal("中心Z"));
        borderZBox.setValue(String.valueOf(worldBorderCenterZ));
        borderZBox.setResponder(val -> {
            worldBorderCenterZ = parseIntSafe(val, worldBorderCenterZ);
            roomStateDirty = true;
        });
        EditBox borderRadiusBox = new EditBox(this.font, 0, 0, 64, 20, Component.literal("半径"));
        borderRadiusBox.setValue(String.valueOf(worldBorderRadius));
        borderRadiusBox.setResponder(val -> {
            worldBorderRadius = parseIntSafe(val, worldBorderRadius);
            roomStateDirty = true;
        });
        LinearLayout borderRow = LinearLayout.horizontal().spacing(6);
        borderRow.addChild(borderXBox);
        borderRow.addChild(borderZBox);
        borderRow.addChild(borderRadiusBox);
        world.addChild(new StringWidget(Component.literal("世界边界 (中心X 中心Z 半径)"), this.font));
        world.addChild(borderRow);
        world.addChild(Button.builder(Component.literal("应用世界边界"), b -> {
            applyWorldBorder();
            ClientSetup.showToast(Component.literal("提示"), Component.literal("世界边界已应用"));
        }).width(200).build());
        content.addChild(world);
    }

    /**
     * 构建「网络与容灾」页。
     *
     * 当前只有两个控件：自动重连开关与重试次数输入框。
     *
     * 设计约束：这两个值目前只随房间管理状态上行同步，客户端尚未真正执行自动重连，
     * 因此界面呈现的能力强于实际行为——调整前先确认后端是否已消费这些字段。
     *
     * @param content 目标内容布局，不能为 null
     */
    private void addNetworkPage(LinearLayout content) {
        LinearLayout network = LinearLayout.vertical().spacing(6);
        network.defaultCellSetting().alignHorizontallyCenter();
        network.addChild(Button.builder(Component.literal("自动重连: " + (autoReconnect ? "开" : "关")), b -> {
            autoReconnect = !autoReconnect;
            b.setMessage(Component.literal("自动重连: " + (autoReconnect ? "开" : "关")));
            roomStateDirty = true;
            ClientSetup.showToast(Component.literal("提示"), Component.literal("设置已更新"));
        }).width(200).build());
        EditBox retryBox = new EditBox(this.font, 0, 0, 200, 20, Component.literal("重试次数"));
        retryBox.setValue(String.valueOf(reconnectRetries));
        retryBox.setResponder(val -> {
            reconnectRetries = parseIntSafe(val, reconnectRetries);
            roomStateDirty = true;
        });
        network.addChild(retryBox);
        content.addChild(network);
    }

    /**
     * 构建「后端与性能」页。
     *
     * 三个循环切换按钮（版本 / 更新策略 / 日志级别）、两个数值输入框（CPU 与内存限制）、
     * 一组导入导出按钮，以及最近 5 条操作日志。
     *
     * 设计约束：版本按钮只在 {@code backendVersions} 非空时生效；导出/导入走系统剪贴板，
     * 导入会直接合并并置脏，属于破坏性操作且无确认。
     *
     * @param content 目标内容布局，不能为 null
     */
    private void addBackendPage(LinearLayout content) {
        // 后端参数区：版本、更新策略、日志级别
        LinearLayout backend = LinearLayout.vertical().spacing(6);
        backend.defaultCellSetting().alignHorizontallyCenter();
        backend.addChild(Button.builder(Component.literal("版本: " + backendVersion), b -> {
            if (backendVersions.size() > 0) {
                int idx = 0;
                for (int i = 0; i < backendVersions.size(); i++) {
                    if (backendVersions.get(i).getAsString().equals(backendVersion)) {
                        idx = i;
                        break;
                    }
                }
                backendVersion = backendVersions.get((idx + 1) % backendVersions.size()).getAsString();
                b.setMessage(Component.literal("版本: " + backendVersion));
                roomStateDirty = true;
                ClientSetup.showToast(Component.literal("提示"), Component.literal("设置已更新"));
            }
        }).width(200).build());
        backend.addChild(Button.builder(Component.literal("更新策略: " + updatePolicy), b -> {
            updatePolicy = "立即".equals(updatePolicy) ? "延后" : "立即";
            b.setMessage(Component.literal("更新策略: " + updatePolicy));
            roomStateDirty = true;
            ClientSetup.showToast(Component.literal("提示"), Component.literal("设置已更新"));
        }).width(200).build());
        backend.addChild(Button.builder(Component.literal("日志级别: " + logLevel), b -> {
            if ("INFO".equals(logLevel)) {
                logLevel = "WARN";
            } else if ("WARN".equals(logLevel)) {
                logLevel = "DEBUG";
            } else {
                logLevel = "INFO";
            }
            b.setMessage(Component.literal("日志级别: " + logLevel));
            roomStateDirty = true;
            ClientSetup.showToast(Component.literal("提示"), Component.literal("设置已更新"));
        }).width(200).build());

        EditBox cpuBox = new EditBox(this.font, 0, 0, 200, 20, Component.literal("CPU限制"));
        cpuBox.setValue(String.valueOf(cpuLimit));
        cpuBox.setResponder(val -> {
            cpuLimit = parseIntSafe(val, cpuLimit);
            roomStateDirty = true;
        });
        backend.addChild(cpuBox);
        EditBox memBox = new EditBox(this.font, 0, 0, 200, 20, Component.literal("内存限制"));
        memBox.setValue(String.valueOf(memoryLimit));
        memBox.setResponder(val -> {
            memoryLimit = parseIntSafe(val, memoryLimit);
            roomStateDirty = true;
        });
        backend.addChild(memBox);

        LinearLayout exportRow = LinearLayout.horizontal().spacing(6);
        exportRow.addChild(Button.builder(Component.literal("导出设置"), b -> exportRoomState()).width(96).build());
        exportRow.addChild(Button.builder(Component.literal("导入设置"), b -> importRoomState()).width(96).build());
        backend.addChild(exportRow);

        if (operationLogs.size() > 0) {
            int start = Math.max(0, operationLogs.size() - 5);
            for (int i = start; i < operationLogs.size(); i++) {
                backend.addChild(new StringWidget(Component.literal(operationLogs.get(i).getAsString()), this.font));
            }
        }
        content.addChild(backend);
    }

    /**
     * 把本地规则字段写到当前世界的集成服务器。
     *
     * 覆盖项：保留物品、天气循环（与 {@code weatherLock} 取反）、火势、刷怪、昼夜循环、
     * PVP、作弊与出生点保护；时间锁为固定时还会直接设定时间。
     *
     * 幂等性：整组规则每轮全量覆盖，重复调用不会累积。
     *
     * 调用时机：房间页的「应用到当前世界」按钮，以及房间管理状态的上行同步链路。
     */
    private void applyRulesToServer() {
        IntegratedServer server = this.minecraft.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(keepInventory, server);
        server.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(!weatherLock, server);
        setBooleanGameRule(server, fireSpread, "RULE_DOFIRETICK", "RULE_DO_FIRE_TICK");
        setBooleanGameRule(server, mobSpawning, "RULE_DOMOBSPAWNING", "RULE_DO_MOB_SPAWNING");
        boolean cycle = "cycle".equals(timeControl);
        setBooleanGameRule(server, cycle, "RULE_DAYLIGHT", "RULE_DAYLIGHT_CYCLE", "RULE_DO_DAYLIGHT_CYCLE");
        if (!cycle) {
            ServerLevel level = server.overworld();
            if (level != null) {
                setWorldTime(level, "night".equals(timeControl) ? 13000L : 1000L);
            }
        }
        server.setPvpAllowed(pvpAllowed);
        setCheatsAllowed(server, allowCheats);
        setSpawnProtection(server, spawnProtection);
    }

    /**
     * 把本地房间管理状态整体应用到当前世界。
     *
     * 顺序固定为：房间描述写入服务器 MOTD、应用游戏规则、执行访问控制。
     * 后两步的顺序不可颠倒——被踢出的玩家不应再被改动游戏模式。
     *
     * 幂等性：三个子步骤都是覆盖式的，重复调用安全。
     */
    private void applyRoomManagementStateToServer() {
        IntegratedServer server = this.minecraft.getSingleplayerServer();
        if (server == null) {
            return;
        }
        
        // 房间描述同步为服务器 MOTD，失败不影响后续规则应用
        if (this.roomRemark != null) {
            try {
                server.setMotd(this.roomRemark);
            } catch (Exception ignored) {
            }
        }

        applyRulesToServer();
        enforceAccessControl(server);
    }

    /**
     * 对当前世界的玩家执行访问控制。
     *
     * 房主本人始终跳过（按名称忽略大小写比较）；其余玩家按黑名单 → 白名单 → 访客权限的顺序判定，
     * 「仅观战」转旁观者、「仅聊天」转冒险模式。
     *
     * 设计约束：与 {@code RoomHostLogic} 的同名逻辑是两份实现，改动需同步，P3 拆分时应合并为一处。
     *
     * @param server 当前集成服务器，不能为 null
     */
    private void enforceAccessControl(IntegratedServer server) {
        String hostName = this.minecraft.getUser().getName();
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        for (ServerPlayer player : players) {
            String name = player.getGameProfile().getName();
            if (name != null && name.equalsIgnoreCase(hostName)) {
                continue;
            }
            if (containsName(blacklist, name)) {
                disconnectPlayer(player, Component.literal("你已被房主加入黑名单"));
                continue;
            }
            if (whitelistEnabled && whitelist != null && !containsName(whitelist, name)) {
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
     * 向名单数组添加玩家名。
     *
     * 忽略大小写去重；已存在时不重复添加。
     * NOTE: 当前无调用点，保留为 P3 拆分时的名单编辑入口。
     *
     * @param array 目标数组，允许为 null（为 null 时不做任何事）
     * @param name 玩家名，允许为 null（为 null 时不做任何事）
     */
    private void addNameToArray(JsonArray array, String name) {
        if (array == null || name == null) {
            return;
        }
        for (JsonElement el : array) {
            if (el != null && el.isJsonPrimitive() && name.equalsIgnoreCase(el.getAsString())) {
                return;
            }
        }
        array.add(name);
    }

    /**
     * 从名单数组移除首个匹配的玩家名。
     *
     * 忽略大小写匹配，只移除第一条命中项。
     * NOTE: 当前无调用点，保留为 P3 拆分时的名单编辑入口。
     *
     * @param array 目标数组，允许为 null（为 null 时不做任何事）
     * @param name 玩家名，允许为 null（为 null 时不做任何事）
     */
    private void removeNameFromArray(JsonArray array, String name) {
        if (array == null || name == null) {
            return;
        }
        for (int i = 0; i < array.size(); i++) {
            JsonElement el = array.get(i);
            if (el != null && el.isJsonPrimitive() && name.equalsIgnoreCase(el.getAsString())) {
                array.remove(i);
                return;
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
    private boolean containsName(JsonArray array, String name) {
        if (array == null || name == null) {
            return false;
        }
        for (JsonElement el : array) {
            if (el != null && el.isJsonPrimitive() && name.equalsIgnoreCase(el.getAsString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把玩家踢出当前世界。
     *
     * 失败被静默忽略：玩家可能已经断开，此时踢出是空操作。
     *
     * @param player 目标玩家，不能为 null
     * @param reason 断开原因，不能为 null
     */
    private void disconnectPlayer(ServerPlayer player, Component reason) {
        try {
            player.connection.disconnect(reason);
        } catch (Exception ignored) {
        }
    }

    /**
     * 设置玩家游戏模式。
     *
     * 走反射回退链（见类注释约束 3），失败静默忽略——是 ADR-03 的待消除目标。
     *
     * @param player 目标玩家，不能为 null
     * @param type 目标游戏模式，不能为 null
     */
    private void setPlayerGameType(ServerPlayer player, GameType type) {
        try {
            java.lang.reflect.Method m = player.getClass().getMethod("setGameMode", GameType.class);
            m.invoke(player, type);
        } catch (Exception ignored) {
        }
    }

    /**
     * 设置「允许所有玩家作弊」。
     *
     * 走反射回退链（见类注释约束 3）：先试 setAllowCheatsForAllPlayers，失败再试 setAllowCommandsForAllPlayers。
     *
     * @param server 当前集成服务器，不能为 null
     * @param value 是否允许
     */
    private void setCheatsAllowed(IntegratedServer server, boolean value) {
        try {
            Object playerList = server.getPlayerList();
            java.lang.reflect.Method m = playerList.getClass().getMethod("setAllowCheatsForAllPlayers", boolean.class);
            m.invoke(playerList, value);
            return;
        } catch (Exception ignored) {
        }
        try {
            Object playerList = server.getPlayerList();
            java.lang.reflect.Method m = playerList.getClass().getMethod("setAllowCommandsForAllPlayers", boolean.class);
            m.invoke(playerList, value);
        } catch (Exception ignored) {
        }
    }

    /**
     * 设置出生点保护半径。
     *
     * 负值归零；具体写入走反射回退链（见类注释约束 3）。
     *
     * @param server 当前集成服务器，不能为 null
     * @param value 半径，单位为方块；负值按 0 处理
     */
    private void setSpawnProtection(IntegratedServer server, int value) {
        int radius = Math.max(0, value);
        try {
            Object playerList = server.getPlayerList();
            java.lang.reflect.Method m = playerList.getClass().getMethod("setSpawnProtectionRadius", int.class);
            m.invoke(playerList, radius);
            return;
        } catch (Exception ignored) {
        }
        try {
            Object playerList = server.getPlayerList();
            java.lang.reflect.Method m = playerList.getClass().getMethod("setSpawnProtection", int.class);
            m.invoke(playerList, radius);
        } catch (Exception ignored) {
        }
    }

    /**
     * 按候选字段名设置布尔游戏规则。
     *
     * 这是本文件里最重的一处反射回退链（见类注释约束 3）：先按字段名拿静态 Key，再尝试用
     * {@code getRule(Class)} 取规则，失败则遍历全部单参 {@code getRule} 方法，最后遍历规则对象上
     * 「首参为 boolean 的双参 set 方法」来写入。每一层失败都被静默吞掉。
     *
     * @param server 当前集成服务器，允许为 null（为 null 时直接返回）
     * @param value 目标值
     * @param fieldNames 候选字段名，按优先级排列；空名会被跳过
     */
    private void setBooleanGameRule(IntegratedServer server, boolean value, String... fieldNames) {
        if (server == null || fieldNames == null) {
            return;
        }
        Object gameRules = server.getGameRules();
        for (String fieldName : fieldNames) {
            if (fieldName == null || fieldName.isBlank()) {
                continue;
            }
            try {
                java.lang.reflect.Field f = GameRules.class.getField(fieldName);
                Object key = f.get(null);
                Object rule = null;
                try {
                    java.lang.reflect.Method m = gameRules.getClass().getMethod("getRule", key.getClass());
                    rule = m.invoke(gameRules, key);
                } catch (Exception ignored) {
                    for (java.lang.reflect.Method m : gameRules.getClass().getMethods()) {
                        if (!"getRule".equals(m.getName()) || m.getParameterCount() != 1) {
                            continue;
                        }
                        rule = m.invoke(gameRules, key);
                        break;
                    }
                }
                if (rule == null) {
                    continue;
                }
                for (java.lang.reflect.Method m : rule.getClass().getMethods()) {
                    if (!"set".equals(m.getName()) || m.getParameterCount() != 2) {
                        continue;
                    }
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 2 && params[0] == boolean.class) {
                        m.invoke(rule, value, server);
                        return;
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 设置主世界时间。
     *
     * 走反射回退链（见类注释约束 3）：先试 setDayTime，失败再试 setTimeOfDay。
     *
     * @param level 目标世界，允许为 null（为 null 时直接返回）
     * @param time 目标时间，单位为 tick（0..23999 为一个昼夜周期）
     */
    private void setWorldTime(ServerLevel level, long time) {
        if (level == null) {
            return;
        }
        try {
            java.lang.reflect.Method m = level.getClass().getMethod("setDayTime", long.class);
            m.invoke(level, time);
            return;
        } catch (Exception ignored) {
        }
        try {
            java.lang.reflect.Method m = level.getClass().getMethod("setTimeOfDay", long.class);
            m.invoke(level, time);
        } catch (Exception ignored) {
        }
    }

    /**
     * 把当前重生点字段应用到主世界。
     *
     * 无集成服务器或主世界不可用时静默返回。
     */
    private void applyRespawn() {
        IntegratedServer server = this.minecraft.getSingleplayerServer();
        if (server == null) {
            return;
        }
        ServerLevel level = server.overworld();
        if (level == null) {
            return;
        }
        level.setDefaultSpawnPos(new BlockPos(respawnX, respawnY, respawnZ), 0.0f);
    }

    /**
     * 把当前世界边界字段应用到主世界。
     *
     * 半径始终会设置中心；但只有当 {@code worldBorderRadius} 大于 0 时才改尺寸，
     * 0 表示「不改边界大小」，不是「收缩到 0」。
     */
    private void applyWorldBorder() {
        IntegratedServer server = this.minecraft.getSingleplayerServer();
        if (server == null) {
            return;
        }
        ServerLevel level = server.overworld();
        if (level == null) {
            return;
        }
        WorldBorder border = level.getWorldBorder();
        border.setCenter(worldBorderCenterX, worldBorderCenterZ);
        if (worldBorderRadius > 0) {
            border.setSize(worldBorderRadius * 2.0);
        }
    }

    /**
     * 把当前房间管理状态导出到系统剪贴板。
     *
     * 序列化格式与 {@link #buildRoomManagementStateJson()} 一致，可被 {@link #importRoomState()} 读回。
     * 写入剪贴板失败（例如无输入焦点）时提示导出失败，不抛异常。
     */
    private void exportRoomState() {
        try {
            String json = buildRoomManagementStateJson().toString();
            this.minecraft.keyboardHandler.setClipboard(json);
            ClientSetup.showToast(Component.literal("提示"), Component.literal("配置已复制到剪贴板"));
        } catch (Exception e) {
            ClientSetup.showToast(Component.literal("提示"), Component.literal("导出失败"));
        }
    }

    /**
     * 从系统剪贴板导入房间管理状态。
     *
     * 剪贴板为空或内容不是合法 JSON 对象时静默返回；成功导入会置脏并重建界面，
     * 因此导入是立即生效的破坏性操作，没有确认步骤（待补）。
     */
    private void importRoomState() {
        try {
            String json = this.minecraft.keyboardHandler.getClipboard();
            if (json == null || json.isBlank()) {
                return;
            }
            JsonObject obj = GSON.fromJson(json, JsonObject.class);
            if (obj == null) {
                return;
            }
            mergeRoomManagementState(obj);
            roomStateDirty = true;
            this.init(this.minecraft, this.width, this.height);
        } catch (Exception ignored) {
        }
    }

    /**
     * 计算屏幕宽度的百分比。
     *
     * 用的是 {@code this.width}（GUI 缩放后的逻辑宽度），不是窗口物理像素。
     *
     * @param percent 百分比，取值 0..100
     * @return 对应宽度，单位为逻辑像素
     */
    // TODO(P5, 2026-12-31): 目前无调用点——响应式计算已定义但尚未接入各页面，接入或删除见 claude_docs/04-uiux-plan.md
    private int percentWidth(double percent) {
        return (int) (this.width * percent / 100.0);
    }

    /**
     * 计算屏幕高度的百分比。
     *
     * 用的是 {@code this.height}（GUI 缩放后的逻辑高度），不是窗口物理像素。
     * NOTE: 当前无调用点，保留为响应式布局的备用换算。
     *
     * @param percent 百分比，取值 0..100
     * @return 对应高度，单位为逻辑像素
     */
    // TODO(P5, 2026-12-31): 目前无调用点——响应式计算已定义但尚未接入各页面，接入或删除见 claude_docs/04-uiux-plan.md
    private int percentHeight(double percent) {
        return (int) (this.height * percent / 100.0);
    }

    /**
     * 计算自适应按钮宽度。
     *
     * @return 按钮宽度，单位为逻辑像素，取值 100..250（屏幕宽度的六分之一，越界则夹紧）
     */
    // TODO(P5, 2026-12-31): 目前无调用点——响应式计算已定义但尚未接入各页面，接入或删除见 claude_docs/04-uiux-plan.md
    private int adaptiveButtonWidth() {
        int base = Math.min(250, Math.max(100, this.width / 6));
        return base;
    }

    /**
     * 计算自适应小按钮宽度。
     *
     * 用于并排两个按钮的场景（如「断开连接 / 返回」）。
     *
     * @return 按钮宽度，单位为逻辑像素，取值 80..150（屏幕宽度的十分之一，越界则夹紧）
     */
    // TODO(P5, 2026-12-31): 目前无调用点——响应式计算已定义但尚未接入各页面，接入或删除见 claude_docs/04-uiux-plan.md
    private int adaptiveSmallButtonWidth() {
        int base = Math.min(150, Math.max(80, this.width / 10));
        return base;
    }

    /**
     * 计算自适应边距。
     *
     * @return 边距，单位为逻辑像素，至少 10（屏幕宽度的四十分之一）
     */
    // TODO(P5, 2026-12-31): 目前无调用点——响应式计算已定义但尚未接入各页面，接入或删除见 claude_docs/04-uiux-plan.md
    private int adaptiveMargin() {
        return Math.max(10, this.width / 40);
    }

    /**
     * 计算自适应间距。
     *
     * @return 间距，单位为逻辑像素，至少 5（屏幕宽度的八十分之一）
     */
    // TODO(P5, 2026-12-31): 目前无调用点——响应式计算已定义但尚未接入各页面，接入或删除见 claude_docs/04-uiux-plan.md
    private int adaptiveSpacing() {
        return Math.max(5, this.width / 80);
    }

    /**
     * 解析整数输入，失败时回落到给定默认值。
     *
     * 用于文本框：玩家输入中间态（空串、负号、字母）不应把字段清掉。
     *
     * @param value 原始输入，不能为 null；会先 trim
     * @param fallback 解析失败时返回的值
     * @return 解析结果；无法解析时返回 {@code fallback}
     */
    private int parseIntSafe(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}



