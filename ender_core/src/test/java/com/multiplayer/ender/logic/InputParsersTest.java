/*
 * 本文件属于 EnderOnline 单元测试。
 *
 * 职责：固化文本框数值解析的语义，作为从 EnderDashboard 拆出该逻辑后的回归基线。
 *
 * 关键约束：解析失败必须回落到调用方传入的当前值，而不是 0——否则玩家在文本框里
 * 刚删掉一位数字，背后的字段就会被清成 0 并同步给整个房间。
 */
package com.multiplayer.ender.logic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link InputParsers} 的行为固化测试。
 *
 * 这组测试守护什么：合法输入的解析、编辑中间态（空串、只有符号、字母、溢出）一律回落原值、
 * 以及前后空白被忽略。
 *
 * 注意：本文件的断言消息可保留中文——测试源码已从 i18n 守卫中排除，中文失败信息更易读。
 *
 * 线程安全性：被测方法是纯静态的，用例之间无共享状态，可并行执行。
 *
 * @see InputParsers
 */
class InputParsersTest {

    @Test
    @DisplayName("合法输入按十进制解析")
    void parsesValidInput() {
        assertEquals(42, InputParsers.parseIntOr("42", -1), "应解析出 42");
        assertEquals(0, InputParsers.parseIntOr("0", -1), "0 是合法取值，不应回落");
        assertEquals(-7, InputParsers.parseIntOr("-7", 0), "负数应可解析");
        assertEquals(25565, InputParsers.parseIntOr("25565", 0), "端口形态的取值应可解析");
    }

    @Test
    @DisplayName("前后空白被忽略")
    void trimsWhitespace() {
        assertEquals(42, InputParsers.parseIntOr("  42  ", -1), "前后空白不应影响解析");
        assertEquals(42, InputParsers.parseIntOr("\t42\n", -1), "制表符与换行也应被 trim");
    }

    @Test
    @DisplayName("空串与纯空白回落到给定值")
    void fallsBackOnBlank() {
        assertEquals(99, InputParsers.parseIntOr("", 99), "空串应回落");
        assertEquals(99, InputParsers.parseIntOr("   ", 99), "纯空白应回落");
    }

    @Test
    @DisplayName("编辑中间态：只有负号或正号时回落")
    void fallsBackOnSignOnly() {
        assertEquals(16, InputParsers.parseIntOr("-", 16), "只输入负号时应保持原值");
        assertEquals(16, InputParsers.parseIntOr("+", 16), "只输入正号时应保持原值");
    }

    @Test
    @DisplayName("非数字输入回落，而不是抛异常")
    void fallsBackOnNonNumeric() {
        assertEquals(16, InputParsers.parseIntOr("abc", 16), "字母应回落");
        assertEquals(16, InputParsers.parseIntOr("12abc", 16), "数字后跟字母应回落");
        assertEquals(16, InputParsers.parseIntOr("1.5", 16), "小数不能解析为整数，应回落");
        assertEquals(16, InputParsers.parseIntOr("1e3", 16), "科学计数法应回落");
    }

    @Test
    @DisplayName("超出 int 范围回落")
    void fallsBackOnOverflow() {
        assertEquals(16, InputParsers.parseIntOr("99999999999999999999", 16), "溢出应回落");
        assertEquals(Integer.MIN_VALUE, InputParsers.parseIntOr(String.valueOf(Integer.MIN_VALUE), 0),
                "int 下界应可解析");
        assertEquals(Integer.MAX_VALUE, InputParsers.parseIntOr(String.valueOf(Integer.MAX_VALUE), 0),
                "int 上界应可解析");
    }

    @Test
    @DisplayName("null 输入回落（不抛 NullPointerException）")
    void fallsBackOnNull() {
        assertEquals(16, InputParsers.parseIntOr(null, 16), "null 应回落");
    }

    @Test
    @DisplayName("回落值原样返回：0 与负数都可用作回落值")
    void fallbackIsReturnedVerbatim() {
        assertEquals(0, InputParsers.parseIntOr("x", 0), "回落值 0 应原样返回");
        assertEquals(-1, InputParsers.parseIntOr("x", -1), "回落值 -1 应原样返回");
    }

    @Test
    @DisplayName("常用字段取值可往返：重生坐标、世界边界半径、重试次数")
    void representativeFieldValuesRoundTrip() {
        int[] values = {0, 1, -64, 320, 1000, 29999984};
        for (int value : values) {
            assertEquals(value, InputParsers.parseIntOr(String.valueOf(value), -999),
                    "取值应原样往返：" + value);
        }
    }
}
