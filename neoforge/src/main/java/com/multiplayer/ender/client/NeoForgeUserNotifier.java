/*
 * 本文件属于 EnderOnline NeoForge 适配层。
 *
 * 职责：把 NeoForge 侧 ClientSetup 的提示能力适配为共享的 UserNotifier 接口。
 */
package com.multiplayer.ender.client;

import net.minecraft.network.chat.Component;

/**
 * NeoForge 侧的提示实现。
 *
 * 本类只做转发：真正的 HUD toast 渲染与事件订阅留在 {@link ClientSetup}，
 * 因为那部分依赖 NeoForge 的 ClientTickEvent / RenderGuiEvent，无法上移到共享层。
 *
 * 线程安全性：无状态，全部委托给 ClientSetup。
 *
 * @since 1.0
 * @see ClientSetup
 */
public final class NeoForgeUserNotifier implements UserNotifier {

    @Override
    public void toast(Component title, Component message) {
        ClientSetup.showToast(title, message);
    }

    @Override
    public void roomCodeNotification(String roomCode) {
        ClientSetup.handleRoomCodeNotification(roomCode);
    }
}
