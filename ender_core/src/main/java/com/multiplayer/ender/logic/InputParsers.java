/*
 * 本文件属于 EnderOnline 逻辑层。
 *
 * 职责：把界面文本框里的原始输入解析为数值——解析失败时回落到调用方给定的当前值。
 *
 * 这段逻辑原先内联在 EnderDashboard 中。它与界面无关，只是「不要用非法输入把字段清掉」这一条
 * 交互约定，抽成纯函数后可以单独测试。
 */
package com.multiplayer.ender.logic;

/**
 * 界面数值输入解析。
 *
 * 文本框在编辑过程中会短暂处于「非法但正常」的中间态：空串、只有一个负号、刚输入字母。
 * 这类输入不应把背后的字段清成 0 或抛异常，而应保持原值——本类集中处理这条约定。
 *
 * 设计约束：
 * 1. 输入先 trim 再解析，因此前后空白不影响结果。
 * 2. 任何解析失败（空串、非数字、超出 int 范围）都返回调用方给的回落值，不抛异常。
 * 3. 不限制取值范围：越界与业务上下限校验由调用方负责，本类只做「能否解析」。
 *
 * 线程安全性：无状态，静态方法只读入参。
 */
public final class InputParsers {

    private InputParsers() {
    }

    /**
     * 解析整数输入，失败时回落到给定值。
     *
     * @param value 原始输入，允许为 null（按解析失败处理）；会先 trim
     * @param fallback 解析失败时返回的值
     * @return 解析结果；无法解析时返回 {@code fallback}
     */
    public static int parseIntOr(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            // ignore-reason: 解析失败是文本框编辑过程中的正常中间态，
            // 由返回值表达结果即可，向界面抛异常反而会打断输入。
            return fallback;
        }
    }
}
