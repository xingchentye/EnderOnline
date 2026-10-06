/*
 * 本文件属于 EnderOnline 后端进程管理。
 *
 * 职责：EasyTier 生命周期的唯一入口：安装、配置持久化、启动、停止与节点信息查询。
 */
package com.endercore.core.easytier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.multiplayer.ender.logic.PlatformHelper;

/**
 * EasyTier 进程与安装的管理者。
 *
 * 本类是 EasyTier 相关状态的唯一所有者：安装目录、配置对象、进程运行器都只在这里持有。
 * 外部通过 getInstance / init 拿到单例后，用 initialize 准备安装、用 start / stop 控制进程。
 *
 * 进程生命周期所有权：
 * 1. 进程句柄由本类持有的 EasyTierRunner 独占，任何其它类都不得自行创建 easytier-core 进程。
 * 2. start 在拉起新进程前会先执行 killAllExistingInstances，也就是按进程名杀掉本机**所有**
 *    easytier-core（Windows 走 taskkill /F /IM，其它平台走 pkill -9 -f）。这是刻意的独占策略，
 *    代价是同一台机器上跑不了第二个 EasyTier 实例，且会误杀用户手工启动的进程。
 * 3. 进程不随 JVM 退出而自动回收，仍由本类负责停止；调用 stop 是本类的契约义务。
 *
 * 设计约束：
 * 1. 单例一旦建立，工作目录即被固定；init 传入不同目录只记录警告，不会迁移或重建实例。
 * 2. Android（PojavLauncher / Amethyst）上进程需要从可写目录或临时目录执行，
 *    initialize 会在非 Windows 平台把可执行文件复制到 java.io.tmpdir 再赋可执行权限。
 * 3. 平台判定存在两套实现：本类的 isWindows 直接读 os.name，其余走 PlatformHelper.getOS，
 *    两者在 Android 上可能给出不同答案，修改其一必须同步核对另一处。
 *
 * 线程安全性：静态字段 instance 的所有读写都在类锁内（getInstance / init 均为 synchronized），
 * 单例的构造不会重复执行。但实例字段 runner、config 本身没有同步保护：initialize 在
 * ForkJoinPool 线程上赋值 runner，isInitialized、start、stop、getPeers 可能从其它线程读取，
 * 存在可见性延迟与「isInitialized 为 false 但实际已就绪」的竞态窗口，调用方必须自行串行化。
 *
 * @since 1.0
 * @see EasyTierDownloader
 * @see EasyTierRunner
 */
public class EasyTierManager {

    /** 本类日志记录器，永不为 null，由 SLF4J 在类初始化时绑定。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(EasyTierManager.class);
    
    /**
     * 单例实例。
     *
     * 允许为 null，表示尚未初始化；赋值时位于 synchronized 块内，但读取方需自行保证可见性。
     */
    private static EasyTierManager instance;
    
    /**
     * 下载器，负责安装分发包。
     *
     * 不允许为 null，由构造器注入；实例本身在构造后不再变化。
     */
    private final EasyTierDownloader downloader;
    
    /**
     * 进程运行器，负责真正拉起与停止 easytier-core。
     *
     * 允许为 null：为 null 表示尚未 initialize 完成，此时 start 会抛 IllegalStateException。
     */
    private EasyTierRunner runner;
    
    /**
     * 工作目录：配置与安装文件都以此为根。
     *
     * 不允许为 null，由构造器注入后不再修改；目录不存在时由 migrateOldFiles 创建。
     */
    private final Path workDir;
    
    /**
     * 当前生效的配置。
     *
     * 允许为 null：为 null 时 getConfig 会触发一次 loadConfig。
     */
    private EasyTierConfig config;
    
    /**
     * Gson 实例，固定采用美化输出。
     *
     * 不允许为 null，声明时即初始化；Gson 本身线程安全，可被多线程共享。
     */
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * 构造管理器。
     *
     * 构造过程带副作用：会创建 workDir 并把当前工作目录下的旧版配置与旧版 easytier 目录迁移进来。
     *
     * @param workDir 工作目录，不能为 null
     */
    private EasyTierManager(Path workDir) {
        this.workDir = workDir;
        this.downloader = new EasyTierDownloader(workDir);
        migrateOldFiles();
    }

    /**
     * 把旧版本遗留的文件迁移到工作目录。
     *
     * 迁移的是「进程当前工作目录」下的 easytier_config.json 与 easytier 目录。
     * 目标已存在同名目录时，旧目录会被整体删除而不是合并。
     *
     * 幂等性：本方法幂等——旧文件被移走后，再次调用不再有任何可迁移内容。
     */
    private void migrateOldFiles() {
        try {
            if (!Files.exists(workDir)) {
                Files.createDirectories(workDir);
            }

            
            Path oldConfig = Paths.get("easytier_config.json");
            Path newConfig = workDir.resolve("easytier_config.json");
            if (Files.exists(oldConfig)) {
                try {
                    Files.move(oldConfig, newConfig, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    LOGGER.info("Migrated easytier_config.json to {}", newConfig);
                } catch (Exception e) {
                    LOGGER.warn("Failed to migrate easytier_config.json", e);
                }
            }

            
            Path oldDir = Paths.get("easytier");
            Path newDir = workDir.resolve("easytier");
            if (Files.exists(oldDir) && Files.isDirectory(oldDir)) {
                if (!Files.exists(newDir)) {
                    try {
                        Files.move(oldDir, newDir, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        LOGGER.info("Migrated easytier directory to {}", newDir);
                    } catch (Exception e) {
                        LOGGER.warn("Failed to migrate easytier directory", e);
                    }
                } else {
                    
                    try (java.util.stream.Stream<Path> walk = Files.walk(oldDir)) {
                        walk.sorted(java.util.Comparator.reverseOrder())
                                .map(Path::toFile)
                                .forEach(java.io.File::delete);
                        LOGGER.info("Removed old easytier directory from root");
                    } catch (Exception e) {
                        LOGGER.warn("Failed to remove old easytier directory", e);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Migration failed", e);
        }
    }

    /**
     * 获取管理器单例。
     *
     * 首次调用会以默认工作目录 ender_core_data 构造实例。
     *
     * @return 单例实例，永不为 null；首次调用时返回新构造的对象
     */
    public static synchronized EasyTierManager getInstance() {
        if (instance == null) {
            
            instance = new EasyTierManager(Paths.get("ender_core_data"));
        }
        return instance;
    }

    /**
     * 以指定工作目录初始化管理器。
     *
     * 只在尚未建立单例时生效；已存在实例时本方法只记录一条警告，不会替换工作目录。
     *
     * 幂等性：本方法幂等，重复调用不会重建实例。
     *
     * @param workDir 工作目录，不能为 null
     */
    public static synchronized void init(Path workDir) {
        if (instance == null) {
            instance = new EasyTierManager(workDir);
        } else if (!instance.workDir.equals(workDir)) {
            LOGGER.warn("EasyTierManager already initialized with different path: {} vs {}", instance.workDir, workDir);
        }
    }

    /**
     * 异步初始化 EasyTier，不带进度回调。
     *
     * @return 完成时无返回值的 Future；失败时以 CompletionException 异常完成
     */
    public CompletableFuture<Void> initialize() {
        return initialize(null);
    }

    /**
     * 异步初始化 EasyTier：加载配置、下载解压、定位可执行文件并构造运行器。
     *
     * 在 ForkJoinPool 的公共线程池上执行，立即返回。
     * 非 Windows 平台会把可执行文件复制到 java.io.tmpdir 并赋可执行权限，
     * 因为 Android 上安装目录通常不可执行，这是平台适配而非可选优化。
     *
     * 幂等性：本方法不幂等，重复调用会重复下载检查并覆盖 runner 字段。
     *
     * @param progressCallback 进度回调，允许为 null；非 null 时接收 0.0 到 1.0 的 double 值
     * @return 完成时无返回值的 Future；失败时以 CompletionException 异常完成，cause 为原始异常
     */
    public CompletableFuture<Void> initialize(Consumer<Double> progressCallback) {
        return CompletableFuture.runAsync(() -> {
            try {
                LOGGER.info("Initializing EasyTier...");
                loadConfig(); 
                Path installDir = downloader.downloadAndExtract(progressCallback);
                
                
                
                
                
                Path executable = installDir.resolve(isWindows() ? "easytier-core.exe" : "easytier-core");
                
                if (!Files.exists(executable)) {
                     try (var stream = Files.walk(installDir, 2)) {
                         Path found = stream.filter(p -> p.getFileName().toString().equals(isWindows() ? "easytier-core.exe" : "easytier-core"))
                                            .findFirst().orElse(null);
                         if (found != null) {
                             executable = found;
                         }
                     }
                }

                if (!isWindows()) {
                    executable.toFile().setExecutable(true);
                    if (PlatformHelper.getOS() == PlatformHelper.OS.ANDROID || !executable.toFile().canExecute()) {
                        LOGGER.info("Android detected or execution permission denied. Copying to temp directory...");
                        try {
                            Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"));
                            Path tmpExe = tmpDir.resolve(executable.getFileName());
                            Files.copy(executable, tmpExe, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            tmpExe.toFile().setExecutable(true);
                            executable = tmpExe;
                            LOGGER.info("Executable copied to: {}", executable);
                        } catch (Exception e) {
                            LOGGER.warn("Failed to copy executable to temp directory", e);
                        }
                    }
                }
                
                runner = new EasyTierRunner(executable);
                LOGGER.info("EasyTier initialized at {}", executable);
            } catch (Exception e) {
                LOGGER.error("Failed to initialize EasyTier", e);
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 加载配置文件。
     *
     * 文件不存在时写入一份默认配置；解析失败时回退到默认配置并覆盖 config 字段。
     * 顺带清理历史上写入的失效中转节点 etnode.zkitefly.eu.org，命中即回写文件。
     *
     * @throws IllegalStateException 当配置解析失败且默认配置也无法构造时抛出（当前实现不会触发）
     */
    public void loadConfig() {
        Path configPath = workDir.resolve("easytier_config.json");
        if (Files.exists(configPath)) {
            try {
                String json = Files.readString(configPath);
                config = gson.fromJson(json, EasyTierConfig.class);
                
                
                if (config.peers != null) {
                    boolean removed = config.peers.removeIf(p -> p.contains("etnode.zkitefly.eu.org"));
                    if (removed) {
                         saveConfig();
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Failed to load config, using default", e);
                config = new EasyTierConfig();
            }
        } else {
            config = new EasyTierConfig();
            saveConfig();
        }
    }

    /**
     * 把当前配置写回配置文件。
     *
     * 失败只记录日志、不抛异常，因此本方法返回不代表写入成功。
     *
     * 幂等性：本方法幂等，重复调用写出相同内容。
     */
    public void saveConfig() {
        try {
            if (!Files.exists(workDir)) {
                Files.createDirectories(workDir);
            }
            Path configPath = workDir.resolve("easytier_config.json");
            Files.writeString(configPath, gson.toJson(config));
        } catch (Exception e) {
            LOGGER.error("Failed to save config", e);
        }
    }

    /**
     * 获取当前配置对象。
     *
     * 返回的是**可变**的实例本身，调用方的修改会直接生效并可能被 saveConfig 持久化。
     * 配置尚未加载时本方法会先触发一次 loadConfig。
     *
     * @return 配置对象，永不为 null
     */
    public EasyTierConfig getConfig() {
        if (config == null) {
            loadConfig();
        }
        return config;
    }

    /**
     * 检查 EasyTier 是否已初始化。
     *
     * 判据是运行器是否已就绪，只反映 initialize 是否执行到末尾，
     * 不保证安装目录完整或进程可执行。
     *
     * @return 已就绪返回 true；尚未完成 initialize 或初始化失败返回 false
     */
    public boolean isInitialized() {
        return runner != null;
    }

    /**
     * 使用指定的网络名称和密钥启动 EasyTier。
     *
     * 会直接改写 getConfig 返回的配置对象并持久化，属于带副作用的启动重载。
     *
     * @param networkName 网络名称，允许为 null 或空字符串，为空时不会生成 --network-name 参数
     * @param networkSecret 网络密钥，允许为 null 或空字符串，为空时不会生成 --network-secret 参数
     * @throws IOException 当进程启动失败时抛出
     * @throws IllegalStateException 当 initialize 尚未完成时抛出
     */
    public void start(String networkName, String networkSecret) throws IOException {
        EasyTierConfig cfg = getConfig();
        cfg.networkName = networkName;
        cfg.networkSecret = networkSecret;
        
        start(cfg);
    }

    /**
     * 使用指定的配置对象启动 EasyTier。
     *
     * 启动前会做两件事：为 RPC 端口寻找替代端口，以及清理本机所有既有 easytier-core 进程。
     * 端口不可用时会**直接修改传入的 config.rpcPort**，调用方持有的同一对象会被一并改动。
     *
     * 幂等性：本方法不幂等，重复调用会先杀掉上一个进程再重新拉起。
     *
     * @param config 配置对象，不能为 null；其 rpcPort 字段可能被本方法覆盖
     * @throws IOException 当进程启动失败时抛出
     * @throws IllegalStateException 当 runner 为 null（尚未 initialize）时抛出
     */
    public void start(EasyTierConfig config) throws IOException {
        if (runner == null) {
            throw new IllegalStateException("EasyTier not initialized. Call initialize() first.");
        }

        
        if (config.rpcPort > 0 && !isPortAvailable(config.rpcPort)) {
            LOGGER.warn("RPC Port {} is in use, finding a new one...", config.rpcPort);
            int newPort = findAvailablePort();
            if (newPort > 0) {
                config.rpcPort = newPort;
                LOGGER.info("Using new RPC Port: {}", newPort);
            }
        }

        killAllExistingInstances();

        List<String> args = config.toArgs();
        
        java.util.Map<String, String> env = new java.util.HashMap<>();
        if (config.logLevel != null && !config.logLevel.isEmpty()) {
            env.put("RUST_LOG", config.logLevel);
        }
        
        runner.start(args.toArray(new String[0]), env);
    }

    /**
     * 查找一个建议可用的本地端口。
     *
     * FIXME(P3, 2026-10-06): 与 PlatformHelper.findAvailablePort 同源缺陷——
     * 绑定端口 0 后立即关闭，返回的是「当前空闲」而非「已保留」的端口，
     * 到真正启动进程之间存在被抢占的窗口。
     *
     * @return 端口号，取值范围 1 到 65535；分配失败时返回 -1
     */
    private int findAvailablePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            LOGGER.error("Failed to find available port", e);
            return -1;
        }
    }

    /**
     * 探测指定端口当前是否可绑定。
     *
     * 实现方式是尝试绑定后立即释放，同样存在 TOCTOU 窗口：返回 true 只说明「此刻」可绑定。
     *
     * @param port 端口号，取值 1 到 65535，超出范围时返回 false
     * @return 可绑定返回 true；端口被占用、无权限或取值非法返回 false
     */
    private boolean isPortAvailable(int port) {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 使用原始参数启动 EasyTier，不做端口与进程清理。
     *
     * 参数被原样透传，调用方需自行保证参数合法。
     *
     * @param args 启动参数，允许为空数组；为 null 时会在数组转换处抛 NullPointerException
     * @throws IOException 当进程启动失败时抛出
     * @throws IllegalStateException 当 runner 为 null（尚未 initialize）时抛出
     */
    public void start(String... args) throws IOException {
        if (runner == null) {
            throw new IllegalStateException("EasyTier not initialized. Call initialize() first.");
        }
        runner.start(args);
    }

    /**
     * 停止 EasyTier 进程。
     *
     * 未初始化的实例调用本方法是安全的空操作。
     *
     * 幂等性：本方法幂等，对已停止的进程重复调用不会报错。
     */
    public void stop() {
        if (runner != null) {
            runner.stop();
        }
    }
    
    /**
     * 获取当前对等节点列表。
     *
     * @return 节点 ID 列表，永不为 null；未初始化时返回空列表
     */
    public List<String> getPeers() {
        if (runner != null) {
            return runner.getPeers();
        }
        return Collections.emptyList();
    }
    
    /**
     * 获取对等节点主机名映射。
     *
     * @return 节点 ID 到主机名的映射，永不为 null；未初始化时返回空映射
     */
    public java.util.Map<String, String> getPeerHostnames() {
        if (runner != null) {
            return runner.getPeerHostnames();
        }
        return Collections.emptyMap();
    }

    /**
     * 获取对等节点 IP 映射。
     *
     * @return 节点 ID 到 IP 地址的映射，永不为 null；未初始化时返回空映射
     */
    public java.util.Map<String, String> getPeerIps() {
        if (runner != null) {
            return runner.getPeerIps();
        }
        return Collections.emptyMap();
    }
    
    /**
     * 判断当前平台是否为 Windows。
     *
     * NOTE: 与 PlatformHelper.getOS 是两套独立判定，Android 上两者可能不一致；
     * 本方法只看 os.name，Android 的 os.name 通常含 linux 而落回 false。
     *
     * @return os.name 含 win 时返回 true，否则返回 false
     */
    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    /**
     * 终止本机所有既有的 EasyTier 进程。
     *
     * Windows 执行 taskkill /F /IM easytier-core.exe，其它平台执行 pkill -9 -f easytier-core。
     * 这是按进程名匹配的全局操作，会一并杀掉非本进程启动的 EasyTier，属于刻意的独占策略。
     * 失败只记录警告，不阻塞后续启动。
     */
    private void killAllExistingInstances() {
        LOGGER.info("Cleaning up existing EasyTier processes...");
        try {
            if (isWindows()) {
                new ProcessBuilder("taskkill", "/F", "/IM", "easytier-core.exe").start().waitFor();
            } else {
                new ProcessBuilder("pkill", "-9", "-f", "easytier-core").start().waitFor();
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to kill existing EasyTier processes: {}", e.getMessage());
        }
    }
}
