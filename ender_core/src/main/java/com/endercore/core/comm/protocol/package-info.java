/*
 * 本文件属于 EnderOnline 通信协议层。
 *
 * 职责：包级说明，声明本包与相邻包的边界。
 */

/**
 * 通信协议包。
 *
 * 本包只负责把帧对象与字节序列互相转换，不持有连接状态、不做重试、不感知业务语义。
 * 连接维护与生命周期归 comm.client 包，房间语义归 comm.server 包。
 *
 * 包内约定：
 * 1. 编解码的自定义异常统一为 CoreProtocolException，不抛其它受检异常。
 * 2. 帧字段的取值范围必须与 CoreProtocol 中的常量保持一致；改动头部布局需同步更新
 * claude_docs/03-protocol-plan.md，因为那是协议的唯一权威定义。
 * 3. 编解码器不接受 null 输入，也不返回 null。
 *
 * @since 1.0
 */
package com.endercore.core.comm.protocol;
