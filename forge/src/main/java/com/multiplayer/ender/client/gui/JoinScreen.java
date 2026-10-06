/*
 * 本文件属于 EnderOnline Forge 适配层。
 *
 * 职责：输入房间号并入房，成功后切到 EnderDashboard 控制台。
 *
 * 入房进度靠 tick 轮询后端状态推进；本类不做房间号格式校验。
 */
package com.multiplayer.ender.client.gui;

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
import org.jetbrains.annotations.NotNull;

/**
 * 加入房间界面。
 *
 * 属于「输入房间号入房」页：由 EnderDashboard 的「加入房间」按钮打开；当剪贴板中出现
 * 符合格式的房间号时，EnderDashboard 也会自动打开本界面并预填房间号。入房成功后切到控制台。
 *
 * 设计约束：
 * 1. 构建时会先查询一次现有状态：若已是 host-ok 或 guest-ok，直接跳到控制台，避免重复入房。
 * 2. isWorking 期间按 1000ms 节流轮询进度；关闭界面时若仍在进行且未建立连接，
 *    会调用 EnderApiClient.setIdle() 取消入房。
 * 3. 房间号只做空值判断，不做格式校验，格式错误由后端返回失败。
 * 4. 输入框上限 128 字符，提示文案给出 U/XXXX-XXXX-XXXX-XXXX 的格式示例。
 *
 * 线程安全性：Screen 只在客户端主线程使用；界面切换已用 minecraft.execute 包裹，
 * 但 checkConnectionStatus 的异步回调直接写 statusText（未切回主线程）。
 *
 * @see EnderDashboard
 */
public class JoinScreen extends EnderBaseScreen {
    /** 房间号输入框，initContent 中创建；joinRoom 中读取其值。 */
    private EditBox roomCodeBox;

    /** 状态文本，初值为语言键 ender.join.status.enter_code 对应的文案。 */
    private Component statusText = Component.translatable("ender.join.status.enter_code");

    /** 是否正在入房，默认 false；为 true 时启用 tick 轮询并禁用加入按钮。 */
    private boolean isWorking = false;

    /** 「加入」按钮，initContent 中创建；请求期间会被置灰。 */
    private Button joinBtn;

    /** 上次状态轮询的时间戳，单位毫秒；仅用于每秒节流。 */
    private long lastStateCheck = 0;

    /** JSON 解析器，Gson 实例线程安全，可跨线程复用。 */
    private static final Gson GSON = new Gson();

    /** 需要预填的房间号，允许为 null 或空串，此时输入框保持空白。 */
    private String autoJoinCode = null;

    /** 是否已成功建立连接，默认 false；为 true 时关闭界面不再取消入房。 */
    private boolean keepConnection = false;

    /** 最近一次已连接状态的状态对象，允许为 null；用于在界面上叠加房间信息区。 */
    private JsonObject connectedState = null;

    /**
     * 构造加入房间界面。
     *
     * @param parent 父屏幕，允许为 null；为 null 时关闭本屏幕会退回游戏界面
     */
    public JoinScreen(Screen parent) {
        super(Component.translatable("ender.join.title"), parent);
    }

    /**
     * 构造加入房间界面并预填房间号。
     *
     * @param parent 父屏幕，允许为 null；为 null 时关闭本屏幕会退回游戏界面
     * @param autoJoinCode 预填的房间号，允许为 null 或空串，此时输入框保持空白
     */
    public JoinScreen(Screen parent, String autoJoinCode) {
        this(parent);
        this.autoJoinCode = autoJoinCode;
    }

    /**
     * 填充内容区，契约见 EnderBaseScreen#initContent。
     *
     * 内容区为房间号输入框与「加入」按钮（均宽 200），尾部件为「返回」；
     * 构建完成后先检查是否存在既有连接，再按 autoJoinCode 预填输入框。
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
        }
    }

    /**
     * 尝试加入房间。
     *
     * 房间号为空时只更新提示文本；否则先做 isWorking 判定，记下房间号，
     * 再查询一次当前状态：已处于已连接状态则直接走已连接流程，否则发起入房请求。
     *
     * 幂等性：进行中的重复调用会被忽略。
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
     * 发起入房请求。
     *
     * 成功只更新状态文本（界面切换由后续轮询完成）；失败时把后端错误文案追加到提示上，
     * 并恢复加入按钮以便重试。
     *
     * @param roomCode 房间号，不能为 null 或空串
     * @param playerName 玩家名，取自当前登录账号，不能为 null
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
     * 每刻更新，契约见 Screen#tick。
     *
     * 仅在 isWorking 为 true 时按 1000ms 节流轮询一次入房进度。
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
     * 轮询一次入房进度。
     *
     * 已连接时走已连接流程；guest-connecting 与 guest-starting 分别更新为对应的提示文案；
     * waiting 状态不改变提示。
     *
     * FIXME(P2, 2026-12-31): 本回调运行在 EnderApiClient 的回调线程，却直接写入 statusText，
     * 未切回客户端主线程，缺少内存可见性保证。
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
     * 构建时检查是否已存在可用连接。
     *
     * 后端未分配动态端口时直接返回；否则查询一次状态，已连接则直接走已连接流程。
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
     * 判断状态对象是否表示「已连接」。
     *
     * @param json 状态对象，允许为 null，为 null 时返回 false
     * @return state 为 guest-ok 或 host-ok 时返回 true
     */
    private boolean isConnectedState(JsonObject json) {
        if (json == null || !json.has("state")) {
            return false;
        }
        String state = json.get("state").getAsString();
        return "guest-ok".equals(state) || "host-ok".equals(state);
    }

    /**
     * 处理「已连接」状态：更新提示、记下房间号并打开控制台。
     *
     * 幂等性：置 keepConnection 之后重复调用不会重复关闭界面（界面切换由 openConnectedScreen 走 execute）。
     *
     * @param json 已连接的状态对象，允许为 null；非 null 且含 room 字段时会记下房间号
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
     * 切到控制台并把已连接状态同步过去。
     *
     * 父屏幕本身就是 EnderDashboard 时复用它，否则新建一个；两种路径都会把状态推给控制台。
     *
     * @param json 已连接的状态对象，允许为 null
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
     * 关闭屏幕，契约见 EnderBaseScreen#onClose。
     *
     * 若入房仍在进行且尚未建立连接，会先把后端置为空闲，避免留下半开的入房流程。
     */
    @Override
    public void onClose() {
        if (isWorking && !keepConnection) {
            EnderApiClient.setIdle();
        }
        super.onClose();
    }

    /**
     * 渲染屏幕，契约见 EnderBaseScreen#render。
     *
     * 在「加入」按钮下方 10 像素处绘制状态文本，再在其下 14 像素处绘制房间信息区
     * （仅当已拿到已连接状态时）。
     */
    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        if (this.joinBtn != null) {
            // 状态文本跟随按钮下沿，间隔 10 像素；房间信息区再下移 14 像素
            int textY = this.joinBtn.getY() + this.joinBtn.getHeight() + 10;
            guiGraphics.drawCenteredString(this.font, this.statusText, this.width / 2, textY, 0xAAAAAA);
            renderRoomInfo(guiGraphics, textY + 14);
        }
    }

    /**
     * 绘制房间信息区。
     *
     * 内容自上而下为：标题「房间信息」、房间号、成员列表（成员为空时省略）；
     * 箱体宽度按最长一行加左右各 6 像素内边距计算，水平居中，行高 10 像素。
     * 未持有已连接状态时直接返回，不绘制任何内容。
     *
     * @param guiGraphics 绘制上下文，不能为 null
     * @param startY 箱体顶部 Y 坐标，单位像素
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
     * 从状态对象中提取成员名列表。
     *
     * 兼容两种后端格式：profiles 对象数组（取其中 name 字段）与 players 字符串数组；
     * profiles 优先。
     *
     * @param json 状态对象，不能为 null
     * @return 成员名列表，永不为 null；两种格式都不存在时返回空列表
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


