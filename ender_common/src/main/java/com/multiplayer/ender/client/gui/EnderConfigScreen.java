/*
 * 本文件属于 EnderOnline 客户端界面层。
 *
 * 职责：客户端全局设置界面，编辑核心路径、自动更新与自动启动三项配置。
 *
 * 采用「临时值 + 显式保存」模式，取消不产生任何写入。
 */
package com.multiplayer.ender.client.gui;

import com.multiplayer.ender.client.PlatformConfigHolder;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 末影联机设置界面。
 *
 * 属于「全局模组设置」页：由 EnderDashboard 空闲页的「设置」按钮打开，可修改核心文件路径、
 * 自动更新开关与自动启动开关；点击「保存并返回」写回 ender.toml 并返回上一屏。
 *
 * 设计约束：
 * 1. 三项配置在构造时读入临时字段，只有 saveConfig 才写回 ConfigForge；
 *    「取消」只关闭界面，不产生任何写入。
 * 2. 本界面不校验核心路径是否存在，非法路径要等到 StartupScreen 启动阶段才会暴露。
 * 3. 路径输入框上限为 1024 字符。
 *
 * 线程安全性：Screen 只在客户端主线程使用；PlatformConfigHolder.get().save() 也只在此线程调用。
 *
 * @see ConfigForge
 * @see EnderDashboard
 */
public class EnderConfigScreen extends EnderBaseScreen {
    /** 核心路径的临时值，初值取自配置；允许为空字符串，表示不指定外部核心。 */
    private String tempPath;

    /** 自动更新开关的临时值，初值取自配置，默认 true。 */
    private boolean tempAutoUpdate;

    /** 自动启动后端开关的临时值，初值取自配置，默认 false。 */
    private boolean tempAutoStart;

    /**
     * 构建设置界面，并把当前配置读入临时字段。
     *
     * 临时值与界面实例绑定，因此界面重新初始化不会丢失用户已做的修改。
     *
     * @param parent 父屏幕，允许为 null；为 null 时关闭本屏幕会退回游戏界面
     */
    public EnderConfigScreen(Screen parent) {
        super(Component.literal("末影联机设置"), parent);
        this.tempPath = PlatformConfigHolder.get().externalCorePath();
        this.tempAutoUpdate = PlatformConfigHolder.get().autoUpdate();
        this.tempAutoStart = PlatformConfigHolder.get().autoStartBackend();
    }

    /**
     * 填充内容区，契约见 EnderBaseScreen#initContent。
     *
     * 内容区用 2 列 GridLayout 排布：第 0 行为路径标签与输入框，第 1、2 行分别是自动更新与
     * 自动启动的切换按钮（各自跨两列，宽 150）；尾部件为「保存并返回」与「取消」两个并排按钮。
     */
    @Override
    protected void initContent() {
        GridLayout grid = new GridLayout();
        grid.defaultCellSetting().paddingBottom(8);

        grid.addChild(new StringWidget(Component.literal("核心路径"), this.font), 0, 0);
        // 路径输入框宽 200、高 20；x/y 传 0 由 GridLayout 接管
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
     * 把三个临时值写回配置并持久化。
     *
     * 会立即把 CLIENT_SPEC 写入 ender.toml，因此保存后配置变更对后续启动即时生效；
     * 本方法不幂等，重复调用会重复落盘。
     */
    private void saveConfig() {
        PlatformConfigHolder.get().setExternalCorePath(this.tempPath);
        PlatformConfigHolder.get().setAutoUpdate(this.tempAutoUpdate);
        PlatformConfigHolder.get().setAutoStartBackend(this.tempAutoStart);
        PlatformConfigHolder.get().save();
    }
}




