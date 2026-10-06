/*
 * 本文件属于 EnderOnline 网络层。
 *
 * 职责：房主定位的扫描结果，用于在「找到房主」「见到主机名但缺 IP」「什么都没找到」之间传递状态。
 *
 * 原先是 EnderApiClient 的私有嵌套类；抽出扫描逻辑时提为包内顶层类，使纯扫描函数与其返回值
 * 都不再依赖 EnderApiClient。
 */
package com.multiplayer.ender.network;

import java.net.InetSocketAddress;

/**
 * 房主定位的扫描结果。
 *
 * 三个字段共同表达一次扫描的结论：{@code address} 非 null 表示成功；为 null 时，
 * {@code hostSeen} 与 {@code ipMissing} 用于区分「房间不存在」与「网络路由尚未就绪」。
 *
 * 设计约束：
 * 1. 可变的值对象，由扫描过程逐项填充，因此字段不是 final。仅供同包使用。
 * 2. {@code ipMissing} 目前只被写入、未被任何调用方读取（历史遗留，见下）。
 *    保留它是因为它记录了「主机名匹配但缺 IP」这一真实中间态，未来给出可区分的错误提示时需要它；
 *    在此显式记录「当前无读取方」，避免被误当作有效分支。
 *
 * 线程安全性：实例只在单次扫描内使用，不跨线程共享。
 *
 * @see ScaffoldingHostScanner
 */
final class ScanResult {

    /** 已解析出的房主地址；为 null 表示本轮未解析成功，此时 hostSeen 与 ipMissing 仍有意义。 */
    InetSocketAddress address;

    /** 是否至少见到过一个符合房主主机名格式的节点。 */
    boolean hostSeen;

    /** 是否出现过「主机名匹配但缺少 IP」的情形，用于区分「房间不存在」与「路由未就绪」。 */
    boolean ipMissing;
}
