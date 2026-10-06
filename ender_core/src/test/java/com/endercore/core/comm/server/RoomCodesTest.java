/*
 * 本文件属于 EnderOnline 单元测试。
 *
 * 职责：固化房间码的生成、解析与凭据派生规则，作为从 CoreRooms 拆出该逻辑后的回归基线。
 *
 * 关键约束：房间码是玩家之间交换的凭据，格式与校验规则一旦改变即为不兼容变更；
 * 本测试固定现有格式，改动必须在此显式更新而不是让实现悄悄漂移。
 */
package com.endercore.core.comm.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RoomCodes} 的行为固化测试。
 *
 * 这组测试守护什么：房间码的格式（U/ + 4 组 4 位）、大小写不敏感、I/O 分别按 1/0 处理、
 * 校验和必须能被 7 整除，以及解析允许房间码出现在任意位置。
 *
 * 注意：本文件的断言消息可保留中文——测试源码已从 i18n 守卫中排除，中文失败信息更易读。
 *
 * 线程安全性：被测方法是纯静态的，用例之间无共享状态，可并行执行。
 *
 * @see RoomCodes
 */
class RoomCodesTest {

    /** 房间码显示长度：U/ 加 16 位有效字符加 3 个连字符。 */
    private static final int CODE_LENGTH = "U/XXXX-XXXX-XXXX-XXXX".length();

    @Test
    @DisplayName("generateRoomCode：格式为 U/ + 4 组 4 位，且长度固定")
    void generatedCodeHasExpectedShape() {
        for (int i = 0; i < 50; i++) {
            RoomCodes.RoomCode code = RoomCodes.generateRoomCode();

            assertNotNull(code, "生成的房间码不应为 null");
            assertEquals(CODE_LENGTH, code.code.length(),
                    "房间码长度应固定，实际=" + code.code);
            assertTrue(code.code.startsWith("U/"), "房间码应以 U/ 开头，实际=" + code.code);
            assertEquals('-', code.code.charAt(6), "第 1 个连字符位置应正确：" + code.code);
            assertEquals('-', code.code.charAt(11), "第 2 个连字符位置应正确：" + code.code);
            assertEquals('-', code.code.charAt(16), "第 3 个连字符位置应正确：" + code.code);
        }
    }

    @Test
    @DisplayName("generateRoomCode：生成的码都能被解析回同一房间码（往返一致）")
    void generatedCodeRoundTrips() {
        for (int i = 0; i < 50; i++) {
            RoomCodes.RoomCode generated = RoomCodes.generateRoomCode();

            RoomCodes.RoomCode parsed = RoomCodes.parseRoomCode(generated.code);
            assertNotNull(parsed, "自己生成的房间码必须能被解析：" + generated.code);
            assertEquals(generated.code, parsed.code, "往返后房间码应一致");
            assertEquals(generated.networkName, parsed.networkName,
                    "往返后网络名称应一致（派生自低位数值）");
            assertEquals(generated.networkSecret, parsed.networkSecret,
                    "往返后网络密钥应一致（派生自高位数值）");
        }
    }

    @Test
    @DisplayName("generateRoomCode：随机性足够，连续生成不重复")
    void generatedCodesAreDistinct() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(RoomCodes.generateRoomCode().code);
        }

        // 34^16 的取值空间里取 200 个，重复概率可忽略
        assertEquals(200, seen.size(), "200 次生成不应出现重复，实际不同值=" + seen.size());
    }

    @Test
    @DisplayName("generateRoomCode：派生的网络名称与密钥非空且带前缀")
    void derivedCredentialsHaveExpectedShape() {
        RoomCodes.RoomCode code = RoomCodes.generateRoomCode();

        assertTrue(code.networkName.startsWith("scaffolding-mc-"),
                "网络名称应带 scaffolding-mc- 前缀，实际=" + code.networkName);
        assertFalse(code.networkSecret.isEmpty(), "网络密钥不应为空");
    }

    @Test
    @DisplayName("parseRoomCode：大小写不敏感")
    void parseIsCaseInsensitive() {
        RoomCodes.RoomCode generated = RoomCodes.generateRoomCode();

        RoomCodes.RoomCode lower = RoomCodes.parseRoomCode(generated.code.toLowerCase());
        assertNotNull(lower, "小写形式也应能解析：" + generated.code.toLowerCase());
        assertEquals(generated.code, lower.code, "大小写不同应解析出同一房间码");
    }

    @Test
    @DisplayName("parseRoomCode：允许房间码出现在输入字符串的任意位置")
    void parseFindsCodeInsideText() {
        RoomCodes.RoomCode generated = RoomCodes.generateRoomCode();

        RoomCodes.RoomCode parsed = RoomCodes.parseRoomCode("房间码是 " + generated.code + " 请加入");
        assertNotNull(parsed, "带前后缀的输入应能解析出房间码");
        assertEquals(generated.code, parsed.code, "解析结果应与内嵌的房间码一致");
    }

    @Test
    @DisplayName("parseRoomCode：null、空串与过短输入返回 null")
    void parseRejectsNullOrTooShort() {
        assertNull(RoomCodes.parseRoomCode(null), "null 应返回 null");
        assertNull(RoomCodes.parseRoomCode(""), "空串应返回 null");
        assertNull(RoomCodes.parseRoomCode("U/XXXX"), "过短输入应返回 null");
        assertNull(RoomCodes.parseRoomCode("hello world"), "不含房间码的文本应返回 null");
    }

    @Test
    @DisplayName("parseRoomCode：缺少分隔符连字符时返回 null")
    void parseRejectsMissingSeparators() {
        RoomCodes.RoomCode generated = RoomCodes.generateRoomCode();
        String noDashes = generated.code.replace("-", "/");

        assertNull(RoomCodes.parseRoomCode(noDashes),
                "连字符被替换后不应被当作合法房间码：" + noDashes);
    }

    @Test
    @DisplayName("parseRoomCode：校验和被破坏时返回 null")
    void parseRejectsBrokenChecksum() {
        RoomCodes.RoomCode generated = RoomCodes.generateRoomCode();
        String code = generated.code;
        int tail = code.length() - 1;
        char original = code.charAt(tail);

        // 逐位尝试替换，直到得到一个「格式合法但校验和不合法」的码。
        // 不能只试一个字符：改动后仍有 1/7 的概率恰好仍是 7 的倍数，那会让用例偶发失败。
        String tampered = null;
        for (char candidate : "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray()) {
            if (candidate == original) {
                continue;
            }
            String attempt = code.substring(0, tail) + candidate;
            if (RoomCodes.parseRoomCode(attempt) == null) {
                tampered = attempt;
                break;
            }
        }

        assertNotNull(tampered, "应当能构造出校验和不合法的房间码用于验证拒绝路径");
        assertNull(RoomCodes.parseRoomCode(tampered),
                "校验和不合法的房间码必须被拒绝：" + tampered);
    }

    @Test
    @DisplayName("parseRoomCode：字符 I 与 O 分别按 1 与 0 处理")
    void parseTreatsIAndOAsOneAndZero() {
        RoomCodes.RoomCode generated = RoomCodes.generateRoomCode();
        String code = generated.code;
        // 把 1 换成 I、0 换成 O 后应当解析回同一房间码
        String ambiguous = code.replace('1', 'I').replace('0', 'O');

        RoomCodes.RoomCode parsed = RoomCodes.parseRoomCode(ambiguous);
        assertNotNull(parsed, "I/O 混用形式应能解析：" + ambiguous);
        assertEquals(generated.code, parsed.code, "I 视作 1、O 视作 0，结果应与原码一致");
    }

    @Test
    @DisplayName("lookupDigit：字符表内返回下标，字符表外返回 -1")
    void lookupDigitMapsCharsAndRejectsOthers() {
        assertEquals(0, RoomCodes.lookupDigit('0'), "0 应映射为 0");
        assertEquals(1, RoomCodes.lookupDigit('1'), "1 应映射为 1");
        assertEquals(1, RoomCodes.lookupDigit('I'), "I 应映射为 1");
        assertEquals(0, RoomCodes.lookupDigit('O'), "O 应映射为 0");
        assertEquals(-1, RoomCodes.lookupDigit('@'), "字符表外的字符应返回 -1");
        assertEquals(-1, RoomCodes.lookupDigit('-'), "连字符不是有效数字，应返回 -1");

        // 字符表刻意去掉了易混淆的 I 与 O：它们只作为 1/0 的别名存在
        assertEquals(1, RoomCodes.lookupDigit('i'), "小写 i 也应映射为 1");
        assertEquals(0, RoomCodes.lookupDigit('o'), "小写 o 也应映射为 0");
    }

    @Test
    @DisplayName("fromDigits：全 0 数值产生全 0 房间码")
    void fromDigitsBuildsFromIndexes() {
        int[] digits = new int[16];
        RoomCodes.RoomCode code = RoomCodes.fromDigits(digits);

        assertEquals("U/0000-0000-0000-0000", code.code, "全 0 数值应对应全 0 房间码");
        assertFalse(code.networkName.isEmpty(), "网络名称不应为空");
        assertFalse(code.networkSecret.isEmpty(), "网络密钥不应为空");
    }

    @Test
    @DisplayName("fromDigits 与 parseRoomCode：构造出的码必须自带合法校验和")
    void fromDigitsProducesParsableCode() {
        int[] digits = new int[16];
        // 手工构造一个数值能被 7 整除的码：全 0 显然满足，再试一个非平凡值
        digits[0] = 7;
        RoomCodes.RoomCode code = RoomCodes.fromDigits(digits);

        RoomCodes.RoomCode parsed = RoomCodes.parseRoomCode(code.code);
        assertNotNull(parsed, "数值为 7 的倍数时应可通过校验：" + code.code);
        assertEquals(code.code, parsed.code, "往返应一致");
    }
}
