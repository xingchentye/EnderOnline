/*
 * 本文件属于 EnderOnline 逻辑层。
 *
 * 职责：全模块唯一的端口探测与分配实现。
 *
 * 合并前存在三份逐字重复的实现（PlatformHelper、EnderApiClient、EasyTierManager），
 * 且失败语义各不相同：PlatformHelper 与 EasyTierManager 返回 -1，
 * EnderApiClient 返回首选端口作为兜底值。本类统一为「失败返回 -1 或兜底值」，由调用方显式选择，
 * 不再各自复制一份探测代码。
 */
package com.multiplayer.ender.logic;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * 端口探测与分配工具。
 *
 * 本类是 P3 归并三处重复实现后的唯一入口，取代了
 * {@code PlatformHelper#findAvailablePort}、{@code EnderApiClient} 的私有端口方法
 * 以及 {@code EasyTierManager} 的私有端口方法。
 *
 * 设计约束：
 * 1. 全部方法为纯静态，不持有任何状态，可安全并发调用。
 * 2. 返回的是「此刻空闲」的端口，不是「已为本进程保留」的端口。
 * 3. 失败语义有两种，调用方必须显式选择：
 *    需要区分失败时用 {@link #findAvailablePort()}（返回 -1），
 *    需要有可用的非负值时用 {@link #findAvailablePortOr(int)}（返回兜底值）。
 *
 * 已知缺陷（记录，不在本类中掩盖）：{@link #findAvailablePort()} 采用
 * 「绑定端口 0 取得号码 → 立即关闭监听 → 返回号码」的方式，在返回与调用方真正 bind 之间
 * 存在时间窗口（TOCTOU），其他进程可能抢先占用。返回值因此是**建议端口**。
 * {@link #isPortAvailable(int)} 同理：返回 true 只说明「探测瞬间」可绑定。
 *
 * 线程安全性：无共享可变状态，方法可并发调用；每次调用各自建立独立的探测套接字。
 *
 * @see PlatformHelper
 */
public final class PortAllocator {

    /** 私有构造函数，防止实例化。 */
    private PortAllocator() {
    }

    /**
     * 查找一个建议可用的本地端口。
     *
     * 实现方式是绑定端口 0 让内核分配、读取端口号、随即关闭监听。
     * 返回的是「当前空闲」的端口，而不是「已为本进程保留」的端口。
     *
     * @return 端口号，取值范围 1 到 65535；分配失败（如无可用端口或被安全策略拒绝）时返回 -1
     */
    public static int findAvailablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * 查找一个建议可用的本地端口，失败时返回调用方给定的兜底值。
     *
     * 供「必须拿到一个非负端口号才能继续」的调用方使用，避免它们在调用点多写一次
     * `port > 0 ? port : FALLBACK` 判断。兜底值本身不做可用性校验——它只是让流程能继续，
     * 调用方仍需为真正的绑定失败负责。
     *
     * @param fallback 分配失败时返回的端口号，取值 1 到 65535
     * @return 找到时返回空闲端口；失败时返回 fallback
     */
    public static int findAvailablePortOr(int fallback) {
        int port = findAvailablePort();
        return port > 0 ? port : fallback;
    }

    /**
     * 探测指定端口当前是否可绑定。
     *
     * 实现方式是尝试绑定后立即释放。先做取值校验：{@code ServerSocket} 会接受端口 0
     * （内核自动分配临时端口）并对超出 1..65535 的取值抛出 IllegalArgumentException，
     * 两种行为都不是调用方想要的「该端口可用吗」，因此在进入绑定前挡掉。
     *
     * @param port 端口号，取值 1 到 65535
     * @return 可绑定返回 true；端口被占用、无权限或取值非法返回 false
     */
    public static boolean isPortAvailable(int port) {
        if (port <= 0 || port > 65535) {
            return false;
        }
        try (ServerSocket socket = new ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 优先返回首选端口，被占用时退化为内核分配的随机端口。
     *
     * 首选端口合法且可绑定即直接返回；取值非法（如 0）或被占用时，
     * 等价于 {@link #findAvailablePortOr(int)}。兜底值用于内核分配也失败（极端情况）时
     * 仍给出可用的非负值。
     *
     * @param preferred 首选端口，取值 1 到 65535；超出范围视为不可用
     * @param fallback 分配失败时返回的端口号，取值 1 到 65535
     * @return 优先返回 preferred；不可用时返回随机空闲端口；均失败时返回 fallback
     */
    public static int pickAvailablePort(int preferred, int fallback) {
        if (preferred > 0 && preferred <= 65535 && isPortAvailable(preferred)) {
            return preferred;
        }
        return findAvailablePortOr(fallback);
    }
}
