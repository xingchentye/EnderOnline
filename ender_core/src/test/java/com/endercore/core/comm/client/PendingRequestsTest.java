/*
 * 本文件属于 EnderOnline 单元测试。
 *
 * 职责：固化在途请求表的配对、超时与整体失败语义，作为从 CoreWebSocketClient 拆出该类后的回归基线。
 *
 * 关键约束：超时用例用较短的超时时间并等待 Future 完成，不用 sleep 猜测时序。
 */
package com.endercore.core.comm.client;

import com.endercore.core.comm.EnderExecutors;
import com.endercore.core.comm.EnderExecutors;
import com.endercore.core.comm.api.CoreExceptionHandler;
import com.endercore.core.comm.exception.CoreClosedException;
import com.endercore.core.comm.exception.CoreTimeoutException;
import com.endercore.core.comm.monitor.ConnectionMetrics;
import com.endercore.core.comm.protocol.CoreResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PendingRequests} 的行为固化测试。
 *
 * 这组测试守护什么：requestId 单调且不重复、配对后条目被移除、超时后完成 Future 并只上报一次、
 * 整体失败会取消全部超时任务，以及登记被拒绝时不留下悬空条目。
 *
 * 注意：本文件的断言消息可保留中文——测试源码已从 i18n 守卫中排除，中文失败信息更易读。
 *
 * 线程安全性：每个用例各自持有调度器与请求表，无共享状态。
 *
 * @see PendingRequests
 */
class PendingRequestsTest {

    /** 超时用到的调度器；与生产实现不同，测试用私有实例以便干净关闭。 */
    private ScheduledExecutorService scheduler;

    /** 记录超时回调次数的异常处理器。 */
    private AtomicInteger timeoutCalls;

    /** 被测对象。 */
    private PendingRequests pendingRequests;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor(
                EnderExecutors.daemonFactory("PendingRequestsTest-Sched"));
        timeoutCalls = new AtomicInteger();
        CoreExceptionHandler handler = new CoreExceptionHandler() {
            @Override
            public void onConnectionError(Throwable error) {
            }

            @Override
            public void onProtocolError(Throwable error) {
            }

            @Override
            public void onRemoteError(Throwable error) {
            }

            @Override
            public void onTimeout(Throwable error) {
                timeoutCalls.incrementAndGet();
            }
        };
        pendingRequests = new PendingRequests(scheduler, new ConnectionMetrics(), handler);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    @DisplayName("allocateId：从 1 开始单调递增且不重复")
    void allocateIdIsMonotonic() {
        long first = pendingRequests.allocateId();
        long second = pendingRequests.allocateId();
        long third = pendingRequests.allocateId();

        assertEquals(1, first, "首个 requestId 应为 1");
        assertEquals(2, second, "requestId 应逐个递增");
        assertEquals(3, third, "requestId 应逐个递增");
    }

    @Test
    @DisplayName("register：登记后在途计数增加，返回的 Future 未完成")
    void registerAddsEntry() {
        long id = pendingRequests.allocateId();

        CompletableFuture<CoreResponse> future = pendingRequests.register(id, "c:ping", Duration.ofSeconds(30));

        assertNotNull(future, "register 应返回 Future");
        assertFalse(future.isDone(), "尚未配对时 Future 不应完成");
        assertEquals(1, pendingRequests.size(), "登记后应有一条在途请求");
    }

    @Test
    @DisplayName("match：命中后移除条目并返回视图，再次匹配返回 null")
    void matchRemovesEntry() {
        long id = pendingRequests.allocateId();
        pendingRequests.register(id, "c:ping", Duration.ofSeconds(30));

        PendingRequests.PendingRequestView view = pendingRequests.match(id);

        assertNotNull(view, "已登记的请求应能匹配到");
        assertEquals("c:ping", view.kind(), "视图应保留请求种类");
        assertTrue(view.startNanos() > 0, "视图应保留发送时刻");
        assertEquals(0, pendingRequests.size(), "匹配后条目应被移除");
        assertNull(pendingRequests.match(id), "重复匹配应返回 null");
    }

    @Test
    @DisplayName("match：未登记的 requestId 返回 null")
    void matchUnknownIdReturnsNull() {
        assertNull(pendingRequests.match(12345L), "不存在的 requestId 应返回 null");
    }

    @Test
    @DisplayName("match：取消超时任务，之后不会再触发超时")
    void matchCancelsTimeout() throws Exception {
        long id = pendingRequests.allocateId();
        pendingRequests.register(id, "c:ping", Duration.ofMillis(60));
        pendingRequests.match(id);

        assertFalse(awaitTimeoutWindow());

        assertEquals(0, timeoutCalls.get(), "已配对的请求不应再触发超时回调");
    }

    @Test
    @DisplayName("超时：完成 Future 为 CoreTimeoutException 并上报一次")
    void timeoutCompletesExceptionallyAndReportsOnce() throws Exception {
        long id = pendingRequests.allocateId();
        CompletableFuture<CoreResponse> future = pendingRequests.register(id, "c:ping", Duration.ofMillis(50));

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> future.get(5, TimeUnit.SECONDS), "超时应以异常完成 Future");
        assertTrue(error.getCause() instanceof CoreTimeoutException,
                "超时异常类型应为 CoreTimeoutException，实际=" + error.getCause());

        assertEquals(1, timeoutCalls.get(), "超时回调应恰好发生一次");
        assertEquals(0, pendingRequests.size(), "超时后条目应被移除");
    }

    @Test
    @DisplayName("超时：已配对的请求不会再产生超时上报")
    void matchedRequestDoesNotReportTimeout() throws Exception {
        long id = pendingRequests.allocateId();
        CompletableFuture<CoreResponse> future = pendingRequests.register(id, "c:ping", Duration.ofMillis(60));
        pendingRequests.match(id);

        // 手动完成 Future，模拟响应到达
        future.complete(new CoreResponse(0, id, "c:ping", new byte[0]));
        assertFalse(awaitTimeoutWindow());

        assertEquals(0, timeoutCalls.get(), "已配对并完成的请求不应触发超时回调");
    }

    @Test
    @DisplayName("discard：移除条目但不完成 Future")
    void discardRemovesWithoutCompleting() {
        long id = pendingRequests.allocateId();
        CompletableFuture<CoreResponse> future = pendingRequests.register(id, "c:ping", Duration.ofSeconds(30));

        pendingRequests.discard(id);

        assertEquals(0, pendingRequests.size(), "撤销后条目应被移除");
        assertFalse(future.isDone(), "discard 不应完成 Future，调用方负责设置失败原因");
    }

    @Test
    @DisplayName("failAll：以同一异常完成全部在途请求并清空")
    void failAllCompletesEveryEntry() throws Exception {
        long idA = pendingRequests.allocateId();
        CompletableFuture<CoreResponse> first = pendingRequests.register(idA, "c:a", Duration.ofSeconds(30));
        long idB = pendingRequests.allocateId();
        CompletableFuture<CoreResponse> second = pendingRequests.register(idB, "c:b", Duration.ofSeconds(30));
        assertEquals(2, pendingRequests.size(), "前置条件：两条在途请求");

        CoreClosedException reason = new CoreClosedException("连接已关闭");
        pendingRequests.failAll(reason);

        assertEquals(0, pendingRequests.size(), "整体失败后请求表应被清空");
        assertTrue(first.isCompletedExceptionally(), "第一条应失败");
        assertTrue(second.isCompletedExceptionally(), "第二条应失败");
        assertEquals(reason, assertThrows(ExecutionException.class, () -> first.get(1, TimeUnit.SECONDS)).getCause(),
                "失败原因应为传入的异常本身");
    }

    @Test
    @DisplayName("failAll：取消超时任务，之后不会再触发超时回调")
    void failAllCancelsTimeouts() throws Exception {
        pendingRequests.register(pendingRequests.allocateId(), "c:a", Duration.ofMillis(60));

        pendingRequests.failAll(new CoreClosedException("连接已关闭"));
        assertFalse(awaitTimeoutWindow());

        assertEquals(0, timeoutCalls.get(), "整体失败后不应再触发超时回调");
    }

    /**
     * 等待一个比超时窗口更长的时间，返回 false。
     *
     * 用于「断言超时不再发生」的三个用例：超时窗口是 50~60 毫秒，这里等 250 毫秒，
     * 让任何已排队的超时任务都有机会执行；若它们真的执行了，随后的回调计数断言就会失败。
     *
     * 返回 false 是为了让调用点写成 assertFalse(...) 的形式，使「等待本身不是断言」
     * 这件事在代码里可见，而不是藏在裸 sleep 里。
     *
     * @return 恒为 false
     */
    private static boolean awaitTimeoutWindow() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    @Test
    @DisplayName("failAll：空表是安全的空操作")
    void failAllOnEmptyIsNoOp() {
        pendingRequests.failAll(new CoreClosedException("连接已关闭"));

        assertEquals(0, pendingRequests.size(), "空表调用后仍应为空");
    }

    @Test
    @DisplayName("register：调度器已关闭时立即以 CoreTimeoutException 失败且不留条目")
    void registerRejectedDoesNotLeakEntry() throws Exception {
        scheduler.shutdownNow();

        CompletableFuture<CoreResponse> future = pendingRequests.register(
                pendingRequests.allocateId(), "c:ping", Duration.ofMillis(10));

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> future.get(2, TimeUnit.SECONDS), "调度被拒绝时应立即失败");
        assertTrue(error.getCause() instanceof CoreTimeoutException,
                "失败原因应为 CoreTimeoutException，实际=" + error.getCause());
        assertEquals(0, pendingRequests.size(), "被拒绝的登记不应留下悬空条目");
    }

    @Test
    @DisplayName("构造与参数校验：null 被拒绝")
    void nullArgumentsAreRejected() {
        assertThrows(NullPointerException.class,
                () -> new PendingRequests(null, new ConnectionMetrics(), new CoreExceptionHandler() {
                    @Override
                    public void onConnectionError(Throwable error) {
                    }

                    @Override
                    public void onProtocolError(Throwable error) {
                    }

                    @Override
                    public void onRemoteError(Throwable error) {
                    }

                    @Override
                    public void onTimeout(Throwable error) {
                    }
                }), "调度器为 null 应被拒绝");
        assertThrows(NullPointerException.class,
                () -> pendingRequests.register(1L, null, Duration.ofSeconds(1)), "kind 为 null 应被拒绝");
        assertThrows(NullPointerException.class,
                () -> pendingRequests.register(1L, "c:ping", null), "timeout 为 null 应被拒绝");
        assertThrows(NullPointerException.class,
                () -> pendingRequests.failAll(null), "异常为 null 应被拒绝");
    }
}
