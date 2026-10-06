/*
 * 本文件属于 EnderOnline 网络层。
 *
 * 职责：Scaffolding 对等协议的名字唯一来源——服务端注册与对外公布的能力清单共用同一组常量。
 *
 * 这些名字原先在 EnderApiClient 里写了两遍：一遍用于注册处理器，一遍用于响应 c:protocols
 * 公布能力。两处不同步会导致「注册了却不公布」或「公布了却没注册」，且没有任何检查会发现。
 */
package com.multiplayer.ender.network;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Scaffolding 对等协议名。
 *
 * 对等双方通过 {@link #SUPPORTED} 探测能力交集，因此这份清单既是「本端注册了哪些处理器」
 * 的表达，也是「对端可以调用哪些请求」的公告。两者必须来自同一处。
 *
 * 设计约束：
 * 1. 协议名以 NUL 字节分隔对外公布，见 {@link #supportedPayloadUtf8()}。
 * 2. 清单是只读常量；增删协议只需改动本类，注册与公告会同时生效。
 * 3. 名字本身属于线格式契约，改名即为不兼容变更。
 *
 * 线程安全性：无可变状态，常量与静态方法可被任意线程使用。
 */
public final class ScaffoldingProtocols {

    /** 连通性探测；对端原样回送负载。 */
    public static final String PING = "c:ping";

    /** 能力探测；返回本类中的支持清单。 */
    public static final String PROTOCOLS = "c:protocols";

    /** 读取房主的 Minecraft 服务器端口；负载为大端 2 字节。 */
    public static final String SERVER_PORT = "c:server_port";

    /** 上报玩家在线状态；负载为含 machine_id、name、vendor 的 JSON。 */
    public static final String PLAYER_PING = "c:player_ping";

    /** 拉取玩家资料列表；负载为 JSON 数组。 */
    public static final String PLAYER_PROFILES_LIST = "c:player_profiles_list";

    /** 拉取房间管理状态；负载为状态 JSON。 */
    public static final String ROOM_STATE_SYNC = "c:room_state_sync";

    /** 支持的对等协议清单，注册与公布共用。 */
    public static final List<String> SUPPORTED = List.of(
            PING,
            PROTOCOLS,
            SERVER_PORT,
            PLAYER_PING,
            PLAYER_PROFILES_LIST,
            ROOM_STATE_SYNC);

    /** 能力清单的载荷分隔符。 */
    private static final String PAYLOAD_SEPARATOR = "\0";

    private ScaffoldingProtocols() {
    }

    /**
     * 生成 c:protocols 的响应载荷。
     *
     * 以 NUL 字节分隔协议名，按 UTF-8 编码。
     *
     * @return 载荷字节，永不为 null
     */
    public static byte[] supportedPayloadUtf8() {
        return String.join(PAYLOAD_SEPARATOR, SUPPORTED).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 判断给定名字是否在本端支持清单内。
     *
     * @param name 协议名，允许为 null
     * @return 在清单内返回 true
     */
    public static boolean isSupported(String name) {
        return name != null && SUPPORTED.contains(name);
    }
}
