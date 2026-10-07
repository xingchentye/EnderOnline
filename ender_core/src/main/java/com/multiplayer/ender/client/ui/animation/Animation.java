/*
 * 本文件属于 EnderOnline 客户端界面层。
 *
 * 职责：定义所有 UI 动画的公共基类，统一时间推进、缓动求值、状态流转与回调分发。
 *
 * 关键约束：动画实例与具体 UI 屏幕同生命周期，全部可变状态为实例字段，不做同步；
 * 时间推进的唯一入口是 update，由 AnimationManager 在客户端刻驱动。
 */
package com.multiplayer.ender.client.ui.animation;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 动画基类，定义动画的生命周期、时间推进与缓动求值。
 *
 * 动画以秒为单位累积 elapsedTime，进度 progress = elapsedTime / duration；
 * 每帧由 update 把缓动后的进度交给子类 onUpdate 驱动实际视觉效果。
 *
 * 设计约束：
 * 1. duration 由构造器与 setDuration 统一钳到不小于 0.001 秒，保证进度计算不会除零。
 * 2. 缓动函数输入 t 落在 [0, 1]；返回值通常也在 [0, 1]，但 elastic 与 back 系列会刻意
 *    短暂越界（弹性回弹与超调），此时插值结果会超出 start 到 end 的区间，这是设计意图。
 * 3. 子类只实现 onUpdate，不得自行推进时间；loop 语义由基类在 update 中统一处理。
 * 4. start 负责把自身注册进 AnimationManager，stop 负责注销，重复 start 由状态标志守卫。
 *
 * 线程安全性：本类所有可变字段（elapsedTime、progress、active、paused、completed、回调引用）
 * 均无同步保护，只允许在客户端 UI 线程读写。AnimationManager 的单例创建是同步的，
 * 但动画实例本身不是线程安全的，禁止跨线程共享同一个动画对象。
 *
 * @since 1.0
 * @see AnimationManager
 * @see Easing
 */
public abstract class Animation {

    /** 动画唯一标识，构造时由随机 UUID 生成，实例生命周期内不变。 */
    protected final String id;

    /** 动画名称，仅用于调试与日志；外部传入 null 时回退为 unnamed。 */
    protected final String name;

    /** 动画持续时间，单位秒，最小 0.001，由构造器与 setDuration 共同保证。 */
    protected float duration;

    /** 已累积的播放时间，单位秒；暂停与未启动期间不增长。 */
    protected float elapsedTime;

    /** 归一化进度 elapsedTime / duration，仅钳制上界 1.0，正常播放落在 [0, 1]。 */
    protected float progress;

    /** 是否处于活动状态；只有活动动画才会被 AnimationManager 推进。 */
    protected boolean active = false;

    /** 是否暂停；管理器级暂停与调用方单独 pause 共用这一个标志，无法区分来源。 */
    protected boolean paused = false;

    /** 是否已完成；完成且 autoRemove 为 true 时会被 AnimationManager 移除。 */
    protected boolean completed = false;

    /** 完成后是否由 AnimationManager 自动移除，默认 true。 */
    protected boolean autoRemove = true;

    /** 是否循环播放，默认 false；AnimationSequence 用同名字段遮蔽本字段，见该类说明。 */
    protected boolean loop = false;

    /** 缓动函数，默认 easeInOutQuad；setEasingFunction 保证不会把它置为 null。 */
    protected Easing.EasingFunction easingFunction = Easing::easeInOutQuad;

    /** 开始回调，允许为 null（表示不回调）。 */
    protected Consumer<Animation> onStartCallback;

    /** 每帧更新回调，允许为 null；在子类 onUpdate 之后触发。 */
    protected Consumer<Animation> onUpdateCallback;

    /** 完成回调，允许为 null；由 AnimationManager 检测到完成后经 onComplete 触发。 */
    protected Consumer<Animation> onCompleteCallback;

    /** 停止回调，允许为 null；由 stop 触发，播放自然完成不会触发本回调。 */
    protected Consumer<Animation> onStopCallback;

    /**
     * 创建动画实例。
     *
     * id 由随机 UUID 生成，name 为 null 时回退为 unnamed，duration 被钳制到最小 0.001 秒。
     *
     * @param name 动画名称，用于调试，允许为 null
     * @param duration 动画持续时间，单位秒；小于 0.001 时按 0.001 处理
     */
    protected Animation(String name, float duration) {
        this.id = UUID.randomUUID().toString();
        this.name = name != null ? name : "unnamed";
        this.duration = Math.max(0.001f, duration); // 最小持续时间
        this.elapsedTime = 0.0f;
        this.progress = 0.0f;
    }

    /**
     * 推进动画一个时间步并触发子类更新。
     *
     * 非活动、已暂停或已完成的动画会被直接忽略。进度取 elapsedTime / duration 并钳到上界 1.0；
     * 到达 1.0 时，loop 为 true 则原地重置时间与进度继续播放且不触发完成回调，
     * 否则置为已完成并转为非活动。
     *
     * 幂等性：本方法有状态副作用，不幂等；同一个 deltaTime 重复调用会重复推进时间。
     *
     * @param deltaTime 时间增量，单位秒；当前由调用方按墙上时钟差值给出，可能为负
     * @param partialTick 当前渲染帧的部分刻，透传给子类用于逐帧平滑，基类自身不使用
     */
    public void update(float deltaTime, float partialTick) {
        if (!active || paused || completed) {
            return;
        }

        // 更新已过去时间。
        // deltaTime 由调用方给出，墙上时钟回拨时会为负；这里先把时间增量钳到非负，
        // 保证 elapsedTime 单调不减，而不是把负值累积进去等后面兜底。
        if (deltaTime > 0.0f) {
            elapsedTime += deltaTime;
        }

        // 进度双向钳制到 [0, 1]：上界防止越界完成判定，下界防止缓动函数收到负输入
        // （elastic、back 一类超调缓动对越界输入会给出远离预期的结果）。
        progress = Math.min(1.0f, Math.max(0.0f, elapsedTime / duration));

        // 应用缓动函数
        float easedProgress = (float) easingFunction.apply(progress);

        // 调用子类的具体更新逻辑
        onUpdate(easedProgress, partialTick);

        // 触发更新回调
        if (onUpdateCallback != null) {
            onUpdateCallback.accept(this);
        }

        // 检查动画是否完成
        if (progress >= 1.0f) {
            if (loop) {
                // 循环播放：重置进度
                elapsedTime = 0.0f;
                progress = 0.0f;
                completed = false;
                // 溢出量（elapsedTime 超出 duration 的部分）在这里被直接丢弃，每个循环周期因此
                // 最多多出一帧的时长，长跑时会累积成可见的节奏漂移
                // 触发循环回调（如果有）
                // 注意：这里不调用onComplete()，因为循环动画不会"完成"
            } else {
                completed = true;
                active = false;
            }
        }
    }

    /**
     * 子类实现的效果推进逻辑。
     *
     * @param easedProgress 已应用缓动函数的进度；elastic 与 back 系列可能短暂越出 [0, 1]
     * @param partialTick 部分游戏刻，与 update 收到的值相同
     */
    protected abstract void onUpdate(float easedProgress, float partialTick);

    /**
     * 启动动画。
     *
     * 仅在既非活动也未完成时生效：置为活动、清除暂停、把时间与进度归零，把自身注册进
     * AnimationManager（注册过程会触发 onStart 钩子），最后触发开始回调。
     */
    public void start() {
        if (!active && !completed) {
            active = true;
            paused = false;
            elapsedTime = 0.0f;
            progress = 0.0f;

            AnimationManager.getInstance().addAnimation(this);

            // 触发开始回调
            if (onStartCallback != null) {
                onStartCallback.accept(this);
            }
        }
    }

    /**
     * 立即停止动画，并从 AnimationManager 注销。
     *
     * 本方法会把状态置为已完成，但不会触发完成回调，只触发停止回调，
     * 所以「被停止」与「播放完成」在回调层面是可区分的。
     */
    public void stop() {
        active = false;
        paused = false;
        completed = true;

        // 触发停止回调
        if (onStopCallback != null) {
            onStopCallback.accept(this);
        }

        // 从管理器中移除
        AnimationManager.getInstance().removeAnimation(this);
    }

    /**
     * 暂停动画。
     *
     * 仅在活动且未暂停时生效；暂停后 update 直接返回，elapsedTime 停止累积。
     */
    public void pause() {
        if (active && !paused) {
            paused = true;
        }
    }

    /**
     * 恢复动画。
     *
     * 仅在活动且已暂停时生效。
     */
    // FIXME(P3, 2026-10-06): paused 一个标志同时承担「调用方暂停」与「管理器暂停」两种语义，
    // 因此 AnimationManager.resumeAll 会把调用方此前单独暂停的动画一并恢复，丢失原有暂停状态。
    // 修复方向：为管理器级暂停引入独立标志，或在管理器内保存恢复前的逐动画暂停快照。
    public void resume() {
        if (active && paused) {
            paused = false;
        }
    }

    /**
     * 重置动画状态。
     *
     * 置为非活动、未暂停、未完成，并把时间与进度归零。本方法不会把自身从 AnimationManager
     * 注销；若 autoRemove 为 false，实例会一直留在管理器列表中直到显式 stop。
     */
    public void reset() {
        active = false;
        paused = false;
        completed = false;
        elapsedTime = 0.0f;
        progress = 0.0f;
    }

    /**
     * 重置后重新开始播放。
     *
     * 等价于先 reset 再 start，因此会重新注册进 AnimationManager。
     */
    public void restart() {
        reset();
        start();
    }

    /**
     * 动画完成时的钩子，由 AnimationManager 在检测到 completed 后调用。
     *
     * 默认实现只转发完成回调；子类重写时必须调用 super.onComplete()，否则完成回调会静默丢失。
     */
    public void onComplete() {
        // 触发完成回调
        if (onCompleteCallback != null) {
            onCompleteCallback.accept(this);
        }
    }

    /**
     * 动画开始时的钩子，由 AnimationManager.addAnimation 调用。
     *
     * 默认实现为空，供子类重写。执行时机早于 onStartCallback。
     */
    public void onStart() {
        // 子类可以重写此方法
    }

    /**
     * 设置缓动函数。
     *
     * @param easingFunction 缓动函数，允许为 null，为 null 时回退为 easeInOutQuad
     */
    public void setEasingFunction(Easing.EasingFunction easingFunction) {
        this.easingFunction = easingFunction != null ? easingFunction : Easing::easeInOutQuad;
    }

    /**
     * 按名称设置缓动函数。
     *
     * @param easingName 缓动函数名称，取值见 Easing.get；无法识别的名称会退化为线性缓动
     */
    public void setEasingFunction(String easingName) {
        this.easingFunction = Easing.get(easingName);
    }

    /**
     * 设置完成后是否由 AnimationManager 自动移除。
     *
     * @param autoRemove false 时实例不会被自动回收，必须由调用方显式 stop，否则持续占用管理器内存
     */
    public void setAutoRemove(boolean autoRemove) {
        this.autoRemove = autoRemove;
    }

    /**
     * 设置是否循环播放。
     *
     * 循环动画不会触发完成回调，也不会被自动移除，需要调用方在不需要时显式 stop。
     *
     * @param loop true 表示到达终点后原地重播
     */
    public void setLoop(boolean loop) {
        this.loop = loop;
    }

    /** 检查动画是否循环播放。 */
    public boolean isLoop() {
        return loop;
    }

    /** 设置开始回调，允许为 null。 */
    public void setOnStartCallback(Consumer<Animation> callback) {
        this.onStartCallback = callback;
    }

    /** 设置每帧更新回调，允许为 null。 */
    public void setOnUpdateCallback(Consumer<Animation> callback) {
        this.onUpdateCallback = callback;
    }

    /** 设置完成回调，允许为 null。 */
    public void setOnCompleteCallback(Consumer<Animation> callback) {
        this.onCompleteCallback = callback;
    }

    /** 设置停止回调，允许为 null。 */
    public void setOnStopCallback(Consumer<Animation> callback) {
        this.onStopCallback = callback;
    }

    /** 获取动画唯一标识，永不为 null。 */
    public String getId() {
        return id;
    }

    /** 获取动画名称，永不为 null。 */
    public String getName() {
        return name;
    }

    /** 获取动画持续时间，单位秒，最小 0.001。 */
    public float getDuration() {
        return duration;
    }

    /**
     * 设置动画持续时间。
     *
     * 只影响后续的进度计算，不会回退已累积的 elapsedTime；若新值小于 elapsedTime，
     * 下一次 update 会立即判定播放完成。移动端可据此按省电策略放宽动画节奏。
     *
     * @param duration 持续时间，单位秒；小于 0.001 时按 0.001 处理
     */
    public void setDuration(float duration) {
        this.duration = Math.max(0.001f, duration);
    }

    /** 获取已累积的播放时间，单位秒；暂停或未启动时不增长。 */
    public float getElapsedTime() {
        return elapsedTime;
    }

    /** 获取归一化进度，范围 [0, 1]；仅在 update 中被刷新。 */
    public float getProgress() {
        return progress;
    }

    /**
     * 获取应用缓动函数后的进度。
     *
     * 与 update 内部使用的值同源，但不是缓存的中间值，每次调用都会重新求值。
     *
     * @return 缓动后的进度；正常位于 [0, 1]，超调类缓动会短暂越界
     */
    public float getEasedProgress() {
        return (float) easingFunction.apply(progress);
    }

    /** 检查动画是否活动；只有活动动画会被 AnimationManager 推进。 */
    public boolean isActive() {
        return active;
    }

    /** 检查动画是否暂停。 */
    public boolean isPaused() {
        return paused;
    }

    /** 检查动画是否已完成。 */
    public boolean isCompleted() {
        return completed;
    }

    /** 检查完成后是否允许 AnimationManager 自动移除。 */
    public boolean shouldAutoRemove() {
        return autoRemove;
    }

    /** 获取剩余播放时间，单位秒，不小于 0；暂停期间不增长。 */
    public float getRemainingTime() {
        return Math.max(0.0f, duration - elapsedTime);
    }

    /**
     * 获取当前缓动函数。
     *
     * @return 缓动函数；本类保证不为 null，子类直接给 protected 字段赋值时不受此保证
     */
    public Easing.EasingFunction getEasingFunction() {
        return easingFunction;
    }

    /** 返回包含 id、名称、进度与状态标志的调试字符串。 */
    @Override
    public String toString() {
        return String.format("Animation{id='%s', name='%s', progress=%.2f, active=%s, paused=%s, completed=%s}",
                id, name, progress, active, paused, completed);
    }
}
