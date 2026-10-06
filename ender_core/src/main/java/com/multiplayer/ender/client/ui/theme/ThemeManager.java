/*
 * 本文件属于 EnderOnline 客户端界面层。
 *
 * 职责：持有当前 UI 主题状态，按主题提供颜色取值，并向订阅方广播主题变更。
 *
 * 主题切换是 UI 线程内的同步行为；颜色本身来自 ColorPalette 的令牌常量。
 */
package com.multiplayer.ender.client.ui.theme;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 主题管理器，主题状态与取色的唯一入口。
 *
 * 职责：持有用户选择的主题类型、按当前主题解析出亮色或暗色、提供各类语义颜色的取值，
 * 并在生效主题发生变化时通知已注册的监听器。
 *
 * 设计约束：
 * 1. 进程内单例；主题的默认值是 DARK（构造时也会初始化系统暗色标记）。
 * 2. getCurrentTheme 负责把 AUTO 解析成 LIGHT 或 DARK，因此对外可观察的主题只有这两种，
 *    AUTO 只表示「跟随系统」的委托态。
 * 3. 持久化格式是「主题ID:系统暗色布尔值」的字符串，由 saveConfig 生成、loadConfig 解析；
 *    两个方法必须成对演进，且以 ThemeType 的 id 为契约。
 * 4. 监听器回调在触发线程内同步执行，不得在监听器里做耗时操作或再次触发主题切换。
 * 5. 本文件位于 ender_core 的 client/ui 包下，按 ADR-04 需随 UI 抽象层一起迁出 core。
 *
 * FIXME(P3, 2026-10-06): 单例字段 instance 是非 final 的静态可变状态且惰性
 * 初始化无同步，违反 ADR-05 并存在并发重复构造风险；themeChangeListeners 为普通 HashMap，
 * 遍历期间增删监听器会抛 ConcurrentModificationException。
 * FIXME(P3, 2026-10-06): AUTO 主题下 updateSystemTheme 无条件广播，与 setTheme
 * 的去重语义不一致，订阅方会收到解析结果并未改变的重复回调。
 *
 * 线程安全性：非线程安全。currentTheme、isSystemDarkMode 与监听器表均无同步，
 * 只允许客户端渲染线程读写；跨线程调用会产生数据竞争与丢失通知。
 *
 * TODO(P3, 2026-10-06): 随 UI 抽象层迁出 ender_core，见 docs/00-decisions-and-open-questions.md 的 ADR-04
 *
 * @since 1.0
 * @see ColorPalette
 * @see UIStyle
 */
public final class ThemeManager {

    /**
     * 进程内单例实例。
     *
     * 允许为 null，表示尚未创建；惰性初始化无同步，见类注释中的已知缺陷说明。
     */
    private static ThemeManager instance;

    /**
     * 主题类型。
     *
     * 状态流转关系：
     * LIGHT 与 DARK 是两种显式主题，可以互相直接切换（toggleTheme 或在 setTheme 中指定）。
     * AUTO 不是第三种可见主题，而是「跟随系统」的委托态：getCurrentTheme 会把 AUTO 解析为
     * LIGHT 或 DARK，解析结果由 isSystemDarkMode 决定，系统判定变化时会再次广播。
     * 因此任意状态都可以流转到任意状态，本枚举没有终态。
     */
    public enum ThemeType {
        /** 亮色主题，id 为 "light"；显式固定，不受系统暗色设置影响。 */
        LIGHT("light", "亮色主题"),

        /** 暗色主题，id 为 "dark"；ThemeManager 的默认主题。 */
        DARK("dark", "暗色主题"),

        /** 自动主题，id 为 "auto"；颜色结果委托给系统暗色判定，不直接决定颜色。 */
        AUTO("auto", "自动（跟随系统）");

        /**
         * 持久化标识，小写英文。
         *
         * 与 loadConfig 解析及 saveConfig 输出的字符串格式耦合，不允许改动。
         */
        private final String id;

        /**
         * 展示名称。
         *
         * FIXME(P3, 2026-10-06): 是中文字符串字面量且属用户可见文案，
         * 违反 ADR-15（字符串用英文、界面文案走语言文件键），应改为语言文件键。
         */
        private final String displayName;

        /**
         * 构造主题类型。
         *
         * @param id 持久化标识，小写英文，不能为 null
         * @param displayName 展示名称，不能为 null
         */
        ThemeType(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        /**
         * 返回持久化标识。
         *
         * @return 小写英文 id（"light"、"dark"、"auto"），永不为 null
         */
        public String getId() {
            return id;
        }

        /**
         * 返回展示名称。
         *
         * @return 用户可见的主题名称，永不为 null；当前实现返回中文字面量，见字段注释的缺陷说明
         */
        public String getDisplayName() {
            return displayName;
        }

        /**
         * 按持久化标识解析主题类型。
         *
         * @param id 主题 id，允许为 null 或未知值
         * @return 匹配的主题类型；id 为 null 或无法识别时返回 DARK，不抛异常
         */
        public static ThemeType fromId(String id) {
            for (ThemeType type : values()) {
                if (type.id.equals(id)) {
                    return type;
                }
            }
            return DARK; // 默认暗色主题
        }
    }

    /**
     * 用户当前选择的主题，可能是 AUTO。
     *
     * 与 getCurrentTheme 的区别：本字段保留 AUTO 原值，读取方若需要可绘制主题必须调用
     * getCurrentTheme。默认 DARK。
     */
    private ThemeType currentTheme = ThemeType.DARK;

    /**
     * 系统是否处于暗色模式。
     *
     * 仅当 currentTheme 为 AUTO 时影响取色结果；由构造函数与 updateSystemTheme 更新。
     */
    private boolean isSystemDarkMode = false;

    /**
     * 主题变更监听器表，键是调用方提供的监听器 ID。
     *
     * 允许用同一 id 覆盖注册；非同步容器，遍历期间增删会抛 ConcurrentModificationException。
     */
    private final Map<String, Consumer<ThemeType>> themeChangeListeners = new HashMap<>();

    private ThemeManager() {
        // 单例模式
        detectSystemTheme();
    }

    /**
     * 获取主题管理器单例。
     *
     * 幂等性：同一进程内重复调用返回同一对象；但惰性初始化无同步，并发首次调用可能
     * 创建多个实例，因此只允许渲染线程首次访问。
     *
     * @return 主题管理器单例，永不为 null
     */
    public static ThemeManager getInstance() {
        if (instance == null) {
            instance = new ThemeManager();
        }
        return instance;
    }

    /**
     * 获取当前生效的主题类型。
     *
     * 若用户选择的是 AUTO，则按系统暗色判定解析为 DARK 或 LIGHT。
     *
     * @return 当前生效主题，只可能是 DARK 或 LIGHT，永不返回 AUTO，永不为 null
     */
    public ThemeType getCurrentTheme() {
        if (currentTheme == ThemeType.AUTO) {
            return isSystemDarkMode ? ThemeType.DARK : ThemeType.LIGHT;
        }
        return currentTheme;
    }

    /**
     * 设置主题类型。
     *
     * 仅当生效主题（解析 AUTO 之后）发生变化时才广播变更；因此从 LIGHT 切到 AUTO（系统为亮色）
     * 不会触发回调。
     *
     * @param theme 新主题类型，不能为 null；为 null 会污染内部状态并使监听器收到 null
     */
    public void setTheme(ThemeType theme) {
        ThemeType oldTheme = getCurrentTheme();
        this.currentTheme = theme;
        ThemeType newTheme = getCurrentTheme();

        if (oldTheme != newTheme) {
            notifyThemeChanged(newTheme);
        }
    }

    /**
     * 按 id 设置主题类型。
     *
     * @param themeId 主题 id（"light"、"dark"、"auto"）；null 或未知 id 回退为 DARK
     */
    public void setTheme(String themeId) {
        setTheme(ThemeType.fromId(themeId));
    }

    /**
     * 在当前亮暗之间切换主题并把结果固定下来。
     *
     * 生效主题为 LIGHT 时切到 DARK，否则切到 LIGHT；因此处于 AUTO 时调用会退出跟随系统的状态。
     */
    public void toggleTheme() {
        ThemeType current = getCurrentTheme();
        setTheme(current == ThemeType.LIGHT ? ThemeType.DARK : ThemeType.LIGHT);
    }

    /**
     * 判断当前生效主题是否为亮色。
     *
     * @return 解析后的生效主题是 LIGHT 时返回 true
     */
    public boolean isLightTheme() {
        return getCurrentTheme() == ThemeType.LIGHT;
    }

    /**
     * 判断当前生效主题是否为暗色。
     *
     * @return 解析后的生效主题是 DARK 时返回 true
     */
    public boolean isDarkTheme() {
        return getCurrentTheme() == ThemeType.DARK;
    }

    /**
     * 判断用户是否选择了跟随系统。
     *
     * 与 isLightTheme、isDarkTheme 不互斥：AUTO 时前两者按解析结果返回，本方法同时返回 true。
     *
     * @return 原始主题设置为 AUTO 时返回 true
     */
    public boolean isAutoTheme() {
        return currentTheme == ThemeType.AUTO;
    }

    // ========== 颜色获取方法 ==========

    /**
     * 获取当前主题的窗口背景色。
     *
     * @return 0xAARRGGBB 颜色，不透明；暗色主题取 ColorPalette.DARK_BACKGROUND
     */
    public int getBackgroundColor() {
        return isDarkTheme() ? ColorPalette.DARK_BACKGROUND : ColorPalette.LIGHT_BACKGROUND;
    }

    /**
     * 获取当前主题的表面色，用于卡片与面板。
     *
     * @return 0xAARRGGBB 颜色，不透明
     */
    public int getSurfaceColor() {
        return isDarkTheme() ? ColorPalette.DARK_SURFACE : ColorPalette.LIGHT_SURFACE;
    }

    /**
     * 获取当前主题的表面变体色，用于在表面之上再分层。
     *
     * @return 0xAARRGGBB 颜色，不透明
     */
    public int getSurfaceVariantColor() {
        return isDarkTheme() ? ColorPalette.DARK_SURFACE_VARIANT : ColorPalette.LIGHT_SURFACE_VARIANT;
    }

    /**
     * 获取当前主题的边框色。
     *
     * @return 0xAARRGGBB 颜色，不透明
     */
    public int getBorderColor() {
        return isDarkTheme() ? ColorPalette.DARK_BORDER : ColorPalette.LIGHT_BORDER;
    }

    /**
     * 获取当前主题的主要文本色。
     *
     * @return 0xAARRGGBB 颜色，不透明；暗色主题下为白色
     */
    public int getTextPrimaryColor() {
        return isDarkTheme() ? ColorPalette.DARK_TEXT_PRIMARY : ColorPalette.LIGHT_TEXT_PRIMARY;
    }

    /**
     * 获取当前主题的次要文本色。
     *
     * @return 0xAARRGGBB 颜色，不透明
     */
    public int getTextSecondaryColor() {
        return isDarkTheme() ? ColorPalette.DARK_TEXT_SECONDARY : ColorPalette.LIGHT_TEXT_SECONDARY;
    }

    /**
     * 获取当前主题的禁用文本色。
     *
     * @return 0xAARRGGBB 颜色，不透明
     */
    public int getTextDisabledColor() {
        return isDarkTheme() ? ColorPalette.DARK_TEXT_DISABLED : ColorPalette.LIGHT_TEXT_DISABLED;
    }

    /**
     * 获取主色调。
     *
     * @return 0xAARRGGBB 颜色，不透明；与主题无关，亮暗共用
     */
    public int getPrimaryColor() {
        return ColorPalette.PRIMARY_COLOR;
    }

    /**
     * 获取主色调的亮变体。
     *
     * @return 0xAARRGGBB 颜色，不透明；用于悬停与高亮
     */
    public int getPrimaryLightColor() {
        return ColorPalette.PRIMARY_LIGHT;
    }

    /**
     * 获取主色调的暗变体。
     *
     * @return 0xAARRGGBB 颜色，不透明；用于按下与禁用
     */
    public int getPrimaryDarkColor() {
        return ColorPalette.PRIMARY_DARK;
    }

    /**
     * 获取半透明主色调，用于悬停高亮。
     *
     * @return 0xAARRGGBB 颜色，alpha 为 51
     */
    public int getPrimaryTransparentColor() {
        return ColorPalette.PRIMARY_TRANSPARENT;
    }

    /**
     * 获取成功语义色。
     *
     * @return 0xAARRGGBB 颜色，不透明
     */
    public int getSuccessColor() {
        return ColorPalette.SUCCESS_COLOR;
    }

    /**
     * 获取警告语义色。
     *
     * @return 0xAARRGGBB 颜色，不透明
     */
    public int getWarningColor() {
        return ColorPalette.WARNING_COLOR;
    }

    /**
     * 获取错误语义色。
     *
     * @return 0xAARRGGBB 颜色，不透明
     */
    public int getErrorColor() {
        return ColorPalette.ERROR_COLOR;
    }

    /**
     * 获取信息语义色。
     *
     * @return 0xAARRGGBB 颜色，不透明
     */
    public int getInfoColor() {
        return ColorPalette.INFO_COLOR;
    }

    /**
     * 按连续质量值获取网络状态色。
     *
     * 分段规则为：大于等于 0.8 优秀，大于等于 0.6 良好，大于等于 0.4 一般，大于 0 差，
     * 其余（含 0 与负数）视为断开；超出 0 到 1 的输入按边界值处理，不抛异常。
     *
     * @param quality 网络质量，取值范围 0.0 到 1.0，越大越好
     * @return 0xAARRGGBB 颜色，不透明，对应 ColorPalette.NETWORK_ 系列常量
     */
    public int getNetworkQualityColor(float quality) {
        if (quality >= 0.8f) return ColorPalette.NETWORK_EXCELLENT;
        if (quality >= 0.6f) return ColorPalette.NETWORK_GOOD;
        if (quality >= 0.4f) return ColorPalette.NETWORK_FAIR;
        if (quality > 0.0f) return ColorPalette.NETWORK_POOR;
        return ColorPalette.NETWORK_DISCONNECTED;
    }

    /**
     * 按离散等级获取网络状态色。
     *
     * @param qualityLevel 网络质量等级，0 表示断开、4 表示优秀；
     *                     超出 0 到 4 的取值按断开处理，不抛异常
     * @return 0xAARRGGBB 颜色，不透明
     */
    public int getNetworkQualityColor(int qualityLevel) {
        switch (qualityLevel) {
            case 4: return ColorPalette.NETWORK_EXCELLENT;
            case 3: return ColorPalette.NETWORK_GOOD;
            case 2: return ColorPalette.NETWORK_FAIR;
            case 1: return ColorPalette.NETWORK_POOR;
            default: return ColorPalette.NETWORK_DISCONNECTED;
        }
    }

    // ========== 事件监听 ==========

    /**
     * 注册主题变更监听器。
     *
     * 幂等性：同一 id 重复注册会覆盖旧回调，不会产生重复通知。
     * 回调由 setTheme 或 updateSystemTheme 在调用线程内同步触发。
     *
     * @param id       监听器 ID，不能为 null，用于后续移除
     * @param listener 监听器回调，不能为 null；接收的是解析后的生效主题
     */
    public void addThemeChangeListener(String id, Consumer<ThemeType> listener) {
        themeChangeListeners.put(id, listener);
    }

    /**
     * 移除主题变更监听器。
     *
     * @param id 监听器 ID，不能为 null；未注册时静默返回
     */
    public void removeThemeChangeListener(String id) {
        themeChangeListeners.remove(id);
    }

    /**
     * 通知所有监听器主题已变更。
     *
     * 监听器回调在调用线程内依次执行；某个监听器抛异常会中断剩余监听器的通知。
     */
    private void notifyThemeChanged(ThemeType newTheme) {
        for (Consumer<ThemeType> listener : themeChangeListeners.values()) {
            listener.accept(newTheme);
        }
    }

    // ========== 系统主题检测 ==========

    /**
     * 检测系统主题设置（亮色/暗色模式）。
     *
     * 当前实现无条件假定系统为暗色，即 AUTO 默认解析为 DARK；真实平台检测尚未接入。
     */
    private void detectSystemTheme() {
        // Minecraft本身不提供系统主题检测API
        // 这里可以留空或使用平台特定方法
        // 目前假设系统使用暗色模式（适合Minecraft）
        this.isSystemDarkMode = true;
    }

    /**
     * 更新系统暗色判定。
     *
     * 仅当用户选择 AUTO 时会广播变更；即使解析结果与更新前相同也会广播，
     * 见类注释中的已知缺陷说明。
     *
     * @param isDarkMode true 表示系统处于暗色模式
     */
    public void updateSystemTheme(boolean isDarkMode) {
        this.isSystemDarkMode = isDarkMode;
        if (currentTheme == ThemeType.AUTO) {
            notifyThemeChanged(getCurrentTheme());
        }
    }

    /**
     * 返回系统是否处于暗色模式。
     *
     * 该值只影响 AUTO 的解析结果，不影响显式选定的 LIGHT 或 DARK。
     *
     * @return true 表示系统被判定为暗色
     */
    public boolean isSystemDarkMode() {
        return isSystemDarkMode;
    }

    // ========== 配置持久化 ==========

    /**
     * 序列化当前主题配置，供写入配置文件或设置项。
     *
     * 格式为「主题ID:系统暗色布尔值」，例如 dark:true；不包含版本号，格式演进需保证向后兼容。
     *
     * @return 配置字符串，永不为 null，且可被 loadConfig 原样解析回等价状态
     */
    public String saveConfig() {
        return currentTheme.getId() + ":" + isSystemDarkMode;
    }

    /**
     * 从配置字符串恢复主题状态。
     *
     * 解析规则：第一段按 ThemeType.fromId 解析主题（未知值回退 DARK），第二段按
     * Boolean.parseBoolean 解析系统暗色标记（非 "true" 一律为 false）；段落缺失时保留默认值。
     * 非法输入只做静默回退，不抛异常，也不返回失败标志。
     *
     * @param config 配置字符串，允许为 null 或空字符串，此时不做任何修改
     */
    public void loadConfig(String config) {
        if (config == null || config.isEmpty()) {
            return;
        }

        String[] parts = config.split(":");
        if (parts.length >= 1) {
            setTheme(parts[0]);
        }
        if (parts.length >= 2) {
            isSystemDarkMode = Boolean.parseBoolean(parts[1]);
        }
    }

    /**
     * 恢复默认主题并重新检测系统主题。
     *
     * 会按 setTheme 的规则广播一次主题变更（若生效主题确实改变）。
     */
    public void reset() {
        setTheme(ThemeType.DARK);
        detectSystemTheme();
    }
}
