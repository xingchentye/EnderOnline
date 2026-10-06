/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：mod 生命周期的统一收口点，负责注册关闭钩子并收敛线程池与后端进程。
 */
package com.endercore.core.comm;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * mod 生命周期入口。
 *
 * 本类回答一个此前没有答案的问题：**mod 卸载或 JVM 退出时，谁负责收拾残局？**
 * 现状是 22 处各自 `new Thread(ProcessLauncher::stop, "Ender-Stopper").start()`，
 * 线程池则完全没有关闭路径。本类把这件事收敛成一次调用。
 *
 * 设计约束：
 * 1. {@link #shutdown()} 必须幂等且可从任意线程调用。它会同时被 JVM shutdown hook 与
 * 各加载器的 mod 卸载事件触发，两者顺序不确定。
 * 2. 关闭顺序为：先停外部进程，再关线程池。反过来会让仍在用线程池的进程停止逻辑失败。
 * 3. 关闭过程不得抛异常：shutdown hook 中抛出异常会中断 JVM 的退出流程。
 *
 * 线程安全性：所有状态由 AtomicBoolean 保护。
 *
 * @since 1.0
 * @see EnderExecutors
 */
public final class EnderLifecycle {

    /** 默认等待在途任务的时间。超过后强制中断。 */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    /** 是否已执行关闭，保护幂等性。 */
    private static final AtomicBoolean CLOSED = new AtomicBoolean(false);

    /** 注册的关闭动作，按注册顺序在关闭时执行。 */
    private static final java.util.List<Runnable> HOOKS =
            java.util.Collections.synchronizedList(new java.util.ArrayList<Runnable>());

    /** JVM 关闭钩子是否已注册。 */
    private static final AtomicBoolean JVM_HOOK_REGISTERED = new AtomicBoolean(false);

    private EnderLifecycle() {
    }

    /**
     * 注册一个关闭动作。
     *
     * 用于让上层（如后端进程服务）把自己的清理逻辑挂进统一关闭流程，
     * 而不是各自去创建 `Ender-Stopper` 线程。
     *
     * @param hook 关闭动作，不能为 null；异常会被吞掉并记录，不中断后续动作
     * @throws NullPointerException 当 hook 为 null 时抛出
     */
    public static void onShutdown(Runnable hook) {
        if (hook == null) {
            throw new NullPointerException("hook");
        }
        HOOKS.add(hook);
        ensureJvmHookRegistered();
    }

    /**
     * 确保 JVM 关闭钩子已注册。
     *
     * 懒注册而不是在静态块中注册：只有真正有清理需求时才挂钩子，
     * 避免在单元测试或纯工具调用场景下无谓地影响 JVM 退出流程。
     */
    private static void ensureJvmHookRegistered() {
        if (!JVM_HOOK_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(EnderLifecycle::shutdown, "Ender-Shutdown"));
        } catch (IllegalStateException e) {
            // ignore-reason: JVM 已在关闭中，注册钩子必然失败且无可补救——
            // 关闭会由正在进行的 shutdown 序列完成。这里只复位标记以便后续调用重试。
            JVM_HOOK_REGISTERED.set(false);
            System.err.println("[ender] cannot register shutdown hook, JVM already shutting down: " + e);
        }
    }

    /**
     * 执行统一关闭。
     *
     * 顺序：先跑已注册的关闭动作（停外部进程），再关闭线程池。
     * 幂等：第二次调用立即返回。
     *
     * @return 实际执行了关闭时返回 true；已关闭过则返回 false
     */
    public static boolean shutdown() {
        return shutdown(DEFAULT_TIMEOUT);
    }

    /**
     * 执行统一关闭并指定等待超时。
     *
     * @param timeout 等待在途任务的总超时；null 表示不等待
     * @return 实际执行了关闭时返回 true；已关闭过则返回 false
     */
    public static boolean shutdown(Duration timeout) {
        if (!CLOSED.compareAndSet(false, true)) {
            return false;
        }

        // 先停外部进程：它们可能仍在使用线程池读取输出
        java.util.List<Runnable> snapshot;
        synchronized (HOOKS) {
            snapshot = new java.util.ArrayList<Runnable>(HOOKS);
        }
        for (Runnable hook : snapshot) {
            runHookSafely(hook);
        }

        // 再关线程池
        EnderExecutors.shutdownGracefully(timeout);
        return true;
    }

    /**
     * 执行单个关闭动作，吞掉并记录其异常。
     *
     * 单独成方法而不是在循环里内联 try/catch 的理由：本方法会被 JVM shutdown hook 调用，
     * 抛出异常会干扰 JVM 退出；同时单个动作失败不应阻断其余清理。
     *
     * @param hook 关闭动作，不能为 null
     */
    private static void runHookSafely(Runnable hook) {
        try {
            hook.run();
        } catch (RuntimeException e) {
            // ignore-reason: 关闭流程必须跑完，重抛会中断后续清理，
            // 留下未停止的后端进程与未关闭的线程池；此处记录后继续执行剩余动作。
            System.err.println("[ender] shutdown hook failed: " + e);
        }
    }

    /**
     * 异步触发关闭。
     *
     * 供 UI 事件处理器使用：关闭会等待后端进程退出，同步执行会卡住渲染线程。
     *
     * 这里刻意新建一条一次性线程，而不是提交到 {@link EnderExecutors} 的任何池：
     * 关闭流程本身要关闭这些池，把关闭任务提交给即将被关闭的池会造成自死锁。
     * 该线程是 daemon，即使关闭流程卡住也不会阻止 JVM 退出。
     *
     * @return 触发后立即返回；重复调用无副作用（关闭本身的幂等性由 shutdown 保证）
     */
    public static void requestShutdownAsync() {
        Thread thread = new Thread(EnderLifecycle::shutdown, "Ender-Shutdown-Trigger");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 是否已完成关闭。
     *
     * @return true 表示关闭流程已执行
     */
    public static boolean isClosed() {
        return CLOSED.get();
    }
}
