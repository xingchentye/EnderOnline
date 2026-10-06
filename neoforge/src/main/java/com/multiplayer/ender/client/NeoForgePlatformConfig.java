/*
 * 本文件属于 EnderOnline NeoForge 适配层。
 *
 * 职责：把 NeoForge 的 ModConfigSpec 适配为共享层使用的 PlatformConfig 接口。
 */
package com.multiplayer.ender.client;

import com.multiplayer.ender.Config;

/**
 * NeoForge 侧的客户端设置实现。
 *
 * 本类只做类型搬运：把 ModConfigSpec 的 ConfigValue 读写映射到 {@link PlatformConfig}
 * 的平台无关方法上，不缓存值、不改变读写时机。因此它自身无状态，可安全地被多个调用方共享。
 *
 * 设计约束：
 * 1. 写入后必须由调用方显式调用 {@link #save()} 才落盘，与 ModConfigSpec 的语义保持一致。
 * 2. 字段命名沿用 Config 的历史命名（EXTERNAL_ender_PATH），不要在适配层做重命名。
 *
 * 线程安全性：无状态，全部委托给 ModConfigSpec；读取可从任意线程，写入应在客户端主线程。
 *
 * @since 1.0
 * @see Config
 * @see PlatformConfig
 */
public final class NeoForgePlatformConfig implements PlatformConfig {

    @Override
    public String externalCorePath() {
        return Config.EXTERNAL_ender_PATH.get();
    }

    @Override
    public void setExternalCorePath(String path) {
        Config.EXTERNAL_ender_PATH.set(path == null ? "" : path);
    }

    @Override
    public boolean autoUpdate() {
        return Config.AUTO_UPDATE.get();
    }

    @Override
    public void setAutoUpdate(boolean value) {
        Config.AUTO_UPDATE.set(value);
    }

    @Override
    public boolean autoStartBackend() {
        return Config.AUTO_START_BACKEND.get();
    }

    @Override
    public void setAutoStartBackend(boolean value) {
        Config.AUTO_START_BACKEND.set(value);
    }

    @Override
    public void save() {
        Config.CLIENT_SPEC.save();
    }
}
