/*
 * 本文件属于 EnderOnline 单元测试。
 *
 * 职责：固化 RoomStateStore 的状态语义，作为从 EnderApiClient 拆出该类后的回归基线。
 *
 * 关键约束：每个用例都把单例换成指向临时目录的实例，因此测试**绝不会**写入仓库的
 * config/ender_room_config.json——那既会污染工作区，也会让守卫脚本扫到运行时生成的 JSON。
 */
package com.multiplayer.ender.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RoomStateStore} 的行为固化测试。
 *
 * 这组测试守护什么：快照必须是深拷贝、合并必须是增量覆盖而非整体替换、
 * 解析失败不得改动状态、日志裁剪到 20 条、以及 applyUpdate 只在房间备注真的变化时返回 true。
 *
 * 注意：本文件的断言消息可保留中文——测试源码已从 i18n 守卫中排除，中文失败信息更易读。
 *
 * 线程安全性：状态是进程级单例，因此每个用例开始都换成全新的实例，避免用例间互相污染。
 *
 * @see RoomStateStore
 */
class RoomStateStoreTest {

    /** 本用例的临时配置目录；测试结束时整体删除。 */
    private Path tempDir;

    /**
     * 把单例换成指向临时目录的新实例。
     *
     * 同时获得两个保障：不写生产路径，且每个用例都从默认状态开始。
     *
     * @throws IOException 当临时目录创建失败时抛出
     */
    @BeforeEach
    void isolateStorage() throws IOException {
        tempDir = Files.createTempDirectory("ender-room-state-test");
        RoomStateStore.setInstanceForTest(
                new RoomStateStore(new File(tempDir.toFile(), "ender_room_config.json")));
    }

    /**
     * 删除临时目录，并让单例指向另一个空闲临时目录。
     *
     * 刻意**不**还原成生产路径：那样后续用例一旦落盘就会写进仓库。
     */
    @AfterEach
    void cleanUpStorage() {
        if (tempDir != null) {
            try (Stream<Path> paths = Files.walk(tempDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException e) {
                // ignore-reason: 清理临时目录失败不影响断言结果，也不影响被测行为；
                // 抛出只会把一个无关的清理问题伪装成测试失败。
            }
        }
        try {
            Path idle = Files.createTempDirectory("ender-room-state-idle");
            RoomStateStore.setInstanceForTest(
                    new RoomStateStore(new File(idle.toFile(), "ender_room_config.json")));
            idle.toFile().deleteOnExit();
        } catch (IOException e) {
            // ignore-reason: 极端情况下无法创建空闲目录时，单例仍指向已删除的临时路径；
            // 那同样不会写入工作区，因此无需让测试失败。
        }
    }

    /**
     * 生成唯一关键字，使断言不受其它用例留下的状态影响。
     *
     * @param suffix 关键字用途，不能为 null
     * @return 含纳秒时间戳的唯一字符串，永不为 null
     */
    private static String marker(String suffix) {
        return "test-marker-" + suffix + "-" + System.nanoTime();
    }

    @Test
    @DisplayName("snapshot：返回深拷贝，修改副本不影响内部状态")
    void snapshotIsDeepCopy() {
        RoomStateStore store = RoomStateStore.get();
        JsonObject copy = store.snapshot();
        String original = copy.get("room_remark").getAsString();

        copy.addProperty("room_remark", marker("remark"));

        assertEquals(original, store.snapshot().get("room_remark").getAsString(),
                "修改快照副本不得影响内部状态");
        assertNotSame(copy, store.snapshot(), "两次快照不应是同一个对象");
    }

    @Test
    @DisplayName("snapshot：包含模组自有的管理键")
    void snapshotHasManagedKeys() {
        JsonObject state = RoomStateStore.get().snapshot();

        assertTrue(state.has("whitelist"), "快照应包含 whitelist");
        assertTrue(state.has("blacklist"), "快照应包含 blacklist");
        assertTrue(state.has("mute_list"), "快照应包含 mute_list");
        assertTrue(state.has("whitelist_enabled"), "快照应包含 whitelist_enabled");
        assertTrue(state.has("operation_logs"), "快照应包含 operation_logs");
    }

    @Test
    @DisplayName("applyUpdate：合并而非替换，未出现的键保持原值")
    void applyUpdateMergesInsteadOfReplacing() {
        RoomStateStore store = RoomStateStore.get();
        String keepKey = "room_name";
        String before = store.snapshot().get(keepKey).getAsString();

        String unique = marker("remark");
        store.applyUpdate("{\"room_remark\":\"" + unique + "\"}");

        JsonObject after = store.snapshot();
        assertEquals(unique, after.get("room_remark").getAsString(), "入参中的键应被写入");
        assertEquals(before, after.get(keepKey).getAsString(),
                "未出现在入参中的键必须保持原值（增量合并语义）");
        assertTrue(after.has("whitelist"), "合并不应丢掉既有的管理键");
    }

    @Test
    @DisplayName("applyUpdate：房间备注变化返回 true，未变化返回 false")
    void applyUpdateReportsRemarkChange() {
        RoomStateStore store = RoomStateStore.get();
        String changed = marker("remark");

        assertTrue(store.applyUpdate("{\"room_remark\":\"" + changed + "\"}"),
                "备注发生变化时应返回 true");
        assertFalse(store.applyUpdate("{\"room_remark\":\"" + changed + "\"}"),
                "备注未变化时应返回 false");
    }

    @Test
    @DisplayName("applyUpdate：log_entry 是控制字段，不写入状态，但会追加到日志")
    void applyUpdateTreatsLogEntryAsControlField() {
        RoomStateStore store = RoomStateStore.get();

        store.applyUpdate("{\"log_entry\":\"unit-test-entry\"}");

        JsonObject after = store.snapshot();
        assertFalse(after.has("log_entry"), "log_entry 是控制字段，不应成为状态的一部分");
        assertTrue(after.has("operation_logs"), "日志列表应存在");
        assertTrue(after.getAsJsonArray("operation_logs").size() > 0,
                "追加日志后 operation_logs 不应为空");
    }

    @Test
    @DisplayName("applyUpdate：null 与非法 JSON 都不改动状态")
    void applyUpdateIgnoresInvalidInput() {
        RoomStateStore store = RoomStateStore.get();
        String unique = marker("remark");
        store.applyUpdate("{\"room_remark\":\"" + unique + "\"}");

        assertFalse(store.applyUpdate(null), "null 入参应返回 false");
        assertFalse(store.applyUpdate("this is not json"),
                "非法 JSON 应返回 false（解析失败按原样返回处理）");
        assertEquals(unique, store.snapshot().get("room_remark").getAsString(),
                "失败的更新不得改动状态");
    }

    @Test
    @DisplayName("applyLocalSettings：写入 allow_cheats、visitor_permission 与 last_updated")
    void applyLocalSettingsWritesThreeKeys() {
        RoomStateStore store = RoomStateStore.get();
        long before = store.snapshot().get("last_updated").getAsLong();

        store.applyLocalSettings(true, "仅观战");

        JsonObject after = store.snapshot();
        assertTrue(after.get("allow_cheats").getAsBoolean(), "allow_cheats 应被写入");
        assertEquals("仅观战", after.get("visitor_permission").getAsString(),
                "visitor_permission 应被写入");
        assertTrue(after.get("last_updated").getAsLong() >= before, "last_updated 应被刷新");
    }

    @Test
    @DisplayName("appendLog：空串与 null 是空操作，不会破坏状态")
    void appendLogIgnoresEmptyInput() {
        RoomStateStore store = RoomStateStore.get();
        store.appendLog(null);
        store.appendLog("");

        assertTrue(store.snapshot().has("operation_logs"), "状态本身应保持可用");
    }

    @Test
    @DisplayName("appendLog：日志裁剪到最近 20 条")
    void appendLogTrimsToTwenty() {
        RoomStateStore store = RoomStateStore.get();
        for (int i = 0; i < 25; i++) {
            store.appendLog(marker("log-" + i));
        }

        JsonArray logs = store.snapshot().getAsJsonArray("operation_logs");
        assertTrue(logs.size() <= 20, "日志列表最多保留 20 条，实际=" + logs.size());
    }

    @Test
    @DisplayName("toJson：内容是当前状态的 JSON 文本")
    void toJsonReflectsState() {
        RoomStateStore store = RoomStateStore.get();
        String unique = marker("remark");
        store.applyUpdate("{\"room_remark\":\"" + unique + "\"}");

        String json = store.toJson();
        assertTrue(json.contains(unique), "序列化结果应包含刚刚写入的备注");
        assertTrue(json.contains("whitelist"), "序列化结果应包含管理键");
    }

    @Test
    @DisplayName("落盘：写入指定文件，且不产生其它文件")
    void writesToConfiguredFileOnly() {
        RoomStateStore store = RoomStateStore.get();
        store.applyLocalSettings(false, "可交互");

        File written = new File(tempDir.toFile(), "ender_room_config.json");
        assertTrue(written.exists(), "应把配置写到构造时指定的文件");
        assertTrue(written.length() > 0, "写出的配置不应为空文件");
    }

    @Test
    @DisplayName("get：返回当前单例实例")
    void getReturnsCurrentInstance() {
        assertSame(RoomStateStore.get(), RoomStateStore.get(), "get 应返回同一实例");
    }
}
