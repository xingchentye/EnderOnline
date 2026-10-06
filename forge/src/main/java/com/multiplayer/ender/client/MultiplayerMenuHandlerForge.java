/*
 * 本文件属于 EnderOnline Forge 适配层。
 *
 * 职责：在多人游戏菜单注入「末影联机」入口按钮，并按后端就绪情况决定跳转目标。
 *
 * 入口通过 ScreenEvent.Init.Post 注入，用于替代已终止的 Fabric Mixin 方案。
 */
package com.multiplayer.ender.client;

import com.multiplayer.ender.client.gui.StartupScreen;
import com.multiplayer.ender.client.gui.EnderDashboard;
import com.multiplayer.ender.network.EnderApiClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 多人游戏菜单入口处理器（Forge）。
 *
 * 属于「多人游戏」页面：屏幕初始化完成后追加一个入口按钮；按钮按后端是否已分配动态端口，
 * 决定直接进入 EnderDashboard，还是先经 StartupScreen 完成启动流程再进入。
 *
 * 设计约束：
 * 1. 入口一律通过 ScreenEvent.Init.Post 注入，不再使用 Mixin——Fabric 支持已终止（ADR-00），
 *    两端统一走加载器事件，可避免映射变动导致注入点失效。
 * 2. 该事件在每个 Screen 初始化后都会触发，必须先以 instanceof 判定目标屏幕再改动控件列表；
 *    非目标屏幕不得留下任何副作用。
 *
 * 线程安全性：静态回调在客户端主线程执行，本类无可变状态。
 *
 * @see EnderDashboard
 * @see StartupScreen
 */
@Mod.EventBusSubscriber(modid = "ender_online", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class MultiplayerMenuHandlerForge {

    /**
     * 屏幕初始化完成后的回调。
     *
     * 仅对 JoinMultiplayerScreen 生效：在其右上角添加宽 120、高 20 的入口按钮。
     * 点击时若后端尚未分配动态端口，先打开 StartupScreen，启动成功后再进入控制台。
     *
     * @param event 屏幕初始化事件，由 Forge 注入，不能为 null
     */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (screen instanceof JoinMultiplayerScreen joinScreen) {
            // 右上角对齐：按钮贴右边缘留 5px 边距，y=5 与暂停菜单入口保持同一视觉基线
            int width = joinScreen.width;
            int buttonWidth = 120;
            int x = width - buttonWidth - 5;
            int y = 5;

            Button enderBtn = Button.builder(Component.literal("末影联机"), button -> {
                Minecraft mc = Minecraft.getInstance();
                if (EnderApiClient.hasDynamicPort()) {
                    mc.setScreen(new EnderDashboard(joinScreen));
                } else {
                    mc.setScreen(new StartupScreen(joinScreen, () -> {
                        mc.setScreen(new EnderDashboard(joinScreen));
                    }));
                }
            }).bounds(x, y, buttonWidth, 20).build();

            event.addListener(enderBtn);
        }
    }
}




