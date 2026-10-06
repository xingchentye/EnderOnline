/*
 * 本文件属于 EnderOnline Forge 适配层。
 *
 * 职责：客户端启动与状态通知，负责自动启动后端、每秒轮询后端状态、以及房间号与成员变更提示。
 *
 * 所有界面副作用都必须切回客户端主线程执行；本类不承载房间业务语义。
 */
package com.multiplayer.ender.client;

import com.endercore.core.comm.EnderLifecycle;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.multiplayer.ender.ConfigForge;
import com.multiplayer.ender.client.gui.StartupScreen;
import com.multiplayer.ender.network.EnderApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端启动与状态通知处理器（Forge）。
 *
 * 承担三类职责，全部由 Forge 事件驱动：
 * 1. 自动启动：首次进入标题界面时，若配置开启自动启动且后端尚未就绪，跳转到 StartupScreen。
 * 2. 状态轮询：玩家进入世界后每 20 tick 调用一次 checkBackendState，
 *    把后端状态变化与成员增减转成 Toast 与聊天栏提示。
 * 3. 房间号通知：房主拿到房间号后由外部直接调用 handleRoomCodeNotification，
 *    弹提示、写聊天栏并复制到剪贴板。
 *
 * 调用时机：onTitleScreenInit 只在首次进入标题界面生效（由 hasAutoStarted 保证一次性）；
 * onClientTick 仅在玩家已进入世界时计数；onClientLogout 在退出世界时关闭托管并停止后端进程。
 *
 * 设计约束：
 * 1. 轮询结果由 EnderApiClient 的回调线程送达，所有界面操作必须经 Minecraft#execute
 *    切回客户端主线程。
 * 2. 提示去重依赖 lastRoomCode / lastStateValue / lastMemberNames 三个静态快照，
 *    它们只允许在客户端主线程读取与写入。
 * 3. onClientLogout 会另起线程停止后端进程，该线程的生命周期归 ProcessLauncher，本类不持有。
 *
 * 线程安全性：三个 Forge 事件回调都在客户端主线程执行；checkBackendState 的 thenAccept
 * 回调在 EnderApiClient 回调线程，需回主线程后才读写静态快照。
 *
 * @see StartupScreen
 */
@Mod.EventBusSubscriber(modid = "ender_online", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ClientSetupForge {
    /** 本类日志器，使用独立名称便于按类过滤日志。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientSetupForge.class);

    /** 是否已尝试过自动启动，默认 false；置为 true 后不再重复跳转到启动界面。 */
    private static boolean hasAutoStarted = false;

    /** 客户端 tick 计数器，单位 tick；累计到 20 触发一次轮询后归零。 */
    private static int tickCounter = 0;

    /** 上一次轮询时后端是否处于 host-ok，默认 false；用于识别房主状态刚刚结束。 */
    private static boolean wasHostOk = false;

    /** 最近一次已提示过的房间号，默认空串；用于避免对同一房间重复提示。 */
    private static String lastRoomCode = "";

    /** JSON 解析器，Gson 实例线程安全，可跨线程复用。 */
    private static final Gson GSON = new Gson();

    /** 上一次写入日志的后端 state 值，默认空串；仅用于抑制重复日志。 */
    private static String lastStateValue = "";

    /**
     * 上一次轮询到的成员名单快照。
     *
     * 始终非 null（初始为空集合）；与新名单做差集即可得到「加入」与「退出」事件。
     */
    private static java.util.Set<String> lastMemberNames = new java.util.HashSet<>();

    /**
     * 简易 Toast 通知实现。
     *
     * 绘制一条黑底白字的临时提示：第一行为标题，第二行为内容（内容允许为 null）。
     * 显示时长固定 2000ms，自首次绘制开始计时。
     *
     * 设计约束：宽度在构造时按标题与内容中较宽者加 16px 内边距计算，并以「屏幕宽度 - 20」
     * 封顶；因此过长的文本会被裁剪，不会换行。
     *
     * 线程安全性：render 由 ToastComponent 在客户端主线程调用，实例字段只在该线程访问。
     */
    private static class SimpleToast implements Toast {
        /** 提示标题，不能为 null。 */
        private final Component title;

        /** 提示内容，允许为 null，为 null 时第二行不绘制。 */
        private final Component message;

        /** 提示框宽度，单位像素；构造时计算，取值不超过屏幕宽度减 20。 */
        private final int width;

        /** 首次被绘制的时间戳，单位毫秒；0 表示尚未绘制。 */
        private long firstDrawTime;

        /**
         * 构造提示。
         *
         * @param title 提示标题，不能为 null
         * @param message 提示内容，允许为 null
         */
        private SimpleToast(Component title, Component message) {
            this.title = title;
            this.message = message;
            this.width = calculateToastWidth(title, message);
        }

        /**
         * 绘制提示，契约见 Toast#render。
         *
         * 首次调用时记录起始时间，之后按 2000ms 计时，超时后返回 HIDE。
         */
        @Override
        public Visibility render(GuiGraphics guiGraphics, ToastComponent toastComponent, long time) {
            if (firstDrawTime == 0L) {
                firstDrawTime = time;
            }

            // 固定两行版式：标题基线 y=8，内容基线 y=20，左内边距 8
            int width = width();
            int height = height();

            guiGraphics.fill(0, 0, width, height, 0xCC000000);

            var font = toastComponent.getMinecraft().font;
            guiGraphics.drawString(font, title, 8, 8, 0xFFFFFF, false);
            if (message != null) {
                guiGraphics.drawString(font, message, 8, 20, 0xFFFFFF, false);
            }

            return time - firstDrawTime >= 2000L ? Visibility.HIDE : Visibility.SHOW;
        }

        /**
         * 提示框宽度，契约见 Toast#width。
         */
        @Override
        public int width() {
            return width;
        }

        /**
         * 提示框高度，固定 32 像素，契约见 Toast#height。
         */
        @Override
        public int height() {
            return 32;
        }
    }

    /**
     * 弹出一次 Toast 提示。
     *
     * 提示由客户端渲染线程消费，因此本方法可从任意线程调用。
     *
     * @param title 提示标题，不能为 null
     * @param message 提示内容，允许为 null，为 null 时只显示标题
     */
    public static void showToast(Component title, Component message) {
        Minecraft mc = Minecraft.getInstance();
        mc.getToasts().addToast(new SimpleToast(title, message));
    }

    /**
     * 计算 Toast 宽度。
     *
     * 取标题与内容中较宽者加 16px 内边距，再以「屏幕宽度 - 20」封顶、以 100 兜底；
     * 字体尚未就绪时直接返回 220。
     *
     * @param title 标题文本，不能为 null
     * @param message 内容文本，允许为 null
     * @return 提示框宽度，单位像素，恒不小于 100
     */
    private static int calculateToastWidth(Component title, Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.font == null) {
            return 220;
        }
        int maxTextWidth = mc.font.width(title);
        if (message != null) {
            maxTextWidth = Math.max(maxTextWidth, mc.font.width(message));
        }
        int padding = 16;
        int rawWidth = maxTextWidth + padding;
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int maxWidth = Math.max(100, screenWidth - 20);
        return Math.min(rawWidth, maxWidth);
    }

    /**
     * 处理房间号通知，由房主启动流程调用。
     *
     * 幂等性：与上次已提示的房间号相同的调用会被直接忽略，因此可以在每秒轮询中安全地反复调用。
     * 可从任意线程调用，内部会切回客户端主线程执行界面操作。
     *
     * @param roomCode 房间号；为 null 或空串时直接返回，不产生任何提示
     */
    public static void handleRoomCodeNotification(String roomCode) {
        if (roomCode == null || roomCode.isEmpty()) {
            return;
        }
        if (roomCode.equals(lastRoomCode)) {
            return;
        }
        lastRoomCode = roomCode;
        LOGGER.info("Host room ready with code {}", roomCode);
        showRoomCodeToasts(roomCode);
    }

    /**
     * 把房间号写入聊天栏。
     *
     * 房间号片段附带「点击复制」的点击事件与悬停提示。
     *
     * @param roomCode 房间号，不能为 null 或空串（由调用方保证）
     */
    private static void showRoomCodeInChat(String roomCode) {
        Minecraft mc = Minecraft.getInstance();
        MutableComponent msg = Component.literal("[Ender Core] 房间已创建！房间号: ");
        msg.withStyle(ChatFormatting.GREEN);

        MutableComponent code = Component.literal(roomCode);
        code.withStyle(style -> style
            .withColor(ChatFormatting.AQUA)
            .withBold(true)
            .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, roomCode))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("点击复制")))
        );

        msg.append(code);
        mc.gui.getChat().addMessage(msg);
    }

    /**
     * 客户端 tick 回调。
     *
     * 仅在 END 阶段且玩家已进入世界时计数；每累计 20 tick 触发一次 checkBackendState。
     *
     * @param event 客户端 Tick 事件，由 Forge 注入，不能为 null
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (Minecraft.getInstance().player == null) return;

        tickCounter++;
        if (tickCounter >= 20) {
            tickCounter = 0;
            checkBackendState();
        }
    }

    /**
     * 拉取一次后端状态并派生通知。
     *
     * 后端未分配动态端口时视为房间已关闭，清空房间号与成员快照后返回。
     * 否则与上一次快照比对：状态变化写一条 info 日志，房间号变化弹提示，
     * 成员差集分别产生「成员加入」与「成员退出」提示（自身不计入提示）。
     *
     * 幂等性：全部判定基于快照字段，重复调用不会对同一事件重复提示。
     * 副作用：更新 lastRoomCode、lastStateValue、lastMemberNames 与 wasHostOk。
     */
    private static void checkBackendState() {
        if (!EnderApiClient.hasDynamicPort()) {
            if (wasHostOk) {
                LOGGER.info("Backend dynamic port lost while previously host-ok, room considered closed");
            }
            wasHostOk = false;
            lastRoomCode = "";
            lastMemberNames.clear();
            return;
        }

        EnderApiClient.getState().thenAccept(stateJson -> {
            if (stateJson == null) return;
            try {
                JsonObject json = GSON.fromJson(stateJson, JsonObject.class);
                if (json.has("state")) {
                    String state = json.get("state").getAsString();
                    if (!state.equals(lastStateValue)) {
                        LOGGER.info("Ender backend state changed: {} -> {}", lastStateValue, state);
                        lastStateValue = state;
                    }
                    boolean isHostOk = "host-ok".equals(state);
                    boolean isConnected = isHostOk || "guest-ok".equals(state);

                    if (isHostOk) {
                        if (json.has("room")) {
                            String roomCode = json.get("room").getAsString();
                            handleRoomCodeNotification(roomCode);
                        }
                    } else {
                        if (wasHostOk) {
                            LOGGER.info("Host room left host-ok state, new state={}", state);
                        }
                        lastRoomCode = "";
                    }

                    if (isConnected) {
                        java.util.Set<String> currentMembers = new java.util.HashSet<>();
                        if (json.has("profiles")) {
                            JsonArray profiles = json.getAsJsonArray("profiles");
                            for (JsonElement element : profiles) {
                                JsonObject profile = element.getAsJsonObject();
                                if (profile.has("name")) {
                                    currentMembers.add(profile.get("name").getAsString());
                                }
                            }
                        } else if (json.has("players")) {
                            JsonArray players = json.getAsJsonArray("players");
                            for (JsonElement element : players) {
                                currentMembers.add(element.getAsString());
                            }
                        }

                        if (!currentMembers.isEmpty() || !lastMemberNames.isEmpty()) {
                            java.util.Set<String> joined = new java.util.HashSet<>(currentMembers);
                            joined.removeAll(lastMemberNames);

                            java.util.Set<String> left = new java.util.HashSet<>(lastMemberNames);
                            left.removeAll(currentMembers);

                            if ((!joined.isEmpty() || !left.isEmpty()) && Minecraft.getInstance() != null) {
                                Minecraft.getInstance().execute(() -> {
                                    String selfName = Minecraft.getInstance().player != null
                                            ? Minecraft.getInstance().player.getGameProfile().getName()
                                            : null;

                                    for (String name : joined) {
                                        if (selfName != null && selfName.equals(name)) continue;
                                        showToast(Component.literal("成员加入"), Component.literal(name + " 加入了房间"));
                                    }

                                    for (String name : left) {
                                        if (selfName != null && selfName.equals(name)) continue;
                                        showToast(Component.literal("成员退出"), Component.literal(name + " 已离开房间"));
                                    }
                                });
                            }

                            lastMemberNames = currentMembers;
                        }
                    } else {
                        lastMemberNames.clear();
                    }

                    wasHostOk = isHostOk;
                }
            } catch (Exception ignored) {}
        });
    }

    /**
     * 弹出一组房间号提示：Toast、聊天栏消息，并尝试复制到剪贴板。
     *
     * 剪贴板不可用时降级为「复制失败」提示，不会抛出异常。
     * 界面操作经 Minecraft#execute 切回客户端主线程。
     *
     * @param roomCode 房间号，不能为 null 或空串
     */
    private static void showRoomCodeToasts(String roomCode) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            showToast(Component.literal("房间号"), Component.literal("房间号: " + roomCode));
            showRoomCodeInChat(roomCode);
            try {
                minecraft.keyboardHandler.setClipboard(roomCode);
                showToast(Component.literal("提示"), Component.literal("房间号已复制到剪贴板"));
            } catch (Exception e) {
                showToast(Component.literal("提示"), Component.literal("复制失败，请手动复制房间号"));
            }
        });
    }

    /**
     * 退出世界事件回调。
     *
     * 仅在上一次状态为 host-ok 时生效：把后端置为空闲、异步停止后端进程并复位标记，
     * 避免离开世界后仍占用端口。
     *
     * @param event 客户端玩家登出事件，由 Forge 注入，不能为 null
     */
    @SubscribeEvent
    public static void onClientLogout(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        if (wasHostOk) {
             LOGGER.info("Detected world disconnect, stopping hosting...");
             EnderApiClient.setIdle();
             EnderLifecycle.requestShutdownAsync();
             wasHostOk = false;
        }
    }

    /**
     * 标题界面初始化回调。
     *
     * 仅在首次进入标题界面、配置开启自动启动且后端尚未就绪时跳转到 StartupScreen。
     * 跳转动作经 Minecraft#execute 投递到主线程，本方法自身不阻塞。
     *
     * @param event 屏幕初始化事件，由 Forge 注入，不能为 null
     */
    @SubscribeEvent
    public static void onTitleScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof TitleScreen && !hasAutoStarted) {
            hasAutoStarted = true;

            if (ConfigForge.AUTO_START_BACKEND.get() && !EnderApiClient.hasDynamicPort()) {
                Minecraft.getInstance().execute(() -> {
                    Minecraft.getInstance().setScreen(new StartupScreen(event.getScreen()));
                });
            }
        }
    }
}


