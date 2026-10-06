/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：实现 mc:query_status 请求，按 Minecraft Java 版协议查询目标服务器的状态与延迟。
 */
package com.endercore.core.comm.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import com.endercore.core.comm.protocol.CoreResponse;

/**
 * Minecraft 服务器状态查询工具。
 *
 * 以插件形式挂在 CoreWebSocketServer 上：install 注册 mc:query_status 请求，
 * 收到请求后按 Minecraft Java 版协议握手、读取状态并回送状态 JSON 与网络延迟。
 *
 * 设计约束：
 * 1. 查询是阻塞式 TCP 往返，只应在 handlerExecutor 线程上执行，不得在状态或事件回调中调用。
 * 2. host、port 与 timeoutMillis 均来自请求负载，必须先通过取值范围校验再建立连接。
 * 3. 本类不缓存任何连接或中间状态，每次查询新建 Socket 并在返回前关闭。
 *
 * 线程安全性：本类无状态，全部方法为静态且不读写共享变量，可被任意线程并发调用。
 *
 * @since 1.0
 * @see CoreWebSocketServer
 */
public final class CoreMinecraft {
    /**
     * 私有构造函数，防止实例化。
     */
    private CoreMinecraft() {
    }

    /**
     * 在服务端上注册 Minecraft 状态查询服务。
     *
     * 注册 mc:query_status 请求处理器；重复调用只会覆盖同一处理器，不会产生重复注册。
     *
     * @param server 目标服务端，不能为 null
     * @throws NullPointerException 当 server 为 null 时抛出
     */
    public static void install(CoreWebSocketServer server) {
        Objects.requireNonNull(server, "server");
        server.register("mc:query_status", CoreMinecraft::queryStatus);
    }

    /**
     * 处理状态查询请求。
     *
     * 负载支持二进制与字符串两种格式；成功时响应负载为延迟毫秒数（4 字节）+ JSON 长度（4 字节）+ JSON 字节。
     * 参数解析失败返回状态码 1，查询失败返回状态码 2，两者都以异常文本为负载。
     * 方法签名保留了 throws Exception，但所有失败路径都已转换为错误响应，实际不会抛出。
     *
     * @param req 查询请求，不能为 null
     * @return 成功时状态码为 0，失败时状态码为 1 或 2，永不为 null
     */
    private static CoreResponse queryStatus(CoreRequest req) throws Exception {
        QueryArgs args;
        try {
            args = parseArgs(req.payload());
        } catch (Exception e) {
            return error(req, 1, "invalid payload");
        }

        long pingId = System.nanoTime();
        String json;
        long latencyMillis;
        try {
            QueryResult r = ping(args.host, args.port, args.timeoutMillis, pingId);
            json = r.json;
            latencyMillis = r.latencyMillis;
        } catch (Exception e) {
            return error(req, 2, String.valueOf(e));
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeInt((int) Math.min(Math.max(latencyMillis, 0), Integer.MAX_VALUE));
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
        return new CoreResponse(0, req.requestId(), req.kind(), baos.toByteArray());
    }

    /**
     * 构建错误响应。
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
     * 解析查询参数。
     *
     * 优先按二进制格式解析，失败后回退为字符串格式 Host:Port|Timeout；
     * 端口缺省为 25565，超时缺省为 3000 毫秒，IPv6 字面量需写在方括号中。
     *
     * @param payload 请求负载，不能为 null，且不能为空数组
     * @return 解析结果，永不为 null
     * @throws IllegalArgumentException 当负载为空、主机名为空、端口不在 1 到 65535 之间
     *                                  或超时不在 1 到 120000 毫秒之间时抛出
     */
    private static QueryArgs parseArgs(byte[] payload) throws Exception {
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("empty");
        }

        QueryArgs binary = tryParseBinary(payload);
        if (binary != null) {
            return binary;
        }

        String s = new String(payload, StandardCharsets.UTF_8).trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("empty");
        }

        long timeoutMillis = 3000;
        int split = s.lastIndexOf('|');
        if (split >= 0) {
            String left = s.substring(0, split).trim();
            String right = s.substring(split + 1).trim();
            if (!right.isEmpty()) {
                timeoutMillis = Long.parseLong(right);
            }
            s = left;
        }

        String host = s;
        int port = 25565;
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            if (close <= 0) {
                throw new IllegalArgumentException("bad ipv6");
            }
            host = s.substring(1, close);
            if (close + 1 < s.length() && s.charAt(close + 1) == ':') {
                port = Integer.parseInt(s.substring(close + 2));
            }
        } else {
            int colon = s.lastIndexOf(':');
            if (colon > 0 && colon < s.length() - 1) {
                host = s.substring(0, colon);
                port = Integer.parseInt(s.substring(colon + 1));
            }
        }

        if (host.isBlank() || port <= 0 || port > 65535 || timeoutMillis <= 0 || timeoutMillis > 120_000) {
            throw new IllegalArgumentException("bad args");
        }
        return new QueryArgs(host, port, timeoutMillis);
    }

    /**
     * 尝试按二进制格式解析查询参数。
     *
     * 负载格式：HostLength(2 字节) + HostBytes + Port(2 字节) + Timeout(4 字节)；
     * 端口为 0 时取 25565，超时为 0 时取 3000 毫秒。
     *
     * @param payload 请求负载，不能为 null
     * @return 解析结果；格式不合法时返回 null，不抛出异常
     */
    private static QueryArgs tryParseBinary(byte[] payload) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
            int hostLen = in.readUnsignedShort();
            if (hostLen <= 0 || hostLen > 1024 || payload.length < 2 + hostLen + 2 + 4) {
                return null;
            }
            byte[] hostBytes = new byte[hostLen];
            in.readFully(hostBytes);
            String host = new String(hostBytes, StandardCharsets.UTF_8).trim();
            int port = in.readUnsignedShort();
            long timeoutMillis = Integer.toUnsignedLong(in.readInt());
            if (port == 0) {
                port = 25565;
            }
            if (timeoutMillis == 0) {
                timeoutMillis = 3000;
            }
            if (host.isBlank() || port <= 0 || port > 65535 || timeoutMillis <= 0 || timeoutMillis > 120_000) {
                return null;
            }
            return new QueryArgs(host, port, timeoutMillis);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 执行一次 Minecraft 状态查询。
     *
     * 依次发送握手包与状态请求包、读取状态 JSON，再以 pingId 做一次 Ping-Pong 往返测延迟。
     * 调用方给出的超时同时用于建连与读操作，Socket 在方法返回前必定关闭。
     *
     * @param host 目标主机名，不能为 null
     * @param port 目标端口，取值 1 到 65535
     * @param timeoutMillis 建连与读取超时，单位毫秒，取值 1 到 120000
     * @param pingId Ping 载荷，用于校验 Pong 是否配对
     * @return 状态 JSON 与实测往返时延，永不为 null
     * @throws Exception 当建连、读写失败或对端返回非预期报文时抛出
     */
    private static QueryResult ping(String host, int port, long timeoutMillis, long pingId) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) timeoutMillis);
            socket.setSoTimeout((int) timeoutMillis);

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            sendHandshake(out, host, port);
            sendStatusRequest(out);

            String json = readStatusResponse(in);

            long t0 = System.nanoTime();
            sendPing(out, pingId);
            readPong(in);
            long latencyMillis = (System.nanoTime() - t0) / 1_000_000L;

            return new QueryResult(json, latencyMillis);
        }
    }

    /**
     * 发送握手包。
     *
     * 协议版本固定为 765，握手后的下一状态固定为 1（状态查询）。
     *
     * @param out 输出流，不能为 null
     * @param host 目标主机名，不能为 null
     * @param port 目标端口
     * @throws Exception 当写入失败时抛出
     */
    private static void sendHandshake(OutputStream out, String host, int port) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeVarInt(body, 0);
        writeVarInt(body, 765);
        writeMcString(body, host);
        body.write((port >>> 8) & 0xFF);
        body.write(port & 0xFF);
        writeVarInt(body, 1);
        sendPacket(out, body.toByteArray());
    }

    /**
     * 发送状态请求包。
     *
     * @param out 输出流
     * @throws Exception 当发送失败时抛出
     */
    private static void sendStatusRequest(OutputStream out) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeVarInt(body, 0);
        sendPacket(out, body.toByteArray());
    }

    /**
     * 读取状态响应。
     *
     * @param in 输入流
     * @return 状态响应 JSON 字符串
     * @throws Exception 当读取失败时抛出
     */
    private static String readStatusResponse(InputStream in) throws Exception {
        int packetLen = readVarInt(in);
        byte[] packet = readFully(in, packetLen);
        DataInputStream data = new DataInputStream(new ByteArrayInputStream(packet));
        int packetId = readVarInt(data);
        if (packetId != 0) {
            throw new IllegalStateException("unexpected packetId=" + packetId);
        }
        return readMcString(data);
    }

    /**
     * 发送 Ping 包。
     *
     * @param out 输出流
     * @param pingId Ping ID
     * @throws Exception 当发送失败时抛出
     */
    private static void sendPing(OutputStream out, long pingId) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeVarInt(body, 1);
        DataOutputStream data = new DataOutputStream(body);
        data.writeLong(pingId);
        sendPacket(out, body.toByteArray());
    }

    /**
     * 读取 Pong 包。
     *
     * @param in 输入流
     * @throws Exception 当读取失败时抛出
     */
    private static void readPong(InputStream in) throws Exception {
        int packetLen = readVarInt(in);
        byte[] packet = readFully(in, packetLen);
        DataInputStream data = new DataInputStream(new ByteArrayInputStream(packet));
        int packetId = readVarInt(data);
        if (packetId != 1) {
            throw new IllegalStateException("unexpected pong packetId=" + packetId);
        }
        data.readLong();
    }

    /**
     * 发送数据包。
     * 格式：PacketLength(VarInt) + PacketBody
     *
     * @param out 输出流
     * @param body 数据包体
     * @throws Exception 当发送失败时抛出
     */
    private static void sendPacket(OutputStream out, byte[] body) throws Exception {
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        writeVarInt(packet, body.length);
        packet.write(body);
        out.write(packet.toByteArray());
        out.flush();
    }

    /**
     * 写入 Minecraft 字符串。
     * 格式：Length(VarInt) + StringBytes
     *
     * @param out 输出流
     * @param s 字符串
     * @throws Exception 当写入失败时抛出
     */
    private static void writeMcString(ByteArrayOutputStream out, String s) throws Exception {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    /**
     * 读取 Minecraft 字符串。
     *
     * 格式为 VarInt 长度加 UTF-8 字节，长度上限为 1048576 字节。
     *
     * @param in 数据输入流，不能为 null
     * @return 解码后的字符串，永不为 null，长度为 0 时返回空串
     * @throws Exception 当读取失败、长度超出上限或长度字段为负时抛出
     */
    private static String readMcString(DataInputStream in) throws Exception {
        int len = readVarInt(in);
        if (len < 0 || len > 1_048_576) {
            throw new IllegalArgumentException("string too long");
        }
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 以 Minecraft VarInt 格式写入整数。
     *
     * VarInt 为小端 7 位分组编码，每字节最高位表示是否还有后续字节，最多占 5 字节。
     *
     * @param out 输出流，不能为 null
     * @param value 待写入的整数值，允许为负
     */
    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        int v = value;
        while ((v & 0xFFFFFF80) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v & 0x7F);
    }

    /**
     * 从字节流读取 VarInt。
     *
     * @param in 输入流，不能为 null
     * @return 解码出的整数值，可能为负
     * @throws Exception 当流提前结束抛出 EOFException，或编码超过 5 字节时抛出
     */
    private static int readVarInt(InputStream in) throws Exception {
        int numRead = 0;
        int result = 0;
        int read;
        do {
            read = in.read();
            if (read == -1) {
                throw new EOFException();
            }
            int value = read & 0x7F;
            result |= (value << (7 * numRead));
            numRead++;
            if (numRead > 5) {
                throw new IllegalArgumentException("VarInt too big");
            }
        } while ((read & 0x80) != 0);
        return result;
    }

    /**
     * 从数据输入流读取 VarInt。
     *
     * @param in 数据输入流，不能为 null
     * @return 解码出的整数值，可能为负
     * @throws Exception 当流提前结束或编码超过 5 字节时抛出
     */
    private static int readVarInt(DataInputStream in) throws Exception {
        int numRead = 0;
        int result = 0;
        byte read;
        do {
            read = in.readByte();
            int value = read & 0x7F;
            result |= (value << (7 * numRead));
            numRead++;
            if (numRead > 5) {
                throw new IllegalArgumentException("VarInt too big");
            }
        } while ((read & 0x80) != 0);
        return result;
    }

    /**
     * 读取指定长度的字节。
     *
     * 循环读取直到填满目标数组，遇到流结束抛出 EOFException 而不是返回短数组。
     *
     * @param in 输入流，不能为 null
     * @param len 待读取字节数，不能为负
     * @return 长度为 len 的字节数组，永不为 null
     * @throws Exception 当流提前结束或 len 为负时抛出
     */
    private static byte[] readFully(InputStream in, int len) throws Exception {
        if (len < 0) {
            throw new IllegalArgumentException("len<0");
        }
        byte[] bytes = new byte[len];
        int off = 0;
        while (off < len) {
            int r = in.read(bytes, off, len - off);
            if (r == -1) {
                throw new EOFException();
            }
            off += r;
        }
        return bytes;
    }

    /**
     * 查询参数。
     *
     * 仅在 parseArgs 与 ping 之间传递中间态，构造后不再修改。
     */
    private static final class QueryArgs {
        /** 目标主机名，不能为 null。 */
        private final String host;
        /** 目标端口，取值 1 到 65535。 */
        private final int port;
        /** 建连与读取超时，单位毫秒，取值 1 到 120000。 */
        private final long timeoutMillis;

        /**
         * 构造查询参数。
         *
         * @param host 目标主机名，不能为 null
         * @param port 目标端口，取值 1 到 65535
         * @param timeoutMillis 建连与读取超时，单位毫秒，取值 1 到 120000
         */
        private QueryArgs(String host, int port, long timeoutMillis) {
            this.host = host;
            this.port = port;
            this.timeoutMillis = timeoutMillis;
        }
    }

    /**
     * 查询结果。
     *
     * 仅在 ping 与 queryStatus 之间传递中间态，构造后不再修改。
     */
    private static final class QueryResult {
        /** 状态响应 JSON，不能为 null。 */
        private final String json;
        /** 实测往返时延，单位毫秒，非负。 */
        private final long latencyMillis;

        /**
         * 构造查询结果。
         *
         * @param json 状态响应 JSON，不能为 null
         * @param latencyMillis 实测往返时延，单位毫秒，非负
         */
        private QueryResult(String json, long latencyMillis) {
            this.json = json;
            this.latencyMillis = latencyMillis;
        }
    }
}
