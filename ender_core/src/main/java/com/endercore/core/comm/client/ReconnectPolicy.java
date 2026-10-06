/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：重连退避策略——退避初值、翻倍与上限，以及避免同时重连的抖动。
 *
 * 这些规则原先内联在 CoreWebSocketClient 里，与该类的连接生命周期、帧分派混在一起；
 * 抽出后可单独测试，也便于将来更换退避算法（如指数退避 + 抖动）而不触碰连接逻辑。
 */
package com.endercore.core.comm.client;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 重连退避策略。
 *
 * 退避从 {@code minMillis} 开始，每次尝试后翻倍并封顶到 {@code maxMillis}，连接成功后由调用方复位。
 * {@link #nextDelayMillis()} 在实际延迟上叠加抖动，避免多个客户端在同一时刻一起重连。
 *
 * 设计约束：
 * 1. 退避状态用 {@link AtomicLong} 保存：读取与翻倍是「读—改—写」，而超时任务与关闭回调可能并发触发，
 *    非原子实现会丢更新，使退避在真实故障下停留在最小值。
 * 2. 翻倍先做溢出保护再封顶：直接乘法溢出会得到负值，把已封顶的退避变成立即重连。
 * 3. 抖动上限固定为 99 毫秒，且恒为非负。
 *
 * 线程安全性：本类可被多个线程并发调用；状态更新是原子的。
 */
final class ReconnectPolicy {

    /** 抖动上限（毫秒），实际抖动取 [0, JITTER_BOUND)。 */
    private static final long JITTER_BOUND = 100L;

    /** 退避初值（毫秒），连接成功后复位到该值。 */
    private final long minMillis;

    /** 退避上限（毫秒）。 */
    private final long maxMillis;

    /** 抖动来源；默认取 [0, 100) 的随机数，测试可注入固定值。 */
    private final LongSupplier jitter;

    /** 当前退避（毫秒），下一次重连使用；初值为 minMillis。 */
    private final AtomicLong backoffMillis = new AtomicLong();

    /**
     * 构造策略，抖动使用默认随机源。
     *
     * @param min 退避初值，不能为 null 且不得为负
     * @param max 退避上限，不能为 null 且不得小于 min
     * @throws NullPointerException 当任一参数为 null 时抛出
     * @throws IllegalArgumentException 当取值为负或 max < min 时抛出
     */
    ReconnectPolicy(Duration min, Duration max) {
        this(min, max, () -> ThreadLocalRandom.current().nextLong(JITTER_BOUND));
    }

    /**
     * 构造策略并指定抖动来源。
     *
     * 供测试注入确定性抖动。
     *
     * @param min 退避初值，不能为 null 且不得为负
     * @param max 退避上限，不能为 null 且不得小于 min
     * @param jitter 抖动来源，不能为 null；返回负数时按 0 处理
     * @throws NullPointerException 当任一参数为 null 时抛出
     * @throws IllegalArgumentException 当取值为负或 max < min 时抛出
     */
    ReconnectPolicy(Duration min, Duration max, LongSupplier jitter) {
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        Objects.requireNonNull(jitter, "jitter");
        long minMillis = min.toMillis();
        long maxMillis = max.toMillis();
        if (minMillis < 0 || maxMillis < 0) {
            throw new IllegalArgumentException("backoff must not be negative");
        }
        if (maxMillis < minMillis) {
            throw new IllegalArgumentException("max backoff must not be less than min backoff");
        }
        this.minMillis = minMillis;
        this.maxMillis = maxMillis;
        this.jitter = jitter;
        this.backoffMillis.set(minMillis);
    }

    /**
     * 本次重连应当等待的毫秒数。
     *
     * 等于当前退避加上抖动。必须在本次尝试**开始前**调用，且完成后调用
     * {@link #recordAttempt()} 推进退避。
     *
     * @return 延迟毫秒数，恒为非负，且不小于退避初值
     */
    long nextDelayMillis() {
        long delay = backoffMillis.get() + Math.max(0L, jitter.getAsLong());
        return Math.max(0L, delay);
    }

    /**
     * 记录一次重连尝试，把退避翻倍并封顶。
     *
     * 用原子更新而非「读再写回」：并发触发时不会丢失这次翻倍。
     */
    void recordAttempt() {
        backoffMillis.updateAndGet(this::doubledAndCapped);
    }

    /**
     * 连接成功后复位退避到初值。
     */
    void reset() {
        backoffMillis.set(minMillis);
    }

    /**
     * 当前退避值（不含抖动）。
     *
     * 供测试断言与诊断使用。
     *
     * @return 当前退避毫秒数，非负且不超过上限
     */
    long currentBackoffMillis() {
        return backoffMillis.get();
    }

    /**
     * 计算翻倍并封顶后的退避值。
     *
     * @param current 当前退避毫秒数，不能为负
     * @return 翻倍后不超过上限的退避毫秒数
     */
    private long doubledAndCapped(long current) {
        if (current >= maxMillis) {
            return maxMillis;
        }
        if (current > Long.MAX_VALUE / 2L) {
            return maxMillis;
        }
        return Math.min(current * 2L, maxMillis);
    }
}
