/*
 * 本文件属于 EnderOnline 后端进程管理。
 *
 * 职责：拉起 easytier-core 子进程、采集其标准输出、解析对等节点信息并负责停止该进程。
 */
package com.endercore.core.easytier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;
import java.util.HashMap;

/**
 * EasyTier 子进程的运行器。
 *
 * 本类是 easytier-core 进程句柄的唯一所有者：创建、读取输出、停止都由这里完成，
 * 上层（EasyTierManager）只负责决定何时调用 start / stop，不得绕过本类操作进程。
 *
 * 进程生命周期所有权：
 * 1. 进程句柄保存在 processRef 中，stop 是唯一会主动终止它的入口；
 *    本类不会注册 JVM 关闭钩子，进程是否被回收取决于调用方是否记得 stop。
 * 2. start 派生一条非守护线程 "EasyTier-Output" 读取子进程输出，该线程以 stdout 关闭为退出条件。
 *    stop 不 join 该线程，只依赖流关闭让它自然结束，因此 stop 返回后线程可能仍在收尾。
 * 3. start 还会派生守护线程 "EasyTier-Poller" 周期性拉起 easytier-cli 子进程，
 *    这两类派生线程的生命周期都必须与本类的进程生命周期绑定。
 *
 * 设计约束：
 * 1. 进程的 stdout 与 stderr 被合并，节点信息靠对日志文本做子串匹配解析，
 *    因此 EasyTier 的日志措辞一旦变化，对等节点列表会静默变空而不报错。
 * 2. isRunning 字段与 isRunning() 方法是两套语义：字段表示「输出读取线程是否还在工作」，
 *    方法以 process.isAlive() 为准，两者可能短暂不一致，判断进程状态请用方法。
 * 3. 平台限制：Android（PojavLauncher / Amethyst）上外部进程的启动依赖设备侧已具备可执行环境，
 *    CLI 子进程同样受此限制。
 *
 * 线程安全性：processRef 为 AtomicReference，isRunning 为 volatile，对等节点集合为并发容器，
 * 单字段访问是安全的。但 start 与 stop 之间没有互斥：两者并发执行会出现「停止后立刻被重新拉起」
 * 或状态字段互相覆盖，调用方必须在外部串行化启动与停止。
 *
 * @since 1.0
 * @see EasyTierManager
 */
public class EasyTierRunner {

    /** 本类日志记录器，永不为 null，由 SLF4J 在类初始化时绑定。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(EasyTierRunner.class);
    
    /**
     * 可执行文件绝对路径。
     *
     * 不允许为 null，由构造器注入后不再修改；其父目录会成为子进程的工作目录。
     */
    private final Path executablePath;
    
    /**
     * 当前子进程句柄。
     *
     * 允许为 null：为 null 表示尚未启动过进程；进程结束后仍保留旧句柄直至下次 start 覆盖。
     */
    private final AtomicReference<Process> processRef = new AtomicReference<>();
    
    /**
     * 运行标志，语义是「输出读取线程仍在工作」。
     *
     * 由 start 置 true、由输出线程的 finally 与 stop 置 false。
     * NOTE: 本字段不参与 isRunning() 的判断，两者不可互相替代。
     */
    private volatile boolean isRunning = false;
    
    /**
     * 对等节点 ID 到主机名的映射。
     *
     * 永不为 null，元素按下标周期刷新；不存在的节点会被整体覆盖而不是增量删除。
     */
    private final Map<String, String> peerHostnames = new ConcurrentHashMap<>();
    
    /**
     * 对等节点 ID 到 IP 的映射。
     *
     * 永不为 null，与 peerHostnames 在同一轮轮询中一起刷新，两者的键集合可能不同。
     */
    private final Map<String, String> peerIps = new ConcurrentHashMap<>();
    
    /**
     * 轮询调度器。
     *
     * 允许为 null：为 null 表示轮询未启动或已停止；由 stopPeerPoller 置回 null。
     */
    private ScheduledExecutorService pollerScheduler;
    
    /**
     * 从启动参数解析出的 RPC 端口，默认 11010。
     *
     * 取值范围 1 到 65535；解析失败时保持上一次成功的取值，用于 easytier-cli 的连接地址。
     */
    private int rpcPort = 11010; 

    /**
     * 构造运行器。
     *
     * 构造不做任何 I/O，也不校验文件是否存在，可执行性由 EasyTierManager 在初始化阶段保证。
     *
     * @param executablePath 可执行文件路径，不能为 null，其父目录必须存在
     */
    public EasyTierRunner(Path executablePath) {
        this.executablePath = executablePath;
    }

    /**
     * 启动 EasyTier 进程，不注入额外环境变量。
     *
     * @param args 启动参数，允许为空数组
     * @throws IOException 当可执行文件不存在或无法创建进程时抛出
     */
    public void start(String... args) throws IOException {
        start(args, null);
    }

    /**
     * 活跃对等节点 ID 列表。
     *
     * 永不为 null，元素由输出线程通过日志解析增删；同步列表，但「读取再复制」不是原子操作。
     */
    private final List<String> peerList = Collections.synchronizedList(new ArrayList<>());
    
    /**
     * 已识别为基础设施的中转节点 ID 列表。
     *
     * 永不为 null；命中该列表的节点不会出现在 getPeers 的结果中。
     */
    private final List<String> ignoredPeers = Collections.synchronizedList(new ArrayList<>());
    
    /**
     * 启动参数中声明的基础设施地址（-p / --peers 的取值）。
     *
     * 永不为 null；用于在日志里把公共中转节点从「活跃对等节点」中剔除。
     */
    private final List<String> infrastructureUrls = Collections.synchronizedList(new ArrayList<>());

    /**
     * 获取活跃对等节点列表。
     *
     * 返回的是副本，调用方修改返回值不会影响内部状态。
     * 复制过程不是原子的，与输出线程的增删并发时可能拿到稍旧的快照。
     *
     * @return 对等节点 ID 列表，永不为 null，可能为空
     */
    public List<String> getPeers() {
        return new ArrayList<>(peerList);
    }

    /**
     * 获取对等节点主机名映射。
     *
     * @return 节点 ID 到主机名的映射副本，永不为 null，可能为空
     */
    public Map<String, String> getPeerHostnames() {
        return new HashMap<>(peerHostnames);
    }

    /**
     * 获取对等节点 IP 映射。
     *
     * @return 节点 ID 到 IP 地址的映射副本，永不为 null，可能为空
     */
    public Map<String, String> getPeerIps() {
        return new HashMap<>(peerIps);
    }

    /**
     * 启动 EasyTier 进程，并派生输出读取线程与对等节点轮询线程。
     *
     * 启动前会清空上一轮采集到的对等节点信息，因此重复启动会造成短暂的列表空窗。
     * 派生线程不加入任何线程池，其存续完全靠进程生命周期约束。
     *
     * 幂等性：本方法不幂等，但内部有防重入判断——已在运行时记录警告并直接返回。
     *
     * @param args 启动参数，不能为 null，会被原样追加到可执行文件路径之后
     * @param env 追加到子进程的环境变量，允许为 null，为 null 时继承当前进程环境
     * @throws IOException 当创建子进程失败时抛出
     */
    public void start(String[] args, Map<String, String> env) throws IOException {
        if (isRunning()) {
            LOGGER.warn("EasyTier is already running.");
            return;
        }

        peerList.clear(); 
        ignoredPeers.clear();
        infrastructureUrls.clear();
        peerHostnames.clear();
        peerIps.clear();

        List<String> command = new ArrayList<>();
        command.add(executablePath.toAbsolutePath().toString());
        Collections.addAll(command, args);

        
        for (int i = 0; i < args.length; i++) {
            if ("-p".equals(args[i]) || "--peers".equals(args[i])) {
                if (i + 1 < args.length) {
                    infrastructureUrls.add(args[i+1]);
                }
            }
            if ("--rpc-portal".equals(args[i]) && i + 1 < args.length) {
                String val = args[i+1]; 
                if (val.contains(":")) {
                    try {
                        rpcPort = Integer.parseInt(val.split(":")[1]);
                    } catch (NumberFormatException e) {
                        
                    }
                }
            }
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(executablePath.getParent().toFile());
        pb.redirectErrorStream(true);
        if (env != null) {
            pb.environment().putAll(env);
        }
        
        LOGGER.info("Starting EasyTier: {}", command);
        Process process = pb.start();

        processRef.set(process);
        isRunning = true;

        startPeerPoller();

        
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LOGGER.info("[EasyTier] {}", line);
                    
                    
                    
                    if (line.contains("new peer connection added")) {
                        try {
                            String dstPeerId = null;
                            String remoteUrl = null;
                            
                            
                            int idIdx = line.indexOf("dst_peer_id: ");
                            if (idIdx != -1) {
                                int end = line.indexOf(",", idIdx);
                                if (end != -1) {
                                    dstPeerId = line.substring(idIdx + 13, end).trim();
                                }
                            }
                            
                            
                            int urlIdx = line.indexOf("url: \"");
                            if (urlIdx != -1) {
                                int end = line.indexOf("\"", urlIdx + 6);
                                if (end != -1) {
                                    remoteUrl = line.substring(urlIdx + 6, end);
                                }
                            }
                            
                            if (dstPeerId != null && remoteUrl != null) {
                                
                                boolean isInfrastructure = false;
                                for (String infraUrl : infrastructureUrls) {
                                    
                                    
                                    
                                    if (remoteUrl.contains(infraUrl) || infraUrl.contains(remoteUrl)) {
                                        isInfrastructure = true;
                                        break;
                                    }
                                    
                                    if (remoteUrl.contains("public.easytier.top") || 
                                        remoteUrl.contains("public2.easytier.cn")) {
                                        isInfrastructure = true;
                                        break;
                                    }
                                }
                                
                                if (isInfrastructure) {
                                    if (!ignoredPeers.contains(dstPeerId)) {
                                        ignoredPeers.add(dstPeerId);
                                        
                                        peerList.remove(dstPeerId);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            LOGGER.warn("Failed to parse peer connection info: {}", line);
                        }
                    }

                    
                    if (line.contains("new peer added")) {
                         
                         try {
                             String[] parts = line.split("peer_id:");
                             if (parts.length > 1) {
                                 String peerId = parts[1].trim();
                                 if (!ignoredPeers.contains(peerId)) {
                                     if (!peerList.contains(peerId)) {
                                         peerList.add(peerId);
                                     }
                                 }
                             }
                         } catch (Exception e) {
                             LOGGER.warn("Failed to parse peer id from line: {}", line);
                         }
                    }
                    if (line.contains("peer connection closed") || line.contains("remove peer")) {
                        
                         try {
                             String[] parts = line.split("peer_id:");
                             if (parts.length > 1) {
                                 String peerId = parts[1].trim();
                                 peerList.remove(peerId);
                                 ignoredPeers.remove(peerId); 
                             }
                         } catch (Exception e) {
                             LOGGER.warn("Failed to parse peer id for removal from line: {}", line);
                         }
                    }
                }
            } catch (IOException e) {
                
            } finally {
                isRunning = false;
                LOGGER.info("EasyTier process exited.");
            }
        }, "EasyTier-Output").start();
    }

    /**
     * 停止 EasyTier 进程。
     *
     * 先优雅终止（destroy），等待约 1 秒后仍未退出则强制终止（destroyForcibly）。
     * 阻塞语义：本方法至少阻塞 1 秒，且不会等待输出读取线程结束，禁止在渲染或事件线程上调用。
     * 未启动过进程时是安全的空操作。
     *
     * 幂等性：本方法幂等，对已停止的进程重复调用只会把运行标志再置一次 false。
     */
    public void stop() {
        Process process = processRef.get();
        if (process != null && process.isAlive()) {
            LOGGER.info("Stopping EasyTier...");
            stopPeerPoller();
            process.destroy(); 
            try {
                Thread.sleep(1000);
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        isRunning = false;
    }

    /**
     * 检查 EasyTier 进程是否正在运行。
     *
     * 判据是句柄存活（process.isAlive()），与 isRunning 字段表示的输出线程状态无关。
     *
     * @return 已有进程且仍存活时返回 true，否则返回 false
     */
    public boolean isRunning() {
        Process process = processRef.get();
        return process != null && process.isAlive();
    }

    /**
     * 启动对等节点轮询线程。
     *
     * 调度器为单线程守护线程 "EasyTier-Poller"，首次延迟 2 秒、之后按固定 2 秒间隔执行。
     * 已在运行（未 shutdown）时直接返回，不会创建第二个调度器。
     */
    private void startPeerPoller() {
        if (pollerScheduler != null && !pollerScheduler.isShutdown()) {
            return;
        }
        pollerScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "EasyTier-Poller");
            t.setDaemon(true);
            return t;
        });
        pollerScheduler.scheduleWithFixedDelay(this::fetchPeerInfo, 2, 2, TimeUnit.SECONDS);
    }

    /**
     * 停止对等节点轮询线程。
     *
     * 使用 shutdownNow 丢弃尚未执行的周期任务，并把调度器引用置回 null。
     * 允许重复调用，未启动时是空操作。
     */
    private void stopPeerPoller() {
        if (pollerScheduler != null) {
            pollerScheduler.shutdownNow();
            pollerScheduler = null;
        }
    }

    /**
     * 通过 easytier-cli 子进程拉取对等节点的主机名与 IP，并刷新两张映射表。
     *
     * 每轮都会整体替换两张映射表，因此某轮解析失败会让已采集到的信息被清空。
     * CLI 文件不存在时直接返回且保留旧数据，这是「未安装 CLI」与「解析失败」两种情形的区别。
     * 方法内部吞掉所有异常，失败仅表现为数据不更新。
     */
    private void fetchPeerInfo() {
        if (!isRunning) return;
        
        try {
            
            Path cliPath;
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            if (isWindows) {
                 cliPath = executablePath.resolveSibling("easytier-cli.exe");
            } else {
                 cliPath = executablePath.resolveSibling("easytier-cli");
            }

            if (!Files.exists(cliPath)) {
                return;
            }

            ProcessBuilder pb = new ProcessBuilder(
                cliPath.toAbsolutePath().toString(), 
                "-p", "127.0.0.1:" + rpcPort, 
                "-o", "json",
                "peer"
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            
            Map<String, String> latestHostnames = new HashMap<>();
            Map<String, String> latestIps = new HashMap<>();
            
            StringBuilder jsonBuilder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    jsonBuilder.append(line);
                }
            }
            
            String jsonOutput = jsonBuilder.toString();
            if (!jsonOutput.isBlank()) {
                LOGGER.info("EasyTier CLI output: {}", jsonOutput);
                try {
                    com.google.gson.JsonArray peers = com.google.gson.JsonParser.parseString(jsonOutput).getAsJsonArray();
                    for (com.google.gson.JsonElement element : peers) {
                        if (!element.isJsonObject()) continue;
                        com.google.gson.JsonObject peer = element.getAsJsonObject();
                        
                        String id = peer.has("peer_id") ? peer.get("peer_id").getAsString() : (peer.has("id") ? peer.get("id").getAsString() : "");
                        String hostname = peer.has("hostname") ? peer.get("hostname").getAsString() : "";
                        String ipv4 = peer.has("ipv4") ? peer.get("ipv4").getAsString() : "";
                        
                        if (!id.isEmpty()) {
                            if (!hostname.isEmpty()) {
                                latestHostnames.put(id, hostname);
                            }
                            if (!ipv4.isEmpty()) {
                                latestIps.put(id, ipv4);
                            }
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to parse peer json: {}", e.getMessage());
                }
            }

            peerHostnames.clear();
            peerHostnames.putAll(latestHostnames);
            peerIps.clear();
            peerIps.putAll(latestIps);
            p.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            
        }
    }
}
