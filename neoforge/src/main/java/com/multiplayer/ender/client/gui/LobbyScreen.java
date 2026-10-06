/*
 * 本文件属于 EnderOnline NeoForge 适配层。
 *
 * 职责：连接成功后的展示界面，提供聊天输入与断开入口。
 *
 * 关键约束：关闭时必须关闭 NetworkClient，保证会话不泄漏。
 */
package com.multiplayer.ender.client.gui;

import com.multiplayer.ender.network.NetworkClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 末影联机大厅界面。
 *
 * 由 {@code NetworkHandler#connectToServer} 在连接成功后跳入，用来确认「已经连上了」。
 *
 * 设计约束：
 * 1. 聊天框与发送按钮按屏幕底部定位，重排时重新计算坐标，不做固定像素布局。
 * 2. 发送按钮当前只清空输入框，聊天消息尚未接入协议；接入前不得让玩家误以为消息已送达。
 * 3. {@link #onClose()} 必须关闭 {@link NetworkClient}，否则连接会随界面关闭而泄漏。
 *
 * 线程安全性：字段只在客户端主线程读写，无并发保护。
 *
 * @since 1.0
 * @see NetworkClient
 */
public class LobbyScreen extends EnderBaseScreen {
    /** 聊天输入框；在 {@link #initContent()} 中创建，之前为 null，最大 256 字符。 */
    private EditBox chatBox;

    /** 发送按钮；在 {@link #initContent()} 中创建，之前为 null。 */
    private Button sendBtn;

    /**
     * 构造大厅界面。
     *
     * @param parent 父屏幕，用于返回，允许为 null
     */
    public LobbyScreen(Screen parent) {
        super(Component.translatable("menu.ender_online.lobby.title"), parent);
    }

    /**
     * 初始化界面内容。
     *
     * 底栏放「断开连接」，内容区下方自绘聊天输入框与发送按钮（不使用布局，便于贴屏幕底边）。
     */
    @Override
    protected void initContent() {
        this.layout.addToFooter(Button.builder(Component.literal("断开连接"), (button) -> {
            this.onClose();
        }).width(200).build());

        this.chatBox = new EditBox(this.font, 20, this.height - 40, this.width - 130, 20, Component.literal("Chat"));
        this.chatBox.setMaxLength(256);
        this.addRenderableWidget(this.chatBox);

        this.sendBtn = Button.builder(Component.literal("发送"), b -> {
            String msg = chatBox.getValue();
            if (!msg.isEmpty()) {
                chatBox.setValue("");
            }
        }).bounds(this.width - 100, this.height - 40, 80, 20).build();
        this.addRenderableWidget(this.sendBtn);
    }

    /**
     * 重新排列元素。
     *
     * 先让基类排布布局，再按新的屏幕宽高把聊天框与发送按钮贴回底部。
     */
    @Override
    protected void repositionElements() {
        super.repositionElements();
        if (this.chatBox != null) {
            this.chatBox.setX(20);
            this.chatBox.setY(this.height - 40);
            this.chatBox.setWidth(this.width - 130);
        }
        if (this.sendBtn != null) {
            this.sendBtn.setX(this.width - 100);
            this.sendBtn.setY(this.height - 40);
        }
    }

    /**
     * 渲染界面。
     *
     * 在原版渲染之上画一行欢迎文案。
     *
     * @param guiGraphics 绘图上下文，不能为 null
     * @param mouseX 鼠标 X 坐标，单位为逻辑像素
     * @param mouseY 鼠标 Y 坐标，单位为逻辑像素
     * @param partialTick 当前帧的部分刻进度，取值 0.0..1.0
     */
    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        
        guiGraphics.drawCenteredString(this.font, Component.literal("欢迎来到末影联机大厅"), this.width / 2, this.height / 2 - 20, 0x00FF00);
    }

    /**
     * 关闭屏幕。
     *
     * 先关闭底层网络会话，再回到父屏幕，避免连接残留。
     */
    @Override
    public void onClose() {
        NetworkClient.getInstance().close();
        super.onClose();
    }
}



