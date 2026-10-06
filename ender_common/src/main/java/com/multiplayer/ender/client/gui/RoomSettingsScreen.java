/*
 * 本文件属于 EnderOnline 客户端界面层。
 *
 * 职责：房间与全局设置界面，集中配置难度、PVP、游戏规则以及自动更新与自动启动。
 *
 * 本类当前没有跳转入口（不可达），且未继承 EnderBaseScreen，全部坐标由 init() 手写。
 */
package com.multiplayer.ender.client.gui;

import com.multiplayer.ender.client.PlatformConfigHolder;

import com.multiplayer.ender.network.EnderApiClient;
import com.multiplayer.ender.logic.ProcessLauncher;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.EditGameRulesScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import org.jetbrains.annotations.NotNull;

/**
 * 房间设置界面（Forge 侧当前不可达）。
 *
 * 用于集中配置房间属性（难度、PVP、游戏规则）与模组全局设置（自动更新、自动启动）。
 * Forge 侧目前没有任何跳转入口，而 EnderDashboard 的「规则与玩法」页已在功能上覆盖其中大部分项，
 * 因此本类属于待合并或待删除的界面。
 *
 * 设计约束：
 * 1. 本类直接继承 Screen 而非 EnderBaseScreen，控件全部由 init() 手写坐标，
 *    屏幕尺寸变化时不会自动重排，只会在重新 init 时按新尺寸重算。
 * 2. 仅当单人世界已发布（server 非 null 且 isPublished()）时才追加房主专属按钮
 *    （难度、PVP、游戏规则）；访客只看到全局设置与退出按钮。
 * 3. 「应用设置」只持久化自动更新与自动启动；难度、PVP 与游戏规则是点击即生效，无需保存。
 * 4. 临时路径字段只读不写，本界面不提供编辑入口。
 *
 * TODO(P5, 2026-12-31): 与 EnderDashboard 的规则页合并去重，或补上入口后改继承 EnderBaseScreen。
 *
 * 线程安全性：Screen 只在客户端主线程使用；退出按钮会另起线程通知 ProcessLauncher 停止后端。
 */
public class RoomSettingsScreen extends Screen {
    /** 父屏幕，用于关闭时返回；允许为 null，为 null 时退回游戏界面。 */
    private final Screen parent;

    /** 核心路径的临时值，init 时从配置读入；本界面没有修改入口，也未写回，属遗留字段。 */
    private String tempPath = "";

    /** 自动更新开关的临时值，默认 false；点击「应用设置」时写回配置。 */
    private boolean tempAutoUpdate = false;

    /** 自动启动后端开关的临时值，默认 false；点击「应用设置」时写回配置。 */
    private boolean tempAutoStart = false;

    /**
     * 构造房间设置界面。
     *
     * @param parent 父屏幕，允许为 null；为 null 时关闭本屏幕会退回游戏界面
     */
    public RoomSettingsScreen(Screen parent) {
        super(Component.literal("房间设置"));
        this.parent = parent;
    }

    /**
     * 初始化屏幕，契约见 Screen#init。
     *
     * 纵向布局以 (宽/2, 高/2-80) 为基准：先放自动更新、自动启动（行距 24）与「应用设置」三个按钮，
     * 再按房主身份追加难度、PVP、游戏规则按钮（同样行距 24），最后在底部并排放置
     * 「关闭房间 / 退出联机」与「返回」（各宽 88，中间留 4 像素间隙）。
     * 屏幕尺寸变化时本方法会被重新调用，所有临时值都会从配置重新读取。
     */
    @Override
    protected void init() {
        tempPath = PlatformConfigHolder.get().externalCorePath();
        tempAutoUpdate = PlatformConfigHolder.get().autoUpdate();
        tempAutoStart = PlatformConfigHolder.get().autoStartBackend();

        // 基准点：屏幕中心，起始 Y 上移 80 像素；按钮统一左边界为 宽/2-90、宽 180
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int startY = centerY - 80;

        this.addRenderableWidget(Button.builder(Component.literal("自动更新: " + (tempAutoUpdate ? "开" : "关")), b -> {
            tempAutoUpdate = !tempAutoUpdate;
            b.setMessage(Component.literal("自动更新: " + (tempAutoUpdate ? "开" : "关")));
        }).bounds(centerX - 90, startY, 180, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("自动启动: " + (tempAutoStart ? "开" : "关")), b -> {
            tempAutoStart = !tempAutoStart;
            b.setMessage(Component.literal("自动启动: " + (tempAutoStart ? "开" : "关")));
        }).bounds(centerX - 90, startY + 24, 180, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("应用设置"), b -> {
            PlatformConfigHolder.get().setAutoUpdate(tempAutoUpdate);
            PlatformConfigHolder.get().setAutoStartBackend(tempAutoStart);
            PlatformConfigHolder.get().save();
            b.setMessage(Component.literal("设置已保存"));
        }).bounds(centerX - 90, startY + 48, 180, 20).build());

        // 房主专属区起点：全局设置三行占 82 像素（48 + 24 起点偏移 + 10 行距）
        int currentY = startY + 82;

        IntegratedServer server = this.minecraft.getSingleplayerServer();
        boolean isHost = server != null && server.isPublished();

        if (isHost) {
            // 难度按钮：点击后在 0..3 之间循环切换难度等级
            this.addRenderableWidget(Button.builder(Component.literal("难度: " + server.getWorldData().getDifficulty().getKey()), b -> {
                Difficulty current = server.getWorldData().getDifficulty();
                Difficulty next = Difficulty.byId((current.getId() + 1) % 4);
                server.setDifficulty(next, true);
                b.setMessage(Component.literal("难度: " + next.getKey()));
            }).bounds(centerX - 90, currentY, 180, 20).build());
            currentY += 24;

            boolean pvp = server.isPvpAllowed();
            this.addRenderableWidget(Button.builder(Component.literal("PVP: " + (pvp ? "允许" : "禁止")), b -> {
                boolean newPvp = !server.isPvpAllowed();
                server.setPvpAllowed(newPvp);
                b.setMessage(Component.literal("PVP: " + (newPvp ? "允许" : "禁止")));
            }).bounds(centerX - 90, currentY, 180, 20).build());
            currentY += 24;

            this.addRenderableWidget(Button.builder(Component.literal("游戏规则"), b -> {
                this.minecraft.setScreen(new EditGameRulesScreen(server.getGameRules().copy(), (optionalRules) -> {
                    this.minecraft.setScreen(this);
                    optionalRules.ifPresent(rules -> server.getGameRules().assignFrom(rules, server));
                }));
            }).bounds(centerX - 90, currentY, 180, 20).build());
            currentY += 24;
        }

        // 底部操作行：左半宽 88 为断开按钮，右半宽 88 为返回按钮，中间留 4 像素
        Button disconnectBtn = Button.builder(Component.literal(isHost ? "关闭房间" : "退出联机"), b -> {
            EnderApiClient.setIdle();
            new Thread(ProcessLauncher::stop, "Ender-Stopper").start();
            this.onClose();
        }).bounds(centerX - 90, currentY, 88, 20).build();
        this.addRenderableWidget(disconnectBtn);

        Button backBtn = Button.builder(Component.literal("返回"), b -> {
            this.onClose();
        }).bounds(centerX + 2, currentY, 88, 20).build();
        this.addRenderableWidget(backBtn);
    }

    /**
     * 渲染屏幕，契约见 Screen#render。
     *
     * 在父类绘制完成后，把标题绘制在屏幕水平居中、垂直中心上方 100 像素处。
     */
    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 100, 0xFFFFFF);
    }

    /**
     * 关闭屏幕，契约见 Screen#onClose。
     *
     * 返回构造时传入的父屏幕；父屏幕为 null 时退回游戏界面。
     */
    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}



