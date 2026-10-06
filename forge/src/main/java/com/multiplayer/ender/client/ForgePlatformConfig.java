/*
 * 本文件属于 EnderOnline Forge 适配层。
 *
 * 职责：把 Forge 的 ForgeConfigSpec 适配为共享层使用的 PlatformConfig 接口。
 */
package com.multiplayer.ender.client;

import com.multiplayer.ender.ConfigForge;

/**
 * Forge 侧的客户端设置实现。
 *
 * 本类只做类型搬运：把 ForgeConfigSpec 的 ConfigValue 读写映射到 {@link PlatformConfig}
 * 的平台无关方法上，不缓存值、不改变读写时机。因此它自身无状态，可安全地被多个调用方共享。
 *
 * 设计约束：
 * 1. 写入后必须由调用方显式调用 {@link #save()} 才落盘，与 ForgeConfigSpec 的语义保持一致。
 * 2. 字段命名沿用 ConfigForge 的历史命名（EXTERNAL_ender_PATH），不要在适配层做重命名，
 *    否则会与配置键 externalEnderPath 的对应关系脱节。
 *
 * 线程安全性：无状态，全部委托给 ForgeConfigSpec；读取可从任意线程，写入应在客户端主线程。
 *
 * @since 1.0
 * @see ConfigForge
 * @see PlatformConfig
 */
public final class ForgePlatformConfig implements PlatformConfig {

    @Override
    public String externalCorePath() {
        return ConfigForge.EXTERNAL_ender_PATH.get();
    }

    @Override
    public void setExternalCorePath(String path) {
        ConfigForge.EXTERNAL_ender_PATH.set(path == null ? "" : path);
    }

    @Override
    public boolean autoUpdate() {
        return ConfigForge.AUTO_UPDATE.get();
    }

    @Override
    public void setAutoUpdate(boolean value) {
        ConfigForge.AUTO_UPDATE.set(value);
    }

    @Override
    public boolean autoStartBackend() {
        return ConfigForge.AUTO_START_BACKEND.get();
    }

    @Override
    public void setAutoStartBackend(boolean value) {
        ConfigForge.AUTO_START_BACKEND.set(value);
    }

    @Override
    public void save() {
        ConfigForge.CLIENT_SPEC.save();
    }
}
