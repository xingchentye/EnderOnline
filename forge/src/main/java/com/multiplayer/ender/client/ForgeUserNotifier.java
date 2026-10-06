/*
 * 本文件属于 EnderOnline Forge 适配层。
 *
 * 职责：把 Forge 侧 ClientSetupForge 的提示能力适配为共享的 UserNotifier 接口。
 */
package com.multiplayer.ender.client;

import net.minecraft.network.chat.Component;

/**
 * Forge 侧的提示实现。
 *
 * 本类只做转发：真正的 toast 渲染与事件订阅留在 {@link ClientSetupForge}，
 * 因为那部分依赖 Forge 的 TickEvent / ScreenEvent，无法上移到共享层。
 *
 * 线程安全性：无状态，全部委托给 ClientSetupForge。
 *
 * @since 1.0
 * @see ClientSetupForge
 */
public final class ForgeUserNotifier implements UserNotifier {

    @Override
    public void toast(Component title, Component message) {
        ClientSetupForge.showToast(title, message);
    }

    @Override
    public void roomCodeNotification(String roomCode) {
        ClientSetupForge.handleRoomCodeNotification(roomCode);
    }
}
