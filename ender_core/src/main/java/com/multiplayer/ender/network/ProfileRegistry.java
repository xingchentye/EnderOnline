/*
 * 本文件属于 EnderOnline 网络层。
 *
 * 职责：玩家名册——本机与访客资料、最后活跃时间、过期访客清理与 JSON 序列化。
 *
 * 这些字段与方法原先内联在 EnderApiClient 中，与房间状态机、Scaffolding 传输混在一起。
 * 抽出后「谁在房间里、谁已离线」只有一处实现，也便于单独测试。
 */
package com.multiplayer.ender.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 玩家名册。
 *
 * 保存本机与访客的 {@link Profile}，按最后上报时间清理过期访客，并负责名册的 JSON 序列化。
 *
 * 设计约束：
 * 1. 房主资料（kind 为 HOST）永不因超时被清理；只有访客会被 {@link #pruneStaleGuests()} 移除。
 * 2. 访客心跳窗口为 10 秒，超过即视为离线。
 * 3. 「遍历名册并修改元素」不是原子操作：容器本身并发安全，但逐个修改字段时其他线程可能读到
 *    中间状态。这只是显示与同步用的名册，因此未加全局锁。
 * 4. 序列化会跳过姓名为空白的资料，因此输出不保证与名册一一对应。
 * 5. 机器标识与 vendor 由构造注入：它们同时被连接层使用，不归本类独占。
 *
 * 线程安全性：底层为 CopyOnWriteArrayList 与 ConcurrentHashMap；{@link Profile} 的可变字段
 * 声明为 volatile。
 *
 * @see EnderApiClient
 */
final class ProfileRegistry {

    /** 访客心跳窗口（毫秒）：超过即视为离线。 */
    private static final long GUEST_TIMEOUT_MILLIS = 10_000L;

    /** 房主角色标识。 */
    static final String KIND_HOST = "HOST";

    /** 访客角色标识。 */
    static final String KIND_GUEST = "GUEST";

    /** 由 EasyTier 名册补全时使用的 vendor 标识。 */
    private static final String VENDOR_EASYTIER = "EasyTier";

    /** 本机标识，由构造注入，永不为 null。 */
    private final String localMachineId;

    /** 本实现的 vendor 标识，由构造注入，永不为 null。 */
    private final String vendor;

    /** 当前已知的玩家资料列表，永不为 null。 */
    private final CopyOnWriteArrayList<Profile> profiles = new CopyOnWriteArrayList<>();

    /** 玩家资料的最后活跃时间戳（毫秒），永不为 null。 */
    private final ConcurrentHashMap<String, Long> lastSeen = new ConcurrentHashMap<>();

    /**
     * 构造名册。
     *
     * @param localMachineId 本机标识，不能为 null
     * @param vendor 本实现的 vendor 标识，不能为 null
     * @throws NullPointerException 当任一参数为 null 时抛出
     */
    ProfileRegistry(String localMachineId, String vendor) {
        this.localMachineId = Objects.requireNonNull(localMachineId, "localMachineId");
        this.vendor = Objects.requireNonNull(vendor, "vendor");
    }

    /**
     * 清空名册与最后活跃时间表。
     */
    void reset() {
        profiles.clear();
        lastSeen.clear();
    }

    /**
     * 本机标识。
     *
     * @return 构造时注入的本机标识，永不为 null
     */
    String localMachineId() {
        return localMachineId;
    }

    /**
     * 名册快照。
     *
     * @return 资料列表副本，永不为 null
     */
    List<Profile> snapshot() {
        return new ArrayList<>(profiles);
    }

    /**
     * 登记或更新房主资料。
     *
     * 已存在同 machineId 且 kind 为 HOST 的条目时只刷新姓名与活跃时间，不新增重复条目。
     *
     * @param name 房主显示名，允许为 null
     */
    void upsertHost(String name) {
        for (Profile profile : profiles) {
            if (profile.machineId.equals(localMachineId) && KIND_HOST.equals(profile.kind)) {
                profile.name = name;
                lastSeen.put(localMachineId, System.currentTimeMillis());
                return;
            }
        }
        profiles.add(new Profile(localMachineId, name, vendor, KIND_HOST));
        lastSeen.put(localMachineId, System.currentTimeMillis());
    }

    /**
     * 登记或更新一个访客。
     *
     * 已存在同 machineId 的条目时覆盖姓名与 vendor，并把 kind 归正为 GUEST。
     *
     * @param machineId 访客的本机标识，不能为 null
     * @param name 访客显示名，不能为 null
     * @param guestVendor 访客客户端标识，允许为 null
     */
    void upsertGuest(String machineId, String name, String guestVendor) {
        for (Profile profile : profiles) {
            if (profile.machineId.equals(machineId)) {
                profile.name = name;
                profile.vendor = guestVendor;
                profile.kind = KIND_GUEST;
                lastSeen.put(machineId, System.currentTimeMillis());
                return;
            }
        }
        profiles.add(new Profile(machineId, name, guestVendor, KIND_GUEST));
        lastSeen.put(machineId, System.currentTimeMillis());
    }

    /**
     * 用 EasyTier 对等节点主机名补全名册。
     *
     * 主机名以房主前缀开头的节点判定为房主，其余判定为访客；公共中转节点与含
     * {@code .easytier.} 的基础设施节点会被跳过。
     * 已存在的条目只刷新最后活跃时间，不覆盖姓名（访客会被归正为 GUEST）。
     *
     * @param hostnames 对等节点主机名表，键为节点 id，不能为 null；值为 null 或空白的条目被跳过
     * @param hostPrefix 房主主机名前缀，不能为 null
     */
    void refreshFromPeerHostnames(Map<String, String> hostnames, String hostPrefix) {
        Objects.requireNonNull(hostnames, "hostnames");
        Objects.requireNonNull(hostPrefix, "hostPrefix");
        for (Map.Entry<String, String> entry : hostnames.entrySet()) {
            String id = entry.getKey();
            String hostname = entry.getValue();
            if (hostname == null || hostname.isBlank()) {
                continue;
            }
            if (hostname.startsWith("PublicServer_") || hostname.contains(".easytier.")) {
                continue;
            }

            String kind = hostname.startsWith(hostPrefix) ? KIND_HOST : KIND_GUEST;
            boolean found = false;
            for (Profile profile : profiles) {
                if (profile.machineId.equals(id)) {
                    if (KIND_GUEST.equals(profile.kind)) {
                        profile.name = hostname;
                        lastSeen.put(id, System.currentTimeMillis());
                    } else if (KIND_HOST.equals(profile.kind)) {
                        lastSeen.put(id, System.currentTimeMillis());
                    }
                    found = true;
                    break;
                }
            }
            if (!found) {
                profiles.add(new Profile(id, hostname, VENDOR_EASYTIER, kind));
                lastSeen.put(id, System.currentTimeMillis());
            }
        }
    }

    /**
     * 清理超时未上报的访客。
     *
     * 房主资料永不清理。遍历的是名册副本，因此遍历期间的并发修改不会抛并发修改异常。
     */
    void pruneStaleGuests() {
        long now = System.currentTimeMillis();
        for (Profile profile : new ArrayList<>(profiles)) {
            if (KIND_HOST.equals(profile.kind)) {
                continue;
            }
            Long last = lastSeen.get(profile.machineId);
            if (last == null || now - last > GUEST_TIMEOUT_MILLIS) {
                profiles.remove(profile);
                lastSeen.remove(profile.machineId);
            }
        }
    }

    /**
     * 用服务端下发的资料数组整体替换本地名册。
     *
     * 这是全量覆盖而非合并：先清空名册与时间表。缺少 machine_id 或 name 的条目被丢弃，
     * kind 为空时按 GUEST 处理。
     *
     * @param array 玩家资料 JSON 数组，不能为 null
     */
    void replaceAllFromArray(JsonArray array) {
        profiles.clear();
        lastSeen.clear();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            String name = obj.has("name") ? obj.get("name").getAsString() : "";
            String machineId = obj.has("machine_id") ? obj.get("machine_id").getAsString() : "";
            String itemVendor = obj.has("vendor") ? obj.get("vendor").getAsString() : "";
            String kind = obj.has("kind") ? obj.get("kind").getAsString() : "";
            if (machineId.isBlank() || name.isBlank()) {
                continue;
            }
            profiles.add(new Profile(machineId, name, itemVendor, kind.isBlank() ? KIND_GUEST : kind));
            lastSeen.put(machineId, System.currentTimeMillis());
        }
    }

    /**
     * 构建玩家资料的 JSON 数组。
     *
     * 姓名为 null 或空白的资料会被跳过。
     *
     * @return 玩家资料数组，永不为 null，可能为空数组
     */
    JsonArray toProfilesJson() {
        JsonArray array = new JsonArray();
        for (Profile profile : profiles) {
            if (profile.name == null || profile.name.isBlank()) {
                continue;
            }
            JsonObject obj = new JsonObject();
            obj.addProperty("name", profile.name);
            obj.addProperty("machine_id", profile.machineId);
            obj.addProperty("vendor", profile.vendor);
            obj.addProperty("kind", profile.kind);
            array.add(obj);
        }
        return array;
    }

    /**
     * 从资料数组中抽取玩家名称。
     *
     * @param profilesArray 玩家资料 JSON 数组，不能为 null
     * @return 玩家名称数组，永不为 null；非对象元素与无 name 字段的元素会被跳过
     */
    static JsonArray toPlayersJson(JsonArray profilesArray) {
        JsonArray players = new JsonArray();
        for (JsonElement element : profilesArray) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            if (!obj.has("name")) {
                continue;
            }
            String name = obj.get("name").getAsString();
            if (name != null && !name.isBlank()) {
                players.add(name);
            }
        }
        return players;
    }

    /**
     * 玩家资料。
     *
     * 跨线程读写：引用本身被并发容器保护，字段用 volatile 保证可见性。
     */
    static final class Profile {

        /** 玩家本机标识，构造后不再变化，永不为 null。 */
        final String machineId;

        /** 玩家显示名，允许为 null（构造时不做校验）。 */
        volatile String name;

        /** 客户端标识，不允许为 null；构造时把 null 归一化为空字符串。 */
        volatile String vendor;

        /** 角色，取值为 HOST 或 GUEST；构造时把 null 归一化为空字符串。 */
        volatile String kind;

        /**
         * 构造玩家资料。
         *
         * @param machineId 本机标识，不能为 null
         * @param name 显示名，允许为 null
         * @param vendor 客户端标识，允许为 null，为 null 时归一化为空字符串
         * @param kind 角色，允许为 null，为 null 时归一化为空字符串
         */
        Profile(String machineId, String name, String vendor, String kind) {
            this.machineId = machineId;
            this.name = name;
            this.vendor = vendor == null ? "" : vendor;
            this.kind = kind == null ? "" : kind;
        }
    }
}
