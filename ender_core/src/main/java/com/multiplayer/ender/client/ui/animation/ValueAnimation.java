/*
 * 本文件属于 EnderOnline 客户端界面层。
 *
 * 职责：在两个数值之间按缓动进度插值，是位移、缩放、透明度与进度条等效果的基础实现。
 *
 * 关键约束：值统一以 float 存储与插值，整数与双精度构造器只是便利重载。
 */
package com.multiplayer.ender.client.ui.animation;

import java.util.function.Consumer;

/**
 * 数值动画，在两个数值之间按缓动进度插值。
 *
 * 值一律以 float 存储与插值；整数与双精度构造器只是便利重载，最终都会收敛到 float 精度。
 *
 * 设计约束：
 * 1. 三个构造器最终都委托给 float 版本，double 入参会先窄化为 float，
 *    超出 float 有效位数的大值会丢精度，需要精确保留时应改用双精度计算并自行缩放。
 * 2. 插值按 easedProgress 直接线性外推，不做范围钳制：超调类缓动会得到超出 start 到 end 的值，
 *    进度条之类的场景若不能接受越界，需由调用方自行钳制。
 * 3. 起始值与结束值可在播放中通过 setValues 修改，只影响后续帧，不重算已发出的值。
 * 4. 回调是唯一的输出通道，本类不持有任何渲染目标。
 *
 * 线程安全性：无同步，只允许在客户端 UI 线程创建、修改与推进；
 * 值回调在推进线程上同步执行，实现方不得阻塞或回改本动画。
 *
 * @since 1.0
 * @see Interpolator#lerp(float, float, double)
 * @see ColorAnimation
 */
public class ValueAnimation extends Animation {

    /**
     * 数值更新回调。
     *
     * 每个 update 帧回调一次，参数是插值后的当前值；超调类缓动下该值可能越出起止区间。
     *
     * 线程安全性：在推进动画的客户端 UI 线程上同步执行，实现方不得阻塞。
     */
    @FunctionalInterface
    public interface ValueUpdateCallback {
        void onValueUpdate(float value);
    }

    /** 起始值；可由 setStartValue 或 setValues 修改，不限定取值范围与单位。 */
    private float startValue;

    /** 结束值；可由 setEndValue 或 setValues 修改，不限定取值范围与单位。 */
    private float endValue;

    /** 最近一帧插值得到的值；构造后初始值为起始值。 */
    private float currentValue;

    /** 值更新回调，允许为 null（表示不回调）。 */
    private ValueUpdateCallback valueUpdateCallback;

    /**
     * 创建浮点数数值动画。
     *
     * @param name 动画名称
     * @param duration 动画持续时间，单位秒；小于 0.001 时按 0.001 处理
     * @param start 起始值，单位由调用方约定
     * @param end 结束值，单位由调用方约定
     */
    public ValueAnimation(String name, float duration, float start, float end) {
        super(name, duration);
        this.startValue = start;
        this.endValue = end;
        this.currentValue = start;
    }

    /**
     * 创建整数数值动画。
     *
     * 入参在构造时即窄化为 float，大整数会丢精度；插值结果同样保持浮点，取整只在读取时发生。
     *
     * @param name 动画名称
     * @param duration 动画持续时间，单位秒；小于 0.001 时按 0.001 处理
     * @param start 起始值
     * @param end 结束值
     */
    public ValueAnimation(String name, float duration, int start, int end) {
        this(name, duration, (float) start, (float) end);
    }

    /**
     * 创建双精度数数值动画。
     *
     * 入参在此处窄化为 float，超过 float 精度的位会被丢弃；本类不提供真正的双精度插值路径。
     *
     * @param name 动画名称
     * @param duration 动画持续时间，单位秒；小于 0.001 时按 0.001 处理
     * @param start 起始值
     * @param end 结束值
     */
    public ValueAnimation(String name, float duration, double start, double end) {
        this(name, duration, (float) start, (float) end);
    }

    /**
     * 按缓动进度刷新当前值并触发回调。
     *
     * @param easedProgress 已应用缓动函数的进度；超调类缓动会短暂越出 [0, 1]
     * @param partialTick 部分游戏刻，本实现忽略
     */
    @Override
    protected void onUpdate(float easedProgress, float partialTick) {
        // 使用插值器计算当前值
        currentValue = Interpolator.lerp(startValue, endValue, easedProgress);

        // 触发值更新回调
        if (valueUpdateCallback != null) {
            valueUpdateCallback.onValueUpdate(currentValue);
        }
    }

    /**
     * 设置值更新回调。
     *
     * @param callback 回调函数，接收当前值，允许为 null（表示清除回调）
     */
    public void setValueUpdateCallback(ValueUpdateCallback callback) {
        this.valueUpdateCallback = callback;
    }

    /**
     * 设置值更新回调，Consumer 适配重载。
     *
     * 与接口重载构成同一方法名的两组签名，传 lambda 时可能产生歧义，新代码请显式转换类型。
     *
     * @param callback 回调函数，允许为 null（表示清除回调）
     */
    public void setValueUpdateCallback(Consumer<Float> callback) {
        if (callback != null) {
            this.valueUpdateCallback = callback::accept;
        } else {
            this.valueUpdateCallback = null;
        }
    }

    /** 获取最近一帧插值得到的值；未推进前等于起始值。 */
    public float getCurrentValue() {
        return currentValue;
    }

    /**
     * 获取当前值并按 Math.round 取整。
     *
     * 半值一律向正无穷方向取整，因此 -0.5 得到 0、0.5 得到 1。
     *
     * @return 取整后的当前值
     */
    public int getCurrentValueAsInt() {
        return Math.round(currentValue);
    }

    /**
     * 获取当前值。
     *
     * 返回类型虽是 double，实际精度仍为 float，不产生额外精度。
     *
     * @return 当前值的双精度表示
     */
    public double getCurrentValueAsDouble() {
        return currentValue;
    }

    /** 获取起始值。 */
    public float getStartValue() {
        return startValue;
    }

    /**
     * 设置起始值。
     *
     * 只影响后续帧的插值，不重置播放进度。
     *
     * @param startValue 起始值
     */
    public void setStartValue(float startValue) {
        this.startValue = startValue;
    }

    /** 获取结束值。 */
    public float getEndValue() {
        return endValue;
    }

    /**
     * 设置结束值。
     *
     * 只影响后续帧的插值，不重置播放进度。
     *
     * @param endValue 结束值
     */
    public void setEndValue(float endValue) {
        this.endValue = endValue;
    }

    /**
     * 同时设置起始值与结束值。
     *
     * @param start 起始值
     * @param end 结束值
     */
    public void setValues(float start, float end) {
        this.startValue = start;
        this.endValue = end;
    }

    /**
     * 同时设置起始值与结束值，整数重载。
     *
     * @param start 起始值
     * @param end 结束值
     */
    public void setValues(int start, int end) {
        setValues((float) start, (float) end);
    }

    /**
     * 同时设置起始值与结束值，双精度重载；入参会窄化为 float。
     *
     * @param start 起始值
     * @param end 结束值
     */
    public void setValues(double start, double end) {
        setValues((float) start, (float) end);
    }

    /**
     * 创建并立即开始一个数值动画。
     *
     * @param name 动画名称
     * @param duration 持续时间，单位秒；小于 0.001 时按 0.001 处理
     * @param start 起始值
     * @param end 结束值
     * @param callback 值更新回调，允许为 null（表示不回调）
     * @return 已启动的动画实例，永不为 null
     */
    public static ValueAnimation createAndStart(String name, float duration, float start, float end, Consumer<Float> callback) {
        ValueAnimation animation = new ValueAnimation(name, duration, start, end);
        if (callback != null) {
            animation.setValueUpdateCallback(callback);
        }
        animation.start();
        return animation;
    }

    /**
     * 创建并立即开始一个整数数值动画。
     *
     * 回调收到 Math.round 取整后的整数，半值向正无穷方向取整（-0.5 得到 0）。
     *
     * @param name 动画名称
     * @param duration 持续时间，单位秒；小于 0.001 时按 0.001 处理
     * @param start 起始值
     * @param end 结束值
     * @param callback 值更新回调，接收取整后的值，允许为 null（表示不回调）
     * @return 已启动的动画实例，永不为 null
     */
    public static ValueAnimation createAndStart(String name, float duration, int start, int end, Consumer<Integer> callback) {
        ValueAnimation animation = new ValueAnimation(name, duration, start, end);
        if (callback != null) {
            animation.setValueUpdateCallback((ValueUpdateCallback) (value -> callback.accept(Math.round(value))));
        }
        animation.start();
        return animation;
    }
}
