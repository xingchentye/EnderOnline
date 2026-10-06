/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：房间码的生成、解析与网络凭据派生，独立于房间注册表与请求处理。
 *
 * 这些方法与房间码字符表原先内联在 CoreRooms 的 RoomManager 里，使该类同时承担
 * 「房间注册」与「房间码编解码」两件事。抽出后房间码规则可以单独测试。
 */
package com.endercore.core.comm.server;

import java.math.BigInteger;
import java.security.SecureRandom;

/**
 * 房间码工具。
 *
 * 房间码形如 {@code U/XXXX-XXXX-XXXX-XXXX}，共 16 位有效字符，采用 34 进制
 * （去掉了易与数字混淆的 I 与 O）。数值必须能被 7 整除，因此最后一位是校验位。
 *
 * 设计约束：
 * 1. 全部方法为纯静态：除随机源外不持有状态，可被多线程并发调用。
 * 2. 解析大小写不敏感；字符 I 与 O 分别按 1 与 0 处理（见 {@link #lookupDigit(char)}）。
 * 3. {@link #parseRoomCode(String)} 允许房间码出现在输入字符串的任意位置，找不到合法房间码时返回 null。
 *
 * 已知瑕疵（记录，不在本次抽取中修复）：{@link #parseRoomCode(String)} 的校验和计算
 * 直接写死了字面量 34 与 7，而 {@link #generateRoomCode()} 使用的是 {@link #CODE_BASE} 与
 * {@link #CODE_CHECK}。两者当前取值相同所以行为一致，但改字符表或改模数时极易只改一处。
 * 应改为引用常量；本抽取保持字节级等价，故暂不改动。
 *
 * 线程安全性：无共享可变状态；{@link #RANDOM} 是线程安全的 SecureRandom。
 *
 * @see CoreRooms
 */
final class RoomCodes {

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

    /** 房间码模板，同时决定其显示长度。 */
    private static final String CODE_TEMPLATE = "U/XXXX-XXXX-XXXX-XXXX";

    /** 有效字符位数。 */
    private static final int DIGIT_COUNT = 16;

    private RoomCodes() {
    }

    /**
     * 生成随机房间码。
     *
     * 取 128 位随机数并对取值空间取模得到 16 位数值，再调整为 7 的倍数，使校验位恒为 0，
     * 最后格式化为可读形式并派生网络凭据。
     *
     * @return 房间码及其派生的网络名称与密钥，永不为 null
     */
    static RoomCode generateRoomCode() {
        BigInteger value = new BigInteger(128, RANDOM).mod(CODE_SPACE);
        value = value.subtract(value.mod(CODE_CHECK));
        int[] digits = new int[DIGIT_COUNT];
        BigInteger v = value;
        for (int i = 0; i < DIGIT_COUNT; i++) {
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
    static RoomCode parseRoomCode(String input) {
        if (input == null) {
            return null;
        }
        String code = input.toUpperCase();
        int wantLen = CODE_TEMPLATE.length();
        if (code.length() < wantLen) {
            return null;
        }
        for (int start = 0; start <= code.length() - wantLen; start++) {
            if (code.charAt(start) != 'U' || code.charAt(start + 1) != '/') {
                continue;
            }
            int[] digits = new int[DIGIT_COUNT];
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
            if (!ok || di != DIGIT_COUNT) {
                continue;
            }
            int rem = 0;
            for (int i = DIGIT_COUNT - 1; i >= 0; i--) {
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
    static int lookupDigit(char c) {
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
    static RoomCode fromDigits(int[] digits) {
        StringBuilder code = new StringBuilder(CODE_TEMPLATE.length());
        code.append("U/");
        StringBuilder networkName = new StringBuilder("scaffolding-mc-XXXX-XXXX".length());
        networkName.append("scaffolding-mc-");
        StringBuilder networkSecret = new StringBuilder("XXXX-XXXX".length());

        for (int i = 0; i < DIGIT_COUNT; i++) {
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
     * 构造后不再修改，可安全跨线程共享。
     */
    static final class RoomCode {
        /** 房间码，形如 U/XXXX-XXXX-XXXX-XXXX。 */
        final String code;
        /** EasyTier 网络名称，由房间码的低位数值派生。 */
        final String networkName;
        /** EasyTier 网络密钥，由房间码的高位数值派生。 */
        final String networkSecret;

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
}
