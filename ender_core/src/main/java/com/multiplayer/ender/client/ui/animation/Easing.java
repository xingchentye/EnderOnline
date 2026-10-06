/*
 * 本文件属于 EnderOnline 客户端界面层。
 *
 * 职责：动画用的缓动函数库与按名称查找缓动函数的入口。
 *
 * NOTE: 全部函数都是纯函数且无实例状态，因此本类可以安全地被任何线程共用；
 * 但调用方（动画驱动）通常只应在 UI 线程推进，线程约束在调用侧而不在这里。
 */
package com.multiplayer.ender.client.ui.animation;

/**
 * 缓动函数集合。
 *
 * 统一契约：入参 t 是标准化进度，取值 [0, 1]；返回值是缓动后的进度。
 * 大部分函数在 [0, 1] 内单调且端点满足 f(0)=0、f(1)=1，但有三类例外必须由调用方处理：
 *
 * 1. elastic 与 back 系列在两端会短暂越过 [0, 1]，这是刻意的过冲/回拉效果。
 * 2. bounce 系列的中间值不会越界，但导数不连续，用于表达碰撞回弹。
 * 3. 单侧函数（easeInX / easeOutX）只保证一端精确，另一端由公式自然逼近。
 *
 * 因此把缓动结果直接用于颜色、透明度或几何尺寸时，调用方必须先自行钳制到合法取值范围。
 *
 * 设计约束：
 * 1. expo、circ、elastic 系列都带 t==0 与 t==1 的边界短路，避免浮点边界上出现 NaN 或越界。
 * 2. elapsed 类函数不做 t 的合法性检查，传入 [0, 1] 之外的值等价于外推，结果同样可能越界。
 * 3. get 对未登记的名称静默返回线性函数，不抛异常；这意味着缓动名拼错不会报错，
 *    而是表现为「动画没有缓动」。新增缓动函数时必须同步登记进 get 的 switch。
 *
 * 线程安全性：本类无实例状态，所有方法都是纯计算的静态方法，可被多线程并发调用。
 *
 * @since 1.0
 * @see Interpolator
 */
public final class Easing {

    /**
     * 私有构造函数，禁止实例化。
     *
     * 本类是纯函数集合，实例化没有意义。
     */
    private Easing() {
        // 工具类，禁止实例化
    }

    /**
     * 线性缓动，直接返回进度本身。
     *
     * 用作其余缓动函数的参数基线与 get 的兜底实现。
     *
     * @param t 标准化进度，期望取值 [0, 1]；越界时原样返回，不做钳制
     * @return 与入参相等的进度值
     */
    public static double linear(double t) {
        return t;
    }

    /**
     * 二次方缓入，起点慢、终点快。
     *
     * @param t 标准化进度，期望取值 [0, 1]
     * @return 缓动后的进度，取值 [0, 1]，t 越界时为抛物线外推
     */
    public static double easeInQuad(double t) {
        return t * t;
    }

    /**
     * 二次方缓出，起点快、终点慢。
     *
     * @param t 标准化进度，期望取值 [0, 1]
     * @return 缓动后的进度，取值 [0, 1]
     */
    public static double easeOutQuad(double t) {
        return t * (2 - t);
    }

    /**
     * 二次方缓入缓出，中段加速、两端减速。
     *
     * @param t 标准化进度，期望取值 [0, 1]
     * @return 缓动后的进度，取值 [0, 1]
     */
    public static double easeInOutQuad(double t) {
        return t < 0.5 ? 2 * t * t : -1 + (4 - 2 * t) * t;
    }

    /**
     * 三次方缓入。
     *
     * @param t 标准化进度，期望取值 [0, 1]
     * @return 缓动后的进度，取值 [0, 1]
     */
    public static double easeInCubic(double t) {
        return t * t * t;
    }

    /**
     * 三次方缓出。
     *
     * @param t 标准化进度，期望取值 [0, 1]
     * @return 缓动后的进度，取值 [0, 1]
     */
    public static double easeOutCubic(double t) {
        return --t * t * t + 1;
    }

    /**
     * 三次方缓入缓出。
     *
     * @param t 标准化进度，期望取值 [0, 1]
     * @return 缓动后的进度，取值 [0, 1]
     */
    public static double easeInOutCubic(double t) {
        return t < 0.5 ? 4 * t * t * t : (t - 1) * (2 * t - 2) * (2 * t - 2) + 1;
    }

    /**
     * 四次方缓入。
     *
     * @param t 标准化进度，期望取值 [0, 1]
     * @return 缓动后的进度，取值 [0, 1]
     */
    public static double easeInQuart(double t) {
        return t * t * t * t;
    }

    /**
     * 四次方缓出。
     *
     * @param t 标准化进度，期望取值 [0, 1]
     * @return 缓动后的进度，取值 [0, 1]
     */
    public static double easeOutQuart(double t) {
        return 1 - --t * t * t * t;
    }

    /**
     * 四次方缓入缓出。
     *
     * @param t 标准化进度，期望取值 [0, 1]
     * @return 缓动后的进度，取值 [0, 1]
     */
    public static double easeInOutQuart(double t) {
        return t < 0.5 ? 8 * t * t * t * t : 1 - 8 * --t * t * t * t;
    }

    /**
     * 五次方缓入。
     *
     * @param t 标准化进度，期望取值 [0, 1]
     * @return 缓动后的进度，取值 [0, 1]
     */
    public static double easeInQuint(double t) {
        return t * t * t * t * t;
    }

    /**
     * 五次方缓出。
     *
     * @param t 标准化进度，期望取值 [0, 1]
     * @return 缓动后的进度，取值 [0, 1]
     */
    public static double easeOutQuint(double t) {
        return 1 + --t * t * t * t * t;
    }

    /**
     * 五次方缓入缓出。
     *
     * @param t 标准化进度，期望取值 [0, 1]
     * @return 缓动后的进度，取值 [0, 1]
     */
    public static double easeInOutQuint(double t) {
        return t < 0.5 ? 16 * t * t * t * t * t : 1 + 16 * --t * t * t * t * t;
    }

    /**
     * 正弦缓入，曲线比多项式缓动更柔和。
     *
     * @param t 标准化进度，期望取值 [0, 1]
     * @return 缓动后的进度，取值 [0, 1]
     */
    public static double easeInSine(double t) {
        return 1 - Math.cos(t * Math.PI / 2);
    }

    /**
     * 正弦缓出。
     *
     * @param t 标准化进度，期望取值 [0, 1]
     * @return 缓动后的进度，取值 [0, 1]
     */
    public static double easeOutSine(double t) {
        return Math.sin(t * Math.PI / 2);
    }

    /**
     * 正弦缓入缓出。
     *
     * @param t 标准化进度，期望取值 [0, 1]
     * @return 缓动后的进度，取值 [0, 1]
     */
    public static double easeInOutSine(double t) {
        return -(Math.cos(Math.PI * t) - 1) / 2;
    }

    /**
     * 指数缓入，起步极慢、接近终点时急剧加速。
     *
     * @param t 标准化进度，期望取值 [0, 1]
     * @return 缓动后的进度，取值 [0, 1]；t 为 0 时短路返回 0
     */
    public static double easeInExpo(double t) {
        return t == 0 ? 0 : Math.pow(2, 10 * (t - 1));
    }

    /**
     * 指数缓出，起步极快、接近终点时急剧减速。
     *
     * @param t 标准化进度，期望取值 [0, 1]
     * @return 缓动后的进度，取值 [0, 1]；t 为 1 时短路返回 1
     */
    public static double easeOutExpo(double t) {
        return t == 1 ? 1 : 1 - Math.pow(2, -10 * t);
    }

    /**
     * 指数缓入缓出。
     *
     * @param t 标准化进度，期望取值 [0, 1]
     * @return 缓动后的进度，取值 [0, 1]；t 为 0 或 1 时短路返回端点值
     */
    public static double easeInOutExpo(double t) {
        if (t == 0) return 0;
        if (t == 1) return 1;
        return t < 0.5 ? Math.pow(2, 20 * t - 10) / 2 : (2 - Math.pow(2, -20 * t + 10)) / 2;
    }

    /**
     * 圆形缓入，等价于四分之一圆的纵向投影。
     *
     * @param t 标准化进度，期望取值 [0, 1]；超出该范围时根号内为负会得到 NaN
     * @return 缓动后的进度，取值 [0, 1]
     */
    public static double easeInCirc(double t) {
        return 1 - Math.sqrt(1 - t * t);
    }

    /**
     * 圆形缓出。
     *
     * @param t 标准化进度，期望取值 [0, 1]；超出该范围时根号内为负会得到 NaN
     * @return 缓动后的进度，取值 [0, 1]
     */
    public static double easeOutCirc(double t) {
        return Math.sqrt(1 - --t * t);
    }

    /**
     * 圆形缓入缓出。
     *
     * @param t 标准化进度，期望取值 [0, 1]；超出该范围时可能得到 NaN
     * @return 缓动后的进度，取值 [0, 1]
     */
    public static double easeInOutCirc(double t) {
        return t < 0.5 ? (1 - Math.sqrt(1 - 4 * t * t)) / 2 : (Math.sqrt(1 - (-2 * t + 2) * (-2 * t + 2)) + 1) / 2;
    }

    /**
     * 弹性缓入，模拟被拉长后释放的弹簧。
     *
     * @param t 标准化进度，期望取值 [0, 1]
     * @return 缓动后的进度；t 为 0 或 1 时精确返回端点，中间会**越过 0 变为负值**，
     *         调用方若用于颜色或不透明度必须先钳制
     */
    public static double easeInElastic(double t) {
        if (t == 0) return 0;
        if (t == 1) return 1;
        return -Math.pow(2, 10 * (t - 1)) * Math.sin((t - 1.1) * 5 * Math.PI);
    }

    /**
     * 弹性缓出，模拟阻尼振荡收敛到终点。
     *
     * @param t 标准化进度，期望取值 [0, 1]
     * @return 缓动后的进度；t 为 0 或 1 时精确返回端点，中间会**越过 1**，
     *         调用方若用于颜色或不透明度必须先钳制
     */
    public static double easeOutElastic(double t) {
        if (t == 0) return 0;
        if (t == 1) return 1;
        return Math.pow(2, -10 * t) * Math.sin((t - 0.1) * 5 * Math.PI) + 1;
    }

    /**
     * 弹性缓入缓出。
     *
     * @param t 标准化进度，期望取值 [0, 1]
     * @return 缓动后的进度；t 为 0 或 1 时精确返回端点，中间会**越过 [0, 1] 两端**，
     *         调用方若用于颜色或不透明度必须先钳制
     */
    public static double easeInOutElastic(double t) {
        if (t == 0) return 0;
        if (t == 1) return 1;
        return t < 0.5 ?
            -(Math.pow(2, 20 * t - 10) * Math.sin((20 * t - 11.125) * Math.PI * 2 / 4.5)) / 2 :
            (Math.pow(2, -20 * t + 10) * Math.sin((20 * t - 11.125) * Math.PI * 2 / 4.5)) / 2 + 1;
    }

    /**
     * 回弹缓入，起步先向反方向蓄力。
     *
     * @param t 标准化进度，期望取值 [0, 1]
     * @return 缓动后的进度，前段会**低于 0**（反方向蓄力），调用方需钳制
     */
    public static double easeInBack(double t) {
        final double c1 = 1.70158;
        final double c3 = c1 + 1;
        return c3 * t * t * t - c1 * t * t;
    }

    /**
     * 回弹缓出，冲过终点后回拉。
     *
     * @param t 标准化进度，期望取值 [0, 1]
     * @return 缓动后的进度，后段会**高于 1**（冲过终点），调用方需钳制
     */
    public static double easeOutBack(double t) {
        final double c1 = 1.70158;
        final double c3 = c1 + 1;
        return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
    }

    /**
     * 回弹缓入缓出，两端都带蓄力与回拉。
     *
     * @param t 标准化进度，期望取值 [0, 1]
     * @return 缓动后的进度，会**越过 [0, 1] 两端**，调用方需钳制
     */
    public static double easeInOutBack(double t) {
        final double c1 = 1.70158;
        final double c2 = c1 * 1.525;
        return t < 0.5
            ? (Math.pow(2 * t, 2) * ((c2 + 1) * 2 * t - c2)) / 2
            : (Math.pow(2 * t - 2, 2) * ((c2 + 1) * (t * 2 - 2) + c2) + 2) / 2;
    }

    /**
     * 反弹缓入。
     *
     * 实现上是「反弹缓出」的时间反转，因此两者必须保持配对一致。
     *
     * @param t 标准化进度，期望取值 [0, 1]
     * @return 缓动后的进度，取值 [0, 1]
     */
    public static double easeInBounce(double t) {
        return 1 - easeOutBounce(1 - t);
    }

    /**
     * 反弹缓出，模拟落地后逐次减弱的弹跳。
     *
     * 分段常量 2.75 与 7.5625 来自标准 bounce 模型，各段偏移量 0.75 / 0.9375 / 0.984375
     * 是保证分段连续的修正项，改动其一必须同步重算其余。
     *
     * @param t 标准化进度，期望取值 [0, 1]
     * @return 缓动后的进度，取值 [0, 1] 且分段连续
     */
    public static double easeOutBounce(double t) {
        if (t < 1 / 2.75) {
            return 7.5625 * t * t;
        } else if (t < 2 / 2.75) {
            return 7.5625 * (t -= 1.5 / 2.75) * t + 0.75;
        } else if (t < 2.5 / 2.75) {
            return 7.5625 * (t -= 2.25 / 2.75) * t + 0.9375;
        } else {
            return 7.5625 * (t -= 2.625 / 2.75) * t + 0.984375;
        }
    }

    /**
     * 反弹缓入缓出，前半段反向弹跳、后半段正向弹跳。
     *
     * @param t 标准化进度，期望取值 [0, 1]
     * @return 缓动后的进度，取值 [0, 1] 且分段连续
     */
    public static double easeInOutBounce(double t) {
        return t < 0.5
            ? (1 - easeOutBounce(1 - 2 * t)) / 2
            : (1 + easeOutBounce(2 * t - 1)) / 2;
    }

    /**
     * 按名称查找缓动函数。
     *
     * 名称与 get 的 switch 分支一一对应，取值形如 "easeInOutQuad"、"easeOutBack"。
     *
     * NOTE: 名称未登记时**静默返回线性函数**，不抛异常也不报错，拼错缓动名只会表现为
     * 「动画没有缓动效果」。新增缓动函数必须同步登记到这里。
     *
     * @param name 缓动函数名称，不能为 null；为 null 时 switch 会抛 NullPointerException
     * @return 对应的缓动函数，永不为 null；未知名称返回等价于 linear 的实现
     */
    public static EasingFunction get(String name) {
        return switch (name) {
            case "linear" -> Easing::linear;
            case "easeInQuad" -> Easing::easeInQuad;
            case "easeOutQuad" -> Easing::easeOutQuad;
            case "easeInOutQuad" -> Easing::easeInOutQuad;
            case "easeInCubic" -> Easing::easeInCubic;
            case "easeOutCubic" -> Easing::easeOutCubic;
            case "easeInOutCubic" -> Easing::easeInOutCubic;
            case "easeInQuart" -> Easing::easeInQuart;
            case "easeOutQuart" -> Easing::easeOutQuart;
            case "easeInOutQuart" -> Easing::easeInOutQuart;
            case "easeInQuint" -> Easing::easeInQuint;
            case "easeOutQuint" -> Easing::easeOutQuint;
            case "easeInOutQuint" -> Easing::easeInOutQuint;
            case "easeInSine" -> Easing::easeInSine;
            case "easeOutSine" -> Easing::easeOutSine;
            case "easeInOutSine" -> Easing::easeInOutSine;
            case "easeInExpo" -> Easing::easeInExpo;
            case "easeOutExpo" -> Easing::easeOutExpo;
            case "easeInOutExpo" -> Easing::easeInOutExpo;
            case "easeInCirc" -> Easing::easeInCirc;
            case "easeOutCirc" -> Easing::easeOutCirc;
            case "easeInOutCirc" -> Easing::easeInOutCirc;
            case "easeInElastic" -> Easing::easeInElastic;
            case "easeOutElastic" -> Easing::easeOutElastic;
            case "easeInOutElastic" -> Easing::easeInOutElastic;
            case "easeInBack" -> Easing::easeInBack;
            case "easeOutBack" -> Easing::easeOutBack;
            case "easeInOutBack" -> Easing::easeInOutBack;
            case "easeInBounce" -> Easing::easeInBounce;
            case "easeOutBounce" -> Easing::easeOutBounce;
            case "easeInOutBounce" -> Easing::easeInOutBounce;
            default -> Easing::linear;
        };
    }

    /**
     * 缓动函数契约。
     *
     * 契约说明：
     * 1. 实现必须是纯函数：相同入参必得相同结果，不得持有或修改外部状态。
     * 2. 返回值语义是「缓动后的进度」，实现方不保证端点精确，调用方需按具体函数自行钳制。
     * 3. 实现必须是线程安全的，因为同一个函数实例可能被多个动画共享。
     *
     * @since 1.0
     */
    @FunctionalInterface
    public interface EasingFunction {

        /**
         * 把标准化进度映射为缓动后的进度。
         *
         * @param t 标准化进度，期望取值 [0, 1]
         * @return 缓动后的进度；是否可能越界取决于具体实现
         */
        double apply(double t);
    }
}
