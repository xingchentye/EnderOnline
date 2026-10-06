/*
 * 本文件属于 EnderOnline Forge 适配层。
 *
 * 职责：联机大厅界面，提供聊天输入框、发送按钮与断开连接按钮。
 *
 * 关闭本界面会断开 NetworkClient 连接，因此它不是可随意返回的普通页面。
 */
package com.multiplayer.ender.client.gui;

import com.multiplayer.ender.network.NetworkClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 联机大厅界面。
 *
 * 属于「已建立连接后」的大厅页：一行聊天输入框、发送按钮与断开连接按钮。
 * Forge 侧目前没有任何跳转入口（neoforge 侧由 NetworkHandler 在连接成功后打开），
 * 因此在 Forge 上属于不可达界面。
 *
 * 设计约束：
 * 1. onClose 会断开 NetworkClient 连接，因此本界面不能作为「临时离开」的返回路径使用。
 * 2. 聊天框与发送按钮不使用基类的中间内容区布局，而是通过重写 repositionElements
 *    固定在屏幕底部，屏幕尺寸变化时需重新赋坐标。
 * 3. 发送按钮目前只清空输入框，尚未接入实际发送链路。
 *
 * TODO(P5, 2026-12-31): 与 neoforge 侧一并迁入共享 UI 目录，并明确 Forge 侧入口或删除本类。
 *
 * 线程安全性：Screen 只在客户端主线程使用，本类无可变静态状态。
 */
public class LobbyScreen extends EnderBaseScreen {
    /** 聊天输入框，initContent 中创建；重定位与发送逻辑需判空。 */
    private EditBox chatBox;

    /** 发送按钮，initContent 中创建；重定位逻辑需判空。 */
    private Button sendBtn;

    /**
     * 构造大厅界面。
     *
     * @param parent 父屏幕，允许为 null；为 null 时关闭本屏幕会退回游戏界面
     */
    public LobbyScreen(Screen parent) {
        super(Component.translatable("menu.ender_online.lobby.title"), parent);
    }

    /**
     * 填充内容区，契约见 EnderBaseScreen#initContent。
     *
     * 尾部件放「断开连接」按钮；聊天框与发送按钮直接注册到屏幕上，
     * 位置由本类的 repositionElements 与这里的初始坐标共同决定。
     */
    @Override
    protected void initContent() {
        this.layout.addToFooter(Button.builder(Component.literal("断开连接"), (button) -> {
            this.onClose();
        }).width(200).build());

        // 聊天框底部对齐：距屏幕底部 40 像素，右侧留出 100 像素给发送按钮
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
     * 重新排布控件，契约见 EnderBaseScreen#repositionElements。
     *
     * 先让布局管理器排布尾部按钮，再把聊天框与发送按钮贴回屏幕底部。
     * 两个控件在 initContent 之前均为 null，因此必须判空。
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
     * 渲染屏幕，契约见 EnderBaseScreen#render。
     *
     * 在父类绘制完成后，于屏幕中央上方 20 像素处绘制一行欢迎文案。
     */
    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(this.font, Component.literal("欢迎来到末影联机大厅"), this.width / 2, this.height / 2 - 20, 0x00FF00);
    }

    /**
     * 关闭屏幕，契约见 EnderBaseScreen#onClose。
     *
     * 先断开 NetworkClient 连接，再返回父屏幕；本方法不幂等，重复调用会重复触发断开。
     */
    @Override
    public void onClose() {
        NetworkClient.getInstance().close();
        super.onClose();
    }
}



