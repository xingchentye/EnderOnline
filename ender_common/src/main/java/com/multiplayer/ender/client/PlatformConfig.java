/*
 * 本文件属于 EnderOnline 客户端界面层。
 *
 * 职责：定义客户端设置的类型化入口，屏蔽两端配置后端的差异。
 */
package com.multiplayer.ender.client;

/**
 * 客户端设置的读写入口。
 *
 * 契约说明：
 * 1. 读取方法（getXxx）可在任意线程调用，不得抛异常；未安装实现前读取返回内置默认值。
 * 2. 写入方法（setXxx）只应在客户端主线程调用。
 * 3. {@link #save()} 才真正落盘；setXxx 只改内存。调用方负责在合适的时机保存。
 * 4. 实现由各加载器模块提供（Forge 侧为 ForgeConfigSpec、NeoForge 侧为 ModConfigSpec），
 * 通过 {@link PlatformConfigHolder#install} 在入口点注入。
 *
 * 为什么需要这层抽象：两端的配置后端类型不同（net.minecraftforge.common.ForgeConfigSpec
 * 与 net.neoforged.neoforge.common.ModConfigSpec），共享代码不得引用任何一个，
 * 否则会破坏 ADR-13 要求的「ender_common 只写 vanilla Mojmap」。
 *
 * @since 1.0
 * @see PlatformConfigHolder
 */
public interface PlatformConfig {

    /**
     * 读取外部核心文件路径。
     *
     * @return 绝对路径；空串表示未设置，应走下载流程
     */
    String externalCorePath();

    /**
     * 写入外部核心文件路径。
     *
     * @param path 绝对路径，允许为空串表示恢复默认行为
     */
    void setExternalCorePath(String path);

    /**
     * 读取是否自动更新核心。
     *
     * @return true 表示允许下载更新
     */
    boolean autoUpdate();

    /**
     * 写入是否自动更新核心。
     *
     * @param value 新的开关状态
     */
    void setAutoUpdate(boolean value);

    /**
     * 读取进入菜单时是否自动启动后端。
     *
     * @return true 表示进入末影联机菜单时自动拉起后端
     */
    boolean autoStartBackend();

    /**
     * 写入进入菜单时是否自动启动后端。
     *
     * @param value 新的开关状态
     */
    void setAutoStartBackend(boolean value);

    /**
     * 把当前内存中的设置落盘。
     *
     * 未调用本方法前，setXxx 的改动不会写入 toml 文件。
     */
    void save();
}
