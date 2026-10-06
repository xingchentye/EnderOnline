/*
 * 本文件属于 EnderOnline NeoForge 适配层。
 *
 * 职责：房间信息只读视图，展示后端状态、房间号与成员数。
 *
 * 关键约束：本屏幕不修改任何房间设置，改动入口在 RoomSettingsScreen 与 RoomListsScreen。
 */
package com.multiplayer.ender.client.gui;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.multiplayer.ender.logic.ProcessLauncher;
import com.multiplayer.ender.network.EnderApiClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

/**
 * 房间信息界面。
 *
 * 由暂停菜单的「房间信息」入口跳入（访客视角），展示当前后端状态的本地化文案、房间号、成员数，
 * 并提供进入详细名单与断开连接的出口。
 *
 * 设计约束：
 * 1. 本屏幕只读：不写回后端房间管理状态。
 * 2. 状态每秒轮询一次，且与上一次原始 JSON 相同时提前返回，避免无谓重建控件。
 * 3. 「断开连接」会同时置空闲并停止本地核心进程，是破坏性操作，无二次确认（待补）。
 *
 * 线程安全性：字段在客户端主线程读写；异步回调通过 {@code minecraft.execute} 回到主线程。
 *
 * @since 1.0
 * @see RoomListsScreen
 */
public class RoomInfoScreen extends EnderBaseScreen {
    /** JSON 解析器，非 null，复用同一实例。 */
    private static final Gson GSON = new Gson();
    /** 上次状态轮询时间戳，单位毫秒（System.currentTimeMillis）。 */
    private long lastStateCheck = 0;
    /** 后端状态的显示文本，非 null，默认取语言键 {@code ender.dashboard.status.fetching}。 */
    private String backendState = Component.translatable("ender.dashboard.status.fetching").getString();
    /** 上次已解析的原始状态 JSON 字符串；允许为 null，用于跳过内容未变的刷新。 */
    private String lastStateRaw = null;
    /** 上次解析出的状态对象；允许为 null，为 null 时房间号回落为「未知」。 */
    private JsonObject lastStateJson = null;

    /**
     * 构造房间信息界面。
     *
     * @param parent 父屏幕，用于返回，允许为 null
     */
    public RoomInfoScreen(Screen parent) {
        super(Component.literal("房间信息"), parent);
    }

    /**
     * 初始化界面内容。
     *
     * 内容区依次是状态行、房间号行、成员数行与「详细列表」按钮；底栏是「断开连接 / 返回」。
     */
    @Override
    protected void initContent() {
        LinearLayout content = LinearLayout.vertical().spacing(8);
        content.defaultCellSetting().alignHorizontallyCenter();

        content.addChild(new StringWidget(Component.literal("状态: " + backendState), this.font));

        String roomCode = lastStateJson != null && lastStateJson.has("room")
                ? lastStateJson.get("room").getAsString()
                : EnderApiClient.getLastRoomCode();
        if (roomCode == null || roomCode.isEmpty()) {
            roomCode = "未知";
        }
        content.addChild(new StringWidget(Component.literal("房间号: " + roomCode), this.font));

        java.util.List<String> members = extractMembers(lastStateJson);
        content.addChild(new StringWidget(Component.literal("成员数: " + members.size()), this.font));
        content.addChild(Button.builder(Component.literal("详细列表"), b -> {
            if (this.minecraft != null) {
                this.minecraft.setScreen(new RoomListsScreen(this));
            }
        }).width(120).build());

        this.layout.addToContents(content);

        LinearLayout footer = LinearLayout.horizontal().spacing(10);
        footer.addChild(Button.builder(Component.literal("断开连接"), button -> {
            EnderApiClient.setIdle();
            new Thread(ProcessLauncher::stop, "Ender-Stopper").start();
            this.onClose();
        }).width(120).build());
        footer.addChild(Button.builder(Component.literal("返回"), button -> this.onClose()).width(120).build());
        this.layout.addToFooter(footer);

        checkStateImmediately();
    }

    /**
     * 每帧更新。
     *
     * 按 1 秒节流轮询后端状态，结果回到主线程后再刷新界面。
     */
    @Override
    public void tick() {
        super.tick();
        long now = System.currentTimeMillis();
        if (now - lastStateCheck > 1000) {
            lastStateCheck = now;
            EnderApiClient.getState().thenAccept(stateJson -> {
                if (stateJson == null) {
                    return;
                }
                if (this.minecraft != null) {
                    this.minecraft.execute(() -> updateBackendState(stateJson));
                }
            });
        }
    }

    /**
     * 渲染界面，透传给基类实现。
     *
     * @param guiGraphics 绘图上下文，不能为 null
     * @param mouseX 鼠标 X 坐标，单位为逻辑像素
     * @param mouseY 鼠标 Y 坐标，单位为逻辑像素
     * @param partialTick 当前帧的部分刻进度，取值 0.0..1.0
     */
    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    /**
     * 立即检查一次状态。
     *
     * 进入界面时调用，跳过 1 秒节流以便首帧就有数据；无动态端口说明后端不可达，直接返回。
     */
    private void checkStateImmediately() {
        if (!EnderApiClient.hasDynamicPort()) {
            return;
        }
        EnderApiClient.getState().thenAccept(stateJson -> {
            if (stateJson == null) {
                return;
            }
            if (this.minecraft != null) {
                this.minecraft.execute(() -> updateBackendState(stateJson));
            }
        });
    }

    /**
     * 更新后端状态显示。
     *
     * 把后端状态码映射到语言键后取本地化文案；未知状态码原样展示。解析失败时保留旧文案。
     *
     * 副作用：内容有变化时会重新调用 {@code init} 重建控件，因此不要在渲染路径中调用。
     *
     * @param stateJson 状态 JSON 字符串；与上次相同时本方法直接返回
     */
    private void updateBackendState(String stateJson) {
        if (stateJson == null || stateJson.equals(this.lastStateRaw)) {
            return;
        }
        this.lastStateRaw = stateJson;
        try {
            JsonObject json = GSON.fromJson(stateJson, JsonObject.class);
            this.lastStateJson = json;
            String state = "";
            if (json.has("state")) {
                state = json.get("state").getAsString();
            }

            String displayKey;
            switch (state) {
                case "idle": displayKey = "ender.state.idle"; break;
                case "host-starting": displayKey = "ender.state.host_starting"; break;
                case "host-scanning": displayKey = "ender.state.host_scanning"; break;
                case "host-ok": displayKey = "ender.state.connected"; break;
                case "guest-starting": displayKey = "ender.state.guest_starting"; break;
                case "guest-connecting": displayKey = "ender.state.guest_connecting"; break;
                case "guest-ok": displayKey = "ender.state.connected"; break;
                case "waiting": displayKey = "ender.state.waiting"; break;
                default: displayKey = null; break;
            }

            if (displayKey != null) {
                this.backendState = Component.translatable(displayKey).getString();
            } else {
                this.backendState = state;
            }
        } catch (Exception ignored) {
        }

        if (this.minecraft != null) {
            this.init(this.minecraft, this.width, this.height);
        }
    }

    /**
     * 从状态 JSON 中提取成员列表。
     *
     * 优先读 {@code profiles[].name}，退回读 {@code players[]} 的字符串元素。
     *
     * @param json 状态 JSON 对象，允许为 null
     * @return 成员名称列表，永不为 null，可能为空
     */
    private java.util.List<String> extractMembers(JsonObject json) {
        java.util.List<String> members = new java.util.ArrayList<>();
        if (json == null) {
            return members;
        }
        if (json.has("profiles")) {
            JsonArray profiles = json.getAsJsonArray("profiles");
            for (JsonElement element : profiles) {
                if (!element.isJsonObject()) continue;
                JsonObject profile = element.getAsJsonObject();
                if (profile.has("name")) {
                    members.add(profile.get("name").getAsString());
                }
            }
        } else if (json.has("players")) {
            JsonArray players = json.getAsJsonArray("players");
            for (JsonElement element : players) {
                members.add(element.getAsString());
            }
        }
        return members;
    }
}



