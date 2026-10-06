/*
 * 本文件属于 EnderOnline NeoForge 适配层。
 *
 * 职责：NeoForge 主入口点，注册 mod 事件总线监听与 COMMON/CLIENT 两份配置规格。
 *
 * 关键约束：本类只做注册，不得承载业务逻辑；所有业务下沉到 ender_core 与共享逻辑。
 */
package com.multiplayer.ender;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

/**
 * 末影联机 NeoForge 主入口点。
 *
 * 由 NeoForge 依据 {@code @Mod} 注解实例化，负责把生命周期回调挂到两套事件总线上，
 * 并把 {@link Config} 的两份规格注册成 {@code ender-common.toml} 与 {@code ender.toml}。
 *
 * 设计约束：
 * 1. 本类只做注册与转发，不放业务逻辑；业务属于 ender_core 与共享逻辑层。
 * 2. commonSetup 与 onServerStarting 必须保持无副作用或幂等，事件总线可能在测试环境重复触发。
 *
 * 线程安全性：本类无可变状态，构造与事件回调均由 NeoForge 在单一生命周期线程调用。
 *
 * @since 1.0
 */
@Mod(MinecraftEnder.MODID)
public class MinecraftEnder {
    /** Mod 标识，与 {@code neoforge.mods.toml} 及所有资源命名空间一致。 */
    public static final String MODID = "ender_online";

    /** 全局日志记录器，非 null；全模块共用以免各自建 logger 导致格式不一致。 */
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 构造 NeoForge 主入口。
     *
     * 注册通用设置监听、服务器启动监听，并把两份配置规格绑定到对应文件名。
     *
     * @param modEventBus mod 专用事件总线，不能为 null，由 NeoForge 注入
     * @param modContainer 当前 mod 容器，不能为 null，用于注册配置
     */
    public MinecraftEnder(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);

        NeoForge.EVENT_BUS.register(this);

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC, "ender-common.toml");
        modContainer.registerConfig(ModConfig.Type.CLIENT, Config.CLIENT_SPEC, "ender.toml");

        // 注入配置实现供共享层使用（ADR-01 适配注入）：共享代码不得引用 ModConfigSpec，
        // 只能通过 PlatformConfig 接口读写设置。
        com.multiplayer.ender.client.PlatformConfigHolder.install(
                new com.multiplayer.ender.client.NeoForgePlatformConfig());

        // 注入提示实现：共享代码不得引用 ClientSetup，只能通过 UserNotifier 接口发提示。
        com.multiplayer.ender.client.UserNotifierHolder.install(
                new com.multiplayer.ender.client.NeoForgeUserNotifier());
    }

    /**
     * 通用设置阶段回调。
     *
     * 在 {@code FMLCommonSetupEvent} 触发时执行；当前只记录日志，两侧平台共用同一条初始化路径。
     *
     * @param event 通用设置事件，不能为 null
     */
    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("末影联机 Mod 已加载 - 通用设置");
    }

    /**
     * 服务器启动事件回调。
     *
     * 监听逻辑服务器的启动，仅用于日志；不在此处启动后端进程，后端生命周期由客户端 StartupScreen 负责。
     *
     * @param event 服务器启动事件，不能为 null
     */
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("末影联机 - 服务器正在启动");
    }
}




