/*
 * 本文件属于 EnderOnline 后端进程管理。
 *
 * 职责：EasyTier 分发包的下载、SHA256 校验与解压。
 */
package com.endercore.core.easytier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.function.Consumer;

/**
 * EasyTier 可执行文件的分发与安装。
 *
 * 本类只负责「把 EasyTier 装上」：下载归档、校验哈希、解压到目标目录，并在已有可用安装时短路返回。
 * 进程的拉起与停止不归本类，见 EasyTierManager / EasyTierRunner。
 *
 * 设计约束：
 * 1. 安装位置由构造器传入的 downloadDir 独占决定，本类不会写入该目录之外的位置。
 * 2. 校验和被钉在 v2.4.5 的具体文件名上。升级版本时必须同时更新 BASE_URL、PlatformHelper 中的文件名
 *    与 getChecksum 的映射，否则 getChecksum 会返回 null 并静默跳过校验（见 getChecksum 的说明）。
 * 3. 解压目标就是 downloadDir，归档内容会被展开在下载目录内，因此下载目录被视为受信区域。
 * 4. 已存在有效安装时不会重新下载：判定方式是「目录下三层内存在名为 easytier-core[.exe] 的文件」。
 *    若该文件存在但已损坏，本类不会自动修复。
 *
 * 线程安全性：唯一实例字段 downloadDir 在构造后不再修改，实例本身可安全共享。
 * 但并发调用 downloadAndExtract 会在同一目录上重复下载与解压，本类不做互斥，需调用方串行化。
 *
 * @since 1.0
 * @see EasyTierManager
 */
public class EasyTierDownloader {

    /** 本类日志记录器，永不为 null，由 SLF4J 在类初始化时绑定。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(EasyTierDownloader.class);

    /**
     * 下载基础 URL，末尾必须保留斜杠以便直接拼接文件名。
     *
     * NOTE: 地址同时硬编码了 gh-proxy 代理与 v2.4.5 版本号，改版本时两处都要动。
     */
    private static final String BASE_URL = "https://hk.gh-proxy.org/https://github.com/EasyTier/EasyTier/releases/download/v2.4.5/";
    
    /** Windows x64 分发包的 SHA256 校验和，小写十六进制，长度 64。 */
    private static final String SHA256_WIN_X64 = "5d67089df8367bf449a1f60dd4edc4f67da5b88db20f92d69a0c2bcddd1e07c5";

    /** Windows x86 分发包的 SHA256 校验和，小写十六进制，长度 64。 */
    private static final String SHA256_WIN_X86 = "4afa5694cdfc28b252ba67b412e338fc541cd25f9b4aaa88f39d7790491bcf261";

    /** Linux x64 分发包的 SHA256 校验和，小写十六进制，长度 64。 */
    private static final String SHA256_LINUX_X64 = "d33d1fe6e06fae6155ca7a6ea214657de8d29c4edd5e16fb51f128bef29d3aec";

    /** Linux ARM64 分发包的 SHA256 校验和，小写十六进制，长度 64。 */
    private static final String SHA256_LINUX_ARM64 = "df08c842f2ab2b8e9922f13c686a1d0f5a5219775cfdabb3e4a8599c6772201f";

    /** macOS x64 分发包的 SHA256 校验和，小写十六进制，长度 64。 */
    private static final String SHA256_MACOS_X64 = "282abe285e7802c74e2ef1dfb0186c794c371d8350f4f5b1d6ade12031b82333";

    /** macOS ARM64 分发包的 SHA256 校验和，小写十六进制，长度 64。 */
    private static final String SHA256_MACOS_ARM64 = "ddf94b070f84d899504ad154666ea3f0369be6cc4da375a2d98143a312daef01";

    /**
     * 安装目录，归档下载与解压都以它为根。
     *
     * 不允许为 null，由构造器注入后不再修改。
     */
    private final Path downloadDir;

    /**
     * 构造下载器。
     *
     * @param downloadDir 下载与解压的目标目录，不能为 null；目录由 downloadAndExtract 按需创建
     * @throws NullPointerException 当 downloadDir 为 null 时抛出
     */
    public EasyTierDownloader(Path downloadDir) {
        this.downloadDir = downloadDir;
    }

    /**
     * 下载并解压 EasyTier。
     *
     * 短路语义：若目标目录下三层内已能找到可执行文件，则直接复用现有安装，不发起任何网络请求。
     * 阻塞执行，耗时取决于网络状况，禁止在渲染或事件线程上调用。
     *
     * 幂等性：本方法幂等——重复调用在首次成功后都会走短路分支，不会重复下载。
     *
     * @param progressCallback 进度回调，允许为 null；非 null 时接收 0.0 到 1.0 的 double 值。
     *        服务器未返回 content-length 时进度以 15MB 估算，且最大值被压到 0.99，
     *        只有在真正下载完成后的解压路径上才会回调 1.0
     * @return 解压后的目录路径：存在唯一顶层目录时返回该子目录，否则返回 downloadDir 本身，永不为 null
     * @throws IOException 当平台不受支持、HTTP 状态非 200、SHA256 不匹配或解压失败时抛出
     * @throws InterruptedException 当下载线程被中断时抛出
     */
    public Path downloadAndExtract(Consumer<Double> progressCallback) throws IOException, InterruptedException {
        String filename = com.multiplayer.ender.logic.PlatformHelper.getDownloadFilename("2.4.5");
        if (filename == null) {
            throw new IOException("Unsupported platform");
        }
        
        String url = BASE_URL + filename;
        Path zipPath = downloadDir.resolve(filename);
        Path extractDir = downloadDir; 
        
        String expectedChecksum = getChecksum(filename);

        if (Files.exists(extractDir) && Files.list(extractDir).count() > 0) {
             
             try (var stream = Files.walk(extractDir, 3)) {
                 boolean hasExecutable = stream.anyMatch(p -> p.getFileName().toString().equals(com.multiplayer.ender.logic.PlatformHelper.getExecutableName()));
                 if (hasExecutable) {
                     LOGGER.info("EasyTier directory exists and valid. Skipping download.");
                     if (progressCallback != null) progressCallback.accept(1.0);
                     
                     try (var innerStream = Files.list(extractDir)) {
                         Path firstChild = innerStream.findFirst().orElse(null);
                         if (firstChild != null && Files.isDirectory(firstChild)) {
                             return firstChild;
                         }
                     }
                     return extractDir;
                 }
             } catch (Exception e) {
                 LOGGER.warn("Failed to check existing EasyTier installation", e);
             }
        }

        Files.createDirectories(downloadDir);

        LOGGER.info("Downloading EasyTier from {}", url);
        downloadFile(url, zipPath, progressCallback);

        LOGGER.info("Verifying checksum...");
        String checksum = calculateSHA256(zipPath);
        LOGGER.info("Downloaded file checksum: {}", checksum);
        
        if (expectedChecksum != null && !checksum.equalsIgnoreCase(expectedChecksum)) {
            LOGGER.error("Checksum mismatch! Expected: {}, Got: {}", expectedChecksum, checksum);
            throw new IOException("Checksum verification failed");
        }

        LOGGER.info("Extracting to {}", extractDir);
        unzip(zipPath, extractDir);

        Files.deleteIfExists(zipPath);
        
        
        try (var stream = Files.list(extractDir)) {
             Path firstChild = stream.findFirst().orElse(null);
             if (firstChild != null && Files.isDirectory(firstChild)) {
                 return firstChild;
             }
        }
        return extractDir;
    }
    
    /**
     * 根据平台分发包文件名查表得到期望校验和。
     *
     * XXX: 未登记的文件名返回 null，而调用方把 null 解释为「无需校验」并继续安装。
     * 也就是说新增平台或升级版本时忘记补表会静默关闭完整性校验，上线前必须回归确认。
     *
     * @param filename 分发包文件名，不能为 null
     * @return 对应的 SHA256 校验和（小写十六进制）；文件名未登记时返回 null
     */
    private String getChecksum(String filename) {
        switch (filename) {
            case "easytier-windows-x86_64-v2.4.5.zip": return SHA256_WIN_X64;
            case "easytier-windows-i686-v2.4.5.zip": return SHA256_WIN_X86;
            case "easytier-linux-x86_64-v2.4.5.zip": return SHA256_LINUX_X64;
            case "easytier-linux-aarch64-v2.4.5.zip": return SHA256_LINUX_ARM64;
            case "easytier-macos-x86_64-v2.4.5.zip": return SHA256_MACOS_X64;
            case "easytier-macos-aarch64-v2.4.5.zip": return SHA256_MACOS_ARM64;
            default: return null;
        }
    }

    /**
     * 把远端文件流式写入本地路径。
     *
     * 单次读取 8192 字节。content-length 缺失（-1）时进度以 15MB 估算并封顶 0.99，
     * 以免在未知总长度时出现超过 100% 的进度。
     *
     * @param url 下载 URL，不能为 null 且必须可被 URI 解析
     * @param target 目标文件路径，不能为 null，父目录必须已存在
     * @param progressCallback 进度回调，允许为 null；非 null 时对每个数据块回调一次
     * @throws IOException 当 HTTP 状态非 200 或写入失败时抛出
     * @throws InterruptedException 当下载线程被中断时抛出
     */
    private void downloadFile(String url, Path target, Consumer<Double> progressCallback) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
        
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        
        if (response.statusCode() != 200) {
            throw new IOException("Download failed with status " + response.statusCode());
        }

        long contentLength = response.headers().firstValueAsLong("content-length").orElse(-1L);
        long estimatedLength = contentLength > 0 ? contentLength : 15 * 1024 * 1024; 

        try (InputStream is = response.body();
             OutputStream os = Files.newOutputStream(target)) {
            
            byte[] buffer = new byte[8192];
            long totalBytesRead = 0;
            int bytesRead;
            
            while ((bytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
                totalBytesRead += bytesRead;
                
                if (progressCallback != null) {
                    double progress = (double) totalBytesRead / estimatedLength;
                    if (contentLength == -1 && progress > 0.99) progress = 0.99; 
                    progressCallback.accept(progress);
                }
            }
        }
    }

    /**
     * 计算文件的 SHA256 校验和。
     *
     * @param file 文件路径，不能为 null，必须存在且为普通文件
     * @return 小写十六进制字符串形式的校验和，长度恒为 64，永不为 null
     * @throws IOException 当读取文件失败时抛出；摘要算法不可用时同样包装为 IOException 抛出
     */
    private String calculateSHA256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream fis = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = fis.read(buffer)) != -1) {
                    digest.update(buffer, 0, n);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new IOException("Failed to calculate checksum", e);
        }
    }

    /**
     * 解压 ZIP 文件到目标目录。
     *
     * 目录条目与文件条目都会被创建，父目录按需 mkdirs。
     *
     * @param zipFile ZIP 文件路径，不能为 null，必须为合法 ZIP
     * @param targetDir 目标解压目录，不能为 null
     * @throws IOException 当创建目录失败、条目越过目标目录或读写失败时抛出
     */
    private void unzip(Path zipFile, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile.toFile()))) {
            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                File newFile = newFile(targetDir.toFile(), zipEntry);
                if (zipEntry.isDirectory()) {
                    if (!newFile.isDirectory() && !newFile.mkdirs()) {
                        throw new IOException("Failed to create directory " + newFile);
                    }
                } else {
                    File parent = newFile.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Failed to create directory " + parent);
                    }
                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        byte[] buffer = new byte[1024];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
        }
    }

    /**
     * 由 ZIP 条目解析出目标文件，并阻止路径穿越。
     *
     * 比较的是两侧的规范化绝对路径，条目名含 .. 或指向盘符之外时直接拒绝，
     * 因此解压不会写出 destinationDir 之外的位置。
     *
     * @param destinationDir 解压目标目录，不能为 null
     * @param zipEntry ZIP 条目，不能为 null
     * @return 位于目标目录内的文件对象，永不为 null
     * @throws IOException 当条目解析出的路径位于目标目录之外时抛出，或规范化路径失败时抛出
     */
    private File newFile(File destinationDir, ZipEntry zipEntry) throws IOException {
        File destFile = new File(destinationDir, zipEntry.getName());
        String destDirPath = destinationDir.getCanonicalPath();
        String destFilePath = destFile.getCanonicalPath();

        if (!destFilePath.startsWith(destDirPath + File.separator)) {
            throw new IOException("Entry is outside of the target dir: " + zipEntry.getName());
        }
        return destFile;
    }
}
