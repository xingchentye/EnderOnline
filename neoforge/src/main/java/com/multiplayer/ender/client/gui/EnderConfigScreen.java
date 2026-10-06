/*
 * 本文件属于 EnderOnline NeoForge 适配层。
 *
 * 职责：核心路径与自动更新/自动启动的配置界面。
 *
 * 关键约束：界面只改临时副本，必须点「保存并返回」才会写入 Config 并持久化。
 */
package com.multiplayer.ender.client.gui;

import com.multiplayer.ender.Config;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 模组设置界面。
 *
 * 由仪表盘的「设置」按钮跳转而来，集中编辑 {@link Config} 中的客户端项：外部核心路径、自动更新、进入菜单时自动启动后端。
 *
 * 设计约束：
 * 1. 采用「临时副本 → 保存」两段式：改控件只动 {@code temp*} 字段，只有「保存并返回」才写回 Config 并 save()。
 * 2. 取消或直接关闭界面一律丢弃改动，不做隐式保存。
 * 3. 路径框最大 1024 字符，未做存在性校验——不存在时由启动流程报错。
 *
 * 线程安全性：字段只在客户端主线程读写，无并发保护。
 *
 * @since 1.0
 * @see Config
 */
public class EnderConfigScreen extends EnderBaseScreen {
    /** 外部核心路径的临时副本，初始取 {@code Config.EXTERNAL_ender_PATH} 的当前值，非 null。 */
    private String tempPath;

    /** 自动更新开关的临时副本，初始取配置当前值。 */
    private boolean tempAutoUpdate;

    /** 自动启动开关的临时副本，初始取配置当前值。 */
    private boolean tempAutoStart;

    /**
     * 构造设置界面。
     *
     * 构造时即把三项配置读入临时副本，之后界面不再回读配置。
     *
     * @param parent 父屏幕，用于返回，允许为 null
     */
    public EnderConfigScreen(Screen parent) {
        super(Component.literal("末影联机设置"), parent);
        this.tempPath = Config.EXTERNAL_ender_PATH.get();
        this.tempAutoUpdate = Config.AUTO_UPDATE.get();
        this.tempAutoStart = Config.AUTO_START_BACKEND.get();
    }

    /**
     * 初始化界面内容。
     *
     * 内容区是按行列排布的配置表单（路径输入 + 两个开关），底栏是「保存并返回 / 取消」。
     */
    @Override
    protected void initContent() {
        GridLayout grid = new GridLayout();
        grid.defaultCellSetting().paddingBottom(8);

        grid.addChild(new StringWidget(Component.literal("核心路径"), this.font), 0, 0);
        EditBox pathBox = new EditBox(this.font, 0, 0, 200, 20, Component.literal("Path"));
        pathBox.setValue(this.tempPath);
        pathBox.setMaxLength(1024);
        pathBox.setResponder(val -> this.tempPath = val);
        grid.addChild(pathBox, 0, 1);

        Button autoUpdateBtn = Button.builder(Component.literal("自动更新: " + (this.tempAutoUpdate ? "开" : "关")), button -> {
            this.tempAutoUpdate = !this.tempAutoUpdate;
            button.setMessage(Component.literal("自动更新: " + (this.tempAutoUpdate ? "开" : "关")));
        }).width(150).build();
        grid.addChild(autoUpdateBtn, 1, 0, 1, 2);

        Button autoStartBtn = Button.builder(Component.literal("自动启动: " + (this.tempAutoStart ? "开" : "关")), button -> {
            this.tempAutoStart = !this.tempAutoStart;
            button.setMessage(Component.literal("自动启动: " + (this.tempAutoStart ? "开" : "关")));
        }).width(150).build();
        grid.addChild(autoStartBtn, 2, 0, 1, 2);

        this.layout.addToContents(grid);

        LinearLayout footerButtons = LinearLayout.horizontal().spacing(8);
        footerButtons.addChild(Button.builder(Component.literal("保存并返回"), button -> {
            this.saveConfig();
            this.onClose();
        }).width(150).build());

        footerButtons.addChild(Button.builder(Component.literal("取消"), button -> {
            this.onClose();
        }).width(150).build());

        this.layout.addToFooter(footerButtons);
    }

    /**
     * 把临时副本写回配置并持久化到 {@code ender.toml}。
     *
     * 副作用：写入 Config 并调用 {@code CLIENT_SPEC.save()}，此后改动无法通过取消撤销。
     */
    private void saveConfig() {
        Config.EXTERNAL_ender_PATH.set(this.tempPath);
        Config.AUTO_UPDATE.set(this.tempAutoUpdate);
        Config.AUTO_START_BACKEND.set(this.tempAutoStart);
        Config.CLIENT_SPEC.save();
    }
}




