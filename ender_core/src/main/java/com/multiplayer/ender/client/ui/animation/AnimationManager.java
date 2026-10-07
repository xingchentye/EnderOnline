/*
 * 本文件属于 EnderOnline 客户端界面层。
 *
 * 职责：持有全部活动动画并在客户端刻统一推进，负责动画的注册、注销与全局暂停。
 *
 * 关键约束：进程内单例；动画列表用写时复制容器承载，但所有读写仍约定只在客户端 UI 线程进行。
 */
package com.multiplayer.ender.client.ui.animation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 动画管理器，进程内唯一的动画调度入口。
 *
 * 每帧由客户端渲染钩子调用一次 update，管理器按「距上次更新的真实时间」推进全部活动动画，
 * 并回收已完成且允许自动移除的动画。
 *
 * 设计约束：
 * 1. 单例通过 getInstance 惰性创建，构造器私有，全进程只有一个实例。
 * 2. 动画必须先经 addAnimation 注册才会被推进；Animation.start 已内置该调用，调用方通常无需手动注册。
 * 3. 活动列表是 CopyOnWriteArrayList：遍历期间对列表的增删不会抛并发修改异常，
 *    但每次 add 与 remove 都要复制整个数组，单帧多次增删的代价接近 O(n²)，应避免在 update 期间批量增删。
 *
 * 线程安全性：getInstance 是 synchronized 的，单例的创建与发布是安全的；
 * 但 paused、lastUpdateTime 与活动列表本身都没有同步保护，约定只由客户端 UI 线程访问。
 * 跨线程调用 update、pauseAll 等会读到撕裂状态，本类不提供跨线程调度能力。
 *
 * @since 1.0
 * @see Animation
 * @see UIAnimationUtils
 */
public class AnimationManager {

    /** 管理器当前持有的动画列表。写时复制容器，遍历安全，增删代价为全量数组复制。 */
    private final List<Animation> activeAnimations = new CopyOnWriteArrayList<>();

    /** 全局暂停标志；true 时 update 直接返回，不再推进任何动画。 */
    private boolean paused = false;

    /**
     * 全局暂停发生时已被调用方单独暂停的动画快照。
     *
     * 非空即表示管理器级暂停生效；{@link #resumeAll()} 只恢复「全局暂停之前就在跑」的动画，
     * 因此本快照是恢复的依据。用写时复制集合，避免遍历期间被列表增删干扰。
     */
    private final Set<Animation> pausedBeforeGlobalPause = new CopyOnWriteArraySet<>();

    /** 上次 update 使用的单调时钟纳秒值，用于换算 deltaTime；暂停与全局恢复期间不刷新。 */
    private long lastUpdateTime = System.nanoTime();

    /** 单例实例，仅在 getInstance 的同步方法内被赋值。 */
    private static AnimationManager instance;

    /**
     * 获取动画管理器单例实例。
     *
     * 首次调用时惰性创建，此后返回同一个实例。
     *
     * @return 全局唯一实例，永不为 null
     */
    public static synchronized AnimationManager getInstance() {
        if (instance == null) {
            instance = new AnimationManager();
        }
        return instance;
    }

    /** 私有构造函数（单例模式），仅由 getInstance 调用。 */
    private AnimationManager() {
    }

    /**
     * 推进所有活动动画一帧。
     *
     * 时间增量取自单调时钟 {@code System.nanoTime()} 与上次更新的差值并换算为秒，
     * 再钳到 [0, 0.1] 秒：上限避免长卡顿或恢复后一次性跳跃，下限兜底非单调时钟或极端调度，
     * 避免负增量让动画倒退。全局暂停时直接返回，且不刷新时间基准，
     * 因此恢复后的第一帧增量同样受 0.1 秒钳制。
     *
     * 副作用：会触发完成回调，并移除已完成且 shouldAutoRemove 为 true 的动画。
     *
     * @param partialTick 当前渲染帧的部分刻，原样透传给每个动画；管理器自身不使用该值
     */
    public void update(float partialTick) {
        if (paused) {
            return;
        }

        long currentTime = System.nanoTime();
        float deltaTime = (currentTime - lastUpdateTime) / 1_000_000_000.0f;
        lastUpdateTime = currentTime;

        advance(deltaTime, partialTick);
    }

    /**
     * 按调用方给定的时间增量推进所有活动动画一帧。
     *
     * 与 {@link #update(float)} 的区别只在于时间增量的来源：本方法不读内部时钟，
     * 因此离线渲染、逐帧定步长推进与倍速播放都能得到与预期一致的节奏。
     * 传 0 或负数等价于不推进时间，但仍会执行完成回调与回收，与 {@link #update(float)} 一致。
     *
     * 副作用与 {@link #update(float)} 相同。注意本方法不刷新内部时间基准，
     * 与 {@link #update(float)} 混用会让后者下一帧的增量出现跳变。
     *
     * @param deltaTime 时间增量，单位秒；大于 0.1 时按 0.1 处理，小于 0 时按 0 处理
     */
    public void manualUpdate(float deltaTime) {
        advance(deltaTime, 0.0f);
    }

    /**
     * 共用的推进实现。
     *
     * 时间增量在这里统一钳制，保证两个公开入口对越界增量的处理完全一致。
     *
     * @param deltaTime 时间增量，单位秒
     * @param partialTick 当前渲染帧的部分刻
     */
    private void advance(float deltaTime, float partialTick) {
        // NOTE: partialTick 只是透传，动画时间完全由传入增量驱动，因此调用频率
        // 直接决定动画的推进步长；按游戏刻调用会让动画呈刻粒度，逐帧调用才会平滑。
        float clamped = Math.min(Math.max(deltaTime, 0.0f), 0.1f);

        for (Animation animation : activeAnimations) {
            if (animation.isActive()) {
                animation.update(clamped, partialTick);

                // 检查动画是否已完成
                if (animation.isCompleted()) {
                    animation.onComplete();
                    if (animation.shouldAutoRemove()) {
                        activeAnimations.remove(animation);
                    }
                }
            }
        }

        // 移除已完成的动画
        // NOTE: 循环内的 remove 与这里的 removeIf 覆盖面重叠，前者只管本帧刚完成的动画，
        // 后者兜底回收本帧之外被置为非活动的动画；两次清理各自复制一次底层数组。
        activeAnimations.removeIf(animation -> !animation.isActive() && animation.shouldAutoRemove());
    }

    /**
     * 注册动画到管理器，并触发动画的开始钩子。
     *
     * 本方法不做去重：同一实例被注册两次会以两个元素存在于列表中，从而每帧被推进两次。
     * 常规路径由 Animation.start 的状态守卫保证幂等，直接调用本方法时调用方需自行确认。
     *
     * @param animation 要注册的动画，允许为 null
     * @return 动画 ID，供后续按 ID 查询或移除；animation 为 null 时返回 null
     */
    public String addAnimation(Animation animation) {
        if (animation != null) {
            activeAnimations.add(animation);
            animation.onStart();
            return animation.getId();
        }
        return null;
    }

    /**
     * 按 ID 移除指定动画。
     *
     * 只从列表中摘除，不改动动画自身状态、不触发停止回调。因此被移除的动画会处于
     * 「自身仍认为活动、却不再被推进」的僵尸状态，调用方若还要保留引用，应改用 stop。
     *
     * @param animationId 目标动画 ID；不存在时静默忽略
     */
    public void removeAnimation(String animationId) {
        activeAnimations.removeIf(animation -> animation.getId().equals(animationId));
    }

    /**
     * 按实例移除指定动画。
     *
     * 依据对象标识匹配（Animation 未重写 equals/hashCode），不会误删内容相同的另一个实例。
     * 与按 ID 移除一样，不改动动画自身状态。
     *
     * @param animation 目标动画实例，允许为 null，为 null 时不产生任何效果
     */
    public void removeAnimation(Animation animation) {
        activeAnimations.remove(animation);
    }

    /**
     * 停止并移除所有动画。
     *
     * 逐个调用 stop 会触发每个动画的停止回调，并因 stop 内部按实例注销而额外复制一次列表；
     * 最后统一清空，返回后列表必定为空。
     */
    public void clearAllAnimations() {
        for (Animation animation : activeAnimations) {
            animation.stop();
        }
        activeAnimations.clear();
    }

    /**
     * 全局暂停，并逐个暂停当前持有的动画。
     *
     * 管理器标志与动画标志各自独立：即使全局标志为 true，未在列表中的动画也不会被暂停。
     *
     * 暂停前会记录哪些动画已被调用方单独暂停（见 {@link #pausedBeforeGlobalPause}），
     * 使 {@link #resumeAll()} 能精确还原，而不是把所有动画一律恢复。
     *
     * 幂等性：已处于全局暂停时重复调用是空操作，不会覆盖首次记录的快照。
     */
    public void pauseAll() {
        if (paused) {
            return;
        }
        for (Animation animation : activeAnimations) {
            if (animation.isPaused()) {
                pausedBeforeGlobalPause.add(animation);
            }
        }
        paused = true;
        for (Animation animation : activeAnimations) {
            animation.pause();
        }
    }

    /**
     * 全局恢复，并恢复此前在运行的动画。
     *
     * 只恢复「本次全局暂停之前未被调用方暂停」的动画：全局暂停前就被单独暂停的动画保持暂停，
     * 由调用方自行 {@link Animation#resume()}。这是与 pauseAll 的对称还原，不改变
     * {@link Animation#isPaused()} 的语义（它始终表示调用方的暂停意图）。
     *
     * 边界：全局暂停期间新加入的动画不在快照中，全局恢复时会被一并恢复。
     *
     * 幂等性：未处于全局暂停时重复调用是空操作。
     */
    public void resumeAll() {
        if (!paused) {
            return;
        }
        paused = false;
        for (Animation animation : activeAnimations) {
            if (!pausedBeforeGlobalPause.contains(animation)) {
                animation.resume();
            }
        }
        pausedBeforeGlobalPause.clear();
    }

    /**
     * 停止并清空所有动画。
     *
     * 行为与 clearAllAnimations 完全一致，保留两个入口是为了兼容既有调用点。
     */
    public void stopAll() {
        for (Animation animation : activeAnimations) {
            animation.stop();
        }
        activeAnimations.clear();
    }

    /** 获取当前持有的动画数量；包含已非活动但 autoRemove 为 false 而尚未回收的实例。 */
    public int getActiveAnimationCount() {
        return activeAnimations.size();
    }

    /** 检查管理器是否还持有任何动画，语义等价于 getActiveAnimationCount 大于 0。 */
    public boolean hasActiveAnimations() {
        return !activeAnimations.isEmpty();
    }

    /**
     * 检查指定 ID 的动画是否仍在管理器中。
     *
     * 线性扫描，复杂度 O(n)。
     *
     * @param animationId 目标动画 ID，允许为 null（永不命中）
     * @return 命中返回 true，否则返回 false
     */
    public boolean hasAnimation(String animationId) {
        return activeAnimations.stream().anyMatch(animation -> animation.getId().equals(animationId));
    }

    /**
     * 按 ID 查询动画。
     *
     * 线性扫描，复杂度 O(n)，高频调用点应缓存结果。
     *
     * @param animationId 目标动画 ID，允许为 null（永不命中）
     * @return 命中的动画实例；不存在时返回 null，调用方需判空
     */
    public Animation getAnimation(String animationId) {
        return activeAnimations.stream()
                .filter(animation -> animation.getId().equals(animationId))
                .findFirst()
                .orElse(null);
    }

    /**
     * 获取当前持有动画的浅拷贝列表。
     *
     * @return 新的列表实例，调用方增删它不会影响管理器内部状态；元素仍是动画实例本身
     */
    public List<Animation> getActiveAnimations() {
        return new ArrayList<>(activeAnimations);
    }

    /**
     * 设置全局暂停状态，并同步设置当前持有的全部动画。
     *
     * 语义与 {@link #pauseAll()} / {@link #resumeAll()} 完全一致：暂停前记录调用方已暂停的动画，
     * 恢复时只还原此前在运行的那些，不会把调用方单独暂停的动画一并恢复。
     *
     * @param paused true 表示暂停全部动画；false 表示恢复到本次全局暂停之前的状态
     */
    public void setPaused(boolean paused) {
        if (paused) {
            pauseAll();
        } else {
            resumeAll();
        }
    }

    /** 检查管理器是否处于全局暂停。 */
    public boolean isPaused() {
        return paused;
    }

    /**
     * 重置管理器：停止并清空所有动画，解除全局暂停，并把时间基准刷新为当前时刻。
     *
     * 刷新时间基准是为了避免重置后的第一帧出现巨大 deltaTime，否则只能依赖 0.1 秒钳制兜底。
     */
    public void reset() {
        clearAllAnimations();
        paused = false;
        pausedBeforeGlobalPause.clear();
        lastUpdateTime = System.nanoTime();
    }
}
