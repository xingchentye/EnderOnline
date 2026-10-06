/*
 * 本文件属于 EnderOnline NeoForge 适配层。
 *
 * 职责：在多人游戏界面注入「末影联机」入口按钮。
 *
 * 关键约束：按钮坐标按屏幕宽度右对齐，禁止写死绝对位置；点击后要么直接进仪表盘，要么先走启动屏幕。
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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * 多人游戏菜单处理器。
 *
 * 在原版「多人游戏」界面右上角追加一个「末影联机」按钮，作为本模组的主入口之一。
 *
 * 设计约束：
 * 1. 只处理 {@link JoinMultiplayerScreen}；其它屏幕即使触发同名事件也直接忽略。
 * 2. 按钮复用屏幕宽度做右对齐（{@code width - buttonWidth - 5}），不依赖具体分辨率。
 *
 * 线程安全性：回调在客户端主线程的屏幕初始化阶段执行，本类无可变状态。
 *
 * @since 1.0
 * @see EnderDashboard
 * @see StartupScreen
 */
@EventBusSubscriber(modid = "ender_online", value = Dist.CLIENT)
public class MultiplayerMenuHandler {

    /**
     * 当屏幕初始化时调用。
     *
     * 仅当屏幕是 {@link JoinMultiplayerScreen} 时注入按钮；点击时若已有动态端口则直接进入仪表盘，
     * 否则先弹启动屏幕，待后端就绪后再进入仪表盘。
     *
     * @param event 屏幕初始化事件，不能为 null
     */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (screen instanceof JoinMultiplayerScreen joinScreen) {
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




