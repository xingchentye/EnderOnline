package com.multiplayer.ender.logic;

import java.util.Locale;

/**
 * 平台辅助工具类。
 * 用于检测操作系统类型、CPU架构以及提供平台相关的文件名和端口操作。
 *
 * @author Ender Developer
 * @version 1.0
 * @since 1.0
 */
public class PlatformHelper {

    /**
     * 操作系统枚举。
     */
    public enum OS {
        WINDOWS, LINUX, MACOS, ANDROID, UNKNOWN
    }

    /**
     * CPU架构枚举。
     */
    public enum Arch {
        X86_64, ARM64, UNKNOWN
    }

    /**
     * 获取当前操作系统类型。
     *
     * @return 当前检测到的操作系统枚举值
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
     * 获取当前CPU架构类型。
     *
     * @return 当前检测到的CPU架构枚举值
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
     * @param version 需要下载的版本号
     * @return 对应平台的压缩包文件名，如果不支持该平台则返回null
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
     * @param version 版本号（当前未使用）
     * @return 可执行文件名（包含扩展名）
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
     * @return 可执行文件名
     */
    public static String getExecutableName() {
        return getExecutableName("2.4.5");
    }

    /**
     * 查找一个可用的本地端口。
     *
     * @return 可用的端口号，如果查找失败则返回-1
     */
    public static int findAvailablePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            return -1;
        }
    }
}

