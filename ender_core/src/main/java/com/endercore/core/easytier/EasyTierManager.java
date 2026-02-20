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
 * EasyTier 管理器类。
 * 负责 EasyTier 实例的初始化、配置加载、启动、停止以及文件迁移。
 *
 * @author Ender Developer
 * @version 1.0
 * @since 1.0
 */
public class EasyTierManager {
    /**
     * 日志记录器
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(EasyTierManager.class);
    
    /**
     * 单例实例
     */
    private static EasyTierManager instance;
    
    /**
     * 下载器实例
     */
    private final EasyTierDownloader downloader;
    
    /**
     * 运行器实例
     */
    private EasyTierRunner runner;
    
    /**
     * 工作目录
     */
    private final Path workDir;
    
    /**
     * 配置对象
     */
    private EasyTierConfig config;
    
    /**
     * GSON 实例
     */
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * 私有构造函数。
     *
     * @param workDir 工作目录
     */
    private EasyTierManager(Path workDir) {
        this.workDir = workDir;
        this.downloader = new EasyTierDownloader(workDir);
        migrateOldFiles();
    }

    /**
     * 迁移旧版本的文件到新目录。
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
     * 获取 EasyTierManager 的单例实例。
     *
     * @return 单例实例
     */
    public static synchronized EasyTierManager getInstance() {
        if (instance == null) {
            
            instance = new EasyTierManager(Paths.get("ender_core_data"));
        }
        return instance;
    }

    /**
     * 初始化 EasyTierManager。
     *
     * @param workDir 工作目录
     */
    public static synchronized void init(Path workDir) {
        if (instance == null) {
            instance = new EasyTierManager(workDir);
        } else if (!instance.workDir.equals(workDir)) {
            LOGGER.warn("EasyTierManager already initialized with different path: {} vs {}", instance.workDir, workDir);
        }
    }

    /**
     * 异步初始化 EasyTier（下载并解压）。
     *
     * @return 完成时无返回值的 CompletableFuture
     */
    public CompletableFuture<Void> initialize() {
        return initialize(null);
    }

    /**
     * 异步初始化 EasyTier（下载并解压），带进度回调。
     *
     * @param progressCallback 进度回调函数
     * @return 完成时无返回值的 CompletableFuture
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
     * 保存配置文件。
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
     * @return 配置对象
     */
    public EasyTierConfig getConfig() {
        if (config == null) {
            loadConfig();
        }
        return config;
    }

    /**
     * 检查 EasyTier 是否已初始化（运行器是否就绪）。
     *
     * @return 如果已初始化则返回 true，否则返回 false
     */
    public boolean isInitialized() {
        return runner != null;
    }

    /**
     * 使用指定的网络名称和密钥启动 EasyTier。
     *
     * @param networkName 网络名称
     * @param networkSecret 网络密钥
     * @throws IOException 当启动失败时抛出
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
     * @param config 配置对象
     * @throws IOException 当启动失败时抛出
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
     * 查找一个可用的本地端口。
     *
     * @return 可用端口号，如果查找失败则返回 -1
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
     * 检查指定端口是否可用。
     *
     * @param port 端口号
     * @return 如果端口可用则返回 true，否则返回 false
     */
    private boolean isPortAvailable(int port) {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 使用原始参数启动 EasyTier。
     *
     * @param args 启动参数
     * @throws IOException 当启动失败时抛出
     */
    public void start(String... args) throws IOException {
        if (runner == null) {
            throw new IllegalStateException("EasyTier not initialized. Call initialize() first.");
        }
        runner.start(args);
    }

    /**
     * 停止 EasyTier 进程。
     */
    public void stop() {
        if (runner != null) {
            runner.stop();
        }
    }
    
    /**
     * 获取当前对等节点列表。
     *
     * @return 对等节点 ID 列表
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
     * @return 节点 ID 到主机名的映射
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
     * @return 节点 ID 到 IP 地址的映射
     */
    public java.util.Map<String, String> getPeerIps() {
        if (runner != null) {
            return runner.getPeerIps();
        }
        return Collections.emptyMap();
    }
    
    /**
     * 检查当前操作系统是否为 Windows。
     *
     * @return 如果是 Windows 则返回 true，否则返回 false
     */
    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    /**
     * 终止所有现有的 EasyTier 进程。
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
