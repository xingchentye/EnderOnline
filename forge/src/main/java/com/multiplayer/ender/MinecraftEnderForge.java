/*
 * 本文件属于 EnderOnline Forge 适配层。
 *
 * 职责：Forge 模组入口点，负责模组实例构造、配置注册与 Forge 事件总线挂载。
 *
 * 本类只做注册与转发，不含房间、网络或界面逻辑。
 */
package com.multiplayer.ender;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * 末影联机 Forge 模组入口类。
 *
 * 由 Forge 依据 @Mod 注解在模组加载阶段实例化。构造器内只做注册，不承载业务逻辑：
 * 房间与网络语义下沉到 network / logic 包，界面语义下沉到 client.gui 包。
 *
 * 设计约束：
 * 1. 构造器由 Forge 调用，禁止在其中执行磁盘 IO、网络请求或拉起外部进程；
 *    这些动作统一推迟到玩家进入末影联机界面后由 StartupScreen 触发。
 * 2. MODID 必须与 neoforge 适配层的 MinecraftEnder.MODID 保持一致，
 *    否则两端的事件订阅 id 与配置文件名会分叉。
 *
 * 线程安全性：本类无可变状态，字段均为编译期常量或线程安全的日志器。
 *
 * @see ConfigForge
 */
@Mod(MinecraftEnderForge.MODID)
public class MinecraftEnderForge {
    /** 模组标识符，同时用作事件总线订阅 id 与配置文件命名前缀。 */
    public static final String MODID = "ender_online";

    /** 模组日志器，绑定当前模组 id。 */
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 构造模组实例，由 Forge 在加载阶段调用一次。
     *
     * 依次完成三件事：绑定 FML 模组事件总线、把本实例挂到 Forge 事件总线、
     * 注册 COMMON 与 CLIENT 两份配置（文件名分别为 ender-common.toml 与 ender.toml）。
     *
     * 副作用：两份配置的文件名一旦改动，既有玩家已写入的配置将不再被读取。
     */
    @SuppressWarnings("removal")
    public MinecraftEnderForge() {
        // NOTE: FMLJavaModLoadingContext.get() 与 ModLoadingContext.get() 在 Forge 1.21.1
        // 已标记为待移除；改为构造器注入 IEventBus / ModContainer 属于 P8 构建收敛范围。
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        modEventBus.addListener(this::commonSetup);

        MinecraftForge.EVENT_BUS.register(this);

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ConfigForge.SPEC, "ender-common.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ConfigForge.CLIENT_SPEC, "ender.toml");
    }

    /**
     * 通用设置阶段回调，由 FMLCommonSetupEvent 触发一次。
     *
     * 当前只记录一条加载日志；后续若需要注册网络通道或能力，应放在此处而非构造器。
     *
     * @param event FML 通用设置事件，由 FML 注入，不能为 null
     */
    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("末影联机 Forge Mod 已加载 - 通用设置");
    }

    /**
     * 服务器启动事件回调。
     *
     * 该事件在专用服务器或集成服务器开始启动时触发，可从任意线程到达，因此回调内不得触碰客户端状态。
     *
     * @param event 服务器启动事件，由 Forge 注入，不能为 null
     */
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("末影联机 Forge - 服务器正在启动");
    }
}



