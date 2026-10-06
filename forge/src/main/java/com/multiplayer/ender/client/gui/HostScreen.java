/*
 * 本文件属于 EnderOnline Forge 适配层。
 *
 * 职责：以房主身份开房的界面，取本地端口发起托管并轮询托管状态。
 *
 * 本类当前没有跳转入口（不可达），且状态回调存在跨线程写入，见类注释中的 FIXME。
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
 * 创建房间界面（Forge 侧当前不可达）。
 *
 * 属于「以房主身份开房」页：点击「开始托管」后取单人世界端口并向后台发起托管请求，
 * 随后在 tick 中轮询后端状态；进入 host-ok 时把父控制台标记为已连接并关闭本界面。
 * Forge 侧目前没有任何跳转入口（neoforge 侧同样没有），因此属于不可达界面，
 * 控制台概览页已提供开房入口。
 *
 * 设计约束：
 * 1. 点击有 300ms 去抖（lastClickTime），窗口内的重复点击被忽略。
 * 2. 默认端口 25565；单人服务器已创建时改用其实际端口。
 * 3. 关闭界面时若托管仍在进行且尚未建立连接，会调用 EnderApiClient.setIdle() 取消托管。
 * 4. downloadProgress 小于 0 表示不显示进度条；但本类内没有任何写入点，
 *    因此进度条分支实际永远不会进入。
 *
 * TODO(P5, 2026-12-31): 明确是否保留本界面；控制台概览页已覆盖开房流程。
 *
 * 线程安全性：Screen 只在客户端主线程使用。但 checkHostStatus 的异步回调直接写 statusText、
 * roomCode 并调用父控制台的 setConnected，未切回主线程。
 *
 * @see EnderDashboard
 */
public class HostScreen extends EnderBaseScreen {
    /** 主状态文本，初值为语言键 ender.host.status.ready 对应的文案。 */
    private Component statusText = Component.translatable("ender.host.status.ready");

    /** 已获得的房间号，默认空串；非空时渲染邀请码与分享提示。 */
    private String roomCode = "";

    /** 是否正在发起托管，默认 false；为 true 时启动 tick 轮询并禁用开始按钮。 */
    private boolean isWorking = false;

    /** 托管是否已成功建立，默认 false；为 true 时关闭界面不再取消托管。 */
    private boolean keepConnection = false;

    /** 「开始托管」按钮，initContent 中创建；发起请求时会被置灰。 */
    private Button startBtn;

    /** 上次状态轮询的时间戳，单位毫秒；仅用于每秒节流。 */
    private long lastStateCheck = 0;

    /** 上次点击的时间戳，单位毫秒；用于 300ms 点击去抖。 */
    private long lastClickTime = 0;

    /**
     * 核心下载进度，取值 -1.0 到 1.0。
     *
     * 小于 0 表示不显示进度条；本类内没有写入点。声明为 volatile 是因为该字段被设计为
     * 供下载线程写入、渲染线程读取。
     */
    private volatile double downloadProgress = -1.0;

    /** JSON 解析器，Gson 实例线程安全，可跨线程复用。 */
    private static final Gson GSON = new Gson();

    /**
     * 构造创建房间界面。
     *
     * @param parent 父屏幕，允许为 null；为 null 时关闭本屏幕会退回游戏界面
     */
    public HostScreen(Screen parent) {
        super(Component.translatable("ender.host.title"), parent);
    }

    /**
     * 填充内容区，契约见 EnderBaseScreen#initContent。
     *
     * 内容区自上而下为「开始托管」与「取消」两个宽 200 的按钮；开始按钮的引用被留存以便置灰。
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
     * 发起托管请求。
     *
     * 先做 300ms 去抖与 isWorking 判定，再取端口与玩家名发起异步托管。
     * 拿到房间号即更新状态文本；拿不到则恢复按钮并允许重试。
     *
     * 幂等性：去抖窗口内与进行中的重复调用都会被忽略。
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

        EnderApiClient.startHosting(port, playerName).thenAccept(roomCode -> {
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
     * 每刻更新，契约见 Screen#tick。
     *
     * 仅在 isWorking 为 true 时按 1000ms 节流轮询一次托管状态。
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
     * 轮询一次托管状态。
     *
     * 状态为 host-ok 时记录房间号、置 keepConnection、把父控制台标记为已连接并关闭本界面；
     * host-starting 与 host-scanning 只更新状态文案。
     *
     * FIXME(P2, 2026-12-31): 本回调运行在 EnderApiClient 的回调线程，却直接写入 statusText、roomCode、
     * keepConnection 并调用父控制台 setConnected，跨线程读写界面状态缺少内存可见性保证；
     * 应统一经 minecraft.execute 切回客户端主线程。
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
     * 关闭屏幕，契约见 EnderBaseScreen#onClose。
     *
     * 若托管仍在进行且尚未建立连接，会先把后端置为空闲，避免留下半开的房间。
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
     * 在头部下方绘制状态文本；已获得房间号时追加邀请码与分享提示；
     * downloadProgress 不小于 0 时再绘制一条进度条与百分比文案。
     */
    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // 状态文本贴在头部下方 20 像素处，后续行按 20/35 像素递进
        int textY = this.layout.getHeaderHeight() + 20;
        guiGraphics.drawCenteredString(this.font, this.statusText, this.width / 2, textY, 0xAAAAAA);

        if (!roomCode.isEmpty()) {
            guiGraphics.drawCenteredString(this.font, Component.translatable("ender.host.invite_code_prefix").append(roomCode), this.width / 2, textY + 20, 0x55FF55);
            guiGraphics.drawCenteredString(this.font, Component.translatable("ender.host.share_hint"), this.width / 2, textY + 35, 0xAAAAAA);
        }

        if (downloadProgress >= 0) {
            // 进度条：宽 200、高 4，水平居中，位于状态文本下方 40 像素
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



