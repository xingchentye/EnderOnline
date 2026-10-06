/*
 * 本文件属于 EnderOnline Forge 适配层。
 *
 * 职责：Forge 侧配置后端，声明并构建 ender-common.toml 与 ender.toml 两份 ForgeConfigSpec。
 *
 * 本类只描述「配置的形状与默认值」，不负责读取时机的判定，也不感知界面。
 */
package com.multiplayer.ender;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Forge 侧配置后端。
 *
 * 持有两份 ForgeConfigSpec：SPEC 为 COMMON 配置（当前无声明项），CLIENT_SPEC 为客户端配置
 * （externalEnderPath / autoUpdate / autoStartBackend）。两份 spec 都在静态块中一次性构建，
 * 之后由 MinecraftEnderForge 按注册的文件名读写。
 *
 * 设计约束：
 * 1. 所有 ConfigValue 必须在该类的静态块内赋值：ForgeConfigSpec 要求定义期与读取期分离，
 *    静态块结束后不得再调用 define/push/pop。
 * 2. 字段名 EXTERNAL_ender_PATH 中的小写 ender 是历史命名，与配置键 externalEnderPath 并非
 *    机械对应，重命名前需同步检查引用方。
 * 3. 默认值只影响首次生成的 toml；改动默认值不会回写既有玩家的配置文件。
 *
 * 线程安全性：静态初始化由类加载器保证仅执行一次；构建完成后 spec 只读，
 * get() 可从任意线程调用，但 set()/save() 只应在客户端主线程进行。
 *
 * TODO(P8, 2026-12-31): 与 neoforge 侧配置后端合并为统一的 ModConfigForge，配置只保留一份实现。
 *
 * @see MinecraftEnderForge
 */
public class ConfigForge {
    /** COMMON 配置构建器，仅静态块内使用，build() 之后不再引用。 */
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    /** CLIENT 配置构建器，仅静态块内使用，build() 之后不再引用。 */
    private static final ForgeConfigSpec.Builder CLIENT_BUILDER = new ForgeConfigSpec.Builder();

    /** 外部核心文件路径，默认空字符串。非空时按绝对路径解析，文件存在则跳过下载。 */
    public static final ForgeConfigSpec.ConfigValue<String> EXTERNAL_ender_PATH;

    /** 是否自动更新核心，默认 true。关闭后只使用本地已存在的核心文件。 */
    public static final ForgeConfigSpec.BooleanValue AUTO_UPDATE;

    /** 是否在进入末影联机菜单时自动启动后端进程，默认 false。 */
    public static final ForgeConfigSpec.BooleanValue AUTO_START_BACKEND;

    static {
        // 客户端配置项在此一次性声明：ForgeConfigSpec 不允许在定义期之外再 define
        CLIENT_BUILDER.comment("客户端设置").push("client");

        EXTERNAL_ender_PATH = CLIENT_BUILDER
                .comment("外部核心文件路径 (如果设置，将跳过下载并直接使用此文件)")
                .define("externalEnderPath", "");

        AUTO_UPDATE = CLIENT_BUILDER
                .comment("是否自动更新核心")
                .define("autoUpdate", true);

        AUTO_START_BACKEND = CLIENT_BUILDER
                .comment("是否自动启动核心 (进入菜单时)")
                .define("autoStartBackend", false);

        CLIENT_BUILDER.pop();
    }

    /** COMMON 配置规范，当前无声明项；由 MinecraftEnderForge 注册为 ender-common.toml。 */
    static final ForgeConfigSpec SPEC = BUILDER.build();

    /** CLIENT 配置规范，由 MinecraftEnderForge 注册为 ender.toml，也是 EnderConfigScreen 保存配置的落点。 */
    public static final ForgeConfigSpec CLIENT_SPEC = CLIENT_BUILDER.build();
}




