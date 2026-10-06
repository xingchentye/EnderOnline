/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：协议种类字符串的格式校验，确保请求与事件都使用 namespace:path 形式。
 */
package com.endercore.core.comm.protocol;

import com.endercore.core.comm.exception.CoreProtocolException;

 
/**
 * 协议种类字符串工具。
 *
 * 只做形状校验，不保存已注册的种类列表，也不与服务端的处理器表联动。
 *
 * 设计约束：合法形式为 namespace:path，冒号有且仅有一个且不在首尾；
 * 本类不校验 namespace 是否已注册，未注册的种类由服务端在分发阶段以错误响应处理。
 *
 * 线程安全性：本类无状态，静态方法可被任意线程并发调用。
 *
 * @since 1.0
 * @see CoreMessageType
 */
public final class CoreKinds {
    /** 私有构造函数，防止实例化。 */
    private CoreKinds() {
    }

    /**
     * 校验收到的协议种类字符串。
     *
     * 要求形如 namespace:path，冒号有且仅有一个，且冒号两侧都非空。
     *
     * @param kind 待校验的种类字符串，允许为 null，为 null 时视为校验失败
     * @throws CoreProtocolException 当 kind 为 null、为空串、不以命名空间开头、
     *                               以冒号结尾或包含多个冒号时抛出
     */
    public static void validate(String kind) {
        if (kind == null || kind.isEmpty()) {
            throw new CoreProtocolException("kind 不能为空");
        }
        int idx = kind.indexOf(':');
        if (idx <= 0 || idx >= kind.length() - 1) {
            throw new CoreProtocolException("kind 必须为 namespace:path: " + kind);
        }
        if (kind.indexOf(':', idx + 1) != -1) {
            throw new CoreProtocolException("kind 只能包含一个冒号: " + kind);
        }
    }
}
