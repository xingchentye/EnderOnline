/*
 * 本文件属于 EnderOnline NeoForge 适配层。
 *
 * 职责：NeoForge 侧的配置后端，声明并持有 COMMON / CLIENT 两份 ModConfigSpec。
 *
 * 关键约束：配置项的读写必须经由这里暴露的 ConfigValue，不得在别处直接解析 toml；
 * 字段命名沿用历史遗留的 EXTERNAL_ender_PATH，P8 阶段统一改为平台无关命名。
 */
package com.multiplayer.ender;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * NeoForge 侧配置后端。
 *
 * 本类在类初始化时构建两份规格：通用规格 {@link #SPEC} 与客户端规格 {@link #CLIENT_SPEC}，
 * 由 {@link MinecraftEnder} 分别注册为 {@code ender-common.toml} 与 {@code ender.toml}。
 *
 * 设计约束：
 * 1. 配置项的注释文本由本类提供，直接写入 toml；其他类只读写值，不重复定义。
 * 2. {@code CLIENT_SPEC.save()} 之后才有持久化效果，改值本身只作用于内存。
 *
 * 线程安全性：所有可变状态在静态初始化块中一次性写完，之后只读；NeoForge 配置系统自身保证跨线程读写安全。
 *
 * 变更记录：
 * 2026-04 抽出 CLIENT 规格，把客户端设置从通用 toml 中分离
 *
 * @since 1.0
 * @see MinecraftEnder
 */
public class Config {
    /** 通用规格构建器，仅在静态初始化期间使用，构建后不再引用。 */
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** 客户端规格构建器，仅在静态初始化期间使用。 */
    private static final ModConfigSpec.Builder CLIENT_BUILDER = new ModConfigSpec.Builder();

    // TODO(P8, 2026-06-30): 统一配置后端命名，去掉 EXTERNAL_ender_PATH 中的平台痕迹并补 migrate 逻辑
    /** 外部核心文件路径。默认空串，空串表示走下载流程而不是直接使用本地文件。 */
    public static final ModConfigSpec.ConfigValue<String> EXTERNAL_ender_PATH;

    /** 是否自动更新核心。默认 true。 */
    public static final ModConfigSpec.BooleanValue AUTO_UPDATE;

    /** 进入菜单时是否自动启动后端。默认 false，避免冷启动时无谓拉起进程。 */
    public static final ModConfigSpec.BooleanValue AUTO_START_BACKEND;

    static {
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

    /** 通用配置规格，非 null；已由 {@link MinecraftEnder} 注册，不要在运行期重新 build。 */
    static final ModConfigSpec SPEC = BUILDER.build();

    /** 客户端配置规格，非 null；对外可见以便配置屏幕直接调用 save()。 */
    public static final ModConfigSpec CLIENT_SPEC = CLIENT_BUILDER.build();
}
