/*
 * 本文件属于 EnderOnline NeoForge 适配层。
 *
 * 职责：核心组件的启动与下载流程界面，负责把本地核心进程拉起并等待后端就绪。
 *
 * 关键约束：所有文件与网络操作都在后台线程执行，界面更新必须回到主线程；
 * 取消启动时若本次是「全新启动」需要停止已拉起的进程。
 */
package com.multiplayer.ender.client.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.multiplayer.ender.Config;
import com.multiplayer.ender.logic.DownloadManager;
import com.multiplayer.ender.logic.PlatformHelper;
import com.multiplayer.ender.logic.ProcessLauncher;
import com.multiplayer.ender.logic.VersionChecker;
import com.multiplayer.ender.network.NetworkClient;
import com.multiplayer.ender.network.EnderApiClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 启动与下载界面。
 *
 * 玩家从主菜单/多人菜单进入时，若后端尚未运行就落到这里。它按顺序完成：
 * 探测系统与架构、检查更新或复用本地核心、下载 EasyTier 组件、分配端口，最后回调启动完成动作。
 *
 * 设计约束：
 * 1. 后台流水线跑在 {@code CompletableFuture.runAsync}（默认 ForkJoinPool）上，所有 statusText/progress 的写入都要经 {@code minecraft.execute} 回到主线程；当前实现直接赋值，属于既存竞态，改动前不要假设线程安全。
 * 2. delay、waitForPort、waitForHealth 共用 STARTUP_EXECUTOR 这个单线程调度器；它同时承担延时与轮询，因此轮询任务必须在完成时取消自己，否则会一直占用调度槽。
 * 3. isStarted 保证一次界面生命周期内只启动一次流水线；isFinished 表示已经成功，用于区分「完成」与「取消」。
 * 4. isFreshLaunch 记录本次是否由本屏幕拉起进程，只有它为真时取消才需要 stop 进程。
 *
 * 线程安全性：字段在多个线程间读写，但只有主线程渲染；这是既存的松散约定（见约束 1），本次只补注释不改行为。
 *
 * @since 1.0
 * @see ProcessLauncher
 * @see EnderApiClient
 */
public class StartupScreen extends EnderBaseScreen {
    /** 本屏日志记录器，非 null。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(StartupScreen.class);

    /** 启动流水线专用单线程调度器，非 null；用于延时与状态轮询，守护线程随进程退出。 */
    private static final ScheduledExecutorService STARTUP_EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "Ender-Startup");
        thread.setDaemon(true);
        return thread;
    });
    
    /** 主状态文案，非 null，初始为「初始化中...」。 */
    private String statusText = "初始化中...";

    /** 次状态文案，非 null；当前流程不写入，保留给需要两行提示的场景。 */
    private String subStatusText = "";

    /** 进度值，取值 0.0..1.0，用于绘制进度条。 */
    private double progress = 0.0;

    /** 是否处于错误态；为真时状态文案按屏幕宽度换行显示且不画进度条。 */
    private boolean isError = false;

    /** 启动流程是否已成功完成。 */
    private boolean isFinished = false;

    /** 启动流程是否已启动过；用于防止重复进入流水线。 */
    private boolean isStarted = false;

    /** 是否保留已拉起的核心进程（成功后置真，关闭界面时不停进程）。 */
    private boolean keepProcessAlive = false;

    /** 本次是否由本屏幕重新拉起后端进程；只有全新启动才在取消时停止进程。 */
    private boolean isFreshLaunch = false;

    /** 启动成功后的回调；允许为 null，为 null 时默认跳到仪表盘。 */
    private Runnable onStartupComplete;

    /**
     * 构造启动界面，不指定完成回调。
     *
     * @param parent 父屏幕，用于返回，允许为 null
     */
    public StartupScreen(Screen parent) {
        this(parent, null);
    }

    /**
     * 构造启动界面。
     *
     * @param parent 父屏幕，用于返回，允许为 null
     * @param onStartupComplete 启动完成后的回调，允许为 null；为 null 时默认打开仪表盘
     */
    public StartupScreen(Screen parent, Runnable onStartupComplete) {
        super(Component.literal("末影联机启动器"), parent);
        this.onStartupComplete = onStartupComplete;
    }

    /**
     * 设置启动完成后的回调。
     *
     * 在流水线启动前设置才有效；启动已开始后替换回调不会改变本次流程的落点。
     *
     * @param onStartupComplete 启动完成后的回调，允许为 null
     */
    public void setOnStartupComplete(Runnable onStartupComplete) {
        this.onStartupComplete = onStartupComplete;
    }

    /**
     * 初始化界面内容。
     *
     * 底栏放「取消」按钮；首次进入且未处于结束/错误态时启动流水线。
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
     * 启动启动流水线。
     *
     * 已有动态端口时只做一次健康检查：健康则直接进入成功路径，不健康则走完整启动。
     * 没有动态端口则直接走完整启动。
     *
     * 幂等性：由 isStarted 保证只执行一次。
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
     * 执行完整的启动流程。
     *
     * 顺序为：读自定义路径 → 扫描/复用或下载核心 → 下载 EasyTier 组件 → 分配端口 → 等待就绪。
     * 任一步骤抛异常都会把界面切到错误态并展示异常消息。
     *
     * 副作用：会创建 {@code <游戏目录>/ender} 目录、写入核心文件并拉起外部进程。
     */
    private void performFullStartup() {
        isFreshLaunch = true;
        CompletableFuture.runAsync(() -> {
           try {
               updateStatus("正在检测系统环境...", 0.05);
               PlatformHelper.OS os = PlatformHelper.getOS();
               PlatformHelper.Arch arch = PlatformHelper.getArch();
               
               String customPathStr = Config.EXTERNAL_ender_PATH.get();
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
               boolean autoUpdate = Config.AUTO_UPDATE.get();
               
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
     * 在目录中浅层查找可执行文件。
     *
     * 按当前平台的可执行文件名匹配，最多下探 3 层。
     *
     * @param dir 待搜索目录，不能为 null；不存在时返回 null
     * @return 首个匹配的可执行文件路径；找不到或发生 IO 异常时返回 null
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
     * 按文件名查找可执行文件。
     *
     * 先看目录直属文件，再下探最多 3 层递归查找。
     *
     * @param dir 待搜索目录，不能为 null；不存在时返回 null
     * @param exeName 目标文件名，不能为 null
     * @return 匹配的文件路径；找不到或发生 IO 异常时返回 null
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
     * 分配端口并把流程推进到等待就绪。
     *
     * 端口从 {@link PlatformHelper#findAvailablePort()} 取得；-1 表示本机无可用端口，
     * 此时抛异常终止启动。端口会记入 {@link EnderApiClient} 供后续探活与跳转使用。
     *
     * @param exePath 已就位或待启动的核心可执行文件，允许为 null（表示由启动器自行处理）
     * @param workDir 核心的工作目录，不能为 null
     * @throws Exception 当找不到可用端口时抛出
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
     * 在启动调度器上延时。
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
     * 轮询端口文件直到拿到有效端口或超出尝试次数。
     *
     * 轮询成功、失败或检测到错误态时都会取消定时任务；超时以异常方式完成 Future。
     *
     * NOTE: 本方法当前无调用点，保留为流水线的备用路径。
     *
     * @param portFile 端口文件路径，不能为 null
     * @param maxAttempts 最大尝试次数，必须为正
     * @param delayMs 两次尝试之间的间隔毫秒数，必须为正
     * @param timeoutMessage 超时异常的消息，不能为 null
     * @return 端口值的 Future；成功为正端口，错误态为 -1，超时则异常完成
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
     * 从端口文件读取端口号。
     *
     * 文件内容应为含 port 字段的 JSON。
     *
     * @param portFile 端口文件路径，不能为 null
     * @return 端口值；文件不存在、无 port 字段或解析失败时返回 -1
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
     * 轮询后端健康检查直到成功或超出尝试次数。
     *
     * NOTE: 本方法当前无调用点，保留为流水线的备用路径。
     *
     * @param maxAttempts 最大尝试次数，必须为正
     * @param delayMs 两次尝试之间的间隔毫秒数，必须为正
     * @param timeoutMessage 超时异常的消息，不能为 null
     * @return 健康结果的 Future；错误态返回 false，超时则异常完成
     */
    private CompletableFuture<Boolean> waitForHealth(int maxAttempts, long delayMs, String timeoutMessage) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<ScheduledFuture<?>> scheduledRef = new AtomicReference<>();
        Runnable task = () -> {
            if (isError) {
                completeAndCancel(future, scheduledRef, false);
                return;
            }
            int attempt = attempts.incrementAndGet();
            if (EnderApiClient.checkHealth().join()) {
                completeAndCancel(future, scheduledRef, true);
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
     * 取消定时任务并完成 Future。
     *
     * 先取消再 complete，避免取消与完成竞争导致回调丢失。
     *
     * @param future 待完成的 Future，不能为 null
     * @param scheduledRef 持有定时任务的引用，不能为 null
     * @param value 结果值，允许为 null
     * @param <T> 结果类型
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
     * 标记保留进程，然后执行回调；回调为 null 时默认打开仪表盘。
     */
    private void onStartupSuccess() {
        keepProcessAlive = true;
        if (onStartupComplete != null) {
            onStartupComplete.run();
        } else {
            Minecraft.getInstance().setScreen(new EnderDashboard(parent));
        }
    }

    /**
     * 更新状态与进度。
     *
     * 会清空次状态文案，保证同一时刻只有一行主提示。
     *
     * @param text 新的状态文案，不能为 null
     * @param progress 进度值，取值 0.0..1.0
     */
    private void updateStatus(String text, double progress) {
        this.statusText = text;
        this.progress = progress;
        this.subStatusText = "";
    }

    /**
     * 渲染界面。
     *
     * 正常态单行居中显示状态并画进度条；错误态按屏幕宽度换行居中显示错误文案。
     *
     * @param guiGraphics 绘图上下文，不能为 null
     * @param mouseX 鼠标 X 坐标，单位为逻辑像素
     * @param mouseY 鼠标 Y 坐标，单位为逻辑像素
     * @param partialTick 当前帧的部分刻进度，取值 0.0..1.0
     */
    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        int color = isError ? 0xFF5555 : 0xAAAAAA;
        int centerY = this.height / 2;
        
        if (isError) {
            int maxWidth = this.width - 40;
            var lines = this.font.split(Component.literal(this.statusText), maxWidth);
            int totalHeight = lines.size() * this.font.lineHeight;
            int startY = centerY - totalHeight / 2;
            
            for (int i = 0; i < lines.size(); i++) {
                guiGraphics.drawCenteredString(this.font, lines.get(i), this.width / 2, startY + i * this.font.lineHeight, color);
            }
        } else {
            guiGraphics.drawCenteredString(this.font, this.statusText, this.width / 2, centerY - 20, color);
        }
        
        if (!subStatusText.isEmpty()) {
            guiGraphics.drawCenteredString(this.font, this.subStatusText, this.width / 2, centerY, 0xFFFFFF);
        }

        if (!isError && !isFinished) {
            int barWidth = 200;
            int barHeight = 4;
            int barX = this.width / 2 - barWidth / 2;
            int barY = centerY + 20;
            
            guiGraphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF555555);
            guiGraphics.fill(barX, barY, barX + (int)(barWidth * progress), barY + barHeight, 0xFF55FF55);
        }
    }
    
    /**
     * 按键处理。
     *
     * ESC（键码 256）等同于点击「取消」：会关闭界面并按需停止进程。
     *
     * @param keyCode 按键码
     * @param scanCode 扫描码
     * @param modifiers 修饰键位掩码
     * @return 事件被消费时返回 true
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            this.cancelAndClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * 取消并关闭界面。
     *
     * 仅当本次是全新启动且尚未完成时才停止核心进程；复用已有后端时不动它。
     */
    private void cancelAndClose() {
        if (isFreshLaunch && !isFinished) {
            new Thread(ProcessLauncher::stop, "Ender-Stopper").start();
        }
        this.onClose();
    }
    
    /**
     * 关闭屏幕，返回父屏幕。
     *
     * 不停止后端进程——进程的停止由 {@link #cancelAndClose()} 或房间页的断开按钮负责。
     */
    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}



