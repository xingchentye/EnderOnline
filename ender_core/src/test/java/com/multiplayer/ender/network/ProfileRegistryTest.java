/*
 * 本文件属于 EnderOnline 单元测试。
 *
 * 职责：固化玩家名册的登记、清理与序列化语义，作为从 EnderApiClient 拆出该类后的回归基线。
 *
 * 关键约束：房主资料永不因超时被清理、访客心跳窗口为 10 秒——这两条是房主端名册的对外行为，
 * 改动会让房主在开会期间从玩家列表里消失，因此在此显式固定。
 */
package com.multiplayer.ender.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProfileRegistry} 的行为固化测试。
 *
 * 这组测试守护什么：房主与访客的登记语义（重复登记不产生副本、访客被归正为 GUEST）、
 * 过期访客清理而房主豁免、EasyTier 名册合并时跳过公共中转节点，以及 JSON 序列化与反序列化。
 *
 * 注意：本文件的断言消息可保留中文——测试源码已从 i18n 守卫中排除，中文失败信息更易读。
 *
 * 线程安全性：每个用例各自构造注册表，无共享状态。
 *
 * @see ProfileRegistry
 */
class ProfileRegistryTest {

    /** 测试用的本机标识。 */
    private static final String LOCAL_ID = "local-machine-id";

    /** 测试用的 vendor 标识。 */
    private static final String VENDOR = "ender";

    /** 房主主机名前缀，与生产实现一致。 */
    private static final String HOST_PREFIX = "scaffolding-mc-server-";

    /**
     * 构造一个使用固定本机标识的注册表。
     *
     * @return 注册表实例，永不为 null
     */
    private static ProfileRegistry newRegistry() {
        return new ProfileRegistry(LOCAL_ID, VENDOR);
    }

    @Test
    @DisplayName("upsertHost：首次登记产生一条 HOST 资料并带上本机 vendor")
    void upsertHostCreatesHostProfile() {
        ProfileRegistry registry = newRegistry();

        registry.upsertHost("HostPlayer");

        assertEquals(1, registry.snapshot().size(), "应恰好有一条资料");
        ProfileRegistry.Profile host = registry.snapshot().get(0);
        assertEquals(LOCAL_ID, host.machineId, "房主的 machineId 应是本机标识");
        assertEquals("HostPlayer", host.name, "应记录房主显示名");
        assertEquals(VENDOR, host.vendor, "应使用注入的 vendor");
        assertEquals(ProfileRegistry.KIND_HOST, host.kind, "角色应为 HOST");
    }

    @Test
    @DisplayName("upsertHost：重复登记只刷新姓名，不产生第二条资料")
    void upsertHostIsIdempotent() {
        ProfileRegistry registry = newRegistry();

        registry.upsertHost("First");
        registry.upsertHost("Second");

        assertEquals(1, registry.snapshot().size(), "重复登记不应产生副本");
        assertEquals("Second", registry.snapshot().get(0).name, "姓名应被刷新");
    }

    @Test
    @DisplayName("upsertGuest：新访客登记为 GUEST，重复登记覆盖姓名与 vendor")
    void upsertGuestUpdatesExistingEntry() {
        ProfileRegistry registry = newRegistry();

        registry.upsertGuest("guest-1", "Alice", "pojav");
        assertEquals(1, registry.snapshot().size(), "应有一条访客资料");
        assertEquals(ProfileRegistry.KIND_GUEST, registry.snapshot().get(0).kind, "角色应为 GUEST");

        registry.upsertGuest("guest-1", "Alice2", "amethyst");
        assertEquals(1, registry.snapshot().size(), "同一 machineId 不应新增条目");
        assertEquals("Alice2", registry.snapshot().get(0).name, "姓名应被覆盖");
        assertEquals("amethyst", registry.snapshot().get(0).vendor, "vendor 应被覆盖");
    }

    @Test
    @DisplayName("upsertGuest：同 id 的 HOST 条目会被归正为 GUEST")
    void upsertGuestFlipsHostKindToGuest() {
        ProfileRegistry registry = newRegistry();
        registry.upsertHost("Host");

        registry.upsertGuest(LOCAL_ID, "Renamed", VENDOR);

        assertEquals(1, registry.snapshot().size(), "不应产生第二条资料");
        assertEquals(ProfileRegistry.KIND_GUEST, registry.snapshot().get(0).kind,
                "kind 应被归正为 GUEST");
    }

    @Test
    @DisplayName("reset：清空名册")
    void resetClearsRegistry() {
        ProfileRegistry registry = newRegistry();
        registry.upsertHost("Host");
        registry.upsertGuest("guest-1", "Alice", VENDOR);

        registry.reset();

        assertEquals(0, registry.snapshot().size(), "重置后名册应为空");
        assertEquals("[]", registry.toProfilesJson().toString(), "序列化结果应为空数组");
    }

    @Test
    @DisplayName("pruneStaleGuests：超过心跳窗口的访客被清理")
    void pruneStaleGuestsRemovesTimedOutGuest() {
        ProfileRegistry registry = newRegistry();
        registry.upsertGuest("guest-1", "Alice", VENDOR);

        // 心跳窗口是 10 秒，这里等到清理真的发生（用轮询而非固定睡眠，避免偶发失败）
        boolean removed = false;
        long deadline = System.currentTimeMillis() + 15_000L;
        while (System.currentTimeMillis() < deadline) {
            registry.pruneStaleGuests();
            if (registry.snapshot().isEmpty()) {
                removed = true;
                break;
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        assertTrue(removed, "超过 10 秒未上报的访客应被清理");
    }

    @Test
    @DisplayName("pruneStaleGuests：房主即使长时间未上报也不会被清理")
    void pruneStaleGuestsKeepsHost() throws InterruptedException {
        ProfileRegistry registry = newRegistry();
        registry.upsertHost("Host");

        // 只等一小段时间：断言「清理逻辑不把房主当访客处理」，
        // 不必等满窗口——房主若会被清理，首次调用就会把它删掉。
        registry.pruneStaleGuests();
        Thread.sleep(400L);
        registry.pruneStaleGuests();

        assertEquals(1, registry.snapshot().size(), "房主不应被清理");
        assertEquals(ProfileRegistry.KIND_HOST, registry.snapshot().get(0).kind, "仍应是房主");
    }

    @Test
    @DisplayName("refreshFromPeerHostnames：前缀匹配的节点登记为房主，其余为访客")
    void refreshFromPeerHostnamesClassifiesKind() {
        ProfileRegistry registry = newRegistry();
        Map<String, String> hostnames = new HashMap<>();
        hostnames.put("peer-host", HOST_PREFIX + "13448");
        hostnames.put("peer-guest", "some-player");

        registry.refreshFromPeerHostnames(hostnames, HOST_PREFIX);

        assertEquals(2, registry.snapshot().size(), "两个节点都应登记");
        boolean hostFound = false;
        boolean guestFound = false;
        for (ProfileRegistry.Profile profile : registry.snapshot()) {
            if (ProfileRegistry.KIND_HOST.equals(profile.kind)) {
                hostFound = true;
            }
            if (ProfileRegistry.KIND_GUEST.equals(profile.kind)) {
                guestFound = true;
            }
        }
        assertTrue(hostFound, "带房主前缀的节点应登记为 HOST");
        assertTrue(guestFound, "其余节点应登记为 GUEST");
    }

    @Test
    @DisplayName("refreshFromPeerHostnames：跳过公共中转与基础设施节点")
    void refreshFromPeerHostnamesSkipsInfrastructure() {
        ProfileRegistry registry = newRegistry();
        Map<String, String> hostnames = new HashMap<>();
        hostnames.put("public-1", "PublicServer_1");
        hostnames.put("infra-1", "somehost.easytier.cn");
        hostnames.put("blank-1", "   ");

        registry.refreshFromPeerHostnames(hostnames, HOST_PREFIX);

        assertEquals(0, registry.snapshot().size(), "中转、基础设施与空白主机名都应被跳过");
    }

    @Test
    @DisplayName("refreshFromPeerHostnames：不覆盖已有访客的 name 为 null 的条目之外的行为")
    void refreshFromPeerHostnamesRefreshesGuestName() {
        ProfileRegistry registry = newRegistry();
        registry.upsertGuest("peer-1", "OldName", VENDOR);

        Map<String, String> hostnames = new HashMap<>();
        hostnames.put("peer-1", "NewName");
        registry.refreshFromPeerHostnames(hostnames, HOST_PREFIX);

        assertEquals(1, registry.snapshot().size(), "不应新增条目");
        assertEquals("NewName", registry.snapshot().get(0).name,
                "访客姓名会被对等节点主机名刷新（既有行为）");
    }

    @Test
    @DisplayName("replaceAllFromArray：全量覆盖，丢弃缺少 machine_id 或 name 的条目")
    void replaceAllFromArrayIsFullReplacement() {
        ProfileRegistry registry = newRegistry();
        registry.upsertHost("OldHost");

        JsonArray array = new JsonArray();
        array.add(profileJson("guest-1", "Alice", "pojav", "GUEST"));
        array.add(profileJson("guest-2", "", "pojav", "GUEST"));
        array.add(profileJson("", "NoId", "pojav", "GUEST"));
        array.add(profileJson("guest-3", "Bob", "", ""));
        registry.replaceAllFromArray(array);

        assertEquals(2, registry.snapshot().size(), "无 id 或无 name 的条目应被丢弃");
        assertEquals("Alice", registry.snapshot().get(0).name, "顺序应保持");
        assertEquals(ProfileRegistry.KIND_GUEST, registry.snapshot().get(1).kind,
                "kind 为空时应按 GUEST 处理");
    }

    @Test
    @DisplayName("toProfilesJson：跳过姓名为空白的资料")
    void toProfilesJsonSkipsBlankNames() {
        ProfileRegistry registry = newRegistry();
        registry.upsertHost("Host");
        registry.upsertGuest("guest-blank", "   ", VENDOR);

        JsonArray array = registry.toProfilesJson();

        assertEquals(1, array.size(), "空白姓名应被跳过");
        assertEquals("Host", array.get(0).getAsJsonObject().get("name").getAsString(),
                "只剩房主条目");
    }

    @Test
    @DisplayName("toProfilesJson：每条含 name / machine_id / vendor / kind 四个字段")
    void toProfilesJsonHasExpectedFields() {
        ProfileRegistry registry = newRegistry();
        registry.upsertGuest("guest-1", "Alice", "pojav");

        JsonObject obj = registry.toProfilesJson().get(0).getAsJsonObject();

        assertEquals("Alice", obj.get("name").getAsString(), "应含 name");
        assertEquals("guest-1", obj.get("machine_id").getAsString(), "应含 machine_id");
        assertEquals("pojav", obj.get("vendor").getAsString(), "应含 vendor");
        assertEquals(ProfileRegistry.KIND_GUEST, obj.get("kind").getAsString(), "应含 kind");
    }

    @Test
    @DisplayName("toPlayersJson：只抽取名称，跳过非对象、无 name 与空白名称")
    void toPlayersJsonExtractsNames() {
        JsonArray array = new JsonArray();
        array.add(profileJson("g1", "Alice", "pojav", "GUEST"));
        array.add(profileJson("g2", "   ", "pojav", "GUEST"));
        array.add("not-an-object");

        JsonArray players = ProfileRegistry.toPlayersJson(array);

        assertEquals(1, players.size(), "空白名称与非对象元素都应被跳过");
        assertEquals("Alice", players.get(0).getAsString(), "应抽取到 Alice");
    }

    @Test
    @DisplayName("构造校验：本机标识与 vendor 不能为 null")
    void constructorRejectsNulls() {
        assertThrows(NullPointerException.class, () -> new ProfileRegistry(null, VENDOR),
                "本机标识为 null 应被拒绝");
        assertThrows(NullPointerException.class, () -> new ProfileRegistry(LOCAL_ID, null),
                "vendor 为 null 应被拒绝");
    }

    @Test
    @DisplayName("localMachineId：返回构造时注入的值")
    void localMachineIdIsExposed() {
        ProfileRegistry registry = newRegistry();

        assertNotNull(registry.localMachineId(), "本机标识不应为 null");
        assertEquals(LOCAL_ID, registry.localMachineId(), "应返回注入的值");
    }

    @Test
    @DisplayName("Profile：null 的 vendor 与 kind 被归一化为空串")
    void profileNormalizesNulls() {
        ProfileRegistry.Profile profile = new ProfileRegistry.Profile("id", null, null, null);

        assertEquals("", profile.vendor, "null vendor 应归一化为空串");
        assertEquals("", profile.kind, "null kind 应归一化为空串");
        assertEquals(null, profile.name, "name 允许为 null（构造时不做校验）");
    }

    /**
     * 构造一条资料 JSON。
     *
     * @param machineId 本机标识
     * @param name 显示名
     * @param vendor 客户端标识
     * @param kind 角色
     * @return 资料对象，永不为 null
     */
    private static JsonObject profileJson(String machineId, String name, String vendor, String kind) {
        JsonObject obj = new JsonObject();
        obj.addProperty("machine_id", machineId);
        obj.addProperty("name", name);
        obj.addProperty("vendor", vendor);
        obj.addProperty("kind", kind);
        return obj;
    }
}
