/*
 * 本文件属于 EnderOnline NeoForge 适配层。
 *
 * 职责：游戏内房间设置界面，编辑客户端配置并直接改写集成服务器的游戏规则。
 *
 * 关键约束：直接继承 Screen 而非 EnderBaseScreen，因为它不使用 HeaderAndFooterLayout；
 * 改动会立即作用于当前世界，没有撤销路径。
 */
package com.multiplayer.ender.client.gui;

import com.multiplayer.ender.Config;
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
 * 房间设置界面。
 *
 * 由暂停菜单的「房间设置」入口跳入（房主视角）。它把三类操作放在同一屏：
 * 客户端配置（自动更新/自动启动）、当前世界的规则（难度、PVP、更多规则），以及断开房间。
 *
 * 设计约束：
 * 1. 不使用 {@link EnderBaseScreen} 的布局体系，控件按 {@code centerX/centerY} 手工排布；改版式时需同时改这些偏移量。
 * 2. 难度、PVP、规则按钮只在「当前世界的集成服务器已发布」时创建，访客看不到这些入口。
 * 3. 配置项同样是「临时副本 → 应用设置」，但世界的规则改动是即时生效的，二者语义不同，不要当成一回事。
 * 4. 「关闭房间 / 退出联机」会停止本地核心进程，是破坏性操作。
 *
 * 线程安全性：只在客户端主线程读写，无并发保护。
 *
 * @since 1.0
 * @see EnderConfigScreen
 */
public class RoomSettingsScreen extends Screen {
    /** 父屏幕，用于返回，允许为 null。 */
    private final Screen parent;
    /** 外部核心路径的临时副本；此处不提供编辑控件，仅为保持与 Config 同步而读入。 */
    private String tempPath = "";
    /** 自动更新开关的临时副本，初始取自配置。 */
    private boolean tempAutoUpdate = false;
    /** 自动启动开关的临时副本，初始取自配置。 */
    private boolean tempAutoStart = false;

    /**
     * 构造房间设置界面。
     *
     * @param parent 父屏幕，用于返回，允许为 null
     */
    public RoomSettingsScreen(Screen parent) {
        super(Component.literal("房间设置"));
        this.parent = parent;
    }

    /**
     * 初始化界面。
     *
     * 先把配置读入临时副本；随后按垂直顺序放置客户端配置按钮、可选的服务器规则按钮
     * （难度、PVP、游戏规则，仅房主可见），最后是「关闭房间 / 退出联机」与「返回」按钮。
     */
    @Override
    protected void init() {
        tempPath = Config.EXTERNAL_ender_PATH.get();
        tempAutoUpdate = Config.AUTO_UPDATE.get();
        tempAutoStart = Config.AUTO_START_BACKEND.get();

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
            Config.AUTO_UPDATE.set(tempAutoUpdate);
            Config.AUTO_START_BACKEND.set(tempAutoStart);
            Config.CLIENT_SPEC.save();
            b.setMessage(Component.literal("设置已保存"));
        }).bounds(centerX - 90, startY + 48, 180, 20).build());

        int currentY = startY + 82;

        IntegratedServer server = this.minecraft.getSingleplayerServer();
        boolean isHost = server != null && server.isPublished();

        if (isHost) {
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
     * 渲染界面。
     *
     * 在原版渲染之上把标题居中画在按钮组上方。
     *
     * @param guiGraphics 绘图上下文，不能为 null
     * @param mouseX 鼠标 X 坐标，单位为逻辑像素
     * @param mouseY 鼠标 Y 坐标，单位为逻辑像素
     * @param partialTick 当前帧的部分刻进度，取值 0.0..1.0
     */
    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 100, 0xFFFFFF);
    }

    /**
     * 关闭屏幕，返回父屏幕。
     *
     * 注意：关闭本界面不会停止托管，停止托管只在点击「关闭房间 / 退出联机」时发生。
     */
    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}



