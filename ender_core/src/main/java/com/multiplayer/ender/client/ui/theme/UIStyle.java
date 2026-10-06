/*
 * 本文件属于 EnderOnline 客户端界面层。
 *
 * 职责：集中定义 UI 的尺寸、间距、圆角、阴影、边框、动画与字体令牌。
 *
 * 所有数值令牌目前都以像素为单位，由 UNIT 这一个基准推导；
 * 按 ADR-09 最终需要换算为 vp 并交给 LayoutEngine，见类注释中的已知缺陷说明。
 */
package com.multiplayer.ender.client.ui.theme;

/**
 * UI 样式令牌表，尺寸与节奏的唯一来源。
 *
 * 职责：定义间距、圆角、组件尺寸、阴影、边框、动画时长与字体缩放；
 * 数值来源是按 Material Design 指南为 Minecraft GUI 调整过的一套设计令牌，
 * UI 代码应引用令牌而不写裸数字。
 *
 * 单位约定：
 * 1. 除动画时长（秒）与字体尺寸（相对倍数）外，所有常量单位都是像素。
 * 2. 间距令牌全部由 UNIT 推导（SPACING_MEDIUM 等于 UNIT 乘 2 等），因此修改 UNIT 会
 *    连锁改变所有间距，属于破坏性改动。
 * 3. SHADOW_ALPHA_* 是 alpha 通道分值，必须与 ColorPalette.withAlpha 组合成颜色后使用。
 *
 * 设计约束：
 * 1. 本类只允许出现编译期常量，不得引入主题状态；主题相关取值走 ThemeManager。
 * 2. RADIUS_CIRCLE 是哨兵值而不是真实半径，调用方不得用它做几何计算。
 * 3. 本文件位于 ender_core 的 client/ui 包下，按 ADR-04 需随 UI 抽象层一起迁出 core。
 *
 * FIXME(P3, 2026-10-06): 全部尺寸令牌以固定像素为单位，与 ADR-09 要求的
 * vp 单位加 LayoutEngine 布局冲突；且 BUTTON_HEIGHT 为 20 像素，低于 ADR-10 规定的
 * 48vp 触控最小命中区，触控端存在误触风险，迁移时必须重新标定。
 * FIXME(P3, 2026-10-06): getSpacing 等字符串键方法使用默认 Locale 的
 * toLowerCase()，在土耳其语等 Locale 下大写 I 不会转成 i，键匹配会失败并静默回退默认值。
 *
 * 线程安全性：本类无可变状态，全部字段都是编译期常量，可被任意线程安全读取。
 *
 * TODO(P3, 2026-10-06): 随 UI 抽象层迁出 ender_core，见 claude_docs/00-decisions-and-open-questions.md 的 ADR-04
 *
 * @since 1.0
 * @see ColorPalette
 * @see ThemeManager
 */
public final class UIStyle {

    private UIStyle() {
        // 工具类，禁止实例化
    }

    // ========== 间距和边距 ==========

    /** 基础网格单位，8 像素；所有间距与边距令牌都由它推导。 */
    public static final int UNIT = 8;

    /** 微小间距，4 像素（UNIT 的一半）。 */
    public static final int SPACING_TINY = UNIT / 2;

    /** 小间距，8 像素（一个 UNIT）。 */
    public static final int SPACING_SMALL = UNIT;

    /** 中等间距，16 像素（两个 UNIT）。 */
    public static final int SPACING_MEDIUM = UNIT * 2;

    /** 大间距，24 像素（三个 UNIT）。 */
    public static final int SPACING_LARGE = UNIT * 3;

    /** 巨大间距，32 像素（四个 UNIT）。 */
    public static final int SPACING_HUGE = UNIT * 4;

    /** 内容区内边距，16 像素，取值等于 SPACING_MEDIUM。 */
    public static final int CONTENT_PADDING = SPACING_MEDIUM;

    /** 屏幕边缘边距，8 像素，取值等于 SPACING_SMALL。 */
    public static final int SCREEN_PADDING = SPACING_SMALL;

    // ========== 圆角半径 ==========

    /** 微小圆角半径，2 像素。 */
    public static final int RADIUS_TINY = 2;

    /** 小圆角半径，4 像素。 */
    public static final int RADIUS_SMALL = 4;

    /** 中等圆角半径，8 像素。 */
    public static final int RADIUS_MEDIUM = 8;

    /** 大圆角半径，12 像素。 */
    public static final int RADIUS_LARGE = 12;

    /** 巨大圆角半径，16 像素。 */
    public static final int RADIUS_HUGE = 16;

    /**
     * 圆形哨兵值，9999。
     *
     * 表示「完全圆形」而不是真实半径；实现方应将其钳制为宽高的一半，
     * 调用方不得用该值参与几何计算，也不要把它当作可比较的半径。
     */
    public static final int RADIUS_CIRCLE = 9999;

    // ========== 组件尺寸 ==========

    /** 标准按钮高度，20 像素；低于触控最小命中区要求，见类注释的已知缺陷说明。 */
    public static final int BUTTON_HEIGHT = 20;

    /** 小按钮高度，16 像素。 */
    public static final int BUTTON_HEIGHT_SMALL = 16;

    /** 大按钮高度，24 像素。 */
    public static final int BUTTON_HEIGHT_LARGE = 24;

    /** 输入框高度，20 像素；与 BUTTON_HEIGHT 对齐以便同行排布。 */
    public static final int INPUT_HEIGHT = 20;

    /** 滑块轨道高度，8 像素。 */
    public static final int SLIDER_HEIGHT = 8;

    /** 滑块手柄边长，16 像素。 */
    public static final int SLIDER_HANDLE_SIZE = 16;

    /** 复选框边长，16 像素。 */
    public static final int CHECKBOX_SIZE = 16;

    /** 单选框边长，16 像素。 */
    public static final int RADIO_SIZE = 16;

    /** 标准图标边长，16 像素。 */
    public static final int ICON_SIZE = 16;

    /** 小图标边长，12 像素。 */
    public static final int ICON_SIZE_SMALL = 12;

    /** 大图标边长，24 像素。 */
    public static final int ICON_SIZE_LARGE = 24;

    // ========== 阴影效果 ==========

    /** 轻微阴影的模糊半径，2 像素。 */
    public static final int SHADOW_BLUR_SMALL = 2;

    /** 中等阴影的模糊半径，4 像素。 */
    public static final int SHADOW_BLUR_MEDIUM = 4;

    /** 大阴影的模糊半径，8 像素。 */
    public static final int SHADOW_BLUR_LARGE = 8;

    /** 轻微阴影的偏移量，1 像素。 */
    public static final int SHADOW_OFFSET_SMALL = 1;

    /** 中等阴影的偏移量，2 像素。 */
    public static final int SHADOW_OFFSET_MEDIUM = 2;

    /** 大阴影的偏移量，4 像素。 */
    public static final int SHADOW_OFFSET_LARGE = 4;

    /** 轻微阴影的 alpha 分值，0x20，需与 ColorPalette.withAlpha 组合成颜色。 */
    public static final int SHADOW_ALPHA_SMALL = 0x20;

    /** 中等阴影的 alpha 分值，0x40，需与 ColorPalette.withAlpha 组合成颜色。 */
    public static final int SHADOW_ALPHA_MEDIUM = 0x40;

    /** 大阴影的 alpha 分值，0x60，需与 ColorPalette.withAlpha 组合成颜色。 */
    public static final int SHADOW_ALPHA_LARGE = 0x60;

    // ========== 边框宽度 ==========

    /** 细边框宽度，1 像素。 */
    public static final int BORDER_THIN = 1;

    /** 中等边框宽度，2 像素。 */
    public static final int BORDER_MEDIUM = 2;

    /** 粗边框宽度，3 像素。 */
    public static final int BORDER_THICK = 3;

    // ========== 动画时长 ==========

    /** 快速动画时长，0.1 秒。用于悬停与按下反馈。 */
    public static final float ANIMATION_FAST = 0.1f;

    /** 标准动画时长，0.25 秒。用于面板展开与淡入淡出。 */
    public static final float ANIMATION_NORMAL = 0.25f;

    /** 慢速动画时长，0.5 秒。用于页面级过渡。 */
    public static final float ANIMATION_SLOW = 0.5f;

    /** 非常慢的动画时长，1.0 秒。用于强调型过渡，慎用。 */
    public static final float ANIMATION_VERY_SLOW = 1.0f;

    // ========== 字体尺寸 ==========

    /** 小字体缩放倍数，0.75，基于 Minecraft 默认字体。 */
    public static final float FONT_SMALL = 0.75f;

    /** 标准字体缩放倍数，1.0，即 Minecraft 默认字体大小。 */
    public static final float FONT_NORMAL = 1.0f;

    /** 大字体缩放倍数，1.25。 */
    public static final float FONT_LARGE = 1.25f;

    /** 标题字体缩放倍数，1.5。 */
    public static final float FONT_TITLE = 1.5f;

    // ========== 辅助方法 ==========

    /**
     * 按名称查询间距令牌，供从配置或语言文件读取间距名时使用。
     *
     * 名称大小写不敏感；无法识别时回退到 SPACING_MEDIUM，不抛异常。
     *
     * @param spacingName 间距名称（"tiny", "small", "medium", "large", "huge"），不能为 null
     * @return 间距值，单位像素，恒为正
     */
    public static int getSpacing(String spacingName) {
        switch (spacingName.toLowerCase()) {
            case "tiny": return SPACING_TINY;
            case "small": return SPACING_SMALL;
            case "medium": return SPACING_MEDIUM;
            case "large": return SPACING_LARGE;
            case "huge": return SPACING_HUGE;
            default: return SPACING_MEDIUM;
        }
    }

    /**
     * 按名称查询圆角半径令牌。
     *
     * 名称大小写不敏感；无法识别时回退到 RADIUS_MEDIUM，不抛异常。
     *
     * @param radiusName 圆角名称（"tiny", "small", "medium", "large", "huge", "circle"），
     *                   不能为 null
     * @return 圆角半径，单位像素；"circle" 返回哨兵值 RADIUS_CIRCLE
     */
    public static int getRadius(String radiusName) {
        switch (radiusName.toLowerCase()) {
            case "tiny": return RADIUS_TINY;
            case "small": return RADIUS_SMALL;
            case "medium": return RADIUS_MEDIUM;
            case "large": return RADIUS_LARGE;
            case "huge": return RADIUS_HUGE;
            case "circle": return RADIUS_CIRCLE;
            default: return RADIUS_MEDIUM;
        }
    }

    /**
     * 按名称查询阴影参数。
     *
     * 名称大小写不敏感；无法识别时回退到轻微阴影，不抛异常。
     *
     * @param intensity 阴影强度（"small", "medium", "large"），不能为 null
     * @return 长度为 2 的新数组，下标 0 是模糊半径、下标 1 是偏移量，单位均为像素；
     *         每次调用返回新数组，调用方可以安全修改
     */
    public static int[] getShadowParams(String intensity) {
        switch (intensity.toLowerCase()) {
            case "small":
                return new int[]{SHADOW_BLUR_SMALL, SHADOW_OFFSET_SMALL};
            case "medium":
                return new int[]{SHADOW_BLUR_MEDIUM, SHADOW_OFFSET_MEDIUM};
            case "large":
                return new int[]{SHADOW_BLUR_LARGE, SHADOW_OFFSET_LARGE};
            default:
                return new int[]{SHADOW_BLUR_SMALL, SHADOW_OFFSET_SMALL};
        }
    }

    /**
     * 按名称查询边框宽度令牌。
     *
     * 名称大小写不敏感；无法识别时回退到 BORDER_MEDIUM，不抛异常。
     *
     * @param thickness 边框粗细（"thin", "medium", "thick"），不能为 null
     * @return 边框宽度，单位像素，恒为正
     */
    public static int getBorderWidth(String thickness) {
        switch (thickness.toLowerCase()) {
            case "thin": return BORDER_THIN;
            case "medium": return BORDER_MEDIUM;
            case "thick": return BORDER_THICK;
            default: return BORDER_MEDIUM;
        }
    }

    /**
     * 按名称查询动画时长令牌。
     *
     * 名称大小写不敏感；无法识别时回退到标准时长，不抛异常。
     *
     * @param speed 动画速度（"fast", "normal", "slow", "very_slow"），不能为 null
     * @return 动画时长，单位秒，恒为正
     */
    public static float getAnimationDuration(String speed) {
        switch (speed.toLowerCase()) {
            case "fast": return ANIMATION_FAST;
            case "normal": return ANIMATION_NORMAL;
            case "slow": return ANIMATION_SLOW;
            case "very_slow": return ANIMATION_VERY_SLOW;
            default: return ANIMATION_NORMAL;
        }
    }
}
