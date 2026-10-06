/*
 * 本文件属于 EnderOnline 单元测试。
 *
 * 职责：固化重连退避策略，作为从 CoreWebSocketClient 拆出该逻辑后的回归基线。
 *
 * 关键约束：退避直接决定故障时的重连频率；翻倍、封顶与抖动都属于对外可观测行为，
 * 本测试把它们固定下来，避免改连接逻辑时顺手改变重连压力。
 */
package com.endercore.core.comm.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.endercore.core.comm.EnderExecutors;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReconnectPolicy} 的行为固化测试。
 *
 * 这组测试守护什么：初值、逐次翻倍、上限封顶、溢出保护、成功复位，以及抖动被计入延迟
 * 但不改变退避本身。抖动源可注入，因此这些断言是确定性的。
 *
 * 注意：本文件的断言消息可保留中文——测试源码已从 i18n 守卫中排除，中文失败信息更易读。
 *
 * 线程安全性：每个用例各自构造策略实例，无共享状态，可并行执行。
 *
 * @see ReconnectPolicy
 */
class ReconnectPolicyTest {

    /** 退避初值，与 CoreWebSocketConfig 的默认值一致。 */
    private static final Duration MIN = Duration.ofMillis(200);

    /** 退避上限，与 CoreWebSocketConfig 的默认值一致。 */
    private static final Duration MAX = Duration.ofSeconds(5);

    /**
     * 构造一个抖动固定的策略，使延迟断言可预测。
     *
     * @param jitter 固定抖动毫秒数
     * @return 策略实例，永不为 null
     */
    private static ReconnectPolicy withFixedJitter(long jitter) {
        return new ReconnectPolicy(MIN, MAX, () -> jitter);
    }

    @Test
    @DisplayName("初始退避等于配置的最小值")
    void initialBackoffIsMin() {
        ReconnectPolicy policy = withFixedJitter(0);

        assertEquals(MIN.toMillis(), policy.currentBackoffMillis(),
                "初始退避应等于配置的最小值");
    }

    @Test
    @DisplayName("每次尝试后翻倍：200 → 400 → 800 → 1600")
    void backoffDoublesOnEachAttempt() {
        ReconnectPolicy policy = withFixedJitter(0);

        long[] expected = {400, 800, 1600, 3200};
        for (long want : expected) {
            policy.recordAttempt();
            assertEquals(want, policy.currentBackoffMillis(),
                    "翻倍序列应为 400/800/1600/3200，实际=" + policy.currentBackoffMillis());
        }
    }

    @Test
    @DisplayName("翻倍到上限后保持不变")
    void backoffCapsAtMax() {
        ReconnectPolicy policy = withFixedJitter(0);

        // 200 -> 400 -> 800 -> 1600 -> 3200 -> 5000(封顶) -> 5000 ...
        for (int i = 0; i < 20; i++) {
            policy.recordAttempt();
        }

        assertEquals(MAX.toMillis(), policy.currentBackoffMillis(),
                "连续尝试后退避应停在上限");
    }

    @Test
    @DisplayName("nextDelayMillis：等于当前退避加抖动")
    void nextDelayIncludesJitter() {
        ReconnectPolicy policy = withFixedJitter(42);

        assertEquals(MIN.toMillis() + 42, policy.nextDelayMillis(),
                "延迟应为退避加抖动");

        policy.recordAttempt();
        assertEquals(400 + 42, policy.nextDelayMillis(),
                "翻倍后延迟应同步变化");
    }

    @Test
    @DisplayName("nextDelayMillis：抖动不改变退避本身")
    void jitterDoesNotAffectBackoff() {
        ReconnectPolicy policy = withFixedJitter(99);

        policy.nextDelayMillis();
        policy.nextDelayMillis();

        assertEquals(MIN.toMillis(), policy.currentBackoffMillis(),
                "取延迟不应推进退避；推进只由 recordAttempt 负责");
    }

    @Test
    @DisplayName("nextDelayMillis：抖动为负时按 0 处理，延迟不会小于退避")
    void negativeJitterIsClampedToZero() {
        ReconnectPolicy policy = withFixedJitter(-500);

        assertTrue(policy.nextDelayMillis() >= MIN.toMillis(),
                "负抖动不应把延迟压到退避以下");
    }

    @Test
    @DisplayName("抖动源每次都参与计算（注入递增序列可观测）")
    void jitterIsQueriedPerCall() {
        AtomicLong counter = new AtomicLong();
        ReconnectPolicy policy = new ReconnectPolicy(MIN, MAX, counter::incrementAndGet);

        assertEquals(MIN.toMillis() + 1, policy.nextDelayMillis(), "第 1 次应为退避 +1");
        assertEquals(MIN.toMillis() + 2, policy.nextDelayMillis(), "第 2 次应为退避 +2");
        assertEquals(2, counter.get(), "抖动源应被查询两次");
    }

    @Test
    @DisplayName("reset：连接成功后退回初值")
    void resetReturnsToMin() {
        ReconnectPolicy policy = withFixedJitter(0);
        policy.recordAttempt();
        policy.recordAttempt();
        assertEquals(800, policy.currentBackoffMillis(), "前置条件：已翻倍两次");

        policy.reset();

        assertEquals(MIN.toMillis(), policy.currentBackoffMillis(),
                "复位后退避应回到初值");
    }

    @Test
    @DisplayName("并发尝试不丢更新：并发达到封顶所需的次数与顺序一致")
    void concurrentAttemptsDoNotLoseUpdates() throws InterruptedException {
        // 200ms 起步、上限 100000ms：需要 9 次翻倍才封顶，远少于线程数，
        // 因此只要有任何一次更新丢失，最终值就会低于上限。这样就无需处理 long 溢出，
        // 断言也保持精确。
        ReconnectPolicy policy = new ReconnectPolicy(MIN, Duration.ofMillis(100_000), () -> 0);
        int attempts = 64;
        Thread[] threads = new Thread[attempts];
        for (int i = 0; i < attempts; i++) {
            // 走 EnderExecutors 的线程工厂：守卫禁止裸 new Thread，测试也不例外
            threads[i] = EnderExecutors.daemonThread(policy::recordAttempt, "ReconnectPolicyTest-" + i);
            threads[i].start();
        }
        for (Thread t : threads) {
            t.join();
        }

        assertEquals(100_000, policy.currentBackoffMillis(),
                "并发尝试后应已封顶；低于上限说明有更新被丢弃");
    }

    @Test
    @DisplayName("溢出保护：翻倍不会因乘法回绕而变成负数")
    void doublingDoesNotWrapNegative() {
        // 初值取 (Long.MAX_VALUE / 2, Long.MAX_VALUE] 区间：翻倍必然溢出，
        // 此时策略应直接收敛到上限，而不是让乘法回绕成负数。
        long huge = Long.MAX_VALUE / 2 + 1;
        ReconnectPolicy policy = new ReconnectPolicy(Duration.ofMillis(huge), Duration.ofMillis(Long.MAX_VALUE),
                () -> 0);

        for (int i = 0; i < 5; i++) {
            policy.recordAttempt();
        }

        assertEquals(Long.MAX_VALUE, policy.currentBackoffMillis(),
                "溢出路径应收敛到上限");
        assertTrue(policy.currentBackoffMillis() > 0, "退避必须始终为正数");
    }

    @Test
    @DisplayName("构造校验：null 参数被拒绝")
    void constructorRejectsNulls() {
        assertThrows(NullPointerException.class,
                () -> new ReconnectPolicy(null, MAX), "min 为 null 应被拒绝");
        assertThrows(NullPointerException.class,
                () -> new ReconnectPolicy(MIN, null), "max 为 null 应被拒绝");
        assertThrows(NullPointerException.class,
                () -> new ReconnectPolicy(MIN, MAX, null), "抖动源为 null 应被拒绝");
    }

    @Test
    @DisplayName("构造校验：负数与 max < min 被拒绝")
    void constructorRejectsInvalidRanges() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReconnectPolicy(Duration.ofMillis(-1), MAX), "负初值应被拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> new ReconnectPolicy(MIN, Duration.ofMillis(-1)), "负上限应被拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> new ReconnectPolicy(Duration.ofSeconds(5), Duration.ofMillis(200)),
                "上限小于初值应被拒绝");
    }

    @Test
    @DisplayName("min 与 max 相等时退避恒定")
    void equalMinAndMaxStaysConstant() {
        ReconnectPolicy policy = new ReconnectPolicy(Duration.ofMillis(300), Duration.ofMillis(300),
                () -> 0);
        policy.recordAttempt();
        policy.recordAttempt();

        assertEquals(300, policy.currentBackoffMillis(), "min == max 时退避不应变化");
    }
}
