/*
 * 本文件属于 EnderOnline 核心逻辑层。
 *
 * 职责：远端最新版本号的查询入口。
 */
package com.multiplayer.ender.logic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 版本检查工具。
 *
 * 约定的返回契约是「可直接用于比较的版本号字符串」，不包含标签前缀说明。
 * 调用方只做字符串比较或展示，不做语义化版本解析。
 *
 * 设计约束：
 * 1. 本方法会在未来引入网络请求，因此必须保持阻塞语义并如实抛出网络类异常，
 *    不得吞掉异常后返回兜底版本号——那会让界面把「查不到」误报为「已是最新」。
 * 2. API_URL 已固定指向 Ender Core 的发布地址，切换仓库需要同步修改解析正则。
 *
 * 线程安全性：本类无实例状态，静态方法可被多线程并发调用。
 *
 * FIXME(P3, 2026-10-06): 当前实现直接返回硬编码版本号，网络校验被整体绕过，
 * 界面会显示错误的「最新版本」，接入真实请求前不得对外宣称具备更新检查能力。
 *
 * @since 1.0
 */
public class VersionChecker {

    /** 本类日志记录器，永不为 null，由 SLF4J 在类初始化时绑定。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(VersionChecker.class);
    
    
    /** 版本检查 API 地址，形如 https://gitee.com/api/v5/repos/burningtnt/Ender/releases/latest。 */
    private static final String API_URL = "https://gitee.com/api/v5/repos/burningtnt/Ender/releases/latest";

    /**
     * 获取最新版本号。
     *
     * 当前实现绕过网络请求，直接返回占位版本号，返回值不反映远端真实状态。
     * 返回值永不为 null，格式为点分的数字串，不含 v 前缀。
     *
     * @return 最新版本号字符串，永不为 null
     * @throws IOException 当网络请求失败时抛出（当前实现不会抛出）
     * @throws InterruptedException 当请求被中断时抛出（当前实现不会抛出）
     */
    public static String getLatestVersion() throws IOException, InterruptedException {
        
        
        
        LOGGER.info("Version check bypassed for Ender Core.");
        return "9.9.9";
    }
}

