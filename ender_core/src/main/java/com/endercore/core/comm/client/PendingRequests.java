/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：在途请求表——requestId 分配、超时调度、响应配对与整体失败。
 *
 * 这些字段与逻辑原先内联在 CoreWebSocketClient 中，与连接生命周期、帧分派混在一起。
 * 抽出后「谁在等响应、等多久、超时了怎么办」只有一处实现，也便于单独测试。
 */
package com.endercore.core.comm.client;

import com.endercore.core.comm.api.CoreExceptionHandler;
import com.endercore.core.comm.exception.CoreTimeoutException;
import com.endercore.core.comm.monitor.ConnectionMetrics;
import com.endercore.core.comm.protocol.CoreResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 在途请求表。
 *
 * 每个已发送但尚未收到响应的请求都会登记为一条 {@link PendingRequest}，键是单调递增的 requestId。
 * 条目在三种情况下被移除：收到响应、超时、连接关闭导致的整体失败。
 *
 * 设计约束：
 * 1. requestId 从 1 开始单调递增，同一实例内不会重复。
 * 2. 超时任务由本类调度，因此「超时后要做什么」只有一处实现：移除条目、以
 *    CoreTimeoutException 完成 Future、计入指标并回调异常处理器。
 * 3. 登记失败（调度器已关闭）不留下悬空条目，直接以异常完成调用方的 Future。
 *
 * 线程安全性：底层是 ConcurrentHashMap，requestId 用原子类型分配；本类可被网络 IO 线程、
 * 调度线程与调用线程并发使用。
 */
final class PendingRequests {

    /** 未指定超时时的默认等待时间。 */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    /** 尚未完成的请求，键为 requestId。 */
    private final ConcurrentHashMap<Long, PendingRequest> entries = new ConcurrentHashMap<>();

    /** 请求 ID 分配器，从 1 开始单调递增。 */
    private final AtomicLong requestIdSeq = new AtomicLong(1);

    /** 超时任务的调度器，不能为 null。 */
    private final ScheduledExecutorService scheduler;

    /** 指标收集器，用于记录超时次数。 */
    private final ConnectionMetrics metrics;

    /** 超时回调目标，不能为 null。 */
    private final CoreExceptionHandler exceptionHandler;

    /**
     * 构造请求表。
     *
     * @param scheduler 超时任务调度器，不能为 null
     * @param metrics 指标收集器，不能为 null
     * @param exceptionHandler 超时回调目标，不能为 null
     * @throws NullPointerException 当任一参数为 null 时抛出
     */
    PendingRequests(ScheduledExecutorService scheduler, ConnectionMetrics metrics,
            CoreExceptionHandler exceptionHandler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.exceptionHandler = Objects.requireNonNull(exceptionHandler, "exceptionHandler");
    }

    /**
     * 分配一个请求 ID。
     *
     * 与 {@link #register(long, String)} 分开是为了让调用方先把 ID 写进帧再登记：
     * 这样发出与登记之间不存在「已发出但未登记」的窗口。
     *
     * @return 单调递增的请求标识，从 1 开始
     */
    long allocateId() {
        return requestIdSeq.getAndIncrement();
    }

    /**
     * 登记一个在途请求并安排超时。
     *
     * @param requestId 请求标识，应取自 {@link #allocateId()}
     * @param kind 请求种类，用于构造超时异常，不能为 null
     * @return 完成的 Future，永不为 null；调度被拒绝时以 CoreTimeoutException 立即失败
     * @throws NullPointerException 当 kind 为 null 时抛出
     */
    CompletableFuture<CoreResponse> register(long requestId, String kind) {
        return register(requestId, kind, DEFAULT_TIMEOUT);
    }

    /**
     * 登记一个在途请求并安排超时。
     *
     * @param requestId 请求标识，应取自 {@link #allocateId()}
     * @param kind 请求种类，用于构造超时异常，不能为 null
     * @param timeout 等待响应的超时时间，不能为 null
     * @return 完成的 Future，永不为 null；调度被拒绝时以 CoreTimeoutException 立即失败
     * @throws NullPointerException 当任一参数为 null 时抛出
     */
    CompletableFuture<CoreResponse> register(long requestId, String kind, Duration timeout) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(timeout, "timeout");

        CompletableFuture<CoreResponse> future = new CompletableFuture<>();
        ScheduledFuture<?> timeoutTask;
        try {
            timeoutTask = scheduler.schedule(() -> onTimeout(requestId, kind, timeout),
                    timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // 调度器已关闭：不留下悬空条目，直接以超时异常结束
            future.completeExceptionally(new CoreTimeoutException(kind, requestId, timeout));
            return future;
        }

        entries.put(requestId, new PendingRequest(kind, System.nanoTime(), future, timeoutTask));
        return future;
    }

    /**
     * 撤销一个在途请求，不完成其 Future。
     *
     * 供「登记后、发送时立即失败」的路径使用：条目必须移除并取消超时，否则会留下
     * 一个永不完成也不会超时的记录。调用方随后自行以发送异常完成 Future。
     *
     * @param requestId 请求标识
     */
    void discard(long requestId) {
        PendingRequest entry = entries.remove(requestId);
        if (entry != null) {
            entry.timeoutTask.cancel(false);
        }
    }

    /**
     * 取出并移除指定请求，返回只读视图。
     *
     * 命中时同时取消其超时任务，因此调用方无需再处理超时。
     *
     * @param requestId 请求标识
     * @return 只读视图；不存在时返回 null
     */
    PendingRequestView match(long requestId) {
        PendingRequest entry = entries.remove(requestId);
        if (entry == null) {
            return null;
        }
        entry.timeoutTask.cancel(false);
        return new PendingRequestView(entry.future, entry.kind, entry.startNanos);
    }

    /**
     * 以同一异常失败所有在途请求并清空请求表。
     *
     * 连接关闭或对端断开时调用；每个条目的超时任务都会被取消。
     *
     * @param error 用于完成各 Future 的异常，不能为 null
     */
    void failAll(RuntimeException error) {
        Objects.requireNonNull(error, "error");
        List<PendingRequest> snapshot = new ArrayList<>(entries.values());
        entries.clear();
        for (PendingRequest entry : snapshot) {
            entry.timeoutTask.cancel(false);
            entry.future.completeExceptionally(error);
        }
    }

    /**
     * 当前在途请求数。
     *
     * @return 条目数量，非负
     */
    int size() {
        return entries.size();
    }

    /**
     * 超时回调：移除条目、完成 Future、计入指标并通知异常处理器。
     *
     * 只有真正移除了条目才计入指标与回调，避免响应与超时竞争时重复上报。
     *
     * @param requestId 超时的请求标识
     * @param kind 请求种类
     * @param timeout 该请求配置的超时时间
     */
    private void onTimeout(long requestId, String kind, Duration timeout) {
        PendingRequest removed = entries.remove(requestId);
        if (removed == null) {
            return;
        }
        CoreTimeoutException error = new CoreTimeoutException(kind, requestId, timeout);
        if (removed.future.completeExceptionally(error)) {
            metrics.onRequestTimeout();
            exceptionHandler.onTimeout(error);
        }
    }

    /**
     * 已配对请求的只读视图。
     *
     * 只暴露响应配对所需的三项信息：完成哪个 Future、用了多长时间、属于哪个 kind。
     * 超时任务不出现在这里——它在匹配时已被取消，调用方无需再关心。
     *
     * @param future 等待响应的 Future，永不为 null
     * @param kind 请求种类，永不为 null
     * @param startNanos 发送时刻的纳秒时间戳
     */
    record PendingRequestView(CompletableFuture<CoreResponse> future, String kind, long startNanos) {
    }

    /**
     * 在途请求记录。
     *
     * 字段均为 final，写入请求表后不再修改；仅在客户端包内使用。
     */
    static final class PendingRequest {
        /** 请求种类，用于构造超时与远程错误消息。 */
        final String kind;
        /** 发送时刻，单位纳秒，取自 System.nanoTime()。 */
        final long startNanos;
        /** 等待响应的 Future，永不为 null。 */
        final CompletableFuture<CoreResponse> future;
        /** 超时定时任务，响应到达或失败时被取消。 */
        final ScheduledFuture<?> timeoutTask;

        /**
         * 构造在途请求记录。
         *
         * @param kind 请求种类，不能为 null
         * @param startNanos 发送时刻的纳秒时间戳
         * @param future 等待响应的 Future，不能为 null
         * @param timeoutTask 超时定时任务，不能为 null
         */
        private PendingRequest(String kind, long startNanos, CompletableFuture<CoreResponse> future,
                ScheduledFuture<?> timeoutTask) {
            this.kind = kind;
            this.startNanos = startNanos;
            this.future = future;
            this.timeoutTask = timeoutTask;
        }
    }
}
