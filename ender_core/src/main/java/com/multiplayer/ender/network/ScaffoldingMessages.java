/*
 * 本文件属于 EnderOnline 网络层。
 *
 * 职责：Scaffolding 对等请求与响应的载荷编解码——玩家心跳、资料数组与 Minecraft 端口。
 *
 * 这些编解码原先内联在 EnderApiClient 的轮询循环与请求处理器里，收发两端各写一遍。
 * 抽成纯静态方法后，字节格式可以在不建立连接的情况下测试。
 */
package com.multiplayer.ender.network;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Scaffolding 消息编解码。
 *
 * 覆盖三类载荷：
 * 1. `c:player_ping`：含 machine_id、name、vendor 的 JSON 对象
 * 2. `c:player_profiles_list`：玩家资料 JSON 数组
 * 3. `c:server_port`：大端 2 字节无符号短整型端口
 *
 * 设计约束：
 * 1. 全部为纯函数，不访问连接、状态或文件。
 * 2. 解码失败一律返回「缺失」，由调用方决定是走错误响应还是保留原值；不抛异常给网络线程。
 * 3. 端口按无符号短整型处理，因此取值上限是 65535；空载荷或长度不足时按缺失处理，
 *    而不是让 EOFException 穿透（原先该类异常会被空 catch 静默吞掉）。
 *
 * 线程安全性：无状态，全部方法为静态且只读入参，可被任意线程并发调用。
 */
public final class ScaffoldingMessages {

    /** JSON 解析器，无特殊配置；Gson 本身线程安全。 */
    private static final Gson GSON = new Gson();

    private ScaffoldingMessages() {
    }

    /**
     * 编码玩家心跳载荷。
     *
     * @param machineId 本机标识，不能为 null
     * @param name 玩家显示名，允许为 null，为 null 时写入 JSON null
     * @param vendor 客户端标识，允许为 null，为 null 时写入 JSON null
     * @return UTF-8 编码的 JSON 对象字节，永不为 null
     */
    public static byte[] encodePlayerPing(String machineId, String name, String vendor) {
        JsonObject json = new JsonObject();
        json.addProperty("machine_id", machineId);
        json.addProperty("name", name);
        json.addProperty("vendor", vendor);
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 解码玩家心跳载荷。
     *
     * @param payload UTF-8 编码的 JSON 对象，允许为 null
     * @return 解析结果；载荷为 null、非法 JSON 或不是对象时返回 null
     */
    public static PlayerPing decodePlayerPing(byte[] payload) {
        if (payload == null) {
            return null;
        }
        try {
            JsonObject json = GSON.fromJson(new String(payload, StandardCharsets.UTF_8), JsonObject.class);
            if (json == null) {
                return null;
            }
            return new PlayerPing(
                    readString(json, "machine_id"),
                    readString(json, "name"),
                    readString(json, "vendor"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 编码玩家资料数组。
     *
     * @param profiles 资料数组，允许为 null（按空数组处理）
     * @return UTF-8 编码的 JSON 数组字节，永不为 null
     */
    public static byte[] encodeProfiles(JsonArray profiles) {
        JsonArray effective = profiles == null ? new JsonArray() : profiles;
        return effective.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 解码玩家资料数组。
     *
     * @param payload UTF-8 编码的 JSON 数组，允许为 null
     * @return 解析出的数组；载荷为 null、非法 JSON 或不是数组时返回 null
     */
    public static JsonArray decodeProfiles(byte[] payload) {
        if (payload == null) {
            return null;
        }
        try {
            return GSON.fromJson(new String(payload, StandardCharsets.UTF_8), JsonArray.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 编码 Minecraft 服务器端口。
     *
     * 与协议其它位置一致，使用大端 2 字节无符号短整型；超出 1..65535 的取值会被截断为低位，
     * 因此调用方应自行保证端口合法。
     *
     * @param port 端口号
     * @return 2 字节载荷，永不为 null
     */
    public static byte[] encodePort(int port) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(2);
            DataOutputStream out = new DataOutputStream(baos);
            out.writeShort((short) port);
            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            // ignore-reason: ByteArrayOutputStream 不会抛出 IO 异常；
            // 该分支仅为满足 writeShort 的受检异常签名，返回空载荷由调用方按缺失处理。
            return new byte[0];
        }
    }

    /**
     * 解码 Minecraft 服务器端口。
     *
     * 按无符号短整型解析，因此 0xFFFF 得到 65535。长度不足 2 字节时返回缺失，
     * 而不是抛出异常。
     *
     * @param payload 2 字节大端载荷，允许为 null
     * @return 端口号（0 到 65535）；载荷为 null 或长度不足时返回 -1
     */
    public static int decodePort(byte[] payload) {
        if (payload == null || payload.length < 2) {
            return -1;
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
            return in.readUnsignedShort();
        } catch (IOException e) {
            // ignore-reason: 数据来自内存字节数组，长度已在上方校验，读取不会失败。
            return -1;
        }
    }

    /**
     * 读取 JSON 中的字符串字段。
     *
     * @param json 源对象，不能为 null
     * @param key 字段名，不能为 null
     * @return 字段值；缺失或为 JSON null 时返回空串
     */
    private static String readString(JsonObject json, String key) {
        if (!json.has(key)) {
            return "";
        }
        try {
            String value = json.get(key).getAsString();
            return value == null ? "" : value;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * 玩家心跳解码结果。
     *
     * @param machineId 本机标识；缺失时为空串，永不为 null
     * @param name 玩家显示名；缺失时为空串，永不为 null
     * @param vendor 客户端标识；缺失时为空串，永不为 null
     */
    public record PlayerPing(String machineId, String name, String vendor) {

        /**
         * 判断该心跳是否缺少登记所需的字段。
         *
         * @return machineId 或 name 为空白时返回 true
         */
        public boolean isIncomplete() {
            return machineId.isBlank() || name.isBlank();
        }
    }
}
