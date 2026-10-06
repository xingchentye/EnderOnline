/*
 * 本文件属于 EnderOnline NeoForge 适配层。
 *
 * 职责：连接过程中的过渡界面，展示当前连接状态并允许取消。
 *
 * 关键约束：本屏幕只显示状态，不发起连接；状态由调用方通过 setStatus 推入。
 */
package com.multiplayer.ender.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 连接等待界面。
 *
 * 由 {@code NetworkHandler#connectToServer} 在发起连接前跳入，展示连接状态；连接成功或失败后由调用方切换屏幕。
 *
 * 设计约束：
 * 1. 本屏幕不持有连接对象，只被动接收 {@link #setStatus} 推来的文本。
 * 2. 「取消」只关闭界面，不中断底层连接；中断由 {@code NetworkClient} 的生命周期负责。
 *
 * 线程安全性：字段只在客户端主线程读写；{@link #setStatus} 必须由主线程调用。
 *
 * @since 1.0
 * @see NetworkHandler
 */
public class ConnectingScreen extends EnderBaseScreen {
    /** 连接状态文本，非 null，默认取语言键 {@code connect.connecting}。 */
    private Component status = Component.translatable("connect.connecting");

    /**
     * 构造连接等待界面。
     *
     * @param parent 父屏幕，用于返回，允许为 null
     */
    public ConnectingScreen(Screen parent) {
        super(Component.translatable("menu.ender_online.title"), parent);
    }

    /**
     * 初始化界面内容。
     *
     * 只在底栏放一个「取消」按钮，状态文本由 {@link #render} 居中绘制。
     */
    @Override
    protected void initContent() {
        this.layout.addToFooter(Button.builder(Component.translatable("gui.cancel"), (button) -> {
            this.onClose();
        }).width(200).build());
    }

    /**
     * 渲染界面。
     *
     * 在原版渲染之上，把状态文本居中画在屏幕垂直中线略上方。
     *
     * @param guiGraphics 绘图上下文，不能为 null
     * @param mouseX 鼠标 X 坐标，单位为逻辑像素
     * @param mouseY 鼠标 Y 坐标，单位为逻辑像素
     * @param partialTick 当前帧的部分刻进度，取值 0.0..1.0
     */
    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.status, this.width / 2, this.height / 2 - 10, 0xAAAAAA);
    }
    
    /**
     * 更新状态文本。
     *
     * @param status 新的状态文本，不能为 null；调用方负责传本地化后的组件
     */
    public void setStatus(Component status) {
        this.status = status;
    }
}


