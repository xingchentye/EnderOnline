/*
 * 本文件属于 EnderOnline 客户端界面层。
 *
 * 职责：按运行时探测到的加载器平台创建并缓存 PlatformRenderer 实例。
 *
 * 本类用反射按类名探测平台与实现类，属于 ADR-03 明确禁止的「运行时发现」，
 * 已被判定为待删除目标：平台差异必须改为编译期适配接口加入口点注入。
 */
package com.multiplayer.ender.client.ui.platform;

/**
 * 平台渲染器工厂，负责平台探测以及渲染器实例的创建与缓存。
 *
 * 探测方式与待删原因：
 * 本类通过 Class.forName 依次探测各加载器的标记类来判定平台，再用同样的方式加载
 * 加载器模块中的 XxxRendererImpl。这种运行时发现会遮蔽真实签名错误，并使
 * 「平台差异只存在于适配接口实现」的约束失效，因此 ADR-03 判定该机制为待删除目标。
 * 目标形态：各加载器入口点在启动时把实现注入适配接口，core 侧不再出现任何类名探测。
 *
 * 设计约束：
 * 1. 平台判定结果与渲染器实例都是进程级缓存，同一进程内最多探测一次。
 * 2. 平台标识字符串（"fabric"、"forge"、"neoforge"）是对外契约，被 isXxx 系列方法与
 *    调用方依赖，改动必须同步所有使用点。
 * 3. 探测顺序为 fabric 到 forge 到 neoforge，先命中的标记类生效；Fabric 支持已终止
 *    （ADR-00/ADR-14），"fabric" 分支只为兼容旧构建保留。
 * 4. 找不到任何标记类时抛 IllegalStateException；平台可识别但实现类缺失时降级为
 *    本包的存根实现（FabricRenderer、ForgeRenderer、NeoForgeRenderer），
 *    其他加载失败包装为 RuntimeException 抛出。
 * 5. 本文件位于 ender_core 的 client/ui 包下，按 ADR-04 需随 UI 抽象层一起迁出 core。
 *
 * 线程安全性：非线程安全。instance 与 detectedPlatform 是非 final 的静态可变字段，
 * 惰性初始化也没有同步（违反 ADR-05「静态只允许常量」），并发首次调用可能重复创建实例
 * 或读到尚未完整构造的对象。当前只被客户端渲染线程访问，迁移前禁止从其他线程调用。
 *
 * TODO(P3, 2026-10-06): 移除反射发现，改为适配接口实现，见 docs/00-decisions-and-open-questions.md 的 ADR-03
 * FIXME(P3, 2026-10-06): 静态可变状态缺同步且非 final，违反 ADR-05，需随守卫基线收敛
 * FIXME(P3, 2026-10-06): 异常消息为中文字符串字面量，违反 ADR-15 的「字符串用英文」
 *
 * @since 1.0
 * @see PlatformRenderer
 */
public final class PlatformRenderers {

    /**
     * 进程内缓存的渲染器实例。
     *
     * 允许为 null，表示尚未创建；必须在渲染线程首次访问后才被赋值，不做同步。
     */
    private static PlatformRenderer instance;

    /** 探测到的平台标识，取值 "fabric"、"forge"、"neoforge"；允许为 null，表示尚未探测。 */
    private static String detectedPlatform;

    private PlatformRenderers() {
        // 工具类，禁止实例化
    }

    /**
     * 获取当前平台的渲染器实例。
     *
     * 首次调用会触发平台探测并创建实现；之后返回同一实例。
     *
     * 幂等性：对同一平台重复调用返回同一对象，但并发调用不保证只创建一次。
     *
     * @return 平台渲染器实例，永不为 null
     * @throws IllegalStateException 当无法检测到任何受支持的加载器平台时抛出
     */
    public static PlatformRenderer getInstance() {
        if (instance == null) {
            instance = createPlatformRenderer();
        }
        return instance;
    }

    /**
     * 获取当前检测到的平台名称。
     *
     * 探测只做一次，结果被缓存；平台标识是稳定的字符串常量，不是本地化文案。
     *
     * @return 平台名称（"fabric"、"forge"、"neoforge"），永不为 null
     * @throws IllegalStateException 当无法检测到任何受支持的加载器平台时抛出
     */
    public static String getPlatformName() {
        if (detectedPlatform == null) {
            detectPlatform();
        }
        return detectedPlatform;
    }

    /**
     * 探测当前运行的加载器平台并写入 detectedPlatform。
     *
     * 按 fabric、forge、neoforge 的顺序逐个尝试加载标记类，先成功者生效；
     * 三者都不存在时抛 IllegalStateException，且不修改 detectedPlatform。
     */
    private static void detectPlatform() {
        try {
            // 尝试加载Fabric特定类
            Class.forName("net.fabricmc.api.Environment");
            detectedPlatform = "fabric";
        } catch (ClassNotFoundException e1) {
            try {
                // 尝试加载Forge特定类
                Class.forName("net.minecraftforge.api.distmarker.Dist");
                detectedPlatform = "forge";
            } catch (ClassNotFoundException e2) {
                try {
                    // 尝试加载NeoForge特定类
                    Class.forName("net.neoforged.api.distmarker.Dist");
                    detectedPlatform = "neoforge";
                } catch (ClassNotFoundException e3) {
                    throw new IllegalStateException(
                        "无法检测到支持的Mod加载器平台。请确保在Fabric、Forge或NeoForge环境中运行。"
                    );
                }
            }
        }
    }

    /**
     * 为当前平台创建渲染器实例。
     *
     * 先确保平台已探测，再按平台标识加载对应实现类；NeoForge 实现缺失时回退到 Forge 实现，
     * 全部候选实现类都不存在时降级为本包的存根实现。
     */
    private static PlatformRenderer createPlatformRenderer() {
        detectPlatform();

        try {
            switch (detectedPlatform) {
                case "fabric":
                    // 尝试加载fabric模块中的真正实现
                    Class<?> fabricClass = Class.forName("com.multiplayer.ender.client.ui.platform.FabricRendererImpl");
                    return (PlatformRenderer) fabricClass.getDeclaredConstructor().newInstance();
                case "forge":
                    // 尝试加载forge模块中的真正实现
                    Class<?> forgeClass = Class.forName("com.multiplayer.ender.client.ui.platform.ForgeRendererImpl");
                    return (PlatformRenderer) forgeClass.getDeclaredConstructor().newInstance();
                case "neoforge":
                    // 尝试加载neoforge模块中的真正实现（或使用ForgeRendererImpl）
                    try {
                        Class<?> neoForgeClass = Class.forName("com.multiplayer.ender.client.ui.platform.NeoForgeRendererImpl");
                        return (PlatformRenderer) neoForgeClass.getDeclaredConstructor().newInstance();
                    } catch (ClassNotFoundException e) {
                        // 回退到ForgeRendererImpl
                        Class<?> forgeClass2 = Class.forName("com.multiplayer.ender.client.ui.platform.ForgeRendererImpl");
                        return (PlatformRenderer) forgeClass2.getDeclaredConstructor().newInstance();
                    }
                default:
                    throw new IllegalStateException("不支持的平台: " + detectedPlatform);
            }
        } catch (ClassNotFoundException e) {
            // 如果找不到平台特定实现，使用存根版本
            switch (detectedPlatform) {
                case "fabric":
                    return new FabricRenderer();
                case "forge":
                    return new ForgeRenderer();
                case "neoforge":
                    return new NeoForgeRenderer();
                default:
                    throw new IllegalStateException("不支持的平台: " + detectedPlatform);
            }
        } catch (Exception e) {
            throw new RuntimeException("创建平台渲染器失败", e);
        }
    }

    /**
     * 判断当前平台是否为 Fabric。
     *
     * @return 平台标识等于 "fabric" 时返回 true；Fabric 支持已终止，正常构建恒为 false
     */
    public static boolean isFabric() {
        return "fabric".equals(getPlatformName());
    }

    /**
     * 判断当前平台是否为 Forge。
     *
     * @return 平台标识等于 "forge" 时返回 true
     */
    public static boolean isForge() {
        return "forge".equals(getPlatformName());
    }

    /**
     * 判断当前平台是否为 NeoForge。
     *
     * @return 平台标识等于 "neoforge" 时返回 true
     */
    public static boolean isNeoForge() {
        return "neoforge".equals(getPlatformName());
    }

    /**
     * 清空缓存的渲染器实例与平台判定结果，下次调用会重新探测。
     *
     * 仅供测试在同一进程内切换平台使用；不会关闭或清理已创建的渲染器。
     * 非线程安全，禁止与应用运行期的读取并发调用。
     */
    public static void reset() {
        instance = null;
        detectedPlatform = null;
    }
}
