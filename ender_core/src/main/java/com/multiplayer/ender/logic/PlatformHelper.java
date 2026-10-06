/*
 * 本文件属于 EnderOnline 核心逻辑层。
 *
 * 职责：平台判定（操作系统、CPU 架构）与平台相关的文件名。
 *
 * 端口工具已全部迁至 {@link PortAllocator}：三处重复实现归并为唯一入口后，本类不再持有端口逻辑。
 */
package com.multiplayer.ender.logic;

import java.util.Locale;

/**
 * 平台判定与平台相关命名的工具类。
 *
 * 本类只做「字符串层面的属性推断」，不探测真实硬件，也不做能力的运行时验证。
 * 调用方在拿到判定结果后仍需自行兜底：所有枚举都包含 UNKNOWN，任何一次判定都可能落回它。
 *
 * 设计约束：
 * 1. getOS / getArch 的结果在进程生命周期内被视为常量，但实现每次都会重新读系统属性，
 *    不缓存也不加锁，因此不允许把它们当作可发布的稳定状态。
 * 2. 判定依据仅为 os.name / os.arch / java.vendor 等字符串，属性可被启动参数覆盖，
 *    不可信环境（如不受控的 JVM 参数）下结果可被伪造。
 * 3. getDownloadFilename / getExecutableName 中的版本号是编译期常量，升级 EasyTier 时必须同步修改。
 *
 * 线程安全性：本类无实例状态，所有方法都是纯计算的静态方法，可被多线程并发调用。
 *
 * @since 1.0
 */
public class PlatformHelper {

    /**
     * 操作系统类型。
     *
     * 判定顺序为厂商属性、vm 厂商属性、os.name 子串匹配。
     * 所有分支都不命中时返回 UNKNOWN，UNKNOWN 不是错误而是「未识别」，调用方必须能处理。
     */
    public enum OS {

        /** 命中 os.name 含 win 的分支。 */
        WINDOWS,

        /** 命中 nix / nux / aix 子串，非 Android 的类 Unix 系统。 */
        LINUX,

        /** 命中 os.name 含 mac 的分支。 */
        MACOS,

        /** java.vendor / java.vm.vendor / os.name 任一含 android。优先级高于 LINUX。 */
        ANDROID,

        /** 以上均未命中，属于未识别而非错误。 */
        UNKNOWN
    }

    /**
     * CPU 架构类型。
     *
     * NOTE: 仅区分 64 位 x86 与 64 位 ARM，32 位 ARM（armv7l / arm）会落回 UNKNOWN，
     * 这是已知判定缺陷，见 claude_docs/05-cross-platform-plan.md。
     */
    public enum Arch {

        /** 命中 amd64 或 x86_64。 */
        X86_64,

        /** 命中 aarch64 或任意含 arm64 的取值。 */
        ARM64,

        /** 未识别，32 位 ARM 也会落到此处。 */
        UNKNOWN
    }

    /**
     * 获取当前操作系统类型。
     *
     * Android 判定优先于 Linux：Android 的 os.name 通常也是 Linux，若顺序颠倒会误判为桌面 Linux。
     *
     * XXX: Android 的真实识别依赖 java.vendor / java.vm.vendor 含 android，
     * 部分 PojavLauncher / Amethyst 构建不设置该属性，此时会被判为 LINUX，上线前必须回归验证。
     *
     * @return 操作系统枚举值，未识别时返回 OS.UNKNOWN，永不为 null
     */
    public static OS getOS() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String vendor = System.getProperty("java.vendor", "").toLowerCase(Locale.ROOT);
        String vmVendor = System.getProperty("java.vm.vendor", "").toLowerCase(Locale.ROOT);
        
        if (vendor.contains("android") || vmVendor.contains("android") || osName.contains("android")) {
            return OS.ANDROID;
        }
        
        if (osName.contains("win")) {
            return OS.WINDOWS;
        } else if (osName.contains("mac")) {
            return OS.MACOS;
        } else if (osName.contains("nix") || osName.contains("nux") || osName.contains("aix")) {
            return OS.LINUX;
        }
        
        return OS.UNKNOWN;
    }

    /**
     * 获取当前 CPU 架构类型。
     *
     * XXX: System.getProperty("os.arch") 未做空值保护，属性被显式清空时会抛 NullPointerException；
     * 32 位 ARM 会被判为 UNKNOWN 而非 ARM64，需要真机验证后再决定是否补齐。
     *
     * @return CPU 架构枚举值，未识别（含 32 位 ARM）时返回 Arch.UNKNOWN，永不为 null
     */
    public static Arch getArch() {
        String osArch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        
        if (osArch.equals("amd64") || osArch.equals("x86_64")) {
            return Arch.X86_64;
        } else if (osArch.equals("aarch64") || osArch.contains("arm64")) {
            return Arch.ARM64;
        }
        
        return Arch.UNKNOWN;
    }

    /**
     * 根据平台获取对应的下载文件名。
     *
     * NOTE: 文件名中的 v2.4.5 是编译期写死的版本号，与 EasyTierDownloader 的下载地址必须一致。
     * version 参数当前未参与文件名拼接，仅为保持签名稳定而保留。
     *
     * @param version 需要下载的版本号，允许为 null，当前实现不读取该参数
     * @return 对应平台的压缩包文件名；平台或架构不受支持时返回 null，调用方必须判空
     */
    public static String getDownloadFilename(String version) {
        OS os = getOS();
        Arch arch = getArch();
        
        if (os == OS.WINDOWS) {
            if (arch == Arch.X86_64) return "easytier-windows-x86_64-v2.4.5.zip";
            return "easytier-windows-i686-v2.4.5.zip";
        } else if (os == OS.LINUX || os == OS.ANDROID) {
            if (arch == Arch.X86_64) return "easytier-linux-x86_64-v2.4.5.zip";
            if (arch == Arch.ARM64) return "easytier-linux-aarch64-v2.4.5.zip";
        } else if (os == OS.MACOS) {
            if (arch == Arch.X86_64) return "easytier-macos-x86_64-v2.4.5.zip";
            if (arch == Arch.ARM64) return "easytier-macos-aarch64-v2.4.5.zip";
        }
        return null;
    }

    /**
     * 根据平台获取对应的可执行文件名。
     *
     * 只有 Windows 需要 .exe 扩展名，其余平台统一返回无扩展名的文件名。
     *
     * @param version 版本号，允许为 null，当前实现未使用该参数
     * @return 可执行文件名（Windows 含 .exe 扩展名），永不为 null
     */
    public static String getExecutableName(String version) {
        OS os = getOS();
        if (os == OS.WINDOWS) {
            return "easytier-core.exe";
        }
        return "easytier-core"; 
    }

    /**
     * 获取默认版本（2.4.5）的可执行文件名。
     *
     * @return 可执行文件名，永不为 null
     */
    public static String getExecutableName() {
        return getExecutableName("2.4.5");
    }

}
