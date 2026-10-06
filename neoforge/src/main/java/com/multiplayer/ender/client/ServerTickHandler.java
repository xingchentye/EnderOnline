/*
 * 本文件属于 EnderOnline NeoForge 适配层。
 *
 * 职责：监听逻辑服务器 Tick，把周期任务转发给 RoomHostLogic。
 *
 * 关键约束：只在客户端（Dist.CLIENT）生效；本类不做任何业务判断，避免与共享逻辑重复。
 */
package com.multiplayer.ender.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 服务器 Tick 事件处理器。
 *
 * 把每 tick 的服务器事件桥接到 {@link RoomHostLogic}，让房主端能周期性把游戏状态同步给后端。
 * 之所以走事件总线而不是直接在主循环里调用，是为了让集成服务器（单人开房间）与专用服务器共用同一入口。
 *
 * 设计约束：本类只是桥，自身的 tick 频率控制与「是否处于托管」判断都归 {@link RoomHostLogic}。
 *
 * 线程安全性：回调由服务器主线程串行触发，本类无可变状态。
 *
 * @since 1.0
 * @see RoomHostLogic
 */
@EventBusSubscriber(modid = "ender_online", value = Dist.CLIENT)
public class ServerTickHandler {

    /**
     * 当服务器 Tick 结束时调用。
     *
     * 取当前逻辑服务器实例；为 null（尚未启动或已停止）时直接返回。
     *
     * @param event Tick 事件，不能为 null
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
         MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
         if (server != null) {
             RoomHostLogic.onServerTick(server);
         }
    }
}
