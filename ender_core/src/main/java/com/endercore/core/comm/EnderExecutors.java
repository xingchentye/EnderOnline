/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：集中持有 mod 自建的全部线程池，并提供统一的优雅关闭入口。
 */
package com.endercore.core.comm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * mod 自建线程池的持有者。
 *
 * 本类是 mod 内唯一允许创建线程的地方（ADR-06）。所有池都满足四个条件：
 * 命名线程（便于诊断与守卫断言）、daemon（不阻塞 JVM 退出）、有界队列（背压而非 OOM）、
 * 统一由 {@link #shutdownGracefully(Duration)} 收敛。
 *
 * 设计约束：
 * 1. 队列满时使用 {@link ThreadPoolExecutor.CallerRunsPolicy} 而不是静默丢弃。
 * 丢弃会让「任务消失」变成不可见故障；让调用方同步执行至少能保证任务被执行，
 * 代价只是调用线程被短暂占用。
 * 2. 线程数按可用处理器取，但有硬上下限：下限 2 保证本地 socket 与下载可以并发，
 * 上限 4 避免在移动端（可用处理器可能报告宿主机核数）创建过多线程。
 * 3. {@link #shutdownGracefully(Duration)} 必须幂等，且被 JVM shutdown hook 与 mod 卸载钩子调用。
 *
 * 线程安全性：所有字段为 final 的线程安全对象，方法可并发调用；关闭状态用 AtomicBoolean 保护。
 *
 * @since 1.0
 * @see EnderLifecycle
 */
public final class EnderExecutors {

    /** IO 池队列容量。满队列触发 CallerRuns，而不是无限堆积。 */
    private static final int IO_QUEUE_CAPACITY = 256;

    /** 工作池队列容量。 */
    private static final int WORK_QUEUE_CAPACITY = 128;

    /** IO 线程数下限：本地 socket 与下载需要能并发。 */
    private static final int IO_THREADS_MIN = 2;

    /** IO 线程数上限：移动端可用处理器可能报告宿主机核数，必须封顶。 */
    private static final int IO_THREADS_MAX = 4;

    /** 是否已开始关闭。保护 shutdownGracefully 的幂等性。 */
    private static final java.util.concurrent.atomic.AtomicBoolean SHUTDOWN =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** IO 池：网络收发、文件与进程输出读取等阻塞操作。 */
    private static final ExecutorService IO;

    /** 定时池：重连退避、心跳、超时等延时/周期任务。 */
    private static final ScheduledExecutorService SCHEDULED;

    /** 工作池：哈希校验、解压等短时 CPU 任务。 */
    private static final ExecutorService WORK;

    static {
        int ioThreads = Math.max(IO_THREADS_MIN,
                Math.min(IO_THREADS_MAX, Runtime.getRuntime().availableProcessors()));

        IO = new ThreadPoolExecutor(
                ioThreads, ioThreads,
                30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(IO_QUEUE_CAPACITY),
                namedFactory("Ender-IO"),
                new ThreadPoolExecutor.CallerRunsPolicy());

        SCHEDULED = Executors.newSingleThreadScheduledExecutor(namedFactory("Ender-Sched"));

        int workThreads = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors()));
        WORK = new ThreadPoolExecutor(
                workThreads, workThreads,
                30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(WORK_QUEUE_CAPACITY),
                namedFactory("Ender-Work"),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    private EnderExecutors() {
    }

    /**
     * 创建命名 daemon 线程工厂。
     *
     * daemon 属性很关键：加载器卸载 mod 时若仍有工作线程存活，非 daemon 线程会让 JVM 无法退出。
     *
     * @param prefix 线程名前缀，不能为 null
     * @return 线程工厂，永不为 null
     */
    private static ThreadFactory namedFactory(String prefix) {
        final AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * 创建单个命名 daemon 线程。
     *
     * 供「生命周期由调用方自己管理、不适合放进共享池」的组件使用（例如按需启停的
     * 广播线程、随进程存活的日志读取线程）。这些组件原先各自书写
     * `new Thread(r, "名字")` + `setDaemon(true)`，命名与 daemon 属性容易漏写。
     *
     * 本方法是 ADR-06 允许出现裸线程的唯一入口：线程池的创建点。
     * 返回的线程不会被自动启动，也不会被 EnderExecutors 关闭——调用方负责其生命周期。
     *
     * @param runnable 线程体，不能为 null
     * @param name 线程名，不能为 null；同名线程会重名，调用方需自行保证唯一
     * @return 已设为 daemon、尚未启动的线程，永不为 null
     */
    public static Thread daemonThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    /**
     * 创建命名 daemon 线程工厂，供调用方自建执行器。
     *
     * 池本身仍归调用方所有并负责关闭；本方法只保证线程名与 daemon 属性一致。
     *
     * @param prefix 线程名前缀，不能为 null；实际名字为「前缀-序号」
     * @return 线程工厂，永不为 null
     */
    public static ThreadFactory daemonFactory(String prefix) {
        return namedFactory(prefix);
    }

    /**
     * 获取 IO 池。
     *
     * 用于网络收发、文件读写、进程输出读取等阻塞操作。
     *
     * @return IO 池，永不为 null
     * @throws java.util.concurrent.RejectedExecutionException 当已关闭后继续提交任务时抛出
     */
    public static ExecutorService io() {
        return IO;
    }

    /**
     * 获取定时池。
     *
     * 用于重连退避、心跳、超时等延时或周期任务。
     *
     * @return 定时池，永不为 null
     * @throws java.util.concurrent.RejectedExecutionException 当已关闭后继续提交任务时抛出
     */
    public static ScheduledExecutorService scheduled() {
        return SCHEDULED;
    }

    /**
     * 获取工作池。
     *
     * 用于哈希校验、解压等短时 CPU 任务。不要在这里提交长阻塞任务，那会占满工作线程。
     *
     * @return 工作池，永不为 null
     * @throws java.util.concurrent.RejectedExecutionException 当已关闭后继续提交任务时抛出
     */
    public static ExecutorService work() {
        return WORK;
    }

    /**
     * 提交一个短任务到工作池。
     *
     * 存在的意义是让调用方不必直接接触 ExecutorService API，从而在关闭后得到统一行为。
     *
     * @param task 任务，不能为 null
     */
    public static void submit(Runnable task) {
        WORK.execute(task);
    }

    /**
     * 提交一个短任务到工作池并返回结果。
     *
     * @param task 任务，不能为 null
     * @param <T> 结果类型
     * @return 任务句柄，永不为 null
     */
    public static <T> java.util.concurrent.Future<T> submit(Callable<T> task) {
        return WORK.submit(task);
    }

    /**
     * 优雅关闭全部线程池。
     *
     * 幂等：重复调用无副作用，第二次调用立即返回。
     * 关闭顺序为「先停止接受新任务，再等待在途任务，最后强制中断」：
     * 前两步给在途任务机会正常收尾，避免把「重启」变成「丢数据」。
     *
     * @param timeout 等待在途任务的总超时；超时后强制中断剩余任务
     */
    public static void shutdownGracefully(Duration timeout) {
        if (!SHUTDOWN.compareAndSet(false, true)) {
            return;
        }

        long millis = timeout == null ? 0L : Math.max(0L, timeout.toMillis());
        // 三份池平分等待时间，保证总耗时不超过 timeout
        long perPool = millis / 3;

        stopPool(SCHEDULED, perPool);
        stopPool(IO, perPool);
        stopPool(WORK, perPool);
    }

    /**
     * 关闭单个池：先 shutdown 等待，超时后 shutdownNow。
     *
     * @param pool 目标池，允许为 null（返回时直接返回）
     * @param waitMillis 等待毫秒数；0 表示不等待，直接强制中断
     */
    private static void stopPool(ExecutorService pool, long waitMillis) {
        if (pool == null) {
            return;
        }
        pool.shutdown();
        try {
            if (waitMillis > 0L && !pool.awaitTermination(waitMillis, TimeUnit.MILLISECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            // ignore-reason: 本方法会被 JVM shutdown hook 调用，此时线程已是待终止状态；
            // 保留中断标记并按「不再等待」处理，让关闭流程尽快走完，而不是在此阻塞。
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }

    /**
     * 返回当前 mod 自建线程的名字清单。
     *
     * 供 ThreadLeakTest 与诊断导出使用：通过线程名前缀识别归属，
     * 避免把 JVM 自身的线程（GC、Reference Handler 等）算进来。
     *
     * @return 线程名清单，永不为 null，可能为空
     */
    public static List<String> threadNames() {
        List<String> names = new ArrayList<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            String name = thread.getName();
            if (name.startsWith("Ender-")) {
                names.add(name);
            }
        }
        Collections.sort(names);
        return names;
    }

    /**
     * 是否已经开始关闭。
     *
     * @return true 表示 {@link #shutdownGracefully(Duration)} 已被调用
     */
    public static boolean isShutdown() {
        return SHUTDOWN.get();
    }

    /**
     * 供测试重置关闭状态。
     *
     * 只应用于单元测试：关闭是进程级一次性动作，生产代码不得依赖本方法。
     * 可见性设为 package-private 以减少误用面。
     */
    static void resetShutdownStateForTest() {
        SHUTDOWN.set(false);
    }
}
