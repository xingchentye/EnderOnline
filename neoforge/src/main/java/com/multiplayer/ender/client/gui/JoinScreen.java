/*
 * 本文件属于 EnderOnline NeoForge 适配层。
 *
 * 职责：加入房间界面，接收玩家输入的联机码并驱动加入流程。
 *
 * 关键约束：请求在途时禁止重复提交；成功后不得把连接回退为空闲。
 */
package com.multiplayer.ender.client.gui;

import org.jetbrains.annotations.NotNull;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.multiplayer.ender.network.EnderApiClient;
import com.multiplayer.ender.client.gui.EnderDashboard;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 加入房间界面。
 *
 * 两个入口：玩家手动输入的常规入口，以及仪表盘从剪贴板识别到联机码后的自动加入入口（带 {@code autoJoinCode}）。
 *
 * 设计约束：
 * 1. {@code isWorking} 期间不允许再次提交；输入为空时只改状态文案，不发请求。
 * 2. 加入前先查一次现有状态：若已处于 guest-ok/host-ok，直接复用连接跳到仪表盘，不再发加入请求。
 * 3. 成功后 {@code keepConnection} 置真，关闭界面时不得把后端置为空闲，否则会把刚建立的房间踢掉。
 * 4. 本屏幕会随状态变化反复 {@code init}，因此 {@link #initContent()} 必须可重复执行。
 *
 * 线程安全性：字段在客户端主线程读写；异步回调通过 {@code minecraft.execute} 回到主线程再触碰 UI。
 *
 * @since 1.0
 * @see EnderApiClient
 */
public class JoinScreen extends EnderBaseScreen {
    /** 房间号输入框；在 {@link #initContent()} 中创建，之前为 null，最大 128 字符。 */
    private EditBox roomCodeBox;
    /** 当前状态文本，非 null，默认取语言键 {@code ender.join.status.enter_code}。 */
    private Component statusText = Component.translatable("ender.join.status.enter_code");
    /** 是否正在处理加入请求，用于去抖与决定关闭时是否回退空闲。 */
    private boolean isWorking = false;
    /** 加入按钮；在 {@link #initContent()} 中创建，之前为 null。 */
    private Button joinBtn;
    /** 上次状态轮询时间戳，单位毫秒（System.currentTimeMillis）。 */
    private long lastStateCheck = 0;
    /** JSON 解析器，非 null，复用同一实例。 */
    private static final Gson GSON = new Gson();
    /** 自动填入并立即提交的联机码；允许为 null，为 null 时不自动加入。 */
    private String autoJoinCode = null;
    /** 是否已成功建立连接；为真时关闭界面不回退空闲。 */
    private boolean keepConnection = false;
    /** 最近一次解析到的已连接状态快照；允许为 null，为 null 时不渲染房间信息概览。 */
    private JsonObject connectedState = null;

    /**
     * 构造加入房间界面。
     *
     * @param parent 父屏幕，用于返回，允许为 null
     */
    public JoinScreen(Screen parent) {
        super(Component.translatable("ender.join.title"), parent);
    }

    /**
     * 构造带自动加入的界面。
     *
     * @param parent 父屏幕，用于返回，允许为 null
     * @param autoJoinCode 预填的联机码，允许为 null 或空串；非空时初始化后会立即尝试加入
     */
    public JoinScreen(Screen parent, String autoJoinCode) {
        this(parent);
        this.autoJoinCode = autoJoinCode;
    }

    /**
     * 初始化界面内容。
     *
     * 内容区是「联机码输入框 + 加入按钮」，底栏是「返回」；随后检查是否已有连接，最后处理自动加入。
     */
    @Override
    protected void initContent() {
        LinearLayout contentLayout = LinearLayout.vertical().spacing(12);

        this.roomCodeBox = new EditBox(this.font, 0, 0, 200, 20, Component.translatable("ender.join.input.label"));
        this.roomCodeBox.setMaxLength(128);
        this.roomCodeBox.setHint(Component.literal("U/XXXX-XXXX-XXXX-XXXX"));
        contentLayout.addChild(this.roomCodeBox);

        this.joinBtn = Button.builder(Component.translatable("ender.common.join"), button -> {
            this.joinRoom();
        }).width(200).build();
        contentLayout.addChild(this.joinBtn);

        this.layout.addToContents(contentLayout);

        this.layout.addToFooter(Button.builder(Component.translatable("ender.common.back"), button -> {
            this.onClose();
        }).width(200).build());
        
        checkExistingConnection();

        if (this.autoJoinCode != null && !this.autoJoinCode.isEmpty()) {
            this.roomCodeBox.setValue(this.autoJoinCode);
            this.minecraft.execute(() -> this.joinRoom());
        }
    }

    /**
     * 尝试加入房间。
     *
     * 校验输入非空、防止重复提交，随后先查一次现有状态；已连接则直接复用，否则发出加入请求。
     *
     * 幂等性：{@code isWorking} 为真时直接返回，重复点击不会重复发请求。
     */
    private void joinRoom() {
        String roomCode = roomCodeBox.getValue();
        if (roomCode.isEmpty()) {
            statusText = Component.translatable("ender.join.error.empty");
            return;
        }

        if (isWorking) return;
        isWorking = true;
        statusText = Component.translatable("ender.join.status.requesting");
        joinBtn.active = false;
        
        String playerName = this.minecraft.getUser().getName();
        EnderApiClient.rememberRoomCode(roomCode);

        EnderApiClient.getState().thenAccept(stateJson -> {
            if (stateJson != null) {
                try {
                    JsonObject json = GSON.fromJson(stateJson, JsonObject.class);
                    if (isConnectedState(json)) {
                        handleConnectedState(json);
                        return;
                    }
                } catch (Exception ignored) {
                }
            }
            doJoinRequest(roomCode, playerName);
        });
    }

    /**
     * 发送加入请求。
     *
     * 成功只更新状态文案，真正跳转由随后的状态轮询完成；失败恢复按钮并把后端最后一条错误追加到文案里。
     *
     * @param roomCode 房间号，不能为 null
     * @param playerName 玩家名称，不能为 null
     */
    private void doJoinRequest(String roomCode, String playerName) {
        EnderApiClient.joinRoom(roomCode, playerName).thenAccept(success -> {
            if (success) {
                statusText = Component.translatable("ender.join.status.success");
            } else {
                statusText = Component.translatable("ender.join.status.failed").append(EnderApiClient.getLastError());
                isWorking = false;
                joinBtn.active = true;
            }
        });
    }

    /**
     * 每 tick 更新。
     *
     * 仅在请求进行中时按 1 秒间隔轮询连接状态。
     */
    @Override
    public void tick() {
        super.tick();
        if (isWorking) {
            long now = System.currentTimeMillis();
            if (now - lastStateCheck > 1000) {
                lastStateCheck = now;
                checkConnectionStatus();
            }
        }
    }

    /**
     * 检查连接状态。
     *
     * 已连接则直接跳到仪表盘；中间状态（guest-connecting / guest-starting）只更新提示文案；
     * waiting 不改变文案——等待房间创建完成，没有更有信息量的话可说。
     */
    private void checkConnectionStatus() {
        EnderApiClient.getState().thenAccept(stateJson -> {
            if (stateJson == null) return;
            try {
                JsonObject json = GSON.fromJson(stateJson, JsonObject.class);
                if (json.has("state")) {
                    String state = json.get("state").getAsString();
                    
                    if (isConnectedState(json)) {
                        handleConnectedState(json);
                    } else if ("guest-connecting".equals(state)) {
                        statusText = Component.translatable("ender.join.status.connecting_p2p");
                    } else if ("guest-starting".equals(state)) {
                        statusText = Component.translatable("ender.join.status.initializing");
                    } else if ("waiting".equals(state)) {
                    }
                }
            } catch (Exception e) {
            }
        });
    }

    /**
     * 检查是否已存在连接。
     *
     * 进入界面时调用一次：若后端已有动态端口且状态已连接，直接复用连接跳到仪表盘，
     * 避免玩家在已连接的情况下重复发起加入。
     */
    private void checkExistingConnection() {
        if (!EnderApiClient.hasDynamicPort()) {
            return;
        }
        EnderApiClient.getState().thenAccept(stateJson -> {
            if (stateJson == null) return;
            try {
                JsonObject json = GSON.fromJson(stateJson, JsonObject.class);
                if (isConnectedState(json)) {
                    handleConnectedState(json);
                }
            } catch (Exception ignored) {
            }
        });
    }

    /**
     * 判断状态是否表示已连接。
     *
     * @param json 状态 JSON，允许为 null
     * @return 状态为 guest-ok 或 host-ok 时返回 true；json 为 null 或缺 state 字段时返回 false
     */
    private boolean isConnectedState(JsonObject json) {
        if (json == null || !json.has("state")) {
            return false;
        }
        String state = json.get("state").getAsString();
        return "guest-ok".equals(state) || "host-ok".equals(state);
    }

    /**
     * 处理连接成功状态。
     *
     * 更新文案与按钮状态，标记保留连接，记住房间号，然后切到仪表盘。副作用不可逆：此后关闭界面不会回退空闲。
     *
     * @param json 状态 JSON，允许为 null；为 null 时只做状态切换
     */
    private void handleConnectedState(JsonObject json) {
        statusText = Component.translatable("ender.join.status.connected");
        isWorking = false;
        keepConnection = true;
        connectedState = json;
        if (json != null && json.has("room")) {
            EnderApiClient.rememberRoomCode(json.get("room").getAsString());
        }
        joinBtn.active = false;
        openConnectedScreen(json);
    }

    /**
     * 跳转到已连接的仪表盘。
     *
     * 父屏幕本身就是仪表盘时复用它并推入最新状态，否则新建一个仪表盘。
     *
     * @param json 状态 JSON，允许为 null，会原样交给仪表盘
     */
    private void openConnectedScreen(JsonObject json) {
        if (this.minecraft == null) {
            return;
        }
        this.minecraft.execute(() -> {
            if (this.parent instanceof EnderDashboard dashboard) {
                this.minecraft.setScreen(dashboard);
                dashboard.updateFromState(json);
            } else {
                EnderDashboard dashboard = new EnderDashboard(this.parent);
                this.minecraft.setScreen(dashboard);
                dashboard.updateFromState(json);
            }
        });
    }

    /**
     * 关闭屏幕。
     *
     * 请求仍在途且尚未成功时先把后端置为空闲，避免留下半途的加入请求；已连接则保留。
     */
    @Override
    public void onClose() {
        if (isWorking && !keepConnection) {
             EnderApiClient.setIdle();
        }
        super.onClose();
    }

    /**
     * 渲染界面。
     *
     * 状态文本对齐在加入按钮下方，其后绘制房间信息概览。
     *
     * @param guiGraphics 绘图上下文，不能为 null
     * @param mouseX 鼠标 X 坐标，单位为逻辑像素
     * @param mouseY 鼠标 Y 坐标，单位为逻辑像素
     * @param partialTick 当前帧的部分刻进度，取值 0.0..1.0
     */
    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        
        if (this.joinBtn != null) {
            int textY = this.joinBtn.getY() + this.joinBtn.getHeight() + 10;
            guiGraphics.drawCenteredString(this.font, this.statusText, this.width / 2, textY, 0xAAAAAA);
            renderRoomInfo(guiGraphics, textY + 14);
        }
    }

    /**
     * 渲染房间信息概览。
     *
     * 按最长一行加内边距算出框宽并居中，依次画标题、房间号与成员列表。
     *
     * @param guiGraphics 图形上下文，不能为 null
     * @param startY 起始 Y 坐标，单位为逻辑像素
     */
    private void renderRoomInfo(GuiGraphics guiGraphics, int startY) {
        if (connectedState == null) {
            return;
        }
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("房间信息");
        String roomCode = connectedState.has("room") ? connectedState.get("room").getAsString() : EnderApiClient.getLastRoomCode();
        if (roomCode == null || roomCode.isEmpty()) {
            roomCode = "未知";
        }
        lines.add("房间号: " + roomCode);
        java.util.List<String> members = extractMembers(connectedState);
        if (!members.isEmpty()) {
            lines.add("成员: " + String.join(", ", members));
        }
        int lineHeight = 10;
        int padding = 6;
        int maxWidth = 0;
        for (String line : lines) {
            maxWidth = Math.max(maxWidth, this.font.width(line));
        }
        int boxWidth = maxWidth + padding * 2;
        int boxHeight = lineHeight * lines.size() + padding * 2;
        int startX = (this.width - boxWidth) / 2;
        guiGraphics.fill(startX, startY, startX + boxWidth, startY + boxHeight, 0x80000000);
        int y = startY + padding;
        for (int i = 0; i < lines.size(); i++) {
            int color = i == 0 ? 0xFFFFFF : (i == 1 ? 0xFFFF55 : 0xCCCCCC);
            guiGraphics.drawString(this.font, lines.get(i), startX + padding, y, color);
            y += lineHeight;
        }
    }

    /**
     * 提取成员名称列表。
     *
     * 优先读 {@code profiles[].name}，退回读 {@code players[]} 的字符串元素。
     *
     * @param json 状态 JSON，不能为 null
     * @return 成员名称列表，永不为 null，可能为空
     */
    private java.util.List<String> extractMembers(JsonObject json) {
        java.util.List<String> members = new java.util.ArrayList<>();
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


