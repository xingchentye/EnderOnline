/*
 * 本文件属于 EnderOnline 客户端界面层。
 *
 * 职责：持有当前的客户端设置实现，供共享代码在不引用加载器类型的前提下读取。
 */
package com.multiplayer.ender.client;

/**
 * 客户端设置实现的持有者。
 *
 * 设计约束：
 * 1. 这是 ADR-01「适配注入」的唯一落点：各加载器在入口点调用 {@link #install} 注入实现，
 * 共享代码只通过 {@link #get()} 读取接口，不感知具体平台。
 * 2. 未注入时 {@link #get()} 返回一份内存实现，读代码无需在各处判空。这一点很重要：
 * 单元测试与脚手架场景下配置后端并不存在，若强制要求先注入会让调用方到处写空检查。
 * 3. 只允许注入一次；重复注入视为编程错误。
 *
 * 线程安全性：{@code current} 为 volatile，install 之后只读；内存实现的写入方法不做同步，
 * 因为它只在未注入的降级场景下使用，不承载真实持久化。
 *
 * @since 1.0
 * @see PlatformConfig
 */
public final class PlatformConfigHolder {

    /** 当前实现。初始为内存降级实现，install 之后替换。 */
    private static volatile PlatformConfig current = new InMemory();

    private PlatformConfigHolder() {
    }

    /**
     * 注入加载器提供的实现。
     *
     * @param config 实现，不能为 null
     * @throws NullPointerException 当 config 为 null 时抛出
     * @throws IllegalStateException 当已经注入过实现时抛出，防止入口点重复注册掩盖装配错误
     */
    public static void install(PlatformConfig config) {
        if (config == null) {
            throw new NullPointerException("config");
        }
        if (!(current instanceof InMemory)) {
            throw new IllegalStateException("PlatformConfig 已注入，不应重复安装");
        }
        current = config;
    }

    /**
     * 获取当前实现。
     *
     * @return 实现，永不为 null；未注入时为内存降级实现
     */
    public static PlatformConfig get() {
        return current;
    }

    /**
     * 内存降级实现，仅用于未注入场景。
     *
     * 读取返回内置默认值，写入只改内存且不产生持久化效果。
     */
    private static final class InMemory implements PlatformConfig {

        /** 默认空串，表示走下载流程。 */
        private String externalCorePath = "";

        /** 默认允许更新。 */
        private boolean autoUpdate = true;

        /** 默认不自动启动，避免冷启动时无谓拉起进程。 */
        private boolean autoStartBackend = false;

        @Override
        public String externalCorePath() {
            return externalCorePath;
        }

        @Override
        public void setExternalCorePath(String path) {
            this.externalCorePath = path == null ? "" : path;
        }

        @Override
        public boolean autoUpdate() {
            return autoUpdate;
        }

        @Override
        public void setAutoUpdate(boolean value) {
            this.autoUpdate = value;
        }

        @Override
        public boolean autoStartBackend() {
            return autoStartBackend;
        }

        @Override
        public void setAutoStartBackend(boolean value) {
            this.autoStartBackend = value;
        }

        @Override
        public void save() {
            // 降级场景没有配置文件可写：真实持久化只在加载器实现中发生
        }
    }
}
