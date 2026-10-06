/*
 * 本文件属于 EnderOnline 客户端界面层。
 *
 * 职责：定义面向玩家的轻提示能力，屏蔽两端 ClientSetup 类的差异。
 */
package com.multiplayer.ender.client;

import net.minecraft.network.chat.Component;

/**
 * 面向玩家的提示通道。
 *
 * 契约说明：
 * 1. 两个方法都可从任意线程调用，实现负责切回客户端主线程；调用方不必自己安排线程。
 * 2. 未安装实现时不应抛异常，也不能静默丢弃——降级实现会把内容送到聊天栏，
 * 否则「提示没出现」会被误判为功能故障。
 * 3. 提示仅用于反馈操作结果，不承载需要玩家确认的信息；需要确认的走对话框。
 *
 * 为什么需要这层抽象：两端各自的实现类名与事件类型不同（ClientSetupForge 用 Forge 的
 * TickEvent / ScreenEvent，ClientSetup 用 NeoForge 的），共享代码不得引用其中任何一个，
 * 否则会破坏 ADR-13。
 *
 * @since 1.0
 * @see UserNotifierHolder
 */
public interface UserNotifier {

    /**
     * 显示一条轻提示。
     *
     * @param title 标题，不能为 null
     * @param message 正文，不能为 null
     */
    void toast(Component title, Component message);

    /**
     * 通知玩家房间码已就绪。
     *
     * 由房主侧发布房间码后调用。实现通常同时发送轻提示并写入聊天栏，
     * 因为玩家往往在提示消失后才需要抄写房间码。
     *
     * @param roomCode 房间码，不能为 null
     */
    void roomCodeNotification(String roomCode);
}
