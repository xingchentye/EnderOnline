/*
 * 本文件属于 EnderOnline 核心逻辑层。
 *
 * 职责：EasyTier 分发包的异步下载与 tar.gz 解压。
 */
package com.multiplayer.ender.logic;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 资源下载与解压工具。
 *
 * 本类只处理「把一个远端归档取到本地并展开」，不校验内容、不判断版本、不清理旧文件，
 * 下载与解压的顺序与目标目录选择由调用方负责。
 *
 * 设计约束：
 * 1. 解压不做路径逃逸检查：归档条目名可以包含 ..，恶意或损坏的归档能把文件写到 outputDir 之外。
 *    归档来源必须受信，或在调用前补齐条目名归一化。
 * 2. 网络请求固定走 HttpClient 默认配置，超时由 JVM 默认值决定，调用方无法覆盖。
 * 3. GitHub 地址会被自动改写为 hk.gh-proxy.org 代理前缀，这是对网络环境的适配而非通用加速策略。
 *
 * 线程安全性：本类无实例状态，两个静态方法各自持有独立的局部上下文（HttpClient、流），
 * 可被多线程并发调用；并发下载同一 filename 到同一 targetDir 会互相覆写，需调用方去重。
 *
 * @since 1.0
 */
public class DownloadManager {

    /** 本类日志记录器，永不为 null，由 SLF4J 在类初始化时绑定。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadManager.class);

    /**
     * 异步下载文件。
     *
     * 立即返回，下载在 ForkJoinPool.commonPool 上执行；目标目录不存在时会被自动创建。
     * 单次读取 8192 字节，content-length 缺失（返回负值）时不会触发任何进度回调。
     *
     * @param url 下载地址，不能为 null；以 https://github.com 开头且未含 gh-proxy 时会被改写为代理地址
     * @param targetDir 目标目录，不能为 null；不存在时自动递归创建
     * @param filename 保存的文件名，不能为 null 或空字符串，会被直接 resolve 到 targetDir 下
     * @param progressCallback 进度回调，允许为 null；非 null 时接收 0.0 到 1.0 的 double 值，
     *        下载完成后必定额外回调一次 1.0
     * @return 在下载完成后以目标文件路径完成的 Future；失败时以 CompletionException 异常完成，
     *         其 cause 为原始异常，调用方需用 handle / exceptionally 处理而不是直接 get
     */
    public static CompletableFuture<Path> download(String url, Path targetDir, String filename, Consumer<Double> progressCallback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                
                String finalUrl = url;
                if (url.startsWith("https://github.com") && !url.contains("gh-proxy")) {
                     finalUrl = "https://hk.gh-proxy.org/" + url;
                }
                
                if (!Files.exists(targetDir)) {
                    Files.createDirectories(targetDir);
                }

                Path targetPath = targetDir.resolve(filename);
                LOGGER.info("开始下载: {}", finalUrl);

                HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
                HttpRequest request = HttpRequest.newBuilder().uri(URI.create(finalUrl)).GET().build();

                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    throw new IOException("下载失败，HTTP状态码: " + response.statusCode());
                }

                long contentLength = response.headers().firstValueAsLong("content-length").orElse(-1L);
                
                try (InputStream in = response.body();
                     OutputStream out = Files.newOutputStream(targetPath)) {
                    
                    byte[] buffer = new byte[8192];
                    long totalBytesRead = 0;
                    int bytesRead;
                    
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                        totalBytesRead += bytesRead;
                        
                        if (contentLength > 0 && progressCallback != null) {
                            double progress = (double) totalBytesRead / contentLength;
                            progressCallback.accept(progress);
                        }
                    }
                }
                
                if (progressCallback != null) {
                    progressCallback.accept(1.0);
                }
                
                LOGGER.info("下载完成: {}", targetPath);
                return targetPath;
            } catch (Exception e) {
                LOGGER.error("下载过程中发生错误", e);
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 解压 .tar.gz 格式的归档文件。
     *
     * 同步阻塞执行，只展开普通文件条目，目录条目会被跳过并由父目录创建逻辑补上。
     *
     * 幂等性：本方法不幂等——同名条目会被覆盖，重复解压等价于重新覆写一遍。
     *
     * @param tarGzPath .tar.gz 文件路径，不能为 null，必须存在且为普通文件
     * @param outputDir 解压输出目录，不能为 null，不存在的父目录会被自动创建
     * @throws IOException 当读取归档或写入目标文件失败时抛出；归档格式损坏同样以 IOException 抛出
     */
    public static void extractTarGz(Path tarGzPath, Path outputDir) throws IOException {
        LOGGER.info("正在解压: {}", tarGzPath);
        try (InputStream fi = Files.newInputStream(tarGzPath);
             InputStream bi = new BufferedInputStream(fi);
             InputStream gzi = new GzipCompressorInputStream(bi);
             TarArchiveInputStream tai = new TarArchiveInputStream(gzi)) {

            TarArchiveEntry entry;
            while ((entry = tai.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                
                Path curfile = outputDir.resolve(entry.getName());
                Path parent = curfile.getParent();
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent);
                }
                
                try (OutputStream out = Files.newOutputStream(curfile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = tai.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                    }
                }
            }
        }
    }
}
