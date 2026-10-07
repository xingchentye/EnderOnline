/*
 * 本文件属于 EnderOnline 客户端界面层。
 *
 * 职责：按值类型（数值、颜色、向量、角度、缩放、透明度）提供动画插值计算。
 *
 * NOTE: 全部方法是纯函数，只有 lerpVector 会分配数组；本类本身无状态，线程约束在调用侧。
 */
package com.multiplayer.ender.client.ui.animation;

/**
 * 动画插值工具。
 *
 * 统一契约：t 是插值因子，取值 [0, 1]，0 表示完全取起始值、1 表示完全取结束值。
 * 除角度与阻尼两个特例外，所有方法都是起始值到结束值的线性或缓动插值，不做钳制，
 * 传入 [0, 1] 之外的 t 等价于外推。
 *
 * 设计约束：
 * 1. 颜色一律以打包 int 传递，格式为 0xAARRGGBB（最高字节是 alpha）。
 * 2. 本类不校验参数区间，调用方应先用 clamp / clampT 收敛 t，尤其是当缓动函数为
 *    elastic 或 back 系列时（它们会产出越界值）。
 * 3. lerpAngle 与 damp 有各自的特殊语义，见方法注释，不要当作普通 lerp 使用。
 *
 * 线程安全性：本类无实例状态，所有方法都是纯计算的静态方法，可被多线程并发调用。
 * 例外是 damp —— 它会写入调用方传入的 velocity 数组，同一数组不得被多线程并发传入。
 *
 * @since 1.0
 * @see Easing
 */
public final class Interpolator {

    /**
     * 私有构造函数，禁止实例化。
     *
     * 本类是纯函数集合，实例化没有意义。
     */
    private Interpolator() {
        // 工具类，禁止实例化
    }

    /**
     * 单精度浮点线性插值。
     *
     * @param start 起始值
     * @param end   结束值
     * @param t     插值因子，期望取值 [0, 1]，越界时按外推计算
     * @return 插值结果，位于 start 与 end 之间（t 越界时可能超出）
     */
    public static float lerp(float start, float end, double t) {
        return (float) (start + (end - start) * t);
    }

    /**
     * 整数线性插值。
     *
     * NOTE: 结果由 double 强制截断为 int，截断方向是向零取整而不是向下取整，
     * 因此负数区间的插值在端点附近存在一格偏差；需要精确取整时应改用浮点版本再自行舍入。
     *
     * @param start 起始值
     * @param end   结束值
     * @param t     插值因子，期望取值 [0, 1]
     * @return 插值结果，向零取整
     */
    public static int lerp(int start, int end, double t) {
        return (int) (start + (end - start) * t);
    }

    /**
     * 双精度浮点线性插值。
     *
     * 用于需要更高中间精度的场景（例如后续还要参与多次运算的坐标计算）。
     *
     * @param start 起始值
     * @param end   结束值
     * @param t     插值因子，期望取值 [0, 1]
     * @return 插值结果
     */
    public static double lerp(double start, double end, double t) {
        return start + (end - start) * t;
    }

    /**
     * 带缓动函数的单精度浮点插值。
     *
     * 先用 easing 把 t 映射为缓动进度，再按线性插值计算。
     *
     * @param start 起始值
     * @param end   结束值
     * @param t     插值因子，期望取值 [0, 1]
     * @param easing 缓动函数，不能为 null；为 null 时抛 NullPointerException
     * @return 插值结果；缓动函数越界时结果同样越界
     */
    public static float lerp(float start, float end, double t, Easing.EasingFunction easing) {
        return lerp(start, end, easing.apply(t));
    }

    /**
     * 带缓动函数的整数插值。
     *
     * @param start 起始值
     * @param end   结束值
     * @param t     插值因子，期望取值 [0, 1]
     * @param easing 缓动函数，不能为 null
     * @return 插值结果，向零取整（见整数 lerp 的说明）
     */
    public static int lerp(int start, int end, double t, Easing.EasingFunction easing) {
        return lerp(start, end, easing.apply(t));
    }

    /**
     * 带缓动函数的双精度浮点插值。
     *
     * @param start 起始值
     * @param end   结束值
     * @param t     插值因子，期望取值 [0, 1]
     * @param easing 缓动函数，不能为 null
     * @return 插值结果
     */
    public static double lerp(double start, double end, double t, Easing.EasingFunction easing) {
        return lerp(start, end, easing.apply(t));
    }

    /**
     * 颜色线性插值。
     *
     * 四个通道（alpha、红、绿、蓝）各自独立插值后重新打包，因此不涉及色相路径选择，
     * 深色到浅色的过渡可能出现中间偏灰的现象，这是通道独立插值的固有结果。
     *
     * 与数值版 lerp 不同，本方法会把插值因子钳到 [0, 1]、并把每个通道结果钳到 [0, 255]。
     * 打包格式要求每个通道占满一个字节，越界分量会经位运算进位污染相邻通道，
     * 因此颜色路径上的钳制是结构正确性的前提，而不是可选的行为修饰。
     *
     * @param startColor 起始颜色，打包格式 0xAARRGGBB
     * @param endColor   结束颜色，打包格式 0xAARRGGBB
     * @param t          插值因子；超出 [0, 1] 时按边界处理
     * @return 插值后的颜色，打包格式 0xAARRGGBB，四个通道均落在 [0, 255]；
     *         每通道各取整一次，可能与逐通道精算值差 1
     */
    public static int lerpColor(int startColor, int endColor, double t) {
        // 打包结构要求每个通道落在 [0, 255]：越界分量会经位运算进位污染相邻通道
        // （例如 red 超过 255 溢出到 alpha），得到结构上错误的 ARGB。
        // 因此这里钳制两处：入参 t 抵抗超调缓动，逐通道结果抵抗越界端点或浮点误差。
        double clampedT = clampT(t);
        int a1 = (startColor >> 24) & 0xFF;
        int r1 = (startColor >> 16) & 0xFF;
        int g1 = (startColor >> 8) & 0xFF;
        int b1 = startColor & 0xFF;

        int a2 = (endColor >> 24) & 0xFF;
        int r2 = (endColor >> 16) & 0xFF;
        int g2 = (endColor >> 8) & 0xFF;
        int b2 = endColor & 0xFF;

        int a = clamp(lerp(a1, a2, clampedT), 0, 255);
        int r = clamp(lerp(r1, r2, clampedT), 0, 255);
        int g = clamp(lerp(g1, g2, clampedT), 0, 255);
        int b = clamp(lerp(b1, b2, clampedT), 0, 255);

        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * 带缓动函数的颜色插值。
     *
     * 先把 t 经 easing 映射为缓动进度，再交给 {@link #lerpColor(int, int, double)}，
     * 因此超调类缓动（elastic / back）的越界值会被该处的钳制吸收，不会产生越界通道。
     *
     * @param startColor 起始颜色，打包格式 0xAARRGGBB
     * @param endColor   结束颜色，打包格式 0xAARRGGBB
     * @param t          插值因子，期望取值 [0, 1]
     * @param easing     缓动函数，不能为 null
     * @return 插值后的颜色，打包格式 0xAARRGGBB，四个通道均落在 [0, 255]
     */
    public static int lerpColor(int startColor, int endColor, double t, Easing.EasingFunction easing) {
        return lerpColor(startColor, endColor, easing.apply(t));
    }

    /**
     * 二维向量插值。
     *
     * NOTE: 每次调用都会新建一个长度为 2 的数组，逐帧动画中会产生稳定的小对象分配；
     * 高频路径上应改为复用调用方的输出数组。
     *
     * @param startX 起始 X 坐标
     * @param startY 起始 Y 坐标
     * @param endX   结束 X 坐标
     * @param endY   结束 Y 坐标
     * @param t      插值因子，期望取值 [0, 1]
     * @return 长度为 2 的新数组，元素依次为 [X, Y]，永不为 null
     */
    public static float[] lerpVector(float startX, float startY, float endX, float endY, double t) {
        return new float[]{
            lerp(startX, endX, t),
            lerp(startY, endY, t)
        };
    }

    /**
     * 三维向量插值。
     *
     * @param startX 起始 X 坐标
     * @param startY 起始 Y 坐标
     * @param startZ 起始 Z 坐标
     * @param endX   结束 X 坐标
     * @param endY   结束 Y 坐标
     * @param endZ   结束 Z 坐标
     * @param t      插值因子，期望取值 [0, 1]
     * @return 长度为 3 的新数组，元素依次为 [X, Y, Z]，永不为 null
     */
    public static float[] lerpVector(float startX, float startY, float startZ, float endX, float endY, float endZ, double t) {
        return new float[]{
            lerp(startX, endX, t),
            lerp(startY, endY, t),
            lerp(startZ, endZ, t)
        };
    }

    /**
     * 缩放值插值。
     *
     * 语义上等价于浮点线性插值，单独成方法是为了让调用点表达意图。
     *
     * @param startScale 起始缩放值，通常大于 0（0 表示不可见，负值表示翻转）
     * @param endScale   结束缩放值
     * @param t          插值因子，期望取值 [0, 1]
     * @return 插值后的缩放值
     */
    public static float lerpScale(float startScale, float endScale, double t) {
        return lerp(startScale, endScale, t);
    }

    /**
     * 透明度插值。
     *
     * 语义上等价于浮点线性插值，单独成方法是为了让调用点表达意图。
     *
     * @param startAlpha 起始透明度，取值 [0, 1]
     * @param endAlpha   结束透明度，取值 [0, 1]
     * @param t          插值因子，期望取值 [0, 1]
     * @return 插值后的透明度，取值 [0, 1]；缓动越界时可能超出，需要调用方钳制
     */
    public static float lerpAlpha(float startAlpha, float endAlpha, double t) {
        return lerp(startAlpha, endAlpha, t);
    }

    /**
     * 角度插值。
     *
     * 先求最短转向：把端点差值归一到 (-180, 180]，再沿该方向插值。
     * 因此 350 度到 10 度会正向转过 20 度，而不是反向绕过 340 度。
     *
     * 返回值不做 [0, 360) 归一化，可能为负或超过 360，调用方按需自行取模。
     *
     * @param startAngle 起始角度，单位度
     * @param endAngle   结束角度，单位度
     * @param t          插值因子，期望取值 [0, 1]；越界时沿最短方向外推
     * @return 插值后的角度，单位度
     */
    public static float lerpAngle(float startAngle, float endAngle, double t) {
        // 处理角度环绕（360度）
        float difference = endAngle - startAngle;
        while (difference > 180) difference -= 360;
        while (difference < -180) difference += 360;

        return startAngle + (float) (difference * t);
    }

    /**
     * 阻尼插值，用于模拟弹性趋近目标的运动。
     *
     * 与 lerp 的区别是本方法是有状态的：除当前值与目标值外，还依赖并回写调用方传入的速度数组，
     * 因此必须逐帧连续调用才能得到正确轨迹，且 velocity[0] 由调用方负责初始化。
     *
     * NOTE: 实现采用「指数衰减积分」的一阶近似，frame-rate 无关性由 deltaTime 承担；
     * 同一速度数组不得被多个动画或线程共用。
     *
     * @param current   当前值
     * @param target    目标值
     * @param velocity  当前速度数组，允许为 null 或空数组，此时直接返回 target；
     *                  方法会写回 velocity[0]，调用方必须允许该副作用
     * @param damping   阻尼系数，单位秒，取值必须严格大于 0；为 0 时内部出现除零并返回 NaN
     * @param deltaTime 距上一帧的时间增量，单位秒，期望大于 0
     * @return 新的当前值；velocity 为 null 或空数组时返回 target
     */
    public static float damp(float current, float target, float[] velocity, float damping, float deltaTime) {
        if (velocity == null || velocity.length == 0) {
            return target;
        }

        float omega = 2.0f * (float) Math.PI / damping;
        float x = omega * deltaTime;
        float exp = 1.0f / (1.0f + x + 0.48f * x * x + 0.235f * x * x * x);

        float change = current - target;
        float temp = (velocity[0] + omega * change) * deltaTime;

        velocity[0] = (velocity[0] - omega * temp) * exp;
        return target + (change + temp) * exp;
    }

    /**
     * 平滑步进插值（smoothstep）。
     *
     * 把 x 从区间 [edge0, edge1] 归一化到 [0, 1] 后做三次平滑，边界处一阶导为 0，
     * 用于避免硬阈值切换带来的视觉跳变。
     *
     * @param edge0 下边缘
     * @param edge1 上边缘，必须大于 edge0；两者相等时内部除零并返回 NaN
     * @param x     输入值，小于 edge0 时返回 0，大于 edge1 时返回 1
     * @return 平滑插值结果，取值 [0, 1]
     */
    public static float smoothstep(float edge0, float edge1, float x) {
        // 将x限制在[0, 1]范围内
        float t = Math.max(0, Math.min(1, (x - edge0) / (edge1 - edge0)));
        return t * t * (3 - 2 * t);
    }

    /**
     * 更平滑的步进插值（smootherstep）。
     *
     * 与 smoothstep 的区别是使用五次多项式，边界处一阶导与二阶导同时为 0，
     * 过渡更柔和但计算量略高。
     *
     * @param edge0 下边缘
     * @param edge1 上边缘，必须大于 edge0；两者相等时内部除零并返回 NaN
     * @param x     输入值，小于 edge0 时返回 0，大于 edge1 时返回 1
     * @return 平滑插值结果，取值 [0, 1]
     */
    public static float smootherstep(float edge0, float edge1, float x) {
        // 将x限制在[0, 1]范围内
        float t = Math.max(0, Math.min(1, (x - edge0) / (edge1 - edge0)));
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    /**
     * 把值从一个区间线性映射到另一个区间。
     *
     * 归一化结果是区间比例而非 [0, 1]，超出原区间时按同一比例外推，不做钳制。
     *
     * @param value   输入值
     * @param oldMin  原范围最小值
     * @param oldMax  原范围最大值
     * @param newMin  新范围最小值
     * @param newMax  新范围最大值
     * @return 映射后的值；oldMax 与 oldMin 相等（原区间退化）时返回 newMin
     */
    public static float normalize(float value, float oldMin, float oldMax, float newMin, float newMax) {
        if (oldMax == oldMin) return newMin;
        return newMin + (value - oldMin) * (newMax - newMin) / (oldMax - oldMin);
    }

    /**
     * 把值钳制到 [min, max]。
     *
     * @param value 输入值
     * @param min   最小值
     * @param max   最大值，期望不小于 min；若传反，结果由 Math.max 与外层 Math.min 的运算顺序决定，
     *              本方法不做校验
     * @return 钳制后的值
     */
    public static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 把整数钳制到 [min, max]。
     *
     * @param value 输入值
     * @param min   最小值
     * @param max   最大值，期望不小于 min，传反时不做校验
     * @return 钳制后的值
     */
    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 把插值因子钳制到 [0, 1]。
     *
     * 与 clamp(t, 0, 1) 等价，单独成方法是为了给「动画进度」这一特定语义提供统一入口。
     *
     * @param t 插值因子
     * @return 钳制到 [0, 1] 的插值因子；入参为 NaN 时返回 NaN
     */
    public static double clampT(double t) {
        return Math.max(0.0, Math.min(1.0, t));
    }
}
