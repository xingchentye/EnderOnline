/*
 * 本文件属于 EnderOnline 共享层（ender_common）。
 *
 * 职责：对服务端玩家执行断开连接与游戏模式切换。
 *
 * 本类由房主轮询路径与界面生效路径共用，不含界面、不做网络传输。
 */
package com.multiplayer.ender.client;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/**
 * 服务端玩家操作：断开连接与游戏模式切换。
 *
 * 本类由 {@link RoomHostLogic}（轮询后端状态）与 {@code EnderDashboard}（界面立即生效）
 * 共用，合并了此前两边各写一份的同名实现，两者的判定语义完全一致。
 *
 * 反射回退链（ADR-03 待消除的目标）：{@code setGameMode} 通过反射调用，
 * 失败静默忽略。失败时表现为「权限设置了但玩家模式没变」，而不是抛出异常中断 tick。
 *
 * 线程约束：本类只做接口提供的同步调用，不加锁。调用方必须运行在服务端主线程
 * （{@link RoomHostLogic} 由 {@code ServerTickHandler} 保证），否则会造成玩家状态竞态。
 *
 * 本类不含任何加载器类型，可被 Forge 与 NeoForge 共享编译（ADR-13）。
 *
 * @see RoomHostLogic
 */
public final class ServerPlayerActions {
    /** 工具类不应被实例化。 */
    private ServerPlayerActions() {
    }

    /**
     * 断开指定玩家的连接。
     *
     * 连接已失效或玩家已离线时静默忽略，避免中断调用方的 tick 循环。
     *
     * @param player 目标玩家，不能为 null
     * @param reason 断开原因，会展示在玩家侧，不能为 null
     */
    public static void disconnectPlayer(ServerPlayer player, Component reason) {
        try {
            player.connection.disconnect(reason);
        } catch (Exception ignored) {}
    }

    /**
     * 切换玩家游戏模式。
     *
     * 已是目标模式时直接返回，避免无意义的反射调用；切换动作通过反射调用
     * {@code setGameMode}，属 ADR-03 待消除的回退链，失败时静默忽略。
     *
     * @param player 目标玩家，不能为 null
     * @param type 目标游戏模式，不能为 null
     */
    public static void setPlayerGameType(ServerPlayer player, GameType type) {
        if (player.gameMode.getGameModeForPlayer() == type) {
            return;
        }
        try {
            java.lang.reflect.Method m = player.getClass().getMethod("setGameMode", GameType.class);
            m.invoke(player, type);
        } catch (Exception ignored) {}
    }

}
