/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：定义帧头中消息类型字段的取值与语义。
 */
package com.endercore.core.comm.protocol;

 
/**
 * 消息类型。
 *
 * 四类消息共用帧头中一个字节的类型字段，code 与 CoreProtocol 的帧布局绑定，数值不得随意调整。
 *
 * 语义关系：REQUEST 与 RESPONSE 通过 requestId 配对；EVENT 是单向消息，不产生响应；
 * HEARTBEAT 由服务端原样回送，仅用于探测链路连通性。
 *
 * 线程安全性：枚举常量不可变，code() 与 from(byte) 都不读写共享可变状态，可被任意线程并发调用。
 *
 * @since 1.0
 * @see CoreFrame
 * @see CoreProtocol
 */
public enum CoreMessageType {
    /** 请求消息，由客户端发出，需要服务端返回相同 requestId 的响应。 */
    REQUEST((byte) 0),

    /** 响应消息，requestId 与对应请求一致，status 非零表示失败。 */
    RESPONSE((byte) 1),

    /** 事件消息，单向投递不产生响应，requestId 固定为 0。 */
    EVENT((byte) 2),

    /** 心跳消息，用于探测链路连通性，接收方原样回送。 */
    HEARTBEAT((byte) 3);

    /** 线上类型代码，取值 0 到 3，占 1 字节。 */
    private final byte code;

    /**
     * 以类型代码构造常量。
     *
     * @param code 线上类型代码，取值 0 到 3
     */
    CoreMessageType(byte code) {
        this.code = code;
    }

    /**
     * 获取类型代码。
     *
     * @return 线上类型代码，取值 0 到 3
     */
    public byte code() {
        return code;
    }

    /**
     * 按类型代码反查枚举常量。
     *
     * 遍历全部常量做线性匹配，常量数量固定为 4，开销可忽略。
     *
     * @param code 线上类型代码，允许为任意字节值
     * @return 与 code 对应的常量，永不为 null
     * @throws IllegalArgumentException 当 code 不对应任何已定义类型时抛出
     */
    public static CoreMessageType from(byte code) {
        for (CoreMessageType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知消息类型: " + (code & 0xFF));
    }
}
