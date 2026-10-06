/*
 * 本文件属于 EnderOnline NeoForge 适配层。
 *
 * 职责：网络相关的上层业务封装，把后端健康检查与连接流程转成屏幕跳转。
 *
 * 关键约束：所有 UI 变更必须回到 Minecraft 主线程执行，禁止在回调线程直接 setScreen。
 */
package com.multiplayer.ender.network;

import org.slf4j.Logger;

import com.multiplayer.ender.MinecraftEnder;
import com.multiplayer.ender.client.gui.ConnectingScreen;
import com.multiplayer.ender.client.gui.LobbyScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import com.multiplayer.ender.client.gui.StartupScreen;
import com.multiplayer.ender.client.gui.EnderDashboard;

/**
 * 网络操作辅助类，供 UI 层调用。
 *
 * 把两类用户动作翻译成流程：主菜单「末影联机」入口的落点选择，以及把某个地址端口接成一次连接。
 * 真正的协议与会话由 {@link NetworkClient} 负责，本类不持有连接状态。
 *
 * 设计约束：
 * 1. 判断「后端是否在跑」只依据 {@link EnderApiClient#hasDynamicPort()} 与一次健康检查，二者都不通过就回落启动屏幕。
 * 2. 所有 {@code setScreen} 必须包在 {@code Minecraft.execute} 中；后台回调线程不得直接触碰 UI。
 *
 * 线程安全性：本类无实例状态，全部是静态方法；异步结果统一由主线程执行器落地。
 *
 * @since 1.0
 * @see EnderApiClient
 * @see NetworkClient
 */
public class NetworkHandler {
    /** 模块日志记录器，复用主入口的全局 logger 以统一日志格式。 */
    private static final Logger LOGGER = MinecraftEnder.LOGGER;

    /**
     * 初始化网络模块。
     *
     * 当前只记录日志，保留为后续注册自定义网络通道的挂点。
     */
    public static void init() {
        LOGGER.info("初始化网络通信模块...");
    }

    /**
     * 处理主菜单「末影联机」按钮点击。
     *
     * 先看是否已记录动态端口（存在端口说明后端可能正在运行）；有端口时异步探活，
     * 健康则进入 {@link EnderDashboard}，不健康则清掉失效端口并落到 {@link StartupScreen}；
     * 无端口时直接落到启动屏幕。
     *
     * 幂等性：本方法只读取状态并跳转，重复调用不会产生额外副作用。
     */
    public static void onConnectButtonClicked() {
        Minecraft minecraft = Minecraft.getInstance();
        if (EnderApiClient.hasDynamicPort()) {
            EnderApiClient.checkHealth().thenAccept(ok -> {
                minecraft.execute(() -> {
                    if (ok) {
                        minecraft.setScreen(new EnderDashboard(minecraft.screen));
                    } else {
                        EnderApiClient.clearDynamicPort();
                        minecraft.setScreen(new StartupScreen(minecraft.screen));
                    }
                });
            });
            return;
        }

        minecraft.setScreen(new StartupScreen(minecraft.screen));
    }

    /**
     * 连接到指定服务器。
     *
     * 立即切换到 {@link ConnectingScreen} 作为过渡，再让 {@link NetworkClient#connect} 执行实际连接；
     * 成功进入 {@link LobbyScreen}，失败把异常消息写回过渡屏幕的状态行。
     *
     * 设计约束：失败路径直接展示 {@code ex.getMessage()} 是 ADR-07 的既有违例（应改为错误码 + 本地化文案），P7 一并处理。
     *
     * @param host 目标主机地址，不能为 null 或空串
     * @param port 目标端口，取值 1..65535
     * @param parentScreen 父屏幕，用于返回，允许为 null
     */
    public static void connectToServer(String host, int port, net.minecraft.client.gui.screens.Screen parentScreen) {
        LOGGER.info("正在连接到 {}:{}...", host, port);
        
        Minecraft minecraft = Minecraft.getInstance();
        ConnectingScreen connectingScreen = new ConnectingScreen(parentScreen);
        minecraft.setScreen(connectingScreen);
        
        NetworkClient.getInstance().connect(host, port).whenComplete((result, ex) -> {
            minecraft.execute(() -> {
                if (ex != null) {
                    LOGGER.error("连接失败", ex);
                    connectingScreen.setStatus(Component.literal("连接失败: " + ex.getMessage()));
                } else {
                    LOGGER.info("连接成功");
                    minecraft.setScreen(new LobbyScreen(parentScreen));
                }
            });
        });
    }
}
