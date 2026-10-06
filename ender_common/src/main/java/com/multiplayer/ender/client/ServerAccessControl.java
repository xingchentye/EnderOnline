/*
 * 本文件属于 EnderOnline 共享层（ender_common）。
 *
 * 职责：按名单与访客权限清理服务端在线玩家。
 *
 * 本类由房主轮询路径与界面生效路径共用，不含界面、不做网络传输。
 */
package com.multiplayer.ender.client;

import com.google.gson.JsonArray;
import com.multiplayer.ender.logic.AccessControlRules;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.List;

/**
 * 服务端访问控制执行器：把名单判定结果落到玩家身上。
 *
 * 名单判定本身在 {@link AccessControlRules}（纯逻辑、可单测）；本类只负责取在线玩家、
 * 逐人判定并执行动作，由 {@link RoomHostLogic}（轮询后端状态）与 {@code EnderDashboard}
 * （界面立即生效）共用，合并了此前两边各写一份的同名实现。
 *
 * 两条路径的差异只在于参数来源：轮询路径从状态 JSON 读名单与访客权限，界面路径读本地字段；
 * 房主名也分别来自服务端单人档案与客户端用户。因此本类把 {@code hostName} 作为参数接收，
 * 不自行推导——推导方式见 {@link #resolveHostName(MinecraftServer)}。
 *
 * 执行顺序固定为「先踢出、后改模式」，且逐人独立判定：被踢出的玩家不会再被改动游戏模式。
 * 重复调用是幂等的——判定不依赖上次结果，已符合要求的玩家维持原状。
 *
 * 线程约束：断开连接与切换模式都是服务端接口调用，本类不加锁。调用方必须运行在服务端主线程
 * （{@link RoomHostLogic} 由 {@code ServerTickHandler} 保证），否则会造成玩家状态竞态。
 *
 * 本类不含任何加载器类型，可被 Forge 与 NeoForge 共享编译（ADR-13）。
 *
 * @see RoomHostLogic
 */
public final class ServerAccessControl {
    /** 工具类不应被实例化。 */
    private ServerAccessControl() {
    }

    /**
     * 推导服务端侧的房主名。
     *
     * 单人档案缺失时返回 null 而不是空串：{@link AccessControlRules#evaluate} 对 null 与空串
     * 的豁免语义不同（两者都不产生豁免），返回 null 是为了让「档案缺失」在调用链上保持可辨识。
     *
     * @param server 目标服务端，不能为 null
     * @return 房主显示名；集成服务器之外或档案缺失时返回 null
     */
    public static String resolveHostName(MinecraftServer server) {
        if (!server.isSingleplayer()) {
            return "";
        }
        return server.getSingleplayerProfile() != null ? server.getSingleplayerProfile().getName() : null;
    }

    /**
     * 按名单与访客权限清理在线玩家。
     *
     * 房主本人始终跳过；黑名单命中即踢出；白名单生效且未命中则踢出；
     * 访客权限为「禁止进入」时踢出全部非房主玩家；「仅观战」与「仅聊天」分别把
     * 玩家切换为旁观者与冒险模式。其余权限值不改变玩家状态。
     *
     * @param server 当前 Minecraft 服务器实例，不能为 null
     * @param hostName 房主显示名，允许为 null（为 null 时不存在豁免）
     * @param blacklist 黑名单，允许为 null（按空名单处理）
     * @param whitelist 白名单，允许为 null（按空名单处理）
     * @param whitelistEnabled 白名单是否生效；轮询路径按「名单非空」推导，界面路径读独立开关
     * @param visitorPermission 访客权限取值，不能为 null
     */
    public static void apply(MinecraftServer server, String hostName, JsonArray blacklist, JsonArray whitelist,
            boolean whitelistEnabled, String visitorPermission) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        for (ServerPlayer player : players) {
            String name = player.getGameProfile().getName();
            AccessControlRules.Outcome outcome = AccessControlRules.evaluate(
                    blacklist, whitelist, whitelistEnabled, visitorPermission, hostName, name);
            switch (outcome.decision()) {
                case DISCONNECT:
                    ServerPlayerActions.disconnectPlayer(player, Component.literal(outcome.reason()));
                    break;
                case SPECTATOR:
                    ServerPlayerActions.setPlayerGameType(player, GameType.SPECTATOR);
                    break;
                case ADVENTURE:
                    ServerPlayerActions.setPlayerGameType(player, GameType.ADVENTURE);
                    break;
                default:
                    break;
            }
        }
    }

}
