/*
 * 本文件属于 EnderOnline 客户端界面层。
 *
 * 职责：创建房间界面，发起托管请求并展示房间码与下载进度。
 *
 * 关键约束：关闭界面时若请求仍在途则回退为空闲状态，避免后端留下「僵尸托管」。
 */
package com.multiplayer.ender.client.gui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.multiplayer.ender.network.EnderApiClient;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

/**
 * 创建房间界面。
 *
 * 由仪表盘或暂停菜单跳入，点击「开始」后向 {@link EnderApiClient} 请求托管；
 * 成功后把房间码展示出来，并让父屏幕（若是仪表盘）切到已连接状态。
 *
 * 设计约束：
 * 1. 开始按钮有 300 毫秒去抖，且 {@code isWorking} 为真时直接返回，防止重复发起托管请求。
 * 2. {@link #onClose()} 区分两种关闭：请求仍在途且尚未成功时把后端置为空闲；已经拿到房间码则保留连接。
 * 3. 界面尺寸走原版布局，不做固定像素定位（ADR-09/10 的目标状态）。
 *
 * 线程安全性：字段均在客户端主线程读写；{@code downloadProgress} 由后台下载回调写入，故声明为 volatile。
 *
 * @since 1.0
 * @see EnderApiClient
 */
public class HostScreen extends EnderBaseScreen {
    /** 当前状态文本，非 null，默认取语言键 {@code ender.host.status.ready}。 */
    private Component statusText = Component.translatable("ender.host.status.ready");
    /** 房间联机码，默认空串表示尚未拿到；非 null。 */
    private String roomCode = "";
    /** 是否正在处理托管请求，用于去抖与决定关闭时是否回退空闲。 */
    private boolean isWorking = false;
    /** 是否已成功建立连接；为真时关闭界面不回退空闲。 */
    private boolean keepConnection = false;
    /** 开始按钮；在 {@link #initContent()} 中创建，之前为 null。 */
    private Button startBtn;
    /** 上次状态轮询时间戳，单位毫秒（System.currentTimeMillis）。 */
    private long lastStateCheck = 0;
    /** 上次点击时间戳，单位毫秒，用于 300 毫秒双击保护。 */
    private long lastClickTime = 0;

    /** 核心下载进度，取值 -1.0 表示「无进度可显示」，否则为 0.0..1.0；由后台线程写入。 */
    private volatile double downloadProgress = -1.0;

    /** JSON 解析器，非 null，复用同一实例以避免每次轮询新建。 */
    private static final Gson GSON = new Gson();

    /**
     * 构造创建房间界面。
     *
     * @param parent 父屏幕，用于返回；若为 {@link EnderDashboard}，成功后会被通知切换状态
     */
    public HostScreen(Screen parent) {
        super(Component.translatable("ender.host.title"), parent);
    }

    /**
     * 初始化界面内容。
     *
     * 内容区从上到下依次是「开始」按钮与「取消」按钮。
     */
    @Override
    protected void initContent() {
        LinearLayout contentLayout = LinearLayout.vertical().spacing(12);

        this.startBtn = Button.builder(Component.translatable("ender.host.button.start"), button -> {
            this.startHosting();
        }).width(200).build();
        contentLayout.addChild(this.startBtn);

        contentLayout.addChild(Button.builder(Component.translatable("ender.common.button.cancel"), button -> {
            this.onClose();
        }).width(200).build());

        this.layout.addToContents(contentLayout);
    }

    /**
     * 开始托管房间。
     *
     * 先做双击与重复请求保护，随后取本地集成服务器的实际端口（单机未开房间时回落 25565），
     * 再向后端发起托管请求；成功写入房间码，失败恢复按钮并给出失败文案。
     */
    private void startHosting() {
        long now = System.currentTimeMillis();
        if (now - lastClickTime < 300) return;
        lastClickTime = now;
        if (isWorking) return;
        isWorking = true;
        statusText = Component.translatable("ender.host.status.requesting");
        startBtn.active = false;
        
        int port = 25565; 
        if (this.minecraft.getSingleplayerServer() != null) {
            port = this.minecraft.getSingleplayerServer().getPort();
        }
        String playerName = this.minecraft.getUser().getName();

        EnderApiClient.startHosting(port, playerName, (p) -> {
            this.downloadProgress = p;
        }).thenAccept(roomCode -> {
            this.downloadProgress = -1.0;
            if (roomCode != null && !roomCode.isEmpty()) {
                statusText = Component.translatable("ender.host.status.success");
                this.roomCode = roomCode;
            } else {
                statusText = Component.translatable("ender.host.status.failed").append(": 未能获取房间号");
                isWorking = false;
                startBtn.active = true;
            }
        });
    }

    /**
     * 每 tick 更新。
     *
     * 仅在请求进行中时按 1 秒间隔轮询托管状态，避免空转。
     */
    @Override
    public void tick() {
        super.tick();
        if (isWorking) {
            long now = System.currentTimeMillis();
            if (now - lastStateCheck > 1000) {
                lastStateCheck = now;
                checkHostStatus();
            }
        }
    }

    /**
     * 检查托管状态。
     *
     * 轮询后端状态 JSON：进入 host-ok 时记录房间码、标记保留连接、通知父仪表盘并自动关闭本界面；
     * host-starting / host-scanning 则更新进度文案。
     *
     * 失败容忍：解析异常被静默忽略——状态轮询本身就是尽力而为，下一 tick 会重试。
     */
    private void checkHostStatus() {
        EnderApiClient.getState().thenAccept(stateJson -> {
            if (stateJson == null) return;
            try {
                JsonObject json = GSON.fromJson(stateJson, JsonObject.class);
                if (json.has("state")) {
                    String state = json.get("state").getAsString();
                    
                    if ("host-ok".equals(state)) {
                        if (json.has("room")) {
                            this.roomCode = json.get("room").getAsString();
                            statusText = Component.translatable("ender.host.status.created");
                            keepConnection = true;
                            if (this.parent instanceof EnderDashboard) {
                                ((EnderDashboard) this.parent).setConnected(true);
                            }
                            
                            this.minecraft.execute(this::onClose);
                        }
                    } else if ("host-starting".equals(state)) {
                        statusText = Component.translatable("ender.host.status.creating");
                    } else if ("host-scanning".equals(state)) {
                        statusText = Component.translatable("ender.host.status.scanning");
                    }
                }
            } catch (Exception e) {
            }
        });
    }

    /**
     * 关闭屏幕。
     *
     * 若请求仍在途且尚未成功，先把后端置为空闲再返回父屏幕，防止留下无人认领的房间。
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
     * 在原版渲染之上画状态文本、房间码与分享提示，并在有下载进度时画一条进度条。
     *
     * @param guiGraphics 绘图上下文，不能为 null
     * @param mouseX 鼠标 X 坐标，单位为逻辑像素
     * @param mouseY 鼠标 Y 坐标，单位为逻辑像素
     * @param partialTick 当前帧的部分刻进度，取值 0.0..1.0
     */
    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        
        int textY = this.layout.getHeaderHeight() + 20;
        guiGraphics.drawCenteredString(this.font, this.statusText, this.width / 2, textY, 0xAAAAAA);
        
        if (!roomCode.isEmpty()) {
             guiGraphics.drawCenteredString(this.font, Component.translatable("ender.host.invite_code_prefix").append(roomCode), this.width / 2, textY + 20, 0x55FF55);
             guiGraphics.drawCenteredString(this.font, Component.translatable("ender.host.share_hint"), this.width / 2, textY + 35, 0xAAAAAA);
        }

        if (downloadProgress >= 0) {
            int barWidth = 200;
            int barHeight = 4;
            int barX = this.width / 2 - barWidth / 2;
            int barY = textY + 40;

            guiGraphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF555555);
            guiGraphics.fill(barX, barY, barX + (int) (barWidth * downloadProgress), barY + barHeight, 0xFF55FF55);
            
            guiGraphics.drawCenteredString(this.font, Component.literal((int)(downloadProgress * 100) + "%"), this.width / 2, barY + 8, 0xFFFFFF);
        }
    }
}



