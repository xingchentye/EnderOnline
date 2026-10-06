/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：实现房间的创建、加入、离开、销毁与房间内消息转发，并把房间请求注册到服务端。
 */
package com.endercore.core.comm.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.endercore.core.comm.protocol.CoreResponse;

/**
 * 房间服务入口。
 *
 * install 把房间相关请求注册到 CoreWebSocketServer，并挂上连接断开回调；
 * 具体状态与负载编解码由内部类 RoomManager 承担。
 *
 * 设计约束：
 * 1. 本类无可变状态，全部房间数据都封装在 install 创建的 RoomManager 中，因此每次 install 得到一套独立房间表。
 * 2. 房间码与其派生的网络名、密钥必须由同一套算法生成，客户端依赖这两个派生子串加入 EasyTier 网络。
 * 3. 成员标识由远端 IP 与端口拼成，依赖连接地址稳定，不适用于 NAT 重绑定场景。
 *
 * 线程安全性：本类无状态；RoomManager 用 ConcurrentHashMap 保存房间表与成员索引，
 * 单个房间内的成员变更由该房间的锁串行化，可被多个处理线程并发调用。
 *
 * @since 1.0
 * @see CoreWebSocketServer
 * @see CoreMinecraft
 */
public final class CoreRooms {
    /** 私有构造函数，防止实例化。 */
    private CoreRooms() {
    }

    /**
     * 在服务端上注册房间服务。
     *
     * 注册 room:create、room:join、room:leave、room:list、room:info、room:send、room:set_meta、room:destroy
     * 八个请求处理器，并注册连接断开监听器以清理异常退出的成员。
     *
     * 幂等性：可重复调用，但每次都会新建一套独立的 RoomManager 并覆盖同名处理器，重复调用等于重置房间状态。
     *
     * @param server 目标服务端，不能为 null
     * @throws NullPointerException 当 server 为 null 时抛出
     */
    public static void install(CoreWebSocketServer server) {
        Objects.requireNonNull(server, "server");
        RoomManager manager = new RoomManager(server);

        server.onConnectionClosed(manager::onDisconnect);

        server.register("room:create", manager::create);
        server.register("room:join", manager::join);
        server.register("room:leave", manager::leave);
        server.register("room:list", manager::list);
        server.register("room:info", manager::info);
        server.register("room:send", manager::send);
        server.register("room:set_meta", manager::setMeta);
        server.register("room:destroy", manager::destroy);
    }

    /**
     * 房间管理器。
     *
     * 持有房间表与成员索引，实现全部房间请求的处理逻辑与事件广播。
     *
     * 设计约束：
     * 1. 房间表与成员索引是两个独立映射，必须同步维护，避免成员索引残留指向已销毁的房间。
     * 2. 成员增删与房主校验都在房间锁内完成，事件广播统一移到锁外，避免持锁执行网络写。
     *
     * 线程安全性：rooms 与 memberRooms 为 ConcurrentHashMap，可并发读写；
     * 单个房间的成员集合由 Room#lock 保护，锁内只做状态变更、不做 IO。
     */
    private static final class RoomManager {
        /** 负载非法的状态码。 */
        private static final int STATUS_INVALID_PAYLOAD = 1;
        /** 房间不存在的状态码。 */
        private static final int STATUS_NOT_FOUND = 2;
        /** 房间已满的状态码。 */
        private static final int STATUS_ROOM_FULL = 3;
        /** 房间已关闭的状态码。 */
        private static final int STATUS_ROOM_CLOSED = 4;
        /** 权限不足的状态码。 */
        private static final int STATUS_PERMISSION_DENIED = 5;
        /** 请求方不在房间内的状态码。 */
        private static final int STATUS_NOT_IN_ROOM = 6;
        /** 房间已存在的状态码。 */
        private static final int STATUS_ALREADY_EXISTS = 7;

        /** 房间码随机源，密码学强度，进程内共享。 */
        private static final SecureRandom RANDOM = new SecureRandom();
        /** 房间码字符表，34 进制，去掉了易与数字混淆的 I 与 O。 */
        private static final char[] CODE_CHARS = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
        /** 房间码进制基数，取值 34，必须与 CODE_CHARS 的长度一致。 */
        private static final BigInteger CODE_BASE = BigInteger.valueOf(34);
        /** 房间码取值空间，34 的 16 次方，对应 16 位有效字符。 */
        private static final BigInteger CODE_SPACE = CODE_BASE.pow(16);
        /** 房间码校验模数，取值 7，房间码数值对 7 取模必须为 0。 */
        private static final BigInteger CODE_CHECK = BigInteger.valueOf(7);

        /** 所属服务端，用于广播事件，不能为 null。 */
        private final CoreWebSocketServer server;
        /** 房间表，键为房间码。 */
        private final ConcurrentHashMap<String, Room> rooms = new ConcurrentHashMap<>();
        /** 成员索引，键为连接标识，值为该连接当前所在的房间码集合。 */
        private final ConcurrentHashMap<String, Set<String>> memberRooms = new ConcurrentHashMap<>();

        /**
         * 构造房间管理器。
         *
         * @param server 所属服务端，不能为 null
         */
        private RoomManager(CoreWebSocketServer server) {
            this.server = server;
        }

        /**
         * 处理连接断开。
         *
         * 按连接标识查出该成员占用的全部房间并逐个移出；成员索引中无记录时直接返回。
         *
         * @param remoteAddress 断开的远端地址，允许为 null，为 null 时按占位标识 unknown:0 处理
         */
        private void onDisconnect(InetSocketAddress remoteAddress) {
            String memberId = connectionId(remoteAddress);
            Set<String> joined = memberRooms.remove(memberId);
            if (joined == null || joined.isEmpty()) {
                return;
            }
            for (String roomId : joined) {
                Room room = rooms.get(roomId);
                if (room == null) {
                    continue;
                }
                handleLeaveInternal(room, memberId);
            }
        }

        /**
         * 创建房间。
         *
         * 负载依次为房间名、成员上限、期望房间码与开放标志；期望房间码为空时随机生成。
         * 创建者会被直接登记为房主与首个成员。
         *
         * 幂等性：本方法不幂等，重复调用会创建多个房间；需要去重的调用方需自行指定期望房间码。
         *
         * @param req 创建请求，不能为 null
         * @return 成功时状态码为 0，负载含房间码、房主标识、成员上限、开放标志、房间名与网络凭据
         * @throws Exception 当负载读取失败时抛出
         */
        private CoreResponse create(CoreRequest req) throws Exception {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(req.payload()));
            String name = readString(in);
            int maxMembers = in.readUnsignedShort();
            String preferredRoomId = readString(in);
            boolean open = in.readUnsignedByte() != 0;

            if (name.isBlank() || name.length() > 64) {
                return error(req, STATUS_INVALID_PAYLOAD, "invalid room name");
            }
            if (maxMembers <= 0 || maxMembers > 256) {
                return error(req, STATUS_INVALID_PAYLOAD, "invalid maxMembers");
            }

            RoomCode code = preferredRoomId.isBlank() ? generateRoomCode() : parseRoomCode(preferredRoomId);
            if (code == null) {
                return error(req, STATUS_INVALID_PAYLOAD, "invalid roomId");
            }
            if (rooms.containsKey(code.code)) {
                return error(req, STATUS_ALREADY_EXISTS, "room already exists");
            }

            String hostId = connectionId(req.remoteAddress());
            Room room = new Room(code.code, code.networkName, code.networkSecret, name, maxMembers, open, hostId);
            room.members.put(hostId, req.remoteAddress());
            rooms.put(code.code, room);
            memberRooms.computeIfAbsent(hostId, k -> ConcurrentHashMap.newKeySet()).add(code.code);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            writeString(out, code.code);
            writeString(out, hostId);
            out.writeShort(maxMembers);
            out.writeByte(open ? 1 : 0);
            writeString(out, name);
            writeString(out, code.networkName);
            writeString(out, code.networkSecret);
            return new CoreResponse(0, req.requestId(), req.kind(), baos.toByteArray());
        }

        /**
     * 加入房间。
     *
     * 重复加入是安全的：已在房间内时不会重复计数，也不会重复广播 room:member_joined。
     *
     * @param req 加入请求，不能为 null
     * @return 成功时状态码为 0，负载含房间信息与当前成员列表
     * @throws Exception 当负载读取失败时抛出
     */
    private CoreResponse join(CoreRequest req) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(req.payload()));
        String roomIdInput = readString(in);
        if (roomIdInput.isBlank()) {
            return error(req, STATUS_INVALID_PAYLOAD, "missing roomId");
        }
        RoomCode parsed = parseRoomCode(roomIdInput);
        if (parsed == null) {
            return error(req, STATUS_INVALID_PAYLOAD, "invalid roomId");
        }
        String roomId = parsed.code;
        Room room = rooms.get(roomId);
        if (room == null) {
            return error(req, STATUS_NOT_FOUND, "room not found");
        }

        String memberId = connectionId(req.remoteAddress());
        InetSocketAddress remote = req.remoteAddress();
        boolean joinedNow;
        List<InetSocketAddress> remotes;
        synchronized (room.lock) {
            if (!room.open) {
                return error(req, STATUS_ROOM_CLOSED, "room is closed");
            }
            if (room.members.size() >= room.maxMembers && !room.members.containsKey(memberId)) {
                return error(req, STATUS_ROOM_FULL, "room is full");
            }
            joinedNow = room.members.putIfAbsent(memberId, remote) == null;
            if (joinedNow) {
                memberRooms.computeIfAbsent(memberId, k -> ConcurrentHashMap.newKeySet()).add(roomId);
            }
            remotes = new ArrayList<>(room.members.values());
        }

        if (joinedNow) {
            server.sendEventToMany(remotes, "room:member_joined", payloadRoomMember(roomId, memberId));
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        writeString(out, room.id);
        writeString(out, memberId);
        writeString(out, room.hostId);
        writeString(out, room.name);
        out.writeShort(room.maxMembers);
        out.writeByte(room.open ? 1 : 0);
        writeMembers(out, room.members.keySet());
        writeString(out, room.networkName);
        writeString(out, room.networkSecret);
        return new CoreResponse(0, req.requestId(), req.kind(), baos.toByteArray());
    }

    /**
     * 离开房间。
     *
     * 房主离开会销毁房间并广播 room:destroyed，普通成员离开只广播 room:member_left。
     *
     * @param req 离开请求，不能为 null
     * @return 成功时状态码为 0；请求方不在房间内时返回状态码 6
     * @throws Exception 当负载读取失败时抛出
     */
    private CoreResponse leave(CoreRequest req) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(req.payload()));
        String roomIdInput = readString(in);
        if (roomIdInput.isBlank()) {
            return error(req, STATUS_INVALID_PAYLOAD, "missing roomId");
        }
        RoomCode parsed = parseRoomCode(roomIdInput);
        if (parsed == null) {
            return error(req, STATUS_INVALID_PAYLOAD, "invalid roomId");
        }
        String roomId = parsed.code;
        Room room = rooms.get(roomId);
        if (room == null) {
            return error(req, STATUS_NOT_FOUND, "room not found");
        }
        String memberId = connectionId(req.remoteAddress());
        boolean left = handleLeaveInternal(room, memberId);
        if (!left) {
            return error(req, STATUS_NOT_IN_ROOM, "not in room");
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        writeString(out, roomId);
        return new CoreResponse(0, req.requestId(), req.kind(), baos.toByteArray());
    }

    /**
     * 执行成员移出的内部逻辑。
     *
     * 移出后：房间为空则销毁；房主离开则销毁房间并清空成员；其余情况只广播成员离开事件。
     * 所有事件广播都在释放房间锁之后进行。
     *
     * @param room 目标房间，不能为 null
     * @param memberId 待移出的成员标识，不能为 null
     * @return 成员确实存在于房间内并被移除时返回 true，否则返回 false
     */
    private boolean handleLeaveInternal(Room room, String memberId) {
        boolean removed;
        List<InetSocketAddress> remainingRemotes = List.of();
        boolean destroyed = false;

        synchronized (room.lock) {
            removed = room.members.remove(memberId) != null;
            if (!removed) {
                return false;
            }
            Set<String> joined = memberRooms.get(memberId);
            if (joined != null) {
                joined.remove(room.id);
                if (joined.isEmpty()) {
                    memberRooms.remove(memberId, joined);
                }
            }
            if (room.members.isEmpty()) {
                rooms.remove(room.id, room);
                destroyed = true;
            } else if (Objects.equals(room.hostId, memberId)) {
                destroyed = true;
                rooms.remove(room.id, room);
                remainingRemotes = new ArrayList<>(room.members.values());
                for (String otherMemberId : room.members.keySet()) {
                    Set<String> otherJoined = memberRooms.get(otherMemberId);
                    if (otherJoined != null) {
                        otherJoined.remove(room.id);
                        if (otherJoined.isEmpty()) {
                            memberRooms.remove(otherMemberId, otherJoined);
                        }
                    }
                }
                room.members.clear();
            } else {
                remainingRemotes = new ArrayList<>(room.members.values());
            }
        }

        if (removed && !destroyed) {
            server.sendEventToMany(remainingRemotes, "room:member_left", payloadRoomMember(room.id, memberId));
        }
        if (destroyed) {
            server.sendEventToMany(remainingRemotes, "room:destroyed", payloadRoom(room.id));
        }
        return true;
    }

    /**
     * 分页列出房间。
     *
     * 结果按房间码升序排列；limit 为 0 时取默认值 50，超过 200 时封顶为 200，offset 超出总数时返回空页。
     *
     * @param req 列表请求，不能为 null
     * @return 状态码为 0，负载为当前页房间的概要列表
     * @throws Exception 当负载读取失败时抛出
     */
    private CoreResponse list(CoreRequest req) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(req.payload()));
        int offset = in.readUnsignedShort();
        int limit = in.readUnsignedShort();
        if (limit <= 0) {
            limit = 50;
        }
        if (limit > 200) {
            limit = 200;
        }

        List<Room> all = new ArrayList<>(rooms.values());
        all.sort(Comparator.comparing(r -> r.id));

        int from = Math.min(offset, all.size());
        int to = Math.min(from + limit, all.size());
        List<Room> page = all.subList(from, to);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeShort(page.size());
        for (Room room : page) {
            writeString(out, room.id);
            writeString(out, room.name);
            writeString(out, room.hostId);
            out.writeShort(room.members.size());
            out.writeShort(room.maxMembers);
            out.writeByte(room.open ? 1 : 0);
        }
        return new CoreResponse(0, req.requestId(), req.kind(), baos.toByteArray());
    }

    /**
     * 查询房间详情。
     *
     * 负载为房间码；返回房间标识、名称、房主、创建时刻、成员上限、开放标志、成员列表、元数据与网络凭据。
     *
     * @param req 信息请求，不能为 null
     * @return 成功时状态码为 0；房间不存在时返回状态码 2
     * @throws Exception 当负载读取失败时抛出
     */
    private CoreResponse info(CoreRequest req) throws Exception {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(req.payload()));
            String roomIdInput = readString(in);
            if (roomIdInput.isBlank()) {
                return error(req, STATUS_INVALID_PAYLOAD, "missing roomId");
            }
            RoomCode parsed = parseRoomCode(roomIdInput);
            if (parsed == null) {
                return error(req, STATUS_INVALID_PAYLOAD, "invalid roomId");
            }
            String roomId = parsed.code;
            Room room = rooms.get(roomId);
            if (room == null) {
                return error(req, STATUS_NOT_FOUND, "room not found");
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            writeString(out, room.id);
            writeString(out, room.name);
            writeString(out, room.hostId);
            out.writeLong(room.createdAtMillis);
            out.writeShort(room.maxMembers);
            out.writeByte(room.open ? 1 : 0);
            writeMembers(out, room.members.keySet());
            byte[] meta = room.meta;
            out.writeInt(meta == null ? 0 : meta.length);
            if (meta != null && meta.length > 0) {
                out.write(meta);
            }
            writeString(out, room.networkName);
            writeString(out, room.networkSecret);
            return new CoreResponse(0, req.requestId(), req.kind(), baos.toByteArray());
        }

        /**
         * 在房间内广播消息。
         *
         * 负载依次为房间码、频道、消息长度与消息体；请求方必须已是该房间成员，否则返回状态码 6。
         * 消息不落库，仅转发给调用时刻的全体成员，包含发送者自身。
         *
         * @param req 发送请求，不能为 null
         * @return 成功时状态码为 0，负载为空
         * @throws Exception 当负载读取失败时抛出
         */
        private CoreResponse send(CoreRequest req) throws Exception {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(req.payload()));
            String roomIdInput = readString(in);
            String channel = readString(in);
            int messageLen = in.readInt();
            if (roomIdInput.isBlank() || channel.isBlank() || messageLen < 0) {
                return error(req, STATUS_INVALID_PAYLOAD, "invalid payload");
            }
            RoomCode parsed = parseRoomCode(roomIdInput);
            if (parsed == null) {
                return error(req, STATUS_INVALID_PAYLOAD, "invalid roomId");
            }
            String roomId = parsed.code;
            byte[] message = new byte[messageLen];
            in.readFully(message);

            Room room = rooms.get(roomId);
            if (room == null) {
                return error(req, STATUS_NOT_FOUND, "room not found");
            }

            String fromId = connectionId(req.remoteAddress());
            List<InetSocketAddress> remotes;
            synchronized (room.lock) {
                if (!room.members.containsKey(fromId)) {
                    return error(req, STATUS_NOT_IN_ROOM, "not in room");
                }
                remotes = new ArrayList<>(room.members.values());
            }

            server.sendEventToMany(remotes, "room:message", payloadRoomMessage(roomId, fromId, channel, message));
            return new CoreResponse(0, req.requestId(), req.kind(), new byte[0]);
        }

        /**
         * 设置房间元数据。
         *
         * 只有房主可以设置，其它成员会得到状态码 5；设置成功后向全体成员广播 room:meta_changed。
         * 元数据整块替换，不做合并。
         *
         * @param req 设置请求，不能为 null
         * @return 成功时状态码为 0，负载为空
         * @throws Exception 当负载读取失败时抛出
         */
        private CoreResponse setMeta(CoreRequest req) throws Exception {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(req.payload()));
            String roomIdInput = readString(in);
            int len = in.readInt();
            if (roomIdInput.isBlank() || len < 0) {
                return error(req, STATUS_INVALID_PAYLOAD, "invalid payload");
            }
            RoomCode parsed = parseRoomCode(roomIdInput);
            if (parsed == null) {
                return error(req, STATUS_INVALID_PAYLOAD, "invalid roomId");
            }
            String roomId = parsed.code;
            byte[] meta = new byte[len];
            in.readFully(meta);

            Room room = rooms.get(roomId);
            if (room == null) {
                return error(req, STATUS_NOT_FOUND, "room not found");
            }

            String callerId = connectionId(req.remoteAddress());
            List<InetSocketAddress> remotes;
            synchronized (room.lock) {
                if (!Objects.equals(room.hostId, callerId)) {
                    return error(req, STATUS_PERMISSION_DENIED, "permission denied");
                }
                room.meta = meta;
                remotes = new ArrayList<>(room.members.values());
            }
            server.sendEventToMany(remotes, "room:meta_changed", payloadRoomMeta(roomId, meta));
            return new CoreResponse(0, req.requestId(), req.kind(), new byte[0]);
        }

        /**
         * 销毁房间。
         *
         * 只有房主可以销毁，其它成员会得到状态码 5；销毁后清理全体成员的索引并向原成员广播 room:destroyed。
         *
         * @param req 销毁请求，不能为 null
         * @return 成功时状态码为 0，负载为空
         * @throws Exception 当负载读取失败时抛出
         */
        private CoreResponse destroy(CoreRequest req) throws Exception {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(req.payload()));
            String roomIdInput = readString(in);
            if (roomIdInput.isBlank()) {
                return error(req, STATUS_INVALID_PAYLOAD, "missing roomId");
            }
            RoomCode parsed = parseRoomCode(roomIdInput);
            if (parsed == null) {
                return error(req, STATUS_INVALID_PAYLOAD, "invalid roomId");
            }
            String roomId = parsed.code;

            Room room = rooms.get(roomId);
            if (room == null) {
                return error(req, STATUS_NOT_FOUND, "room not found");
            }

            String callerId = connectionId(req.remoteAddress());
            Collection<InetSocketAddress> remotes;
            synchronized (room.lock) {
                if (!Objects.equals(room.hostId, callerId)) {
                    return error(req, STATUS_PERMISSION_DENIED, "permission denied");
                }
                rooms.remove(roomId, room);
                remotes = new ArrayList<>(room.members.values());
                for (String memberId : room.members.keySet()) {
                    Set<String> joined = memberRooms.get(memberId);
                    if (joined != null) {
                        joined.remove(roomId);
                        if (joined.isEmpty()) {
                            memberRooms.remove(memberId, joined);
                        }
                    }
                }
                room.members.clear();
            }
            server.sendEventToMany(remotes, "room:destroyed", payloadRoom(roomId));
            return new CoreResponse(0, req.requestId(), req.kind(), new byte[0]);
        }

        /**
         * 构建错误响应。
         *
         * 响应状态码为传入的非零值，requestId 与 kind 沿用原请求以便客户端配对。
         *
         * @param req 原始请求，不能为 null
         * @param status 非零错误状态码
         * @param messageUtf8 错误消息，不能为 null，以 UTF-8 编码为响应负载
         * @return 错误响应，永不为 null
         */
        private static CoreResponse error(CoreRequest req, int status, String messageUtf8) {
            return new CoreResponse(status, req.requestId(), req.kind(), messageUtf8.getBytes(StandardCharsets.UTF_8));
        }

        /**
         * 计算连接标识。
         *
         * 格式为 IP:Port；地址不可解析时退回主机名，IPv6 保留其原始形式。
         *
         * @param remoteAddress 远端地址，允许为 null，为 null 时返回固定占位标识 unknown:0
         * @return 连接标识，永不为 null
         */
        private static String connectionId(InetSocketAddress remoteAddress) {
            if (remoteAddress == null) {
                return "unknown:0";
            }
            String host = remoteAddress.getAddress() != null ? remoteAddress.getAddress().getHostAddress() : remoteAddress.getHostString();
            return host + ":" + remoteAddress.getPort();
        }

        /**
         * 生成随机房间码。
         *
         * 取 128 位随机数并对取值空间取模得到 16 位数值，再调整为 7 的倍数，使校验位恒为 0，
         * 最后格式化为可读形式并派生网络凭据。
         *
         * @return 房间码及其派生的网络名称与密钥，永不为 null
         */
        private static RoomCode generateRoomCode() {
            BigInteger value = new BigInteger(128, RANDOM).mod(CODE_SPACE);
            value = value.subtract(value.mod(CODE_CHECK));
            int[] digits = new int[16];
            BigInteger v = value;
            for (int i = 0; i < 16; i++) {
                BigInteger[] divRem = v.divideAndRemainder(CODE_BASE);
                digits[i] = divRem[1].intValue();
                v = divRem[0];
            }
            return fromDigits(digits);
        }

        /**
         * 解析并校验房间码。
         *
         * 允许房间码出现在输入字符串的任意位置，要求形如 U/XXXX-XXXX-XXXX-XXXX 且数值对 7 取模为 0；
         * 大小写不敏感，字符 I 与 O 分别按 1 与 0 处理。
         *
         * @param input 待解析字符串，允许为 null
         * @return 解析结果；格式或校验和不合法时返回 null
         */
        private static RoomCode parseRoomCode(String input) {
            if (input == null) {
                return null;
            }
            String code = input.toUpperCase();
            int wantLen = "U/XXXX-XXXX-XXXX-XXXX".length();
            if (code.length() < wantLen) {
                return null;
            }
            for (int start = 0; start <= code.length() - wantLen; start++) {
                if (code.charAt(start) != 'U' || code.charAt(start + 1) != '/') {
                    continue;
                }
                int[] digits = new int[16];
                int di = 0;
                boolean ok = true;
                for (int i = 2; i < wantLen; i++) {
                    char c = code.charAt(start + i);
                    if (i == 6 || i == 11 || i == 16) {
                        if (c != '-') {
                            ok = false;
                            break;
                        }
                        continue;
                    }
                    int v = lookupDigit(c);
                    if (v < 0) {
                        ok = false;
                        break;
                    }
                    digits[di++] = v;
                }
                if (!ok || di != 16) {
                    continue;
                }
                int rem = 0;
                for (int i = 15; i >= 0; i--) {
                    rem = (rem * 34 + digits[i]) % 7;
                }
                if (rem != 0) {
                    continue;
                }
                return fromDigits(digits);
            }
            return null;
        }

        /**
         * 查字符在房间码字符表中的数值。
         *
         * @param c 待查字符，大小写均可，I 与 O 分别视作 1 与 0
         * @return 0 到 33 的数值；字符不在字符表中时返回 -1
         */
        private static int lookupDigit(char c) {
            char up = Character.toUpperCase(c);
            if (up == 'I') {
                up = '1';
            } else if (up == 'O') {
                up = '0';
            }
            for (int i = 0; i < CODE_CHARS.length; i++) {
                if (CODE_CHARS[i] == up) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * 由 16 位数值构造房间码。
         *
         * 房间码按 4 位一组用连字符分隔；低位 8 个数值派生网络名称，高位 8 个数值派生网络密钥。
         *
         * @param digits 长度为 16 的数值数组，每个元素取值 0 到 33
         * @return 房间码及其派生的网络名称与密钥，永不为 null
         */
        private static RoomCode fromDigits(int[] digits) {
            StringBuilder code = new StringBuilder("U/XXXX-XXXX-XXXX-XXXX".length());
            code.append("U/");
            StringBuilder networkName = new StringBuilder("scaffolding-mc-XXXX-XXXX".length());
            networkName.append("scaffolding-mc-");
            StringBuilder networkSecret = new StringBuilder("XXXX-XXXX".length());

            for (int i = 0; i < 16; i++) {
                char ch = CODE_CHARS[digits[i]];
                if (i == 4 || i == 8 || i == 12) {
                    code.append('-');
                }
                code.append(ch);
                if (i < 8) {
                    if (i == 4) {
                        networkName.append('-');
                    }
                    networkName.append(ch);
                } else {
                    if (i == 12) {
                        networkSecret.append('-');
                    }
                    networkSecret.append(ch);
                }
            }
            return new RoomCode(code.toString(), networkName.toString(), networkSecret.toString());
        }

        /**
         * 房间码及其派生凭据。
         *
         * 仅在 RoomManager 内部使用，构造后不再修改，不跨线程共享。
         */
        private static final class RoomCode {
            /** 房间码，形如 U/XXXX-XXXX-XXXX-XXXX。 */
            private final String code;
            /** EasyTier 网络名称，由房间码的低位数值派生。 */
            private final String networkName;
            /** EasyTier 网络密钥，由房间码的高位数值派生。 */
            private final String networkSecret;

            /**
             * 构造房间码。
             *
             * @param code 房间码，不能为 null
             * @param networkName 网络名称，不能为 null
             * @param networkSecret 网络密钥，不能为 null
             */
            private RoomCode(String code, String networkName, String networkSecret) {
                this.code = code;
                this.networkName = networkName;
                this.networkSecret = networkSecret;
            }
        }

        /**
     * 读取一个长度前缀字符串。
     *
     * 格式：长度（2 字节无符号短整型）+ UTF-8 字节；长度为 0 时直接返回空串。
     *
     * @param in 输入流，不能为 null
     * @return 解码后的字符串，永不为 null，长度前缀为 0 时返回空串
     * @throws Exception 当流读取失败或剩余数据不足声明长度时抛出
     */
    private static String readString(DataInputStream in) throws Exception {
        int len = in.readUnsignedShort();
        if (len == 0) {
            return "";
        }
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 写入一个长度前缀字符串。
     *
     * 格式：长度（2 字节无符号短整型）+ UTF-8 字节；null 与空串都写成零长度。
     *
     * @param out 输出流，不能为 null
     * @param s 待写入字符串，允许为 null
     * @throws IllegalArgumentException 当 UTF-8 编码后长度超过 65535 字节时抛出
     * @throws Exception 当底层流写入失败时抛出
     */
    private static void writeString(DataOutputStream out, String s) throws Exception {
        if (s == null || s.isEmpty()) {
            out.writeShort(0);
            return;
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65535) {
            throw new IllegalArgumentException("string too long");
        }
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    /**
     * 写入成员标识集合。
     *
     * 格式：成员数（2 字节无符号短整型）+ 逐个长度前缀字符串；集合的迭代顺序即写入顺序。
     *
     * @param out 输出流，不能为 null
     * @param memberIds 成员标识集合，不能为 null
     * @throws Exception 当底层流写入失败时抛出
     */
    private static void writeMembers(DataOutputStream out, Set<String> memberIds) throws Exception {
        out.writeShort(memberIds.size());
        for (String id : memberIds) {
            writeString(out, id);
        }
    }

    /**
     * 构建房间事件负载。
     *
     * @param roomId 房间码，不能为 null
     * @return 仅含房间码的负载；编码失败时返回空数组而不抛出异常
     */
    private static byte[] payloadRoom(String roomId) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            writeString(out, roomId);
            return baos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    /**
     * 构建房间成员事件负载。
     *
     * @param roomId 房间码，不能为 null
     * @param memberId 成员标识，不能为 null
     * @return 房间码与成员标识依次写入的负载；编码失败时返回空数组而不抛出异常
     */
    private static byte[] payloadRoomMember(String roomId, String memberId) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            writeString(out, roomId);
            writeString(out, memberId);
            return baos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    /**
     * 构建房间元数据事件负载。
     *
     * @param roomId 房间码，不能为 null
     * @param meta 元数据，允许为 null，为 null 时只写入长度 0
     * @return 房间码、元数据长度与元数据依次写入的负载；编码失败时返回空数组而不抛出异常
     */
    private static byte[] payloadRoomMeta(String roomId, byte[] meta) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            writeString(out, roomId);
            out.writeInt(meta == null ? 0 : meta.length);
            if (meta != null && meta.length > 0) {
                out.write(meta);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    /**
     * 构建房间消息事件负载。
     *
     * @param roomId 房间码，不能为 null
     * @param fromId 发送者标识，不能为 null
     * @param channel 频道名，不能为 null
     * @param message 消息体，允许为 null，为 null 时只写入长度 0
     * @return 房间码、发送者、频道、消息长度与消息体依次写入的负载；编码失败时返回空数组而不抛出异常
     */
    private static byte[] payloadRoomMessage(String roomId, String fromId, String channel, byte[] message) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            writeString(out, roomId);
            writeString(out, fromId);
            writeString(out, channel);
            out.writeInt(message == null ? 0 : message.length);
            if (message != null && message.length > 0) {
                out.write(message);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }
}

/**
 * 房间。
 *
 * 保存房间标识、派生网络凭据、房主与成员表；成员集合与元数据在运行期可变。
 *
 * 成员上限、成员增删与房主校验必须以 lock 为临界区，否则并发加入可能出现超员。
 *
 * 线程安全性：id、网络凭据、成员表与创建时刻不可变；members 为并发映射；
 * name、open、hostId、meta 声明为 volatile 保证可见性，其中前三者构造后不再修改。
 */
private static final class Room {
    /** 房间码，构造后不变，同时作为房间表的键。 */
    private final String id;
    /** EasyTier 网络名称，构造后不变。 */
    private final String networkName;
    /** EasyTier 网络密钥，构造后不变。 */
    private final String networkSecret;
    /** 房间内状态变更的互斥锁，保护成员增删与需要一致性的读改写。 */
    private final Object lock = new Object();
    /** 成员表，键为连接标识，值为远端地址。 */
    private final ConcurrentHashMap<String, InetSocketAddress> members = new ConcurrentHashMap<>();
    /** 创建时刻，单位毫秒，取自 System.currentTimeMillis()。 */
    private final long createdAtMillis = System.currentTimeMillis();
    /** 成员数上限，构造后不变，取值 1 到 256。 */
    private final int maxMembers;
    /** 房间名，构造时确定。 */
    private volatile String name;
    /** 是否允许新成员加入，构造时确定。 */
    private volatile boolean open;
    /** 房主连接标识，构造时确定，当前实现不支持转让房主。 */
    private volatile String hostId;
    /** 房间元数据，允许为 null 表示未设置；由房主经 room:set_meta 整块替换。 */
    private volatile byte[] meta;

    /**
     * 构造房间。
     *
     * 不校验成员上限与房间名，合法性由创建请求的解析阶段保证。
     *
     * @param id 房间码，不能为 null
     * @param networkName 网络名称，不能为 null
     * @param networkSecret 网络密钥，不能为 null
     * @param name 房间名，不能为 null
     * @param maxMembers 成员数上限，取值 1 到 256
     * @param open 是否允许新成员加入
     * @param hostId 房主连接标识，不能为 null
     */
    private Room(String id, String networkName, String networkSecret, String name, int maxMembers, boolean open, String hostId) {
        this.id = id;
        this.networkName = networkName;
        this.networkSecret = networkSecret;
        this.name = name;
        this.maxMembers = maxMembers;
        this.open = open;
        this.hostId = hostId;
    }
}
}
