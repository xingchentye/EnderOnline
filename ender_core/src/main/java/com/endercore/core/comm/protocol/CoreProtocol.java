/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：集中定义帧头布局中的固定取值，作为协议格式的唯一常量来源。
 */
package com.endercore.core.comm.protocol;

 
/**
 * 协议常量。
 *
 * 定义魔数、版本号与帧头长度，是协议格式的唯一来源；CoreFrameCodec 的字段读写顺序
 * 必须与本类注释中的布局描述保持一致。
 *
 * 设计约束：任一取值的变更都属于协议不兼容变更，
 * 必须同步更新 docs/03-protocol-plan.md 并提升 VERSION 常量。
 *
 * 线程安全性：全部成员为编译期常量，且类不可实例化，天然线程安全。
 *
 * @since 1.0
 * @see CoreFrameCodec
 * @see CoreMessageType
 */
public final class CoreProtocol {
    /** 魔数第一字节，固定为 0x45（字符 E）。 */
    public static final byte MAGIC_0 = 0x45;

    /** 魔数第二字节，固定为 0x43（字符 C）。 */
    public static final byte MAGIC_1 = 0x43;

    /** 协议版本号，取值 1；解码端遇到不匹配的版本会直接拒绝该帧。 */
    public static final byte VERSION = 1;

    /**
     * 帧头固定长度，单位字节，取值为 20。
     *
     * 布局：Magic(2) + Version(1) + Type(1) + Flags(1) + Status(1) + RequestId(8) + KindLen(2) + PayloadLen(4)。
     */
    public static final int HEADER_BYTES = 2 + 1 + 1 + 1 + 1 + 8 + 2 + 4;

    /** 私有构造函数，防止实例化。 */
    private CoreProtocol() {
    }
}
