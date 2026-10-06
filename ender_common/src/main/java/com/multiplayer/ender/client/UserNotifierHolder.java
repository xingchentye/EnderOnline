/*
 * 本文件属于 EnderOnline 客户端界面层。
 *
 * 职责：持有当前的提示实现，供共享代码在不引用加载器类型的前提下使用。
 */
package com.multiplayer.ender.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 提示实现的持有者。
 *
 * 设计约束：
 * 1. 与 {@link PlatformConfigHolder} 同构：加载器在入口点调用 {@link #install} 注入实现，
 * 共享代码只通过 {@link #get()} 使用接口。
 * 2. 未注入时回落为「聊天栏 + 动作栏」实现，而不是空实现。理由：空实现会让调用方
 * 以为提示已显示，实际什么都没发生，排查成本远高于直接打在聊天栏里。
 * 3. 只允许注入一次，重复注入视为装配错误。
 *
 * 线程安全性：{@code current} 为 volatile，install 之后只读；降级实现每次调用都重新取
 * 客户端实例，不缓存状态。
 *
 * @since 1.0
 * @see UserNotifier
 */
public final class UserNotifierHolder {

    /** 当前实现。初始为聊天栏降级实现，install 之后替换。 */
    private static volatile UserNotifier current = new ChatFallback();

    private UserNotifierHolder() {
    }

    /**
     * 注入加载器提供的实现。
     *
     * @param notifier 实现，不能为 null
     * @throws NullPointerException 当 notifier 为 null 时抛出
     * @throws IllegalStateException 当已经注入过实现时抛出
     */
    public static void install(UserNotifier notifier) {
        if (notifier == null) {
            throw new NullPointerException("notifier");
        }
        if (!(current instanceof ChatFallback)) {
            throw new IllegalStateException("UserNotifier 已注入，不应重复安装");
        }
        current = notifier;
    }

    /**
     * 获取当前实现。
     *
     * @return 实现，永不为 null；未注入时为聊天栏降级实现
     */
    public static UserNotifier get() {
        return current;
    }

    /**
     * 降级实现：把提示写进聊天栏与动作栏。
     *
     * 用于加载器尚未注入实现的场景（例如共享代码在单元测试或早期初始化阶段被触达）。
     */
    private static final class ChatFallback implements UserNotifier {

        @Override
        public void toast(Component title, Component message) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.player == null) {
                // 玩家还没进入世界时没有可用的聊天栏，此时丢弃提示是唯一合理选择
                return;
            }
            // Component.literal 拼接：两段都是已本地化的文本，无需再做翻译
            minecraft.player.displayClientMessage(Component.literal(title.getString() + ": ")
                    .append(message), false);
        }

        @Override
        public void roomCodeNotification(String roomCode) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.player == null) {
                return;
            }
            minecraft.player.displayClientMessage(
                    Component.literal("Ender Online room code: " + roomCode), false);
        }
    }
}
