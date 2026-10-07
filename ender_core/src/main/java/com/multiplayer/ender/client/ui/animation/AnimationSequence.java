/*
 * 本文件属于 EnderOnline 客户端界面层。
 *
 * 职责：把多个子动画按顺序串联成一个序列，并提供插入、移除、跳转与循环等编排能力。
 *
 * 关键约束：序列自身只决定「当前播放哪个子动画」，子动画的时间仍由 AnimationManager 独立推进。
 */
package com.multiplayer.ender.client.ui.animation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 动画序列，把多个子动画按顺序串联播放。
 *
 * 序列自身也是一个 Animation：它被注册进 AnimationManager，但它的 update 只用来轮询当前子动画
 * 是否播完，真正的时间推进发生在各子动画各自的 update 中。
 *
 * 设计约束：
 * 1. duration 是所持子动画持续时间之和，addAnimation、insertAnimation 与 removeAnimation 会同步增减。
 * 2. startCurrentAnimation 会调用子动画的 start，子动画因此被独立注册进 AnimationManager 一并推进；
 *    序列只负责切换「谁在当前播放」，不负责子动画的时间推进。
 * 3. parallel 字段目前没有任何实现读取，序列恒为串行播放。
 * 4. 序列忽略父类传入的 easedProgress，其自身的 progress 对编排没有意义。
 *
 * 线程安全性：与基类一致，无同步，只允许在客户端 UI 线程构造、修改与推进；
 * 内部 animations 是普通 ArrayList，禁止在 onUpdate 遍历期间由其他线程修改。
 *
 * @since 1.0
 * @see Animation
 * @see Transition
 */
public class AnimationSequence extends Animation {

    /** 序列持有的子动画，按播放顺序排列。播放期间不得由其他线程修改。 */
    private final List<Animation> animations = new ArrayList<>();

    /** 当前子动画下标，正常落在 [0, animations.size())；播完或清空后可能等于子动画数量。 */
    private int currentIndex = 0;

    /** 是否并行播放；当前没有任何实现读取，序列恒为串行。 */
    private boolean parallel = false; // 是否并行播放（暂不支持）

    /** 当前正在播放的子动画；未开始、已结束或已清空时为 null。 */
    private Animation currentAnimation;

    /**
     * 创建动画序列。
     *
     * 传给父类的持续时间为 0，父类会把它钳到 0.001 秒；实际时长由后续添加的子动画累计决定。
     *
     * @param name 序列名称，用于调试
     */
    public AnimationSequence(String name) {
        super(name, 0.0f); // 持续时间由子动画决定
    }

    /**
     * 追加子动画到序列末尾，并累加序列时长。
     *
     * 本方法不去重：同一个子动画实例可以被加入多次，此时它会在序列中播放多轮。
     *
     * @param animation 子动画，允许为 null，为 null 时忽略
     */
    public void addAnimation(Animation animation) {
        if (animation != null) {
            animations.add(animation);
            // 更新总持续时间（所有动画持续时间之和）
            duration += animation.getDuration();
        }
    }

    /**
     * 在指定位置插入子动画，并累加序列时长。
     *
     * 插入不会修正 currentIndex，在当前位置之前插入会让正在播放的位置发生偏移。
     *
     * @param index 插入位置，合法范围 [0, animations.size()]；越界时静默忽略
     * @param animation 子动画，允许为 null，为 null 时忽略
     */
    public void insertAnimation(int index, Animation animation) {
        if (animation != null && index >= 0 && index <= animations.size()) {
            animations.add(index, animation);
            duration += animation.getDuration();
        }
    }

    /**
     * 按实例移除子动画，并扣减序列时长。
     *
     * 不修正 currentIndex，移除当前动画之前的元素同样会让播放位置偏移。
     *
     * @param animation 目标子动画，允许为 null
     * @return 确实移除了返回 true；不存在或为 null 返回 false
     */
    public boolean removeAnimation(Animation animation) {
        boolean removed = animations.remove(animation);
        if (removed) {
            duration -= animation.getDuration();
        }
        return removed;
    }

    /**
     * 按位置移除子动画，并扣减序列时长。
     *
     * 不修正 currentIndex。
     *
     * @param index 目标位置
     * @return 被移除的子动画；越界时返回 null
     */
    public Animation removeAnimation(int index) {
        if (index >= 0 && index < animations.size()) {
            Animation removed = animations.remove(index);
            if (removed != null) {
                duration -= removed.getDuration();
            }
            return removed;
        }
        return null;
    }

    /** 获取序列持有的子动画总数（不是剩余未播放数量）。 */
    public int getAnimationCount() {
        return animations.size();
    }

    /**
     * 按位置获取子动画。
     *
     * @param index 目标位置
     * @return 子动画实例；越界时返回 null
     */
    public Animation getAnimation(int index) {
        if (index >= 0 && index < animations.size()) {
            return animations.get(index);
        }
        return null;
    }

    /**
     * 清空序列。
     *
     * 会归零下标、时长与当前动画引用；但不会停止或注销正在播放的子动画，
     * 需要同时停止时应先调用 stop。
     */
    public void clear() {
        animations.clear();
        duration = 0.0f;
        currentIndex = 0;
        currentAnimation = null;
    }

    /**
     * 序列的推进逻辑，由父类每帧调用。
     *
     * 本方法不使用 easedProgress：序列进度不是时间源，子动画各自在 AnimationManager 中推进，
     * 这里只轮询当前子动画是否结束并切换下标。序列为空时直接把自身标记为已完成。
     *
     * @param easedProgress 父类算出的缓动进度，本实现忽略
     * @param partialTick 部分游戏刻，本实现忽略
     */
    @Override
    protected void onUpdate(float easedProgress, float partialTick) {
        if (animations.isEmpty()) {
            completed = true;
            return;
        }

        // 如果当前没有活动动画，开始第一个
        if (currentAnimation == null && currentIndex < animations.size()) {
            startCurrentAnimation();
        }

        // 更新当前动画
        if (currentAnimation != null) {
            if (!currentAnimation.isActive() || currentAnimation.isCompleted()) {
                // 当前动画完成，开始下一个
                currentIndex++;
                if (currentIndex >= animations.size()) {
                    // 序列完成
                    if (loop) {
                        // 循环播放：重置索引。
                        // 父类读的是同一个字段（本类不再另立字段），因此这里的重置不会被完成判定覆盖。
                        currentIndex = 0;
                        startCurrentAnimation();
                    } else {
                        // 序列结束
                        completed = true;
                        currentAnimation = null;
                        return;
                    }
                } else {
                    startCurrentAnimation();
                }
            }
        }
    }

    /**
     * 把当前下标的子动画重置并启动。
     *
     * 私有方法，副作用不明显：子动画的 start 会把它独立注册进 AnimationManager，
     * 因此调用方无需再手动注册子动画。
     */
    private void startCurrentAnimation() {
        if (currentIndex < animations.size()) {
            currentAnimation = animations.get(currentIndex);
            currentAnimation.reset();
            currentAnimation.start();
        }
    }

    /**
     * 启动序列并重置播放位置。
     *
     * 覆写父类：先走父类流程完成注册与开始回调，再把下标、当前动画、时间与进度归零。
     * 父类 start 带有「已完成则不重启」的守卫，已播完的序列需改用 restart。
     */
    @Override
    public void start() {
        super.start();
        currentIndex = 0;
        currentAnimation = null;
        elapsedTime = 0.0f;
        progress = 0.0f;
    }

    /**
     * 停止序列，并停止正在播放的子动画。
     *
     * 覆写父类：子动画会被一并 stop 并从 AnimationManager 注销，
     * 避免序列结束后子动画仍在后台继续推进。
     */
    @Override
    public void stop() {
        super.stop();
        if (currentAnimation != null) {
            currentAnimation.stop();
        }
        currentAnimation = null;
    }

    /**
     * 暂停序列，并暂停正在播放的子动画。
     *
     * 子动画自身也注册在管理器中，只暂停序列并不会阻止子动画继续推进，必须一并暂停。
     */
    @Override
    public void pause() {
        super.pause();
        if (currentAnimation != null) {
            currentAnimation.pause();
        }
    }

    /**
     * 恢复序列，并恢复正在播放的子动画。
     *
     * 与父类相同的限制：无法区分暂停来源，会一并解除子动画上由调用方单独设置的暂停。
     */
    @Override
    public void resume() {
        super.resume();
        if (currentAnimation != null) {
            currentAnimation.resume();
        }
    }

    /**
     * 重置序列及其全部子动画。
     *
     * 覆写父类：除基类状态外还会归零下标并逐个 reset 子动画；子动画不会被注销，
     * 后续由 startCurrentAnimation 重新启动。
     */
    @Override
    public void reset() {
        super.reset();
        currentIndex = 0;
        currentAnimation = null;
        for (Animation animation : animations) {
            animation.reset();
        }
    }

    /**
     * 序列完成时的钩子，停止仍在活动的子动画。
     *
     * 覆写父类：必须先调用父类以保留完成回调，再清理子动画。
     */
    @Override
    public void onComplete() {
        super.onComplete();
        // 序列完成时停止所有子动画
        for (Animation animation : animations) {
            if (animation.isActive()) {
                animation.stop();
            }
        }
    }

    /**
     * 设置序列是否循环。
     *
     * 转发给父类字段。父类 {@code Animation.update} 的循环判定读的正是该字段，
     * 因此这里必须委托而不能另立字段，否则父类会把序列提前置为完成。
     *
     * @param loop true 表示播完一轮后从头再来
     */
    @Override
    public void setLoop(boolean loop) {
        super.setLoop(loop);
    }

    /** 检查序列是否被标记为循环；直接取父类字段，与 {@link Animation#isLoop()} 恒一致。 */
    @Override
    public boolean isLoop() {
        return super.isLoop();
    }

    /**
     * 获取当前子动画下标。
     *
     * @return 下标；序列播完或清空后可能等于子动画数量
     */
    public int getCurrentIndex() {
        return currentIndex;
    }

    /**
     * 获取当前正在播放的子动画。
     *
     * @return 子动画实例；未开始、已结束或已清空后返回 null
     */
    public Animation getCurrentAnimation() {
        return currentAnimation;
    }

    /**
     * 获取子动画的浅拷贝列表。
     *
     * @return 新的列表实例，修改它不影响序列；元素仍是子动画实例本身
     */
    public List<Animation> getAnimations() {
        return new ArrayList<>(animations);
    }

    /**
     * 跳转到指定下标的子动画。
     *
     * 会停止当前子动画、重置目标下标及其之后的全部子动画，并清空当前动画引用，
     * 由下一次 update 启动目标子动画。
     *
     * 注意：本方法不回退序列自身的 elapsedTime 与 progress，父类的完成判定仍按累计时间计算，
     * 因此向后跳转后序列可能很快被判定为已完成。
     *
     * @param index 目标下标；越界时静默忽略
     */
    public void seekTo(int index) {
        if (index >= 0 && index < animations.size()) {
            // 停止当前动画
            if (currentAnimation != null && currentAnimation.isActive()) {
                currentAnimation.stop();
            }

            currentIndex = index;
            currentAnimation = null;

            // 重置所有后续动画
            for (int i = index; i < animations.size(); i++) {
                animations.get(i).reset();
            }
        }
    }

    /** 检查序列是否没有任何子动画。 */
    public boolean isEmpty() {
        return animations.isEmpty();
    }

    /**
     * 创建并立即开始一个动画序列。
     *
     * @param name 序列名称
     * @param animations 子动画数组，按传入顺序串联；数组本身不能为 null，元素允许为 null
     * @return 已启动的序列实例，永不为 null
     */
    public static AnimationSequence createAndStart(String name, Animation... animations) {
        AnimationSequence sequence = new AnimationSequence(name);
        for (Animation animation : animations) {
            sequence.addAnimation(animation);
        }
        sequence.start();
        return sequence;
    }

    /**
     * 创建并开始一个循环动画序列。
     *
     * 循环开关直接写在父类字段上，因此序列会真正从头重播而不是在首轮结束时停止。
     *
     * @param name 序列名称
     * @param animations 子动画数组，按传入顺序串联；数组本身不能为 null，元素允许为 null
     * @return 已启动的序列实例，永不为 null
     */
    public static AnimationSequence createAndStartLoop(String name, Animation... animations) {
        AnimationSequence sequence = createAndStart(name, animations);
        sequence.setLoop(true);
        return sequence;
    }
}
