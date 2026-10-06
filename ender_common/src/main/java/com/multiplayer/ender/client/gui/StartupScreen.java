/*
 * 本文件属于 EnderOnline 客户端界面层。
 *
 * 职责：后端启动器界面，检测环境、下载核心组件、分配端口并把结果交还调用方。
 *
 * 启动工作全部在公共线程池上执行，界面线程只负责展示进度；状态字段缺少同步，见类注释的 FIXME。
 */
package com.multiplayer.ender.client.gui;

import com.endercore.core.comm.EnderExecutors;
import com.endercore.core.comm.EnderLifecycle;

import com.multiplayer.ender.client.PlatformConfigHolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.multiplayer.ender.logic.DownloadManager;
import com.multiplayer.ender.logic.PlatformHelper;
import com.multiplayer.ender.logic.VersionChecker;
import com.multiplayer.ender.network.NetworkClient;
import com.multiplayer.ender.network.EnderApiClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 后端启动器界面。
 *
 * 属于「启动后端服务」页：由 MultiplayerMenuHandlerForge、InGameMenuHandlerForge、
 * LanShareHandlerForge 以及 ClientSetupForge 的自动启动逻辑打开，也是 EnderDashboard 空闲页
 * 在后端未就绪时的跳转目标。启动成功后由 onStartupComplete 回调把控制权交还调用方。
 *
 * 处理链路：先判断是否已有动态端口（已在运行则只做健康检查），否则执行完整启动：
 * 环境检测到自定义核心到扫描本地核心到检查更新到下载 EasyTier 组件到分配可用端口。
 *
 * 设计约束：
 * 1. 启动流程运行在 CompletableFuture.runAsync 的公共线程池上，渲染在客户端主线程；
 *    statusText、progress、isError 等字段被两边的线程同时读写且未做同步。
 * 2. isStarted 与 isError 保证同一实例内启动序列只执行一次；isFinished 目前没有任何赋值点，
 *    因此它恒为 false，实际不起作用。
 * 3. 取消或界面被移除时，只要本次是全新启动且未置 keepProcessAlive，就会另起线程停止后端进程。
 * 4. subStatusText 没有任何写入点，因此渲染中的次要状态行永远不会显示。
 * 5. STARTUP_EXECUTOR 复用 EnderExecutors 的共享调度器，其生命周期由 EnderLifecycle 统一关闭。
 *
 * FIXME(P2, 2026-12-31): 启动线程与渲染线程共享状态字段而未同步，进度展示的可见性无保证；
 * 应改为经 Minecraft#execute 回主线程写入状态。
 *
 * @see ClientSetupForge
 * @see EnderDashboard
 */
public class StartupScreen extends EnderBaseScreen {
    /** 本类日志器，使用独立名称便于按类过滤日志。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(StartupScreen.class);

    /**
     * 启动任务调度执行器。
     *
     * 复用 EnderExecutors 的共享单线程调度器：它与网络重连退避等定时任务同池，虽然会互相排队，
     * 但都是毫秒级短任务，且省掉了本类自己持有线程的生命周期问题。
     * daemon 属性由共享池保证，不随本界面关闭而终止，随客户端进程退出。
     */
    private static final ScheduledExecutorService STARTUP_EXECUTOR = EnderExecutors.scheduled();

    /** 主状态文本，初值「初始化中...」；由启动线程写入、渲染线程读取。 */
    private String statusText = "初始化中...";

    /** 次要状态文本，默认空串；当前没有任何写入点，因此对应的渲染分支永远不会进入。 */
    private String subStatusText = "";

    /** 启动进度，取值范围 0.0 到 1.0；用于绘制进度条，超出范围会画出界。 */
    private double progress = 0.0;

    /** 是否已进入错误状态，默认 false；为 true 时状态文本与进度条改用红色。 */
    private boolean isError = false;

    /** 启动是否已完成，默认 false；当前没有任何赋值点，恒为 false。 */
    private boolean isFinished = false;

    /** 启动序列是否已触发，默认 false；防止同一实例重复触发。 */
    private boolean isStarted = false;

    /** 是否保持后端进程存活，默认 false；为 true 时取消或移除界面都不再停止进程。 */
    private boolean keepProcessAlive = false;

    /** 本次是否为全新启动（而非复用已在运行的后端），默认 false。 */
    private boolean isFreshLaunch = false;

    /** 启动完成后的回调，允许为 null；为 null 时 onStartupSuccess 会直接关闭本界面。 */
    private Runnable onStartupComplete;

    /**
     * 构造启动器界面，不设置完成回调。
     *
     * @param parent 父屏幕，允许为 null；为 null 时关闭本屏幕会退回游戏界面
     */
    public StartupScreen(Screen parent) {
        this(parent, null);
    }

    /**
     * 构造启动器界面。
     *
     * @param parent 父屏幕，允许为 null；为 null 时关闭本屏幕会退回游戏界面
     * @param onStartupComplete 启动成功后的回调，允许为 null，为 null 时改为关闭本界面
     */
    public StartupScreen(Screen parent, Runnable onStartupComplete) {
        super(Component.literal("末影联机启动器"), parent);
        this.onStartupComplete = onStartupComplete;
    }

    /**
     * 设置启动完成后的回调。
     *
     * 可在启动进行中替换回调；之后调用 onStartupSuccess 时使用最新值。
     *
     * @param onStartupComplete 回调函数，允许为 null，为 null 时启动成功后直接关闭界面
     */
    public void setOnStartupComplete(Runnable onStartupComplete) {
        this.onStartupComplete = onStartupComplete;
    }

    /**
     * 填充内容区，契约见 EnderBaseScreen#initContent。
     *
     * 尾部只有「取消」按钮；若启动序列尚未触发且未处于错误状态，则同时触发一次启动。
     * 界面被重建（例如屏幕尺寸变化）时会因 isStarted 已置位而不会重启启动序列。
     */
    @Override
    protected void initContent() {
        this.layout.addToFooter(Button.builder(Component.literal("取消"), (button) -> {
            this.cancelAndClose();
        }).width(200).build());

        if (!isStarted && !isFinished && !isError) {
            startStartupSequence();
        }
    }

    /**
     * 触发启动序列。
     *
     * 已分配动态端口时走「复用」路径：重置 isFreshLaunch、做一次健康检查，
     * 正常则延时 500ms 后进入成功流程，异常则转入完整启动。
     * 未分配端口时直接执行完整启动。
     *
     * 副作用：立即置 isStarted，因此本方法在同一实例内只会真正执行一次。
     */
    private void startStartupSequence() {
        isStarted = true;

        if (EnderApiClient.hasDynamicPort()) {
            isFreshLaunch = false;
            updateStatus("检测到后台服务运行中...", 0.5);
            CompletableFuture.runAsync(() -> {
                if (EnderApiClient.checkHealth().join()) {
                    updateStatus("服务状态正常，正在连接...", 1.0);
                    delay(500).thenRun(() -> this.minecraft.execute(this::onStartupSuccess));
                } else {
                    LOGGER.warn("后台服务无响应，将重新启动...");
                    performFullStartup();
                }
            });
            return;
        }

        performFullStartup();
    }

    /**
     * 执行完整启动流程。
     *
     * 顺序为：检测系统与架构、优先使用自定义核心路径、准备下载目录、扫描本地核心，
     * 按自动更新开关决定是否跳过更新、检查最新版本、下载 EasyTier 组件、分配端口并进入成功流程。
     * 任意一步抛出异常都会置 isError 并把异常信息写入状态文本。
     *
     * 副作用：置 isFreshLaunch 为 true，并在整个流程结束前持续通过 updateStatus 推进进度。
     */
    private void performFullStartup() {
        isFreshLaunch = true;
        CompletableFuture.runAsync(() -> {
           try {
               updateStatus("正在检测系统环境...", 0.05);
               PlatformHelper.OS os = PlatformHelper.getOS();
               PlatformHelper.Arch arch = PlatformHelper.getArch();

               String customPathStr = PlatformConfigHolder.get().externalCorePath();
               if (!customPathStr.isEmpty()) {
                    Path customPath = Path.of(customPathStr);
                    if (Files.exists(customPath)) {
                        updateStatus("使用自定义核心...", 1.0);
                        launchAndConnect(customPath, customPath.getParent());
                        return;
                    } else {
                        throw new RuntimeException("找不到自定义核心文件: " + customPathStr);
                    }
               }

               Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
               Path downloadDir = gameDir.resolve("ender");
               if (!Files.exists(downloadDir)) {
                   Files.createDirectories(downloadDir);
               }
               boolean autoUpdate = PlatformConfigHolder.get().autoUpdate();

               Path existingExe = scanForExecutable(downloadDir);

               if (existingExe != null && !autoUpdate) {
                   updateStatus("发现本地核心，跳过更新...", 1.0);
                   launchAndConnect(existingExe, downloadDir);
                   return;
               }

               updateStatus("正在检查更新...", 0.1);
               String version;
               String filename;
               try {
                   version = VersionChecker.getLatestVersion();
                   filename = PlatformHelper.getDownloadFilename(version);
               } catch (Exception e) {
                   if (existingExe != null) {
                        LOGGER.warn("检查更新失败，使用本地版本", e);
                        updateStatus("检查更新失败，使用本地核心...", 1.0);
                        launchAndConnect(existingExe, downloadDir);
                        return;
                   }
                   throw e;
               }

               if (filename == null) {
                   throw new RuntimeException("不支持的平台: " + os + " (" + arch + ")");
               }

               String exeName = PlatformHelper.getExecutableName(version);

               existingExe = findExecutable(downloadDir, exeName);
               if (existingExe != null) {
                   updateStatus("发现最新版本核心，跳过下载...", 1.0);
                   launchAndConnect(existingExe, downloadDir);
                   return;
               }

               updateStatus("检测到平台: " + os + " (" + arch + ")", 0.15);
               delay(500).join();

               updateStatus("正在下载 EnderCore 组件...", 0.2);
               com.endercore.core.easytier.EasyTierManager.init(downloadDir);
               com.endercore.core.easytier.EasyTierManager.getInstance().initialize(p -> {
                   updateStatus("正在下载 EnderCore 组件... " + String.format("%.0f%%", p * 100), 0.2 + p * 0.7);
               }).join();

               launchAndConnect(null, downloadDir);

           } catch (Exception e) {
               LOGGER.error("启动失败", e);
               isError = true;
               updateStatus("启动失败: " + e.getMessage(), 0);
           }
       });
    }

    /**
     * 在下载目录中按可执行文件名递归查找核心文件（最大深度 3 层）。
     *
     * @param dir 搜索根目录，不存在时返回 null
     * @return 命中的第一个匹配文件；未命中或遍历出错时返回 null
     */
    private Path scanForExecutable(Path dir) {
        if (!Files.exists(dir)) return null;
        try (var stream = Files.walk(dir, 3)) {
            return stream
                    .filter(p -> p.getFileName().toString().equals(PlatformHelper.getExecutableName()))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            LOGGER.warn("查找可执行文件失败", e);
            return null;
        }
    }

    /**
     * 在指定目录下查找给定文件名的可执行文件。
     *
     * 先尝试直接拼接路径，未命中再递归查找（最大深度 3 层）。
     *
     * @param dir 搜索根目录，不存在时返回 null
     * @param exeName 目标文件名，不能为 null
     * @return 命中的文件；未命中或遍历出错时返回 null
     */
    private Path findExecutable(Path dir, String exeName) {
        if (!Files.exists(dir)) return null;
        try {
            Path direct = dir.resolve(exeName);
            if (Files.exists(direct)) return direct;

            try (var stream = Files.walk(dir, 3)) {
                return stream
                    .filter(p -> p.getFileName().toString().equals(exeName))
                    .findFirst()
                    .orElse(null);
            }
        } catch (Exception e) {
            LOGGER.warn("查找可执行文件失败", e);
            return null;
        }
    }

    /**
     * 分配端口并结束启动流程。
     *
     * 两个入参当前都未被使用：核心进程的启动已改由 EasyTier 组件在别处完成，
     * 本方法只负责分配可用端口并写入 EnderApiClient。
     *
     * @param exePath 核心可执行文件路径，当前未使用，允许为 null
     * @param workDir 工作目录，当前未使用，允许为 null
     * @throws Exception 声明保留自旧实现；当前只抛出 RuntimeException（找不到可用端口时）
     */
    private void launchAndConnect(Path exePath, Path workDir) throws Exception {
        updateStatus("正在初始化...", 0.95);

        
        

        updateStatus("初始化完成", 1.0);
        
        int port = PlatformHelper.findAvailablePort();
        if (port == -1) {
            throw new RuntimeException("无法找到可用端口");
        }
        EnderApiClient.setPort(port); 

        

        delay(500).thenRun(() -> this.minecraft.execute(this::onStartupSuccess));
    }

    /**
     * 在 STARTUP_EXECUTOR 上延时指定毫秒后完成一个 Future。
     *
     * 用于在启动流程中给出可见的进度停顿；返回的 Future 只作为完成信号，不携带值。
     * 不响应取消：调用方放弃等待也不会取消已排队的任务。
     *
     * @param millis 延时毫秒数，非负
     * @return 延时结束后完成的 Future，永不为 null
     */
    private CompletableFuture<Void> delay(long millis) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        STARTUP_EXECUTOR.schedule(() -> future.complete(null), millis, TimeUnit.MILLISECONDS);
        return future;
    }

    /**
     * 轮询端口文件直到读出端口或超过最大尝试次数。
     *
     * 当前没有任何调用点：核心进程改由 EasyTier 组件启动后不再写端口文件，
     * 因此这段轮询属于待清理的遗留实现（连同 readPortFile 与 completeAndCancel）。
     *
     * @param portFile 端口文件路径，不能为 null
     * @param maxAttempts 最大轮询次数，达到后以异常完成 Future
     * @param delayMs 轮询间隔，单位毫秒
     * @param timeoutMessage 超时异常的消息文本
     * @return 读到端口时以端口值完成、超时时以异常完成的 Future，永不为 null
     */
    private CompletableFuture<Integer> waitForPort(Path portFile, int maxAttempts, long delayMs, String timeoutMessage) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<ScheduledFuture<?>> scheduledRef = new AtomicReference<>();
        Runnable task = () -> {
            if (isError) {
                completeAndCancel(future, scheduledRef, -1);
                return;
            }
            int attempt = attempts.incrementAndGet();
            int port = readPortFile(portFile);
            if (port > 0) {
                completeAndCancel(future, scheduledRef, port);
                return;
            }
            if (attempt >= maxAttempts) {
                ScheduledFuture<?> scheduled = scheduledRef.get();
                if (scheduled != null) {
                    scheduled.cancel(false);
                }
                future.completeExceptionally(new RuntimeException(timeoutMessage));
            }
        };
        scheduledRef.set(STARTUP_EXECUTOR.scheduleAtFixedRate(task, 0, delayMs, TimeUnit.MILLISECONDS));
        return future;
    }

    /**
     * 读取端口文件中的 port 字段。
     *
     * 仅供 waitForPort 使用，而 waitForPort 当前没有调用点。
     *
     * @param portFile 端口文件路径，不能为 null
     * @return 文件中的 port 值；文件不存在、解析失败或缺少 port 字段时返回 -1
     */
    private int readPortFile(Path portFile) {
        if (!Files.exists(portFile)) return -1;
        try {
            String content = Files.readString(portFile);
            com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(content).getAsJsonObject();
            if (json.has("port")) {
                return json.get("port").getAsInt();
            }
        } catch (Exception e) {
        }
        return -1;
    }

    /**
     * 取消已排队的任务并以给定值完成 Future。
     *
     * 仅供 waitForPort 使用，而 waitForPort 当前没有调用点。
     *
     * @param future 待完成的 Future，不能为 null
     * @param scheduledRef 已排队任务的引用，允许为 null
     * @param value 用于完成 Future 的值
     */
    private <T> void completeAndCancel(CompletableFuture<T> future, AtomicReference<ScheduledFuture<?>> scheduledRef, T value) {
        ScheduledFuture<?> scheduled = scheduledRef.get();
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        future.complete(value);
    }

    /**
     * 启动成功后的收尾。
     *
     * 有回调时置 keepProcessAlive 并触发回调（由调用方决定后续界面）；
     * 无回调时直接关闭本界面，此时 keepProcessAlive 保持 false，关闭界面会停掉后端进程。
     */
    private void onStartupSuccess() {
        if (onStartupComplete != null) {
            keepProcessAlive = true;
            onStartupComplete.run();
        } else {
            this.onClose();
        }
    }

    /**
     * 更新状态文本与进度。
     *
     * 由启动线程调用，渲染线程读取；两处都未做同步（见类注释中的 FIXME）。
     *
     * @param text 新的主状态文本，不能为 null
     * @param progress 进度值，取值 0.0 到 1.0
     */
    private void updateStatus(String text, double progress) {
        this.statusText = text;
        this.progress = progress;
    }

    /**
     * 取消启动并关闭界面。
     *
     * 仅当本次是全新启动且未置 keepProcessAlive 时才停止后端进程，避免误杀已在运行的后端。
     */
    private void cancelAndClose() {
        if (!keepProcessAlive && isFreshLaunch) {
            EnderLifecycle.requestShutdownAsync();
        }
        this.onClose();
    }

    /**
     * 界面被移除时的清理，契约见 Screen#removed。
     *
     * 与 cancelAndClose 同为「放弃启动」的出口，但本方法在所有移除路径上都会执行；
     * 是否停止后端进程的判定条件与 cancelAndClose 一致。
     */
    @Override
    public void removed() {
        super.removed();
        if (!keepProcessAlive && isFreshLaunch) {
            EnderLifecycle.requestShutdownAsync();
        }
    }

    /**
     * 渲染屏幕，契约见 EnderBaseScreen#render。
     *
     * 垂直居中绘制状态文本（错误时用红色）；次要状态文本非空时绘制在其下方 5 像素处；
     * 最后在其下方 20 像素处绘制宽 200、高 10 的进度条，条内按 progress 比例填充。
     */
    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // 基准点为屏幕中心；状态文本上移 10，次要文本下移 5，进度条下移 20
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        guiGraphics.drawCenteredString(this.font, this.statusText, centerX, centerY - 10, isError ? 0xFF5555 : 0xFFFFFF);
        if (!this.subStatusText.isEmpty()) {
            guiGraphics.drawCenteredString(this.font, this.subStatusText, centerX, centerY + 5, 0xAAAAAA);
        }

        // 进度条：宽 200、高 10，水平居中对齐到 centerX
        int barWidth = 200;
        int barHeight = 10;
        int barX = centerX - barWidth / 2;
        int barY = centerY + 20;

        guiGraphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF555555);

        int filledWidth = (int) (barWidth * this.progress);
        guiGraphics.fill(barX, barY, barX + filledWidth, barY + barHeight, isError ? 0xFFFF5555 : 0xFF55FF55);
    }
}



