/*
 * 本文件属于 EnderOnline Forge 适配层。
 *
 * 职责：把 Forge 的服务器 Tick 事件转发给 RoomHostLogic，驱动房主端状态同步。
 *
 * 本类只做阶段筛选与空值保护，不实现任何同步逻辑。
 */
package com.multiplayer.ender.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * 服务器 Tick 事件转发器（Forge）。
 *
 * 由 Forge 事件总线按 @Mod.EventBusSubscriber 注册，每个服务器 tick 触发一次；
 * 仅在 END 阶段且当前存在服务器实例时把调用转交 RoomHostLogic。
 *
 * 设计约束：
 * 1. 回调体必须保持廉价：它每 tick 都在服务器主线程执行，节流责任下沉到 RoomHostLogic
 *    （内部按 20 tick 计数），此处不得再叠加耗时操作。
 * 2. 只订阅 FORGE 总线上的服务器 Tick；客户端 Tick 由 ClientSetupForge 单独订阅，
 *    两者不共用回调。
 *
 * 线程安全性：静态回调在服务器主线程串行执行，本类无可变状态。
 *
 * @see RoomHostLogic#onServerTick(net.minecraft.server.MinecraftServer)
 */
@Mod.EventBusSubscriber(modid = "ender_online", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ServerTickHandler {

    /**
     * 服务器 Tick 事件回调，仅在 TickEvent.Phase.END 阶段生效。
     *
     * 世界尚未创建或服务器已停止时 ServerLifecycleHooks 返回 null，此时直接跳过本 tick。
     *
     * @param event 服务器 Tick 事件，由 Forge 注入，不能为 null
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
             MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
             if (server != null) {
                 RoomHostLogic.onServerTick(server);
             }
        }
    }
}
