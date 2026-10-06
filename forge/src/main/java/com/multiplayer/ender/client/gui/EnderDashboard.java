/*
 * 本文件属于 EnderOnline Forge 适配层。
 *
 * 职责：末影联机控制台（上帝类），承担状态轮询、房间管理业务、界面重建与服务器写回。
 *
 * 本类同时是视图、状态容器与业务动作入口，已知需要拆分；详见类注释中的 P3 待办。
 */
package com.multiplayer.ender.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.multiplayer.ender.ConfigForge;
import com.multiplayer.ender.client.ClientSetupForge;
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
import net.minecraft.client.gui.components.ObjectSelectionList;

/**
 * 末影联机控制台界面（上帝类）。
 *
 * 属于 Mod 的核心 GUI：由多人游戏菜单与游戏内暂停菜单打开，加入房间成功后也会切到这里。
 * 功能覆盖状态展示、房主房间管理（概览、权限、规则、世界、网络、后端六个子页）、
 * 访客房间信息与断开连接。
 *
 * 已知问题：本类是典型的上帝类，约 60 个方法与 50 余个可变字段集中在一个文件中，
 * 视图、状态、业务动作与服务器写回四类职责互相纠缠，其中大部分方法只服务于某一个子页。
 *
 * 设计约束：
 * 1. 静态字段（wasConnected、lastStateJson、lastClipboard）在多个实例之间共享，
 *    因此同一时刻只应存在一个控制台实例。
 * 2. 房间管理采用「脏标记 + 节流推送」：本地改动置 roomStateDirty，tick 中每 500ms 最多推送一次；
 *    未脏时每 2000ms 拉取一次后端状态并合并到本地字段。
 * 3. 界面重建统一通过 this.init(...) 触发，因此 initContent 与各页面构建方法会被重复执行，
 *    必须保持幂等。
 * 4. 本类内部保留了多组反射回退链（getMethod / getField），成因是 Fabric 时期需要同时兼容
 *    Yarn 与 Mojmap 两套映射与不同版本的方法名；两端映射统一后，这些回退链属于 ADR-03 的待消除目标。
 * 5. 页面之间不通过接口解耦，而是由本类直接 new 各 Screen，这会在拆分时成为主要障碍。
 *
 * TODO(P3, 2026-09-30): 按 claude_docs/04-uiux-plan.md §8 拆成 5 个页面 + 5 个 ViewModel
 * （DashboardScreen 只保留骨架、导航与权限门禁），当前本类内含 6 个 RoomPage 子页。
 *
 * 线程安全性：Screen 只在客户端主线程使用，但多个状态回调运行在 EnderApiClient 的回调线程，
 * 其中一部分直接写入实例字段而未切回主线程；静态共享状态因此存在跨线程可见性隐患。
 *
 * @see EnderBaseScreen
 * @see JoinScreen
 */
public class EnderDashboard extends EnderBaseScreen {
    /** 后端状态展示文本，初值为语言键 ender.dashboard.status.fetching 对应的文案。 */
    private String backendState = Component.translatable("ender.dashboard.status.fetching").getString();

    /** 上次后端状态轮询的时间戳，单位毫秒；用于每秒节流。 */
    private long lastStateCheck = 0;

    /** JSON 解析器，Gson 实例线程安全，可跨线程复用。 */
    private static final Gson GSON = new Gson();

    /** 本类日志器，使用独立名称便于按类过滤日志。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(EnderDashboard.class);

    /** 已处理过的剪贴板内容，默认空串；用于避免对同一房间号重复触发自动入房。 */
    private static String lastClipboard = "";

    /** 后端是否处于已连接状态；静态字段，跨实例共享，只应在客户端主线程读写。 */
    private static boolean wasConnected = false;

    /** 当前界面上呈现的连接状态，用于与 wasConnected 比对以决定是否需要重建界面。 */
    private boolean isUiConnected = false;

    /** 最近一次解析成功的状态对象，允许为 null；静态字段，跨实例共享。 */
    private static JsonObject lastStateJson = null;

    /** 玩家列表是否展开，默认 true；当前没有任何读取点。 */
    private boolean showPlayerList = true;

    /** 服务器设置区是否展开，默认 false；当前没有任何读取点。 */
    private boolean showServerSettings = false;

    /** 核心路径的临时值，构造时从配置读入，saveConfig 时写回。 */
    private String tempPath;

    /** 自动更新开关的临时值，构造时从配置读入，saveConfig 时写回。 */
    private boolean tempAutoUpdate;

    /** 自动启动后端开关的临时值，构造时从配置读入，saveConfig 时写回。 */
    private boolean tempAutoStart;

    /** 上次房间管理状态同步的时间戳，单位毫秒；用于 500ms 推送 / 2000ms 拉取的节流。 */
    private long lastRoomSync = 0;

    /** 上次网络质量检测的时间戳，单位毫秒；当前没有任何读写点。 */
    private long lastPingCheck = 0;

    /** 最近一次状态查询耗时，单位毫秒；-1 表示尚未测得过，取值用于展示为延迟。 */
    private int lastPingMs = -1;

    /** 网络质量对应的颜色（ARGB）；当前只有写入点、没有任何读取点。 */
    private int networkQualityColor = 0x00FF00;

    /** 网络质量文案，按 lastPingMs 分档为 优秀/良好/一般/较差，默认「良好」。 */
    private String networkQualityLabel = "良好";

    /*
     * NOTE: showPlayerList、showServerSettings、lastPingCheck、currentPlayers、maxPlayers、
     * performanceSamples 六个字段当前没有任何读取点，属于上帝类演化过程中遗留的僵尸字段，
     * 拆分（P3）时应直接删除而不是搬迁。
     */

    // ---- 房间设置镜像字段：以下字段是后端房间管理状态在本地的副本，由 mergeRoomManagementState 写入 ----

    /** 房间名，默认「未命名房间」；仅参与序列化回推，界面未提供编辑入口。 */
    private String roomName = "未命名房间";

    /** 房间备注（MOTD），默认空串；会通过 server.setMotd 写回服务器。 */
    private String roomRemark = "";

    /** 在线人数，默认 0；当前没有任何读写点。 */
    private int currentPlayers = 0;

    /** 人数上限，默认 0；当前没有任何读写点。 */
    private int maxPlayers = 0;

    /** 访客权限，取值「可交互」「仅聊天」「仅观战」「禁止进入」，默认「可交互」。 */
    private String visitorPermission = "可交互";

    /** 白名单是否启用，默认 false；与 whitelist 内容共同决定访客是否被踢出。 */
    private boolean whitelistEnabled = false;

    /** 白名单玩家名数组，始终非 null（缺数据时为空数组）。 */
    private JsonArray whitelist = new JsonArray();

    /** 黑名单玩家名数组，始终非 null（缺数据时为空数组）。 */
    private JsonArray blacklist = new JsonArray();

    /** 禁言名单玩家名数组，始终非 null；当前只有镜像与回推，没有强制执行点。 */
    private JsonArray muteList = new JsonArray();

    /** 操作日志数组，始终非 null；后端页只展示最后 5 条。 */
    private JsonArray operationLogs = new JsonArray();

    /** 是否允许所有玩家使用命令，默认 false；写回时通过反射调用玩家列表 setter。 */
    private boolean allowCheats = false;

    /** 出生点保护半径，单位方块，默认 16（与原版单人世界一致）；负值写回前会被归一化为 0。 */
    private int spawnProtection = 16;

    /** 死亡是否保留物品，默认 false。 */
    private boolean keepInventory = false;

    /** 火焰是否蔓延，默认 true。 */
    private boolean fireSpread = true;

    /** 是否自然生成生物，默认 true。 */
    private boolean mobSpawning = true;

    /** 时间控制模式，取值 cycle（随昼夜循环）或 fixed（锁定），默认 cycle。 */
    private String timeControl = "cycle";

    /** 是否锁定天气，默认 false。 */
    private boolean weatherLock = false;

    /** 重生点 X 坐标，单位方块，默认 0。 */
    private int respawnX = 0;

    /** 重生点 Y 坐标，单位方块，默认 0。 */
    private int respawnY = 0;

    /** 重生点 Z 坐标，单位方块，默认 0。 */
    private int respawnZ = 0;

    /** 世界边界中心 X 坐标，单位方块，默认 0。 */
    private int worldBorderCenterX = 0;

    /** 世界边界中心 Z 坐标，单位方块，默认 0。 */
    private int worldBorderCenterZ = 0;

    /** 世界边界半径，单位方块，默认 0；仅当大于 0 时才应用边界。 */
    private int worldBorderRadius = 0;

    /** 是否自动重连，默认 true；当前只有镜像与回推，没有实际的自动重连实现。 */
    private boolean autoReconnect = true;

    /** 自动重连重试次数，默认 3；当前只有镜像与回推。 */
    private int reconnectRetries = 3;

    /** 是否启用主机迁移，默认 false；当前只有镜像与回推。 */
    private boolean hostMigration = false;

    /** 后端版本选择值，默认「当前」；取值来自 backendVersions 数组。 */
    private String backendVersion = "当前";

    /** 更新策略，取值「立即」或「延后」，默认「立即」；当前只有镜像与回推。 */
    private String updatePolicy = "立即";

    /** 日志级别，取值 INFO / WARN / DEBUG，默认 INFO；当前只有镜像与回推。 */
    private String logLevel = "INFO";

    /** CPU 限制，取值 0 表示不限制；当前只有镜像与回推。 */
    private int cpuLimit = 0;

    /** 内存限制（单位交由后端解释），取值 0 表示不限制；当前只有镜像与回推。 */
    private int memoryLimit = 0;

    /** 性能采样缓冲；当前没有任何读写点。 */
    private final List<Integer> performanceSamples = new ArrayList<>();

    /** 可选的后端版本列表，始终非 null；为空时「版本」按钮点击后不改变取值。 */
    private JsonArray backendVersions = new JsonArray();

    /** 是否存在尚未推送的本地改动，默认 false；置位后由 tick 节流推送并复位。 */
    private boolean roomStateDirty = false;

    /** 上次推送房间管理状态的时间戳，单位毫秒，默认 0。 */
    private long lastPushTime = 0;

    /** 当前房间管理页已注册的控件，用于切换子页时逐个摘除，始终非 null。 */
    private final List<AbstractWidget> roomPageWidgets = new ArrayList<>();

    /** 左侧子页菜单按钮，顺序与 RoomPage.values() 一致，始终非 null。 */
    private final List<Button> roomPageButtons = new ArrayList<>();

    /** 子页内容容器，initRoomManagementContent 中创建；在此之前为 null。 */
    private RoomPagePanel roomPagePanel;

    /** 访客视角的玩家列表组件，允许为 null（未构建或不处于访客已连接状态）。 */
    private PlayerListScrollWidget playerListWidget;

    /**
     * 控制台的视图模式。
     *
     * 取值之间不存在状态流转关系：模式在构造时确定，之后不再改变。
     * 三种模式的内容区实现完全相同，实际差异只有两处：
     * FULL 的页脚是「断开连接」而其余两种是「返回」；状态标语行只在 FULL 与 INGAME_INFO 下绘制。
     */
    public enum ViewMode {
        /** 完整模式：由多人游戏菜单进入，提供子页导航与「断开连接」。 */
        FULL,

        /** 游戏内信息模式：由暂停菜单「显示信息」进入，与 INGAME_SETTINGS 行为一致。 */
        INGAME_INFO,

        /** 游戏内设置模式：由暂停菜单「房间设置」进入，页脚为「返回」。 */
        INGAME_SETTINGS
    }

    /**
     * 玩家列表包装组件。
     *
     * 把 ObjectSelectionList 包装成 AbstractWidget，使其能参与 LinearLayout 之类的布局计算；
     * 自身不绘制任何内容，只把渲染与鼠标事件按当前坐标转发给被包装的列表。
     *
     * 设计约束：被包装列表的尺寸与位置在每个渲染帧开始时按本组件的当前边界同步，
     * 因此布局变化会在下一帧生效。
     *
     * 线程安全性：只在客户端主线程使用。
     */
    private class PlayerListWrapperWidget extends AbstractWidget {
        /** 被包装的列表实例，不能为 null。 */
        private final PlayerListScrollWidget list;
        
        /**
         * 构造包装组件。
         *
         * @param list 被包装的列表，不能为 null
         * @param width 组件宽度，单位像素
         * @param height 组件高度，单位像素
         */
        public PlayerListWrapperWidget(PlayerListScrollWidget list, int width, int height) {
            super(0, 0, width, height, Component.empty());
            this.list = list;
        }

        /**
         * 绘制组件，契约见 AbstractWidget#renderWidget。
         *
         * 先把本组件当前边界同步给被包装列表，再转发渲染，因此每帧都会重新定位列表。
         */
        @Override
        public void renderWidget(net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            this.list.updateWidgetSize(this.width, this.height, this.getY(), this.getY() + this.height, this.getX());
            this.list.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        /**
         * 点击事件，契约见 AbstractWidget#mouseClicked，直接转发给被包装列表。
         */
        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            return this.list.mouseClicked(mouseX, mouseY, button);
        }

        /**
         * 滚轮事件，契约见 AbstractWidget#mouseScrolled，直接转发给被包装列表。
         */
        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
             return this.list.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        /**
         * 拖拽事件，契约见 AbstractWidget#mouseDragged，直接转发给被包装列表。
         */
        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
            return this.list.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
        }

        /**
         * 无障碍朗读内容，本组件不绘制内容，故留空实现。
         */
        @Override
        protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput narrationElementOutput) {}
    }

    /**
     * 房间玩家列表组件。
     *
     * 继承 ObjectSelectionList，展示房间内玩家：姓名、身份（房主/成员）、连接状态，
     * 可选带一个「移除」按钮。行宽固定 300 像素、行高 24 像素。
     *
     * 设计约束：
     * 1. 条目在每次 updateEntries 时整体重建，不做增量更新。
     * 2. 位置与尺寸由外部通过 updateWidgetSize 强制同步，因此本组件不参与布局计算。
     *
     * 线程安全性：只在客户端主线程使用。
     */
    private class PlayerListScrollWidget extends ObjectSelectionList<PlayerListScrollWidget.Entry> {
        /**
         * 构造玩家列表。
         *
         * @param minecraft 客户端实例，不能为 null
         * @param width 列表宽度，单位像素
         * @param height 列表高度，单位像素，决定可见条目数
         * @param top 列表顶部 Y 坐标，单位像素
         * @param bottom 列表底部 Y 坐标，单位像素；当前未参与计算
         */
        public PlayerListScrollWidget(net.minecraft.client.Minecraft minecraft, int width, int height, int top, int bottom) {
            super(minecraft, width, height, top, 24);
        }

        /**
         * 强制同步列表的尺寸与位置。
         *
         * 由包装组件每帧调用，因此手工改动列表坐标会在下一帧被覆盖。
         *
         * @param width 新的宽度，单位像素
         * @param height 新的高度，单位像素
         * @param top 新的顶部 Y 坐标，单位像素
         * @param bottom 新的底部 Y 坐标，单位像素；当前未参与计算
         * @param left 新的左边界 X 坐标，单位像素
         */
        public void updateWidgetSize(int width, int height, int top, int bottom, int left) {
            this.width = width;
            this.height = height;
            this.setX(left);
            this.setY(top);
        }

        /**
         * 按后端状态重建全部条目。
         *
         * 兼容两种后端格式：profiles 对象数组（取 name / kind / vendor）与 players 字符串数组；
         * profiles 优先。kind 为 HOST 标记为房主；vendor 为 EasyTier 时状态显示为「连接中」，
         * 其余一律显示「已连接」。解析异常只记录警告，已加入的条目不会被回滚。
         *
         * @param state 状态对象，允许为 null，为 null 时仅清空条目
         */
        public void updateEntries(JsonObject state) {
            this.clearEntries();
            if (state == null) return;

            if (state.has("profiles")) {
                try {
                    JsonArray profiles = state.getAsJsonArray("profiles");
                    for (JsonElement p : profiles) {
                        JsonObject profile = p.getAsJsonObject();
                        // 身份标记：[房主] 用于高亮显示，[成员] 为默认
                        String name = profile.get("name").getAsString();
                        String kind = profile.has("kind") ? profile.get("kind").getAsString() : "";
                        String vendor = profile.has("vendor") ? profile.get("vendor").getAsString() : "";
                        String type = "HOST".equals(kind) ? "[房主]" : "[成员]";

                        String status = "[已连接]";
                        if ("EasyTier".equals(vendor)) {
                            status = "[连接中]";
                        }

                        this.addEntry(new Entry(name, type, null, status));
                    }
                } catch (Exception e) {
                    LOGGER.warn("failed to parse backend state json", e);
                }
            } else if (state.has("players")) {
                try {
                    JsonArray players = state.getAsJsonArray("players");
                    for (JsonElement p : players) {
                        this.addEntry(new Entry(p.getAsString(), "[成员]", null, "[已连接]"));
                    }
                } catch (Exception e) {
                    LOGGER.warn("failed to parse backend state json", e);
                }
            }
        }

        /**
         * 追加一条带「移除」回调的条目。
         *
         * 与 updateEntries 不同，本方法不清空已有条目，可用于增量追加。
         *
         * @param name 玩家名，不能为 null
         * @param type 身份标记文案，不能为 null
         * @param onRemove 「移除」按钮的回调，不能为 null
         */
        public void addItem(String name, String type, Button.OnPress onRemove) {
            this.addEntry(new Entry(name, type, onRemove, "[已连接]"));
        }
        
        /**
         * 行宽，固定 300 像素，契约见 ObjectSelectionList#getRowWidth。
         */
        @Override
        public int getRowWidth() {
            return 300;
        }

        /**
         * 滚动条 X 坐标，贴列表右缘内缩 6 像素，契约见 ObjectSelectionList#getScrollbarPosition。
         */
        @Override
        protected int getScrollbarPosition() {
            return this.getX() + this.width - 6;
        }

        /**
         * 单行玩家条目。
         *
         * 从左到右依次绘制玩家名（名字过长会按 125 像素裁剪并追加省略号）、身份标记（房主高亮为黄色）、
         * 连接状态（未连接红、连接中黄、已连接绿）；移除按钮为可选，为 null 时不绘制。
         *
         * 线程安全性：只在客户端主线程使用。
         */
        public class Entry extends ObjectSelectionList.Entry<Entry> {
             /** 玩家名，不能为 null。 */
             private final String name;

             /** 身份标记文案，取值 [房主] 或 [成员]，不能为 null。 */
             private final String type;

             /** 连接状态文案，取值 [已连接]、[连接中] 或 [未连接]，不能为 null。 */
             private final String status;

             /** 「移除」按钮；允许为 null，为 null 时本行不可移除。 */
             private final Button removeBtn;

             /**
              * 构造条目。
              *
              * @param name 玩家名，不能为 null
              * @param type 身份标记文案，不能为 null
              * @param onRemove 「移除」按钮回调，允许为 null，为 null 时不创建按钮
              * @param status 连接状态文案，不能为 null
              */
             public Entry(String name, String type, Button.OnPress onRemove, String status) {
                 this.name = name;
                 this.type = type;
                 this.status = status;
                 if (onRemove != null) {
                     this.removeBtn = Button.builder(Component.literal("移除"), onRemove).width(40).build();
                 } else {
                     this.removeBtn = null;
                 }
             }

             /**
              * 绘制条目，契约见 ObjectSelectionList.Entry#render。
              *
              * 列位置硬编码：玩家名左缩进 10（超过 125 像素则裁剪并加省略号）、
              * 身份标记左移 140、连接状态左移 200、移除按钮左移 240；三者均按行内垂直居中。
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

                 // 状态颜色：已连接绿、未连接红、连接中黄
                 int statusColor = 0x55FF55;
                 if ("[未连接]".equals(status)) {
                     statusColor = 0xFF5555;
                 } else if ("[连接中]".equals(status)) {
                     statusColor = 0xFFFF55;
                 }
                 if (status != null) {
                    guiGraphics.drawString(EnderDashboard.this.font, Component.literal(status).withStyle(net.minecraft.ChatFormatting.GRAY), x + 200, y + (entryHeight - 8) / 2, statusColor);
                 }

                 if (this.removeBtn != null) {
                     this.removeBtn.setX(x + 240);
                     this.removeBtn.setY(y + (entryHeight - 20) / 2);
                     this.removeBtn.render(guiGraphics, mouseX, mouseY, partialTick);
                 }
             }

             /**
              * 点击事件，契约见 ObjectSelectionList.Entry#mouseClicked。
              *
              * 仅处理「移除」按钮命中，其余情况一律返回 false。
              */
             @Override
             public boolean mouseClicked(double mouseX, double mouseY, int button) {
                 if (this.removeBtn != null && this.removeBtn.mouseClicked(mouseX, mouseY, button)) {
                     return true;
                 }
                 return false;
             }

             /**
              * 朗读文本，直接使用玩家名，契约见 ObjectSelectionList.Entry#getNarration。
              */
             @Override
             public Component getNarration() {
                 return Component.literal(name);
             }
        }
    }

    /**
     * 房主房间管理的子页。
     *
     * 子页之间没有先后依赖，任意两个都可以直接切换：切换只重建右侧内容面板，
     * 左侧菜单按钮的可用态由 updateRoomPageMenuButtons 统一刷新（当前页置灰）。
     * 六个子页共用同一份房间管理镜像字段，任一处修改都会置 roomStateDirty。
     *
     * P3 计划把这些子页重整为 5 个页面：概览并入首页，规则并入世界页，后端并入网络页
     * （见 claude_docs/04-uiux-plan.md §8）。
     */
    public enum RoomPage {
        /** 概览：房间号、玩家数与网络质量，另含备注、复制房间号与关闭房间入口。 */
        OVERVIEW,

        /** 权限：访客权限、白名单开关与三个名单的人数概况。 */
        PERMISSIONS,

        /** 规则：允许作弊、保留物品、PVP、天气锁定与游戏规则跳转。 */
        RULES,

        /** 世界：重生点与世界边界。 */
        WORLD,

        /** 网络：自动重连开关与重试次数。 */
        NETWORK,

        /** 后端：版本、更新策略、日志级别、CPU/内存限制与配置导入导出。 */
        BACKEND
    }

    /** 当前视图模式，默认 FULL；构造后不再改变。 */
    private ViewMode currentMode = ViewMode.FULL;

    /** 当前选中的房间管理子页，默认 OVERVIEW。 */
    private RoomPage currentRoomPage = RoomPage.OVERVIEW;

    /**
     * 构造控制台界面，使用完整视图模式。
     *
     * @param parent 父屏幕，允许为 null；为 null 时关闭本屏幕会退回游戏界面
     */
    public EnderDashboard(Screen parent) {
        this(parent, ViewMode.FULL);
    }

    /**
     * 构造控制台界面。
     *
     * 同时把三项客户端配置读入临时字段，供概览页以外的保存动作使用。
     *
     * @param parent 父屏幕，允许为 null；为 null 时关闭本屏幕会退回游戏界面
     * @param mode 视图模式，不能为 null
     */
    public EnderDashboard(Screen parent, ViewMode mode) {
        super(Component.literal("末影联机中心"), parent);
        this.currentMode = mode;
        this.tempPath = ConfigForge.EXTERNAL_ender_PATH.get();
        this.tempAutoUpdate = ConfigForge.AUTO_UPDATE.get();
        this.tempAutoStart = ConfigForge.AUTO_START_BACKEND.get();
    }

    /**
     * 设置连接状态标记。
     *
     * 由 HostScreen 在托管成功后调用。注意写入的是静态字段 wasConnected，
     * 会同时影响其它控制台实例，因此调用方需自行保证同一时刻只有一个实例。
     *
     * @param connected 是否已连接到后端
     */
    public void setConnected(boolean connected) {
        wasConnected = connected;
    }

    /**
     * 检查剪贴板中的房间号，命中时自动打开入房界面。
     *
     * 剪贴板内容必须完全匹配 U/XXXX-XXXX-XXXX-XXXX（大写字母与数字）；
     * 已连接状态下直接跳过；同一内容只会触发一次（与 lastClipboard 比对）。
     *
     * 副作用：命中时经 Minecraft#execute 把屏幕切到 JoinScreen 并预填房间号。
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
                        if (this.minecraft != null) this.minecraft.execute(() -> {
                             this.minecraft.setScreen(new JoinScreen(this, finalClipboard));
                        });
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("failed to inspect clipboard for a room code", e);
        }
    }

    /**
     * 填充内容区，契约见 EnderBaseScreen#initContent。
     *
     * 先做一次「静态快照与实际后端状态」的对齐，再按 wasConnected 分流：
     * 1. 实际状态为 IDLE 时清空连接标记与状态快照。
     * 2. 实际状态为 HOSTING/JOINING 时若缓存的 host/guest 身份与实际不符，
     *    丢弃快照，避免把上一个房间的成员列表显示出来。
     * 3. 快照可用时以其 state 字段校正 wasConnected，不一致时立即重新查询一次。
     *
     * 本方法会被 updateBackendState 通过 this.init(...) 间接重复调用，必须保持幂等。
     */
    @Override
    protected void initContent() {
        // 对齐第 1、2 条：静态快照可能与后端实际状态不一致
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

        // 访客列表组件随每次重建一起丢弃，避免指向已经失效的列表
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

        if (!wasConnected || (wasConnected && lastStateJson == null)) {
             checkStateImmediately();
        }

        if (wasConnected) {
            initConnectedContent();
        } else {
            initIdleContent();
        }
    }

    /**
     * 立即查询一次后端状态并对齐界面。
     *
     * 后端未分配动态端口时直接返回。状态发生变化或快照缺失时写入快照并经
     * Minecraft#execute 重建控件；IDLE 状态只在实际已连接时才触发重建。
     *
     * 幂等性：仅在状态确实变化时重建，重复调用不会造成多余刷新。
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
                            this.minecraft.execute(this::rebuildWidgets);
                        } else if (isConnected && lastStateJson == null) {
                            lastStateJson = json;
                            this.minecraft.execute(this::rebuildWidgets);
                        }
                    } else if (json.has("status") && "IDLE".equals(json.get("status").getAsString())) {
                        if (wasConnected) {
                            wasConnected = false;
                            this.isUiConnected = false;
                            lastStateJson = null;
                            this.minecraft.execute(this::rebuildWidgets);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("failed to parse backend state json", e);
                }
            }
        });
    }

    /**
     * 用外部提供的状态对象刷新界面。
     *
     * 由 JoinScreen 在入房成功后调用：写入状态快照、同步连接标记，并立即重建控件。
     * 注意写入的是静态字段与静态快照，会同时影响其它控制台实例。
     *
     * @param json 状态对象，允许为 null，为 null 时直接返回且不改变界面
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
     * 构建「已连接」状态下的内容区。
     *
     * 判定身份与阶段：优先读状态快照的 state 字段，快照缺失时退回 EnderApiClient 的枚举状态。
     * 处于 host-starting / guest-starting 时只显示一行「请求中」并返回；否则按 host/guest
     * 分别进入房间管理页或访客页，页脚按钮则按视图模式在「断开连接」与「返回」之间选择。
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
        
        if (isStarting) {
            LinearLayout loadingLayout = LinearLayout.vertical().spacing(10);
            loadingLayout.defaultCellSetting().alignHorizontallyCenter();
            loadingLayout.addChild(new StringWidget(Component.translatable("ender.host.status.requesting"), this.font));
            this.layout.addToContents(loadingLayout);
            return;
        }

        if (!isHost) {
            initGuestConnectedContent();
            return;
        }
        initRoomManagementContent();
        if (currentMode == ViewMode.FULL) {
            this.layout.addToFooter(Button.builder(Component.literal("断开连接"), button -> {
                EnderApiClient.setIdle();
                new Thread(ProcessLauncher::stop, "Ender-Stopper").start();
                wasConnected = false;
                this.isUiConnected = false;
                this.rebuildWidgets();
            }).width(200).build());
        } else {
            this.layout.addToFooter(Button.builder(Component.literal("返回"), button -> {
                this.onClose();
            }).width(200).build());
        }
    }

    /**
     * 构建访客视角的「已连接」内容区。
     *
     * 页脚为并排的「断开连接」与「返回」（各宽 80）；正文是一行居中标题、
     * 一个覆盖正文区域的玩家列表，以及列表下方居中的「加入游戏」按钮。
     *
     * 列表坐标硬编码：顶部为头部高度加 35 像素，底部为屏幕高度减页脚高度再减 30 像素，宽 300 居中。
     */
    private void initGuestConnectedContent() {
        
        LinearLayout footerLayout = LinearLayout.horizontal().spacing(10);
        
        footerLayout.addChild(Button.builder(Component.literal("断开连接"), b -> {
            EnderApiClient.setIdle();
            new Thread(ProcessLauncher::stop, "Ender-Stopper").start();
            wasConnected = false; 
            this.isUiConnected = false;
            this.onClose();
        }).width(80).build());
        
        footerLayout.addChild(Button.builder(Component.literal("返回"), b -> this.onClose()).width(80).build());
        
        this.layout.addToFooter(footerLayout);

        
        // 正文区：标题行下方 25 像素处开始，结束时为屏幕底部减去页脚区域与 30 像素留白
        int headerHeight = this.layout.getHeaderHeight() + 10;
        int footerHeight = this.layout.getFooterHeight() + 10;
        
        int titleY = headerHeight;
        StringWidget title = new StringWidget(0, titleY, this.width, 20, Component.literal("房间玩家列表"), this.font);
        title.alignCenter();
        this.addRenderableWidget(title);
        
        int listTop = titleY + 25;
        int listBottom = this.height - footerHeight - 30;
        int listWidth = 300;
        int listX = (this.width - listWidth) / 2;
        
        PlayerListScrollWidget list = new PlayerListScrollWidget(this.minecraft, listWidth, listBottom - listTop, listTop, listBottom);
        list.updateWidgetSize(listWidth, listBottom - listTop, listTop, listBottom, listX);
        list.updateEntries(lastStateJson);
        this.playerListWidget = list;
        this.addRenderableWidget(list);

        Button joinGameBtn = Button.builder(Component.literal("加入游戏"), b -> {
            String ip = EnderApiClient.getHostIp();
            if (ip != null) {
                int port = EnderApiClient.getRemoteMcPort();
                this.connectToServer(ip + ":" + port);
            }
        }).width(200).build();
        joinGameBtn.setPosition((this.width - 200) / 2, listBottom + 5);
        this.addRenderableWidget(joinGameBtn);
    }

    /**
     * 把当前玩家列表作为只读文本行追加到给定布局。
     *
     * 与 PlayerListScrollWidget 不同，这里生成的是不可交互的 StringWidget。
     * 兼容 profiles（取 name，HOST 追加「房主」标记）与 players 两种格式；profiles 优先。
     * 当前没有任何调用点。
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
     * 把三项临时配置写回并持久化到 ender.toml。
     *
     * 当前没有任何调用点（控制台未提供保存入口，实际保存由 EnderConfigScreen 完成）。
     */
    private void saveConfig() {
        ConfigForge.EXTERNAL_ender_PATH.set(this.tempPath);
        ConfigForge.AUTO_UPDATE.set(this.tempAutoUpdate);
        ConfigForge.AUTO_START_BACKEND.set(this.tempAutoStart);
        ConfigForge.CLIENT_SPEC.save();
    }

    /**
     * 连接到底端指定的 MC 服务器。
     *
     * 入参形如 host:port；不含端口时按 25565 处理，解析失败则静默忽略（不提示用户）。
     * 使用 EnderDashboard 的父屏幕作为返回目标。
     *
     * @param connectUrl 连接地址，格式为 host 或 host:port，不能为 null
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
     * 构建未连接（空闲）状态下的内容区。
     *
     * 内容区为两个宽 200 的按钮：「加入房间」（后端未就绪时改为先走 StartupScreen）与「设置」；
     * 页脚是「退出」。同时把界面连接标记复位为 false。
     */
    private void initIdleContent() {
        this.isUiConnected = false;
        LinearLayout contentLayout = LinearLayout.vertical().spacing(15);

        contentLayout.addChild(Button.builder(Component.literal("加入房间"), button -> {
            if (EnderApiClient.hasDynamicPort()) {
                this.minecraft.setScreen(new JoinScreen(this));
            } else {
                this.minecraft.setScreen(new StartupScreen(this.parent));
            }
        }).width(200).build());

        contentLayout.addChild(Button.builder(Component.literal("设置"), button -> {
            this.minecraft.setScreen(new EnderConfigScreen(this));
        }).width(200).build());

        this.layout.addToContents(contentLayout);

        this.layout.addToFooter(Button.builder(Component.literal("退出"), button -> {
            this.onClose();
        }).width(200).build());
    }

    /**
     * 每刻更新，契约见 Screen#tick。
     *
     * 两条互相独立的节流链：
     * 1. 每 1000ms 查询一次后端状态（记录请求起始时间用于估算延迟）；后端未就绪时只把状态文案
     *    置为「未启动」。
     * 2. 房主已连接时每 500ms 处理一次房间管理状态：本地有脏改动就推送到后端并把改动应用到服务器，
     *    否则每 2000ms 从后端拉取一次快照合并到本地。
     */
    @Override
    public void tick() {
        super.tick();
        
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
     * 渲染屏幕，契约见 EnderBaseScreen#render。
     *
     * 仅在 FULL 与 INGAME_INFO 模式下绘制一行状态标语；状态尚未取得时只显示文案本身，
     * 否则加上语言键 ender.dashboard.status_prefix 的前缀。
     */
    @Override
    public void render(net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        if (this.currentMode == ViewMode.FULL || this.currentMode == ViewMode.INGAME_INFO) {
            // 状态标语贴在头部下方 5 像素处，水平居中
            int textY = this.layout.getHeaderHeight() + 5;
            guiGraphics.drawCenteredString(this.font, Component.translatable("ender.dashboard.status.fetching").getString().equals(this.backendState) ? 
                this.backendState : Component.translatable("ender.dashboard.status_prefix").append(this.backendState).getString(), this.width / 2, textY, 0xAAAAAA);
        }
    }

    /**
     * 解析一次状态响应并驱动界面刷新。
     *
     * 状态来源有两套：新协议直接给 state 字段，旧协议给 status 枚举值，本方法把后者映射到前者。
     * 随后按需要决定是否重建界面（连接状态变化或已连接但缺少快照时），
     * 并把连接时延换算成网络质量分档（不高于 80ms 优秀、150ms 良好、250ms 一般，其余较差）。
     * 未连接时清空状态快照，避免残留上一个房间的成员列表。
     *
     * 副作用：更新 backendState、lastPingMs、networkQualityLabel 与 networkQualityColor。
     *
     * @param stateJson 状态响应 JSON 字符串，允许为 null，为 null 时不产生任何副作用
     * @param startedAt 请求发起时刻的毫秒时间戳，用于估算往返时延
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

                    // 时延分档：越慢颜色越偏红，用于概览页的「网络质量」一行
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
             } catch (Exception e) {}
        }
    }

    /**
     * 判断当前快照是否表示「房主已连接」。
     *
     * @return 快照存在且 state 为 host-ok 时返回 true；快照缺失或缺少 state 字段时返回 false
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
     * 用后端返回的字符串刷新房间管理镜像字段。
     *
     * 本地存在未推送的改动时直接放弃本次合并（避免覆盖用户操作）；合并后若确有字段变化，
     * 会把结果应用到服务器并重建界面。
     *
     * @param stateJson 后端返回的状态 JSON 字符串，允许为 null，为 null 时直接返回
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
        } catch (Exception e) {
        }
    }

    /**
     * 把后端下发的房间管理状态合并到本地镜像字段。
     *
     * 逐键判定，仅当键存在时才写入；标量字段只在值确实变化时置 changed，
     * 数组字段（whitelist / blacklist / mute_list / operation_logs / backend_versions）一律视为变化。
     * 缺失的键保持本地现值不变。
     *
     * @param json 后端返回的状态对象，不能为 null
     * @return 任一字段发生变化时返回 true，否则返回 false
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
     * 把本地镜像字段序列化为房间管理状态对象。
     *
     * 键名与后端约定的下划线命名一一对应，缺失的键会被后端视为「不下发该项」。
     * 数组字段在被置为 null 时序列化为空数组，保证后端不会收到 null。
     *
     * @return 状态对象，永不为 null
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
     * 构建房主视角的房间管理内容区：左侧子页菜单 + 右侧内容面板。
     *
     * 备注为空时会先向后端同步查询一次以补全（避免每次重建都闪一下空描述）。
     * 坐标硬编码：菜单左边界 10、宽 120；内容区左边界为 120 + 30 = 150，右边界留 10 像素；
     * 两者顶部均为头部高度加 10 像素，高度为屏幕高度减去头尾区域再减 20 像素。
     */
    private void initRoomManagementContent() {
        
        if (roomRemark == null || roomRemark.isEmpty()) {
            JsonObject state = EnderApiClient.getRoomManagementStateSync();
            if (state != null && state.has("room_remark")) {
                roomRemark = state.get("room_remark").getAsString();
            }
        }

        // 左侧菜单宽 120，与右侧内容区之间留 30 像素间距，右侧再留 10 像素边距
        int menuWidth = 120;
        int spacing = 30;
        int contentX = menuWidth + spacing;
        int contentWidth = this.width - contentX - 10;
        
        LinearLayout menu = LinearLayout.vertical().spacing(6);
        menu.defaultCellSetting().alignHorizontallyLeft();
        roomPageButtons.clear();
        for (RoomPage page : RoomPage.values()) {
            addRoomPageMenuButton(menu, page, menuWidth);
        }
        updateRoomPageMenuButtons();
        
        
        menu.arrangeElements();
        // 菜单贴左上角：左 10 像素，顶部让出头部高度再留 10 像素
        menu.setPosition(10, this.layout.getHeaderHeight() + 10);
        
        this.roomPagePanel = new RoomPagePanel(contentWidth, this.height - this.layout.getHeaderHeight() - this.layout.getFooterHeight() - 20);
        this.roomPagePanel.setPosition(contentX, this.layout.getHeaderHeight() + 10);
        
        rebuildRoomPageContent(false);
        
        
        
        
        menu.visitWidgets(this::addRenderableWidget);
        
    }

    /**
     * 为某个子页创建菜单按钮并挂到左侧菜单栏。
     *
     * 按钮同时按顺序记入 roomPageButtons，保证下标与 RoomPage.values() 一一对应。
     *
     * @param menu 左侧菜单布局，不能为 null
     * @param page 目标子页，不能为 null
     * @param width 按钮宽度，单位像素
     */
    private void addRoomPageMenuButton(LinearLayout menu, RoomPage page, int width) {
        Button button = Button.builder(Component.literal(getRoomPageTitle(page)), b -> switchToPage(page)).width(width).build();
        roomPageButtons.add(button);
        menu.addChild(button);
    }

    /**
     * 切换房间管理子页。
     *
     * 幂等性：切换到当前页时直接返回，不触发重建。
     * 切换后会刷新菜单可用态并重建右侧内容。
     *
     * @param page 目标子页，不能为 null
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
     * 刷新左侧菜单按钮的可用态：当前子页置灰，其余可点。
     *
     * 依赖 roomPageButtons 与 RoomPage.values() 下标一致，因此循环取两者长度的较小值以容错。
     */
    private void updateRoomPageMenuButtons() {
        RoomPage[] pages = RoomPage.values();
        for (int i = 0; i < pages.length && i < roomPageButtons.size(); i++) {
            roomPageButtons.get(i).active = pages[i] != currentRoomPage;
        }
    }

    /**
     * 重建右侧子页内容。
     *
     * 先摘除上一页注册过的全部控件，再按当前子页调用对应的构建方法，最后统一注册新控件。
     *
     * NOTE: 参数 registerWidgets 目前不起作用：为 true 时在 visitWidgets 回调里注册，
     * 为 false 时改由末尾的补充分支注册，两条路径的结果完全相同。拆分（P3）时应删除该参数。
     */
    private void rebuildRoomPageContent(boolean registerWidgets) {
        if (roomPagePanel == null) {
            return;
        }
        
        
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
     * 返回子页菜单上显示的中文标题。
     *
     * @param page 子页，不能为 null
     * @return 标题文案；枚举新增取值而未同步补充分支时会抛 IllegalArgumentException
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
     * 子页内容容器。
     *
     * 只服务本类的最小 Layout 实现：自身尺寸固定，把唯一的内容子树在水平方向居中、垂直方向顶对齐。
     * 由于强制固定宽高，放在其中的内容若比容器更宽会被裁掉而不是换行。
     *
     * 线程安全性：只在客户端主线程使用。
     */
    private static class RoomPagePanel extends AbstractLayout implements Layout {
        /** 唯一的内容子树，允许为 null（尚未设置内容）。 */
        private LayoutElement content;

        /** 容器固定宽度，单位像素；每次排布后回写以抵消内容对尺寸的影响。 */
        private final int fixedWidth;

        /** 容器固定高度，单位像素；每次排布后回写以抵消内容对尺寸的影响。 */
        private final int fixedHeight;

        /**
         * 构造内容容器。
         *
         * @param width 固定宽度，单位像素
         * @param height 固定高度，单位像素
         */
        private RoomPagePanel(int width, int height) {
            super(0, 0, width, height);
            this.fixedWidth = width;
            this.fixedHeight = height;
        }

        /**
         * 设置内容子树。
         *
         * @param content 新的内容子树，允许为 null
         */
        private void setContent(LayoutElement content) {
            this.content = content;
        }

        /**
         * 遍历子元素，契约见 Layout#visitChildren。
         *
         * 最多暴露一个元素，即当前内容子树。
         */
        @Override
        public void visitChildren(Consumer<LayoutElement> consumer) {
            if (content != null) {
                consumer.accept(content);
            }
        }

        /**
         * 排布子元素，契约见 Layout#arrangeElements。
         *
         * 先让内容自身完成排布，再把它水平居中、垂直顶对齐到本容器的原点，
         * 最后把自身宽高复位为构造时的固定值。
         */
        @Override
        public void arrangeElements() {
            if (content instanceof Layout layout) {
                layout.arrangeElements();
            }
            if (content != null) {
                // 内容在容器内水平居中：偏移量为容器与内容宽度差的一半
                int contentW = content.getWidth();
                int offset = (this.fixedWidth - contentW) / 2;
                content.setPosition(this.getX() + offset, this.getY());
            }
            this.width = fixedWidth;
            this.height = fixedHeight;
        }
    }

    /**
     * 构建「房间概览」子页。
     *
     * 自上而下为：房间号、玩家数、网络质量三行只读文本；「房间描述」输入框与「保存」按钮
     * （访客不可编辑，按钮也会置灰）；「复制房间号」与「关闭房间」两个操作按钮。
     * 房间号取自状态快照的 room 字段，缺失时显示「未知」。
     */
    private void addOverviewPage(LinearLayout content) {
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
            ClientSetupForge.showToast(Component.literal("提示"), Component.literal("房间描述已保存"));
        }).width(44).build();
        saveBtn.active = isHostConnected();
        remarkLayout.addChild(saveBtn);
        roomInfo.addChild(remarkLayout);

        roomInfo.addChild(Button.builder(Component.literal("复制房间号"), button -> {
            try {
                this.minecraft.keyboardHandler.setClipboard(finalRoomCode);
                ClientSetupForge.showToast(Component.literal("提示"), Component.literal("房间号已复制"));
            } catch (Exception e) {
                ClientSetupForge.showToast(Component.literal("提示"), Component.literal("复制失败，请手动复制房间号"));
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
     * 构建「权限与访客」子页。
     *
     * 自上而下为：标题、访客权限循环按钮、白名单开关按钮、「详细名单管理」按钮
     * （跳到 RoomListsScreen）、三个名单的人数概况（数据来自同步查询而非镜像字段）。
     * 名单概况使用实时同步查询，因此在网络阻塞时构成本页会被卡住。
     */
    private void addPermissionsPage(LinearLayout content) {
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
            ClientSetupForge.showToast(Component.literal("提示"), Component.literal("访客权限已更新"));
        }).width(200).build();
        permission.addChild(permissionBtn);

        permission.addChild(Button.builder(Component.literal("白名单启用: " + (whitelistEnabled ? "开" : "关")), button -> {
            whitelistEnabled = !whitelistEnabled;
            button.setMessage(Component.literal("白名单启用: " + (whitelistEnabled ? "开" : "关")));
            roomStateDirty = true;
            ClientSetupForge.showToast(Component.literal("提示"), Component.literal("白名单设置已更新"));
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
    
    /**
     * 把一份名单渲染成带「移除」按钮的条目并追加到列表。
     *
     * 移除会直接改动传入的数组（由调用方持有），置脏标记后重建当前子页并弹出提示。
     * 当前没有任何调用点；每行条目的类型文案由标题去掉「列表」后缀得到。
     *
     * @param widget 目标列表，不能为 null
     * @param title 名单标题，用于生成类型文案与提示文本
     * @param list 名单数组，允许为 null 或空数组（此时不做任何事）
     */
    private void appendListToScroll(PlayerListScrollWidget widget, String title, JsonArray list) {
        if (list == null || list.size() == 0) return;
        for (JsonElement el : list) {
             if (el != null && el.isJsonPrimitive()) {
                 String name = el.getAsString();
                 String typeStr = title.replace("列表", "");
                 widget.addItem(name, typeStr, b -> {
                    removeNameFromArray(list, name);
                    roomStateDirty = true;
                    rebuildRoomPageContent(true);
                    ClientSetupForge.showToast(Component.literal("提示"), Component.literal("已从" + title + "移除 " + name));
                 });
             }
        }
    }

    /**
     * 名单表头组件。
     *
     * 只负责绘制「游戏名 / 名单类型 / 操作」三列标题，不响应任何输入（active 恒为 false）。
     * 列位置与 PlayerListScrollWidget.Entry 的列位置保持一致（左缩进 10、140、240）。
     *
     * 线程安全性：只在客户端主线程使用。
     */
    private class ListHeaderWidget extends AbstractWidget {
        /**
         * 构造表头组件。
         *
         * @param width 组件宽度，单位像素；仅在命中判定中使用
         */
        public ListHeaderWidget(int width) {
            super(0, 0, width, 20, Component.empty());
            this.active = false;
        }

        /**
         * 绘制三列表头，契约见 AbstractWidget#renderWidget。
         *
         * 三列基线均相对组件左上角：列名 +10、类型 +140、操作 +240，行内下移 6 像素。
         */
        @Override
        public void renderWidget(net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
             guiGraphics.drawString(EnderDashboard.this.font, Component.literal("游戏名").withStyle(net.minecraft.ChatFormatting.YELLOW), getX() + 10, getY() + 6, 0xFFFFFF);
             guiGraphics.drawString(EnderDashboard.this.font, Component.literal("名单类型").withStyle(net.minecraft.ChatFormatting.YELLOW), getX() + 140, getY() + 6, 0xFFFFFF);
             guiGraphics.drawString(EnderDashboard.this.font, Component.literal("操作").withStyle(net.minecraft.ChatFormatting.YELLOW), getX() + 240, getY() + 6, 0xFFFFFF);
        }

        /**
         * 无障碍朗读内容，本组件不可交互，故留空实现。
         */
        @Override
        protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput narrationElementOutput) {}
    }







    /** 是否允许 PVP，默认 true；字段被声明在类的靠后位置（规则页附近），与其它镜像字段分离。 */
    private boolean pvpAllowed = true;

    /**
     * 构建「规则与玩法」子页。
     *
     * 自上而下为：允许作弊、保留物品、允许 PVP、天气锁定四个开关按钮（均只改本地字段并置脏）、
     * 「更多游戏规则设置」按钮（打开原版 EditGameRulesScreen 并回写服务器）、
     * 以及「应用到当前世界」按钮（把镜像字段一次性写入服务器）。
     */
    private void addRulesPage(LinearLayout content) {
        LinearLayout rules = LinearLayout.vertical().spacing(6);
        rules.defaultCellSetting().alignHorizontallyCenter();

        rules.addChild(Button.builder(Component.literal("允许作弊: " + (allowCheats ? "开" : "关")), b -> {
            allowCheats = !allowCheats;
            b.setMessage(Component.literal("允许作弊: " + (allowCheats ? "开" : "关")));
            roomStateDirty = true;
            ClientSetupForge.showToast(Component.literal("提示"), Component.literal("规则已更新"));
        }).width(200).build());
        rules.addChild(Button.builder(Component.literal("保留物品: " + (keepInventory ? "开" : "关")), b -> {
            keepInventory = !keepInventory;
            b.setMessage(Component.literal("保留物品: " + (keepInventory ? "开" : "关")));
            roomStateDirty = true;
            ClientSetupForge.showToast(Component.literal("提示"), Component.literal("规则已更新"));
        }).width(200).build());
        rules.addChild(Button.builder(Component.literal("允许PVP: " + (pvpAllowed ? "开" : "关")), b -> {
            pvpAllowed = !pvpAllowed;
            b.setMessage(Component.literal("允许PVP: " + (pvpAllowed ? "开" : "关")));
            roomStateDirty = true;
            ClientSetupForge.showToast(Component.literal("提示"), Component.literal("规则已更新"));
        }).width(200).build());
        rules.addChild(Button.builder(Component.literal("天气锁定: " + (weatherLock ? "开" : "关")), b -> {
            weatherLock = !weatherLock;
            b.setMessage(Component.literal("天气锁定: " + (weatherLock ? "开" : "关")));
            roomStateDirty = true;
            ClientSetupForge.showToast(Component.literal("提示"), Component.literal("规则已更新"));
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
     * 构建「世界与边界」子页。
     *
     * 两组控件：重生点（X/Y/Z 三个宽 64 的输入框 + 「应用重生点」）与世界边界
     * （中心 X/中心 Z/半径 + 「应用世界边界」）。输入框初值优先取玩家当前位置，
     * 取不到时退回镜像字段；每次输入都经 parseIntSafe 容错并置脏。
     */
    private void addWorldPage(LinearLayout content) {
        LinearLayout world = LinearLayout.vertical().spacing(6);
        world.defaultCellSetting().alignHorizontallyCenter();

        // 初值优先用玩家当前坐标，玩家不存在（例如刚进界面）时退回已保存的重生点
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
            ClientSetupForge.showToast(Component.literal("提示"), Component.literal("重生点已应用"));
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
            ClientSetupForge.showToast(Component.literal("提示"), Component.literal("世界边界已应用"));
        }).width(200).build());
        content.addChild(world);
    }

    /**
     * 构建「网络与容灾」子页。
     *
     * 只有两项：「自动重连」开关按钮与「重试次数」输入框（宽 200）。
     * 这两项目前只参与序列化回推，后端与客户端都没有实际的自动重连实现。
     */
    private void addNetworkPage(LinearLayout content) {
        LinearLayout network = LinearLayout.vertical().spacing(6);
        network.defaultCellSetting().alignHorizontallyCenter();
        network.addChild(Button.builder(Component.literal("自动重连: " + (autoReconnect ? "开" : "关")), b -> {
            autoReconnect = !autoReconnect;
            b.setMessage(Component.literal("自动重连: " + (autoReconnect ? "开" : "关")));
            roomStateDirty = true;
            ClientSetupForge.showToast(Component.literal("提示"), Component.literal("设置已更新"));
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
     * 构建「后端与性能」子页。
     *
     * 自上而下为：版本循环按钮（取值来自 backendVersions，数组为空时不改变）、更新策略、
     * 日志级别、CPU 限制与内存限制输入框、「导出设置」与「导入设置」按钮（走剪贴板），
     * 以及最后 5 条操作日志。
     */
    private void addBackendPage(LinearLayout content) {
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
                ClientSetupForge.showToast(Component.literal("提示"), Component.literal("设置已更新"));
            }
        }).width(200).build());
        backend.addChild(Button.builder(Component.literal("更新策略: " + updatePolicy), b -> {
            updatePolicy = "立即".equals(updatePolicy) ? "延后" : "立即";
            b.setMessage(Component.literal("更新策略: " + updatePolicy));
            roomStateDirty = true;
            ClientSetupForge.showToast(Component.literal("提示"), Component.literal("设置已更新"));
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
            ClientSetupForge.showToast(Component.literal("提示"), Component.literal("设置已更新"));
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
     * 把规则镜像字段写入本机单人服务器。
     *
     * 保留物品与天气循环直接走 GameRules，其余三条（火焰蔓延、生物生成、昼夜循环）用候选字段名
     * 反射写入；时间控制非 cycle 时把世界时间对齐到黑夜 13000 或白天 1000。
     * 最后写回 PVP、作弊权限与出生点保护。服务器实例不存在时整体跳过（例如玩家不在单人世界）。
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
     * 把房间管理镜像字段整体应用到本机服务器。
     *
     * 依次写入 MOTD（备注）、规则与访问控制。服务器实例不存在时整体跳过。
     * 由 tick 的脏推送路径与后端状态合并路径共同调用。
     */
    private void applyRoomManagementStateToServer() {
        IntegratedServer server = this.minecraft.getSingleplayerServer();
        if (server == null) {
            return;
        }
        
        
        if (this.roomRemark != null) {
            try {
                server.setMotd(this.roomRemark);
            } catch (Exception e) {
            }
        }

        applyRulesToServer();
        enforceAccessControl(server);
    }

    /**
     * 按黑/白名单与访客权限清理本机服务器上的在线玩家。
     *
     * 房主本人始终跳过；黑名单命中即踢出；白名单启用且未命中则踢出；
     * 访客权限为「禁止进入」时踢出全部非房主玩家；「仅观战」与「仅聊天」分别切换为
     * 旁观者与冒险模式。该动作在每个脏推送周期都会重跑，因此被踢出的玩家重新加入后会被再次踢出。
     *
     * @param server 本机集成服务器实例，不能为 null
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
     * 向名单追加玩家名，已存在（忽略大小写）时不重复添加。
     *
     * 当前没有任何调用点。
     *
     * @param array 目标名单，允许为 null，为 null 时直接返回
     * @param name 玩家名，允许为 null，为 null 时直接返回
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
     * 从名单中移除第一个同名玩家（忽略大小写）。
     *
     * 只移除首条命中项，不做全量清理。
     *
     * @param array 目标名单，允许为 null，为 null 时直接返回
     * @param name 玩家名，允许为 null，为 null 时直接返回
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
     * 判断名单中是否存在指定玩家名（忽略大小写）。
     *
     * @param array 名单数组，允许为 null，为 null 时返回 false
     * @param name 玩家名，允许为 null，为 null 时返回 false
     * @return 命中返回 true，否则返回 false
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
     * 断开指定玩家的连接。
     *
     * 连接已失效时静默忽略，避免中断 tick 循环。
     *
     * @param player 目标玩家，不能为 null
     * @param reason 断开原因，会展示在玩家侧，不能为 null
     */
    private void disconnectPlayer(ServerPlayer player, Component reason) {
        try {
            player.connection.disconnect(reason);
        } catch (Exception e) {
        }
    }

    /**
     * 切换玩家游戏模式。
     *
     * 通过反射调用 setGameMode，属 ADR-03 待消除的回退链；失败时静默忽略。
     *
     * @param player 目标玩家，不能为 null
     * @param type 目标游戏模式，不能为 null
     */
    private void setPlayerGameType(ServerPlayer player, GameType type) {
        try {
            java.lang.reflect.Method m = player.getClass().getMethod("setGameMode", GameType.class);
            m.invoke(player, type);
        } catch (Exception e) {
        }
    }

    /**
     * 写回「允许所有玩家使用命令」开关。
     *
     * 依次尝试 Mojmap 的 setAllowCheatsForAllPlayers 与 Yarn 遗留的
     * setAllowCommandsForAllPlayers，都失败时静默忽略（ADR-03 待消除的回退链）。
     *
     * @param server 本机集成服务器实例，不能为 null
     * @param value 目标开关值
     */
    private void setCheatsAllowed(IntegratedServer server, boolean value) {
        try {
            Object playerList = server.getPlayerList();
            java.lang.reflect.Method m = playerList.getClass().getMethod("setAllowCheatsForAllPlayers", boolean.class);
            m.invoke(playerList, value);
            return;
        } catch (Exception e) {
        }
        try {
            Object playerList = server.getPlayerList();
            java.lang.reflect.Method m = playerList.getClass().getMethod("setAllowCommandsForAllPlayers", boolean.class);
            m.invoke(playerList, value);
        } catch (Exception e) {
        }
    }

    /**
     * 写回出生点保护半径。
     *
     * 半径先做 Math.max(0, value) 归一化；随后依次尝试 setSpawnProtectionRadius 与
     * Yarn 遗留的 setSpawnProtection，都失败时静默忽略。
     *
     * @param server 本机集成服务器实例，不能为 null
     * @param value 目标半径，单位方块，负值会被归一化为 0
     */
    private void setSpawnProtection(IntegratedServer server, int value) {
        int radius = Math.max(0, value);
        try {
            Object playerList = server.getPlayerList();
            java.lang.reflect.Method m = playerList.getClass().getMethod("setSpawnProtectionRadius", int.class);
            m.invoke(playerList, radius);
            return;
        } catch (Exception e) {
        }
        try {
            Object playerList = server.getPlayerList();
            java.lang.reflect.Method m = playerList.getClass().getMethod("setSpawnProtection", int.class);
            m.invoke(playerList, radius);
        } catch (Exception e) {
        }
    }

    /**
     * 按候选字段名反射写入布尔型游戏规则。
     *
     * 定位链路：从 GameRules 上取出静态字段作为规则键，再在 gameRules 实例上找 getRule（先精确匹配
     * 参数类型，失败后退化为按名字与参数个数匹配），最后在规则对象上找第一个「首参为 boolean」的
     * 双参 set 方法写入。任一环节失败即继续尝试下一个候选字段名。
     * 这是本类中最长的一条反射回退链，属 ADR-03 的待消除目标。
     *
     * @param server 本机集成服务器实例，允许为 null，为 null 时直接返回
     * @param value 目标规则值
     * @param fieldNames 候选的 GameRules 静态字段名，按优先级排列；允许为 null
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
                } catch (Exception e) {
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
            } catch (Exception e) {
            }
        }
    }

    /**
     * 设置世界时间。
     *
     * 依次尝试 setDayTime 与 Yarn 遗留的 setTimeOfDay，都失败时静默忽略。
     *
     * @param level 目标世界，允许为 null，为 null 时直接返回
     * @param time 目标时间，单位刻（0 到 24000 为一个完整昼夜）
     */
    private void setWorldTime(ServerLevel level, long time) {
        if (level == null) {
            return;
        }
        try {
            java.lang.reflect.Method m = level.getClass().getMethod("setDayTime", long.class);
            m.invoke(level, time);
            return;
        } catch (Exception e) {
        }
        try {
            java.lang.reflect.Method m = level.getClass().getMethod("setTimeOfDay", long.class);
            m.invoke(level, time);
        } catch (Exception e) {
        }
    }

    /**
     * 把重生点镜像字段应用到本机服务器。
     *
     * 只改主世界；世界时间不变。服务器或主世界不存在时静默跳过。
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
     * 把世界边界镜像字段应用到本机服务器主世界。
     *
     * 单位语义：镜像字段存的是半径，而 WorldBorder#setSize 接收直径，因此写回时乘 2；
     * 半径不大于 0 时只改中心、不缩边界。服务器或主世界不存在时静默跳过。
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
     * 把当前房间管理状态导出到剪贴板。
     *
     * 导出内容为序列化后的 JSON；失败（例如剪贴板不可用）时改为提示「导出失败」。
     */
    private void exportRoomState() {
        try {
            String json = buildRoomManagementStateJson().toString();
            this.minecraft.keyboardHandler.setClipboard(json);
            ClientSetupForge.showToast(Component.literal("提示"), Component.literal("配置已复制到剪贴板"));
        } catch (Exception e) {
            ClientSetupForge.showToast(Component.literal("提示"), Component.literal("导出失败"));
        }
    }

    /**
     * 从剪贴板导入房间管理状态。
     *
     * 剪贴板为空或不是合法 JSON 对象时静默返回；成功合并后置脏标记并重建界面。
     * 导入不做字段校验，缺失的键保持本地现值。
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
        } catch (Exception e) {
        }
    }

    /*
     * NOTE: percentWidth / percentHeight / adaptiveButtonWidth / adaptiveSmallButtonWidth /
     * adaptiveMargin / adaptiveSpacing 这六个自适应辅助方法目前都没有任何调用点，
     * 而各子页仍在硬编码像素坐标，两套方案并存。它们是为 ADR-09（UI 不得使用固定像素定位）
     * 预留的过渡物；P5 引入 LayoutEngine 后应整体替换，届时这几个方法可一并删除。
     */

    /**
     * 把屏幕宽度的百分比换算为像素。
     *
     * 当前没有调用点。
     *
     * @param percent 百分比，取值 0 到 100，超出范围不做校验
     * @return 对应的像素宽度，向下取整
     */
    // TODO(P5, 2026-12-31): 目前无调用点——响应式计算已定义但尚未接入各页面，接入或删除见 claude_docs/04-uiux-plan.md
    private int percentWidth(double percent) {
        return (int) (this.width * percent / 100.0);
    }

    /**
     * 把屏幕高度的百分比换算为像素。
     *
     * 当前没有调用点。
     *
     * @param percent 百分比，取值 0 到 100，超出范围不做校验
     * @return 对应的像素高度，向下取整
     */
    // TODO(P5, 2026-12-31): 目前无调用点——响应式计算已定义但尚未接入各页面，接入或删除见 claude_docs/04-uiux-plan.md
    private int percentHeight(double percent) {
        return (int) (this.height * percent / 100.0);
    }

    /**
     * 计算自适应按钮宽度。
     *
     * 当前没有调用点。
     *
     * @return 按钮宽度，单位像素，取值范围 100 到 250（按屏幕宽度的 1/6 取值后夹逼）
     */
    // TODO(P5, 2026-12-31): 目前无调用点——响应式计算已定义但尚未接入各页面，接入或删除见 claude_docs/04-uiux-plan.md
    private int adaptiveButtonWidth() {
        int base = Math.min(250, Math.max(100, this.width / 6));
        return base;
    }

    /**
     * 计算自适应小按钮宽度。
     *
     * 当前没有调用点。
     *
     * @return 按钮宽度，单位像素，取值范围 80 到 150（按屏幕宽度的 1/10 取值后夹逼）
     */
    // TODO(P5, 2026-12-31): 目前无调用点——响应式计算已定义但尚未接入各页面，接入或删除见 claude_docs/04-uiux-plan.md
    private int adaptiveSmallButtonWidth() {
        int base = Math.min(150, Math.max(80, this.width / 10));
        return base;
    }

    /**
     * 计算自适应边距。
     *
     * 当前没有调用点。
     *
     * @return 边距，单位像素，不小于 10（按屏幕宽度的 1/40 取值）
     */
    // TODO(P5, 2026-12-31): 目前无调用点——响应式计算已定义但尚未接入各页面，接入或删除见 claude_docs/04-uiux-plan.md
    private int adaptiveMargin() {
        return Math.max(10, this.width / 40);
    }

    /**
     * 计算自适应间距。
     *
     * 当前没有调用点。
     *
     * @return 间距，单位像素，不小于 5（按屏幕宽度的 1/80 取值）
     */
    // TODO(P5, 2026-12-31): 目前无调用点——响应式计算已定义但尚未接入各页面，接入或删除见 claude_docs/04-uiux-plan.md
    private int adaptiveSpacing() {
        return Math.max(5, this.width / 80);
    }

    /**
     * 解析整数，失败时返回兜底值。
     *
     * 用于各页面的输入框响应回调：用户输入过程中的空串或非法字符不应清空已有取值。
     *
     * @param value 待解析文本，不能为 null（内部会 trim）
     * @param fallback 解析失败时返回的值
     * @return 解析成功时返回解析结果，否则返回 fallback
     */
    private int parseIntSafe(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
