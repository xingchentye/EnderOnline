/*
 * 本文件属于 EnderOnline NeoForge 适配层。
 *
 * 职责：NeoForge 客户端专用入口，注册配置屏幕工厂与客户端设置事件监听。
 *
 * 关键约束：本类标记 Dist.CLIENT，禁止在专用服务端加载；不得在此启动任何网络或后端进程。
 */
package com.multiplayer.ender;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * 末影联机 NeoForge 客户端入口点。
 *
 * 与 {@link MinecraftEnder} 同 modid 但限定 {@code Dist.CLIENT}，因此只在客户端进程实例化。
 * 它做两件事：把 {@code IConfigScreenFactory} 注册为配置屏幕工厂（让玩家能从 Mod 列表打开配置界面），
 * 以及把客户端设置回调挂到 mod 事件总线。
 *
 * 设计约束：不使用 {@code @EventBusSubscriber}，改为在构造器里显式 addListener，以消除过时警告并让注册点集中在入口。
 *
 * 线程安全性：本类无可变状态，构造由 NeoForge 在客户端生命周期线程调用一次。
 *
 * @since 1.0
 * @see MinecraftEnder
 */
@Mod(value = MinecraftEnder.MODID, dist = Dist.CLIENT)
public class MinecraftEnderClient {
    /**
     * 构造客户端入口。
     *
     * 注册配置屏幕工厂，并把 {@link #onClientSetup} 挂到 mod 事件总线。
     *
     * @param container 当前 mod 容器，不能为 null；既用于扩展点注册，也用于取得 mod 事件总线
     */
    public MinecraftEnderClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        // 手动注册客户端设置事件监听器，替代 @EventBusSubscriber 以消除过时警告
        container.getEventBus().addListener(MinecraftEnderClient::onClientSetup);
    }

    /**
     * 客户端设置事件回调。
     *
     * 在 {@code FMLClientSetupEvent} 触发时执行；当前只记录日志，不注册渲染器或按键绑定。
     *
     * @param event 客户端设置事件，不能为 null
     */
    static void onClientSetup(FMLClientSetupEvent event) {
        MinecraftEnder.LOGGER.info("MinecraftEnder 客户端设置完成");
    }
}

