/*
 * 本文件属于 EnderOnline 核心逻辑层。
 *
 * 职责：外部进程启动的兼容门面，把旧调用点转发给 EasyTierManager。
 */
package com.multiplayer.ender.logic;

import com.endercore.core.easytier.EasyTierManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 外部进程启动器（兼容门面）。
 *
 * 本类自身不创建、不持有、不回收任何进程：真正的进程生命周期由 EasyTierManager 单例独占。
 * 这里保留的只是为了不破坏既有调用点而存在的转发层。
 *
 * 进程生命周期所有权：
 * 1. 进程的启动、停止与状态查询唯一归 EasyTierManager，本类只做转发，不得引入第二份进程句柄。
 * 2. launch 传入的 executablePath 与 workDir 被忽略，实际路径由 EasyTierManager 决定；
 *    调用方不要依赖这两个参数生效。
 * 3. stop 会终止 EasyTierManager 持有的全局进程，属于进程级副作用，不是本类实例的局部操作。
 *
 * 设计约束：
 * 1. isRunning / getStatus / isCrashed 当前返回编译期常量，不反映真实进程状态，
 *    任何依赖它们做控制流的代码都会得到错误结论。
 * 2. Android（PojavLauncher / Amethyst）上的外部进程调用需要设备侧已运行 EasyTier，
 *    平台限制见 docs/05-cross-platform-plan.md。
 *
 * 线程安全性：本类无实例状态；EasyTierManager 单例是唯一可变态的持有者，并发语义由它保证。
 *
 * TODO(P3, 2026-10-06): 让 isRunning / getStatus / isCrashed 转发到 EasyTierManager 的真实状态。
 *
 * @since 1.0
 * @see EasyTierManager
 */
public class ProcessLauncher {

    /** 本类日志记录器，永不为 null，由 SLF4J 在类初始化时绑定。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessLauncher.class);
    
    /**
     * 进程状态。
     *
     * 合法流转路径：
     * STOPPED 到 STARTING 到 RUNNING；STARTING 或 RUNNING 均可因异常流转到 CRASHED；
     * CRASHED 与 STOPPED 均可再次流转到 STARTING 以重启。
     * 终态只有 STOPPED，CRASHED 属于可恢复的中间态。
     */
    public enum ProcessStatus {

        /** 未运行，初始状态，可由 RUNNING 正常停止或 CRASHED 后清理到达。 */
        STOPPED,

        /** 正在拉起进程，尚未确认就绪，超时或异常后流转到 CRASHED。 */
        STARTING,

        /** 进程已就绪，可接受 start / stop 之外的查询。 */
        RUNNING,

        /** 进程异常退出，可重试拉起；与 STOPPED 的区别是「非预期终止」。 */
        CRASHED
    }

    /**
     * 检查进程是否正在运行。
     *
     * FIXME(P3, 2026-10-06): 当前实现无条件返回 true，与真实进程状态无关，
     * 调用方据此判断「已在运行」会跳过必要的启动步骤。接入真实状态前不得用于控制流。
     *
     * @return 当前实现恒为 true，不代表进程真的在运行
     */
    public static boolean isRunning() {
        
        return true; 
    }

    /**
     * 获取当前进程状态。
     *
     * FIXME(P3, 2026-10-06): 当前实现恒返回 RUNNING，与真实状态无关，
     * 会让上层误判「首次启动无需拉起进程」。
     *
     * @return 当前实现恒为 ProcessStatus.RUNNING，不代表真实状态
     */
    public static ProcessStatus getStatus() {
        return ProcessStatus.RUNNING;
    }

    /**
     * 检查进程是否已崩溃。
     *
     * FIXME(P3, 2026-10-06): 当前实现恒返回 false，崩溃永远不会被上报，
     * 依赖本方法触发重启的逻辑目前是死代码。
     *
     * @return 当前实现恒为 false
     */
    public static boolean isCrashed() {
        return false;
    }

    /**
     * 启动 EasyTier 进程（无输出处理）。
     *
     * 等价于把 outputHandler 传 null 的三参重载，子进程输出不会回传到调用方。
     *
     * @param executablePath 可执行文件路径，允许为 null，当前实现忽略该参数
     * @param workDir 工作目录，允许为 null，当前实现忽略该参数
     * @param args 启动参数，允许为空数组；参数内容直接透传给 EasyTier
     * @throws IOException 当 EasyTier 初始化或启动失败时抛出，原始异常作为 cause 保留
     */
    public static void launch(Path executablePath, Path workDir, String... args) throws IOException {
        launch(executablePath, workDir, null, args);
    }

    /**
     * 启动 EasyTier 进程。
     *
     * 阻塞语义：方法内部会等待 EasyTierManager 初始化完成（join）后才返回，
     * 因此调用线程会被占用，禁止在渲染线程或事件线程上直接调用。
     *
     * 幂等性：本方法不幂等，重复调用会向 EasyTierManager 重复下发启动请求。
     *
     * @param executablePath 可执行文件路径，允许为 null，当前实现忽略该参数
     * @param workDir 工作目录，允许为 null，当前实现忽略该参数
     * @param outputHandler 标准输出处理函数，允许为 null，为 null 时不接收子进程输出
     * @param args 启动参数，允许为空数组
     * @throws IOException 当初始化或启动过程中抛出任何异常时抛出，原始异常作为 cause 保留
     */
    public static void launch(Path executablePath, Path workDir, java.util.function.Consumer<String> outputHandler, String... args) throws IOException {
        LOGGER.info("ProcessLauncher (Compat) launching EasyTier...");
        try {
            EasyTierManager.getInstance().initialize().join();
            EasyTierManager.getInstance().start(args);
        } catch (Exception e) {
            throw new IOException("Failed to start EasyTier", e);
        }
    }

    /**
     * 停止正在运行的进程。
     *
     * 停止的是 EasyTierManager 持有的全局进程，属于进程级副作用，会同时影响本 JVM 内其它调用方。
     * 停止失败不抛出异常，只记录 error 日志，调用方无法从返回值判断停止是否成功。
     *
     * 幂等性：本方法幂等，对已停止的进程重复调用效果相同。
     */
    public static void stop() {
        LOGGER.info("Stopping process...");
        try {
            EasyTierManager.getInstance().stop();
        } catch (Exception e) {
            LOGGER.error("Failed to stop process", e);
        }
    }
}
