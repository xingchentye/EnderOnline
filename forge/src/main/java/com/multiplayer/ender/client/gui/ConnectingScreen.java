/*
 * 本文件属于 EnderOnline Forge 适配层。
 *
 * 职责：连接中转场界面，只展示一行可外部替换的状态文本与一个取消按钮。
 *
 * 状态文本由持有者通过 setStatus 更新，本类不主动查询任何连接状态。
 */
package com.multiplayer.ender.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 连接中过渡界面。
 *
 * 属于「加入/托管进行中」的过渡页：屏幕中央显示一行状态文本，尾部提供「取消」按钮。
 * Forge 侧目前没有任何跳转入口（neoforge 侧由 NetworkHandler 在建立连接时打开），
 * 因此在 Forge 上属于不可达界面。
 *
 * 设计约束：
 * 1. 本界面不感知连接结果，只被动接受 setStatus 传入的文本；状态查询与结果处理由调用方负责。
 * 2. 取消按钮只调用 onClose，不会中止后端流程；需要中止的调用方必须自行处理。
 *
 * TODO(P5, 2026-12-31): 与 neoforge 侧一并迁入共享 UI 目录，并明确 Forge 侧入口或删除本类。
 *
 * 线程安全性：Screen 只在客户端主线程使用；status 字段只应在主线程写入。
 */
public class ConnectingScreen extends EnderBaseScreen {
    /** 当前状态文本，默认取语言键 connect.connecting；不允许为 null。 */
    private Component status = Component.translatable("connect.connecting");

    /**
     * 构造连接中界面。
     *
     * @param parent 父屏幕，允许为 null；为 null 时关闭本屏幕会退回游戏界面
     */
    public ConnectingScreen(Screen parent) {
        super(Component.translatable("menu.ender_online.title"), parent);
    }

    /**
     * 填充内容区，契约见 EnderBaseScreen#initContent。
     *
     * 仅向尾部添加一个宽 200 的「取消」按钮，点击即关闭本屏幕。
     */
    @Override
    protected void initContent() {
        this.layout.addToFooter(Button.builder(Component.translatable("gui.cancel"), (button) -> {
            this.onClose();
        }).width(200).build());
    }

    /**
     * 渲染屏幕，契约见 EnderBaseScreen#render。
     *
     * 在父类绘制完成后，把状态文本绘制在屏幕水平居中、垂直中心上方 10 像素处。
     */
    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.status, this.width / 2, this.height / 2 - 10, 0xAAAAAA);
    }

    /**
     * 更新状态文本。
     *
     * 可从渲染回调之外的主线程代码调用，下帧生效。
     *
     * @param status 新的状态文本，不能为 null
     */
    public void setStatus(Component status) {
        this.status = status;
    }
}


