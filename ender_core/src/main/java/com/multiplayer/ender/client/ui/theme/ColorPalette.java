/*
 * 本文件属于 EnderOnline 客户端界面层。
 *
 * 职责：集中定义 UI 的颜色设计令牌，作为界面颜色的唯一来源。
 *
 * 所有令牌均为 0xAARRGGBB 形式的 int 常量，亮暗两套主题各占一组；
 * 取色必须经 ThemeManager，禁止在绘制代码里内联颜色常量。
 */
package com.multiplayer.ender.client.ui.theme;

/**
 * 颜色调色板，UI 层颜色令牌的唯一来源。
 *
 * 职责：定义品牌色、主题色与语义状态色；亮暗主题的取哪一组由 ThemeManager 决定，
 * 本类本身不含任何主题状态。
 *
 * 颜色格式：
 * 1. 所有常量都是 0xAARRGGBB 形式的 int，最高字节为 alpha。
 * 2. 主题色与文本色一律使用 0xFF 前缀（完全不透明）；覆盖层与悬停色使用 0x80、0x33 等
 *    半透明前缀表示叠加效果。
 * 3. 需要按运行时透明度合成时使用 withAlpha(int, int)，不要手写位移。
 * 4. 注意令牌命名里的 LIGHT_ 与 DARK_ 表示所属主题而不是颜色明度：
 *    例如 DARK_TEXT_PRIMARY 是暗色主题下的文本色，实际值为白色。
 *
 * 设计约束：
 * 1. 本类只允许出现编译期常量，不得引入可变字段或主题状态。
 * 2. 新增令牌必须沿用既有的前缀约定（LIGHT_ 与 DARK_ 表示主题归属，
 *    NETWORK_ 与 PLAYER_ 表示语义状态），不允许出现与用途无关的颜色名。
 * 3. 颜色值来自设计系统令牌，UI 绘制代码不得新增字面量颜色。
 * 4. 本文件位于 ender_core 的 client/ui 包下，按 ADR-04 需随 UI 抽象层一起迁出 core。
 *
 * 线程安全性：本类无可变状态，全部字段都是编译期常量，可被任意线程安全读取。
 *
 * TODO(P3, 2026-10-06): 随 UI 抽象层迁出 ender_core，见 claude_docs/00-decisions-and-open-questions.md 的 ADR-04
 *
 * @since 1.0
 * @see ThemeManager
 * @see UIStyle
 */
public final class ColorPalette {

    private ColorPalette() {
        // 工具类，禁止实例化
    }

    // ========== 主题颜色 ==========

    /** 主色调（末影紫，偏蓝紫），0xFF8A2BE2，不透明。用于强调操作、选中态与品牌标识。 */
    public static final int PRIMARY_COLOR = 0xFF8A2BE2;

    /** 主色调亮变体，0xFFA855F7，不透明。用于悬停与高亮状态。 */
    public static final int PRIMARY_LIGHT = 0xFFA855F7;

    /** 主色调暗变体，0xFF7B1FA2，不透明。用于按下与禁用状态。 */
    public static final int PRIMARY_DARK = 0xFF7B1FA2;

    /** 次要色调（青色），0xFF00BCD4，不透明。用于次级强调与辅助图形。 */
    public static final int SECONDARY_COLOR = 0xFF00BCD4;

    /** 成功色（绿色），0xFF4CAF50，不透明。用于成功提示与已连接状态。 */
    public static final int SUCCESS_COLOR = 0xFF4CAF50;

    /** 警告色（橙色），0xFFFF9800，不透明。用于可继续操作的警示。 */
    public static final int WARNING_COLOR = 0xFFFF9800;

    /** 错误色（红色），0xFFF44336，不透明。用于失败提示与危险操作。 */
    public static final int ERROR_COLOR = 0xFFF44336;

    /** 信息色（蓝色），0xFF2196F3，不透明。用于中性提示。 */
    public static final int INFO_COLOR = 0xFF2196F3;

    // ========== 亮色主题 ==========

    /** 亮色主题窗口背景，0xFFF5F5F5，不透明。 */
    public static final int LIGHT_BACKGROUND = 0xFFF5F5F5;

    /** 亮色主题表面色，0xFFFFFFFF，不透明。用于卡片与面板底色。 */
    public static final int LIGHT_SURFACE = 0xFFFFFFFF;

    /** 亮色主题表面变体，0xFFEEEEEE，不透明。用于在表面之上再分层的区块。 */
    public static final int LIGHT_SURFACE_VARIANT = 0xFFEEEEEE;

    /** 亮色主题边框色，0xFFE0E0E0，不透明。 */
    public static final int LIGHT_BORDER = 0xFFE0E0E0;

    /** 亮色主题主要文本色，0xFF000000（黑色），不透明。 */
    public static final int LIGHT_TEXT_PRIMARY = 0xFF000000;

    /** 亮色主题次要文本色，0xFF757575，不透明。用于说明与辅助信息。 */
    public static final int LIGHT_TEXT_SECONDARY = 0xFF757575;

    /** 亮色主题禁用文本色，0xFFBDBDBD，不透明。 */
    public static final int LIGHT_TEXT_DISABLED = 0xFFBDBDBD;

    // ========== 暗色主题 ==========

    /** 暗色主题窗口背景，0xFF121212，不透明。 */
    public static final int DARK_BACKGROUND = 0xFF121212;

    /** 暗色主题表面色，0xFF1E1E1E，不透明。用于卡片与面板底色。 */
    public static final int DARK_SURFACE = 0xFF1E1E1E;

    /** 暗色主题表面变体，0xFF2D2D2D，不透明。用于在表面之上再分层的区块。 */
    public static final int DARK_SURFACE_VARIANT = 0xFF2D2D2D;

    /** 暗色主题边框色，0xFF424242，不透明。 */
    public static final int DARK_BORDER = 0xFF424242;

    /**
     * 暗色主题主要文本色，0xFFFFFFFF（白色），不透明。
     *
     * 命名中的 DARK_ 指所属主题而非颜色明度，因此本常量是白色；
     * getContrastTextColor 在深色背景上返回的正是本常量。
     */
    public static final int DARK_TEXT_PRIMARY = 0xFFFFFFFF;

    /** 暗色主题次要文本色，0xFFBDBDBD，不透明。用于说明与辅助信息。 */
    public static final int DARK_TEXT_SECONDARY = 0xFFBDBDBD;

    /** 暗色主题禁用文本色，0xFF757575，不透明。 */
    public static final int DARK_TEXT_DISABLED = 0xFF757575;

    // ========== 透明度 ==========

    /** 完全透明，0x00000000；alpha 为 0，绘制结果不可见。 */
    public static final int TRANSPARENT = 0x00000000;

    /** 半透明黑色覆盖层，0x80000000；alpha 为 128，用于模态遮罩。 */
    public static final int OVERLAY_BLACK = 0x80000000;

    /** 半透明白色覆盖层，0x80FFFFFF；alpha 为 128。 */
    public static final int OVERLAY_WHITE = 0x80FFFFFF;

    /** 半透明主色调，0x338A2BE2；alpha 为 51，用于悬停高亮。 */
    public static final int PRIMARY_TRANSPARENT = 0x338A2BE2;

    // ========== 网络状态颜色 ==========

    /**
     * 网络质量优秀，0xFF00C853，不透明。
     *
     * 由 ThemeManager.getNetworkQualityColor 在质量大于等于 0.8 或等级为 4 时返回。
     */
    public static final int NETWORK_EXCELLENT = 0xFF00C853;

    /** 网络质量良好，0xFFFFD600，不透明。对应质量 0.6 到 0.8 或等级 3。 */
    public static final int NETWORK_GOOD = 0xFFFFD600;

    /** 网络质量一般，0xFFFF9100，不透明。对应质量 0.4 到 0.6 或等级 2。 */
    public static final int NETWORK_FAIR = 0xFFFF9100;

    /** 网络质量差，0xFFDD2C00，不透明。对应质量大于 0 且小于 0.4 或等级 1。 */
    public static final int NETWORK_POOR = 0xFFDD2C00;

    /** 网络断开，0xFF9E9E9E，不透明。对应质量等于 0 或等级 0。 */
    public static final int NETWORK_DISCONNECTED = 0xFF9E9E9E;

    // ========== 玩家状态颜色 ==========

    /** 玩家在线，0xFF4CAF50，不透明。 */
    public static final int PLAYER_ONLINE = 0xFF4CAF50;

    /** 玩家暂时离开，0xFFFF9800，不透明。 */
    public static final int PLAYER_AWAY = 0xFFFF9800;

    /** 玩家离线，0xFF757575，不透明。 */
    public static final int PLAYER_OFFLINE = 0xFF757575;

    /** 房主标记，0xFF8A2BE2，不透明；与主色调同值，用于成员列表的身份标识。 */
    public static final int PLAYER_HOST = 0xFF8A2BE2;

    // ========== 辅助方法 ==========

    /**
     * 用指定透明度替换颜色的 alpha 通道。
     *
     * 本方法是构造半透明颜色的唯一入口，避免各处手写位移。
     *
     * @param alpha 透明度，取值范围 0 到 255，只取低 8 位，超出部分被截断
     * @param rgb   颜色值，只使用低 24 位（0x00RRGGBB），高位被忽略
     * @return 合成后的 0xAARRGGBB 颜色
     */
    public static int withAlpha(int alpha, int rgb) {
        return ((alpha & 0xFF) << 24) | (rgb & 0x00FFFFFF);
    }

    /**
     * 按因子线性缩放 RGB 分量以调整亮度。
     *
     * 只调整 R、G、B 三个分量，alpha 原样保留；每个分量按 0 到 255 截断，
     * 因此放大因子不会让颜色溢出到相邻通道。
     *
     * @param color  原始颜色，0xAARRGGBB
     * @param factor 亮度因子，0.0 表示全黑，1.0 表示原始亮度，大于 1.0 表示更亮；
     *               负值结果与 0.0 相同
     * @return 调整后的 0xAARRGGBB 颜色
     */
    public static int adjustBrightness(int color, float factor) {
        int a = (color >> 24) & 0xFF;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;

        r = Math.min(255, Math.max(0, Math.round(r * factor)));
        g = Math.min(255, Math.max(0, Math.round(g * factor)));
        b = Math.min(255, Math.max(0, Math.round(b * factor)));

        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * 返回颜色的十六进制字符串表示，便于日志与调试面板输出。
     *
     * 输出固定为 8 位十六进制并含 alpha 通道，格式为 #AARRGGBB，字母大写。
     *
     * XXX: String.format 未指定 Locale，十六进制输出会跟随默认 Locale 的数字形，
     * 在非拉丁数字的 Locale 下表现未经验证。
     *
     * @param color 颜色，0xAARRGGBB
     * @return 形如 #AARRGGBB 的字符串，永不为 null
     */
    public static String toHexString(int color) {
        return String.format("#%08X", color);
    }

    /**
     * 判断颜色是否属于暗色，用于决定叠加文本的颜色。
     *
     * 判定基于 R、G、B 的加权亮度，完全忽略 alpha 通道。
     *
     * XXX: 亮度采用 0.299、0.587、0.114 的线性近似且未做 sRGB 反伽马校正，
     * 0.5 的阈值是经验值，未验证是否满足可访问性对比度要求。
     *
     * @param color 颜色，0xAARRGGBB，alpha 被忽略
     * @return 亮度小于 0.5 时返回 true
     */
    public static boolean isDarkColor(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;

        // 计算相对亮度（标准公式）
        double luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        return luminance < 0.5;
    }

    /**
     * 返回与给定背景色对比度较高的文本颜色。
     *
     * 只返回两种取值：深色背景返回 DARK_TEXT_PRIMARY（白色），浅色背景返回
     * LIGHT_TEXT_PRIMARY（黑色）；背景色的 alpha 被忽略。
     *
     * @param backgroundColor 背景颜色，0xAARRGGBB
     * @return 纯黑或纯白文本色，二者都是不透明颜色
     */
    public static int getContrastTextColor(int backgroundColor) {
        return isDarkColor(backgroundColor) ?
                DARK_TEXT_PRIMARY : // 深色背景用白色文本
                LIGHT_TEXT_PRIMARY; // 浅色背景用黑色文本
    }
}
