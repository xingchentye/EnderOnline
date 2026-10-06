/*
 * 本文件属于 EnderOnline 通信层。
 *
 * 职责：包级说明，声明异常的语义与使用边界。
 */

/**
 * 通信层异常体系。
 *
 * 本包提供协议栈内部的受检异常，语义边界如下：
 * 1. CoreCommException 是其余异常的共同基类，只应在无法归入更具体类别时使用。
 * 2. CoreProtocolException 表示帧格式或取值非法，属于「对端/数据有问题」。
 * 3. CoreConnectException 表示连接建立失败，属于「网络有问题」，可重试。
 * 4. CoreTimeoutException 表示等待超时，属于「对端无响应」，可重试。
 * 5. CoreRemoteException 表示对端返回了业务错误，属于「请求被拒绝」，通常不应重试。
 * 6. CoreClosedException 表示本地连接已关闭，属于「调用时机错误」，重试前需重新建连。
 *
 * 包内约定：
 * 1. 异常消息用于日志，**不直接展示给用户**。面向用户的文案由错误码映射得到，
 * 见 claude_docs/06-logic-and-code-quality.md §2 的错误模型。
 * 2. 异常消息使用英文，避免日志中混入非 UTF-8 环境下的乱码。
 * 3. 所有异常必须携带 cause（若存在），禁止吞掉原始异常。
 *
 * @since 1.0
 */
package com.endercore.core.comm.exception;
