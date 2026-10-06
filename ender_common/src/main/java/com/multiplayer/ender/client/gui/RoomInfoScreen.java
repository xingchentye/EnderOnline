/*
 * 本文件属于 EnderOnline Forge 适配层。
 *
 * 职责：房间信息展示界面，轮询后端状态并展示房间号、成员数与详细名单入口。
 *
 * 本类只读后端状态，唯一会改变全局状态的动作是「断开连接」。
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
 * 房间详细信息界面。
 *
 * 属于「查看当前房间」页：展示后端状态、房间号、成员数，并提供「详细列表」入口与
 * 「断开连接」「返回」按钮。Forge 侧目前没有任何跳转入口（neoforge 侧同样只被自身引用），
 * 属于不可达界面；房主视角的房间信息已由 EnderDashboard 的概览页承担。
 *
 * 设计约束：
 * 1. 状态每秒轮询一次（tick 内按 lastStateCheck 节流），结果经 Minecraft#execute 切回主线程后再刷新。
 * 2. 状态文本来自语言键（ender.state.*）；未识别的状态直接显示后端原始值。
 * 3. 刷新通过再次调用 this.init(...) 实现，因此 initContent 会被重复执行，实现必须保持幂等。
 *
 * TODO(P5, 2026-12-31): 决定保留或删除本界面；保留则迁入共享 UI 目录并补上入口。
 *
 * 线程安全性：Screen 只在客户端主线程使用；异步回调经 execute 回主线程后才写入
 * lastStateRaw、lastStateJson 与 backendState。
 */
public class RoomInfoScreen extends EnderBaseScreen {
    /** JSON 解析器，Gson 实例线程安全，可跨线程复用。 */
    private static final Gson GSON = new Gson();

    /** 上次状态轮询的时间戳，单位毫秒；仅用于每秒节流，初值 0 表示尚未轮询。 */
    private long lastStateCheck = 0;

    /** 当前展示的状态文本，初值为语言键 ender.dashboard.status.fetching 对应的文案。 */
    private String backendState = Component.translatable("ender.dashboard.status.fetching").getString();

    /** 上一次已处理的原始状态 JSON，允许为 null；用于跳过内容未变化的重复刷新。 */
    private String lastStateRaw = null;

    /** 最近一次解析成功的状态对象，允许为 null；房间号与成员列表都从这里读取。 */
    private JsonObject lastStateJson = null;

    /**
     * 构造房间信息界面。
     *
     * @param parent 父屏幕，允许为 null；为 null 时关闭本屏幕会退回游戏界面
     */
    public RoomInfoScreen(Screen parent) {
        super(Component.literal("房间信息"), parent);
    }

    /**
     * 填充内容区，契约见 EnderBaseScreen#initContent。
     *
     * 内容区自上而下为：状态、房间号、成员数、「详细列表」按钮；尾部件为「断开连接」与「返回」。
     * 结束时立即拉取一次状态，因此首次进入即可显示真实数据。
     *
     * 本方法会被 updateBackendState 通过 this.init(...) 间接重复调用，必须保持幂等。
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
     * 每刻更新，契约见 Screen#tick。
     *
     * 距上次轮询超过 1000ms 时发起一次异步查询；屏幕已卸载（minecraft 为 null）时丢弃结果。
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
     * 渲染屏幕，契约见 EnderBaseScreen#render。
     *
     * 当前实现只转发给父类，未附加额外绘制。
     */
    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    /**
     * 立即拉取一次状态，用于界面首次构建时填充数据。
     *
     * 后端未分配动态端口时直接返回；屏幕已卸载时丢弃结果。
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
     * 解析状态 JSON，把 state 映射为语言键并更新 backendState；与上次完全相同的 JSON 会被跳过。
     * 处理完成后重建整个界面（调用 this.init），因此本方法会间接触发 initContent 重跑。
     *
     * @param stateJson 状态 JSON 字符串，允许为 null，为 null 时直接返回
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
     * 从状态对象中提取成员名列表。
     *
     * 兼容两种后端格式：profiles 对象数组（取其中 name 字段）与 players 字符串数组。
     * profiles 优先，两者都存在时只取 profiles。
     *
     * @param json 状态对象，允许为 null，为 null 时返回空列表
     * @return 成员名列表，永不为 null；两种格式都不存在时返回空列表
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



