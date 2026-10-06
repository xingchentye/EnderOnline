/*
 * 本文件属于 EnderOnline NeoForge 适配层。
 *
 * 职责：客户端启动初始化、后端状态轮询、HUD toast 通知与登出清理。
 *
 * 关键约束：本类持有多个客户端级静态状态（上一帧状态、成员名单、活跃 toast），
 * 所有 UI 操作必须回到主线程；轮询由 tick 事件驱动，不做定时器。
 */
package com.multiplayer.ender.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.multiplayer.ender.Config;
import com.multiplayer.ender.client.gui.StartupScreen;
import com.multiplayer.ender.network.EnderApiClient;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * 客户端启动与状态管理。
 *
 * 这是客户端侧的「状态观察者」：它不主动发请求，只在几个事件点上观察后端状态并把它翻译成界面反馈。
 *
 * 职责与调用时机：
 * 1. 自动启动后端：在标题界面首次初始化（{@code ScreenEvent.Init.Post} + TitleScreen）时判断，
 *    受 {@link Config#AUTO_START_BACKEND} 控制，且进程内只触发一次。
 * 2. 轮询后端状态：在 {@code ClientTickEvent.Post} 中每 20 tick（约 1 秒）调用一次
 *    {@link #checkBackendState()}，仅在玩家已进入世界（player 非 null）时轮询。
 * 3. HUD toast 通知：{@code RenderGuiEvent.Post} 中绘制 {@code activeToasts} 里的通知，
 *    每条存活 2000 毫秒，进出各 200 毫秒位移；房间号、成员进出都走这一条通道。
 * 4. 登出清理：{@code ClientPlayerNetworkEvent.LoggingOut} 时，若此前处于 host-ok 就置空闲并停止后端。
 *
 * 设计约束：
 * 1. 所有静态状态都是「上一次观察到的值」，用于检测跳变；它们不参与业务判定，重启客户端即复位。
 * 2. {@link #checkBackendState()} 是唯一的轮询入口，不要在别处再造一份。
 * 3. 本类直接持有中文提示文案，属 P7 的 i18n 债务；改文案必须走语言文件键，不要在这里新增字面量。
 *
 * 线程安全性：静态状态由客户端主线程与异步回调共同触碰，没有加锁——这是既存风险。
 * 所有对 UI 的写入都已包在 {@code Minecraft.execute} 中，这是本类唯一坚持的线程约定。
 *
 * @since 1.0
 * @see EnderApiClient
 * @see StartupScreen
 */
@EventBusSubscriber(modid = "ender_online", value = Dist.CLIENT)
public class ClientSetup {
    /** 本类日志记录器，非 null。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientSetup.class);

    /** 是否已执行过「进入标题界面自动启动后端」，保证进程内只尝试一次。 */
    private static boolean hasAutoStarted = false;

    /** 客户端 tick 计数器，累计到 20 时触发一次状态轮询。 */
    private static int tickCounter = 0;

    /** 记录上一次检查时是否处于 Hosting 状态，用于状态跳变检测。 */
    private static boolean wasHostOk = false;

    /** 上一次已提示过的房间号，非 null，空串表示尚未提示；用于避免重复提示同一房间。 */
    private static String lastRoomCode = "";

    /** JSON 解析器，非 null，复用同一实例。 */
    private static final Gson GSON = new Gson();

    /** 上一次已记录的后端状态码，非 null，空串表示尚未记录；变化时打一条日志。 */
    private static String lastStateValue = "";

    /** 记录上一次检查时的房间成员名单，非 null，用于计算成员增减。 */
    private static java.util.Set<String> lastMemberNames = new java.util.HashSet<>();

    /** 当前活跃的 HUD 通知列表，非 null；每帧渲染后移除超时的条目。 */
    private static final java.util.List<HudToast> activeToasts = new java.util.ArrayList<>();

    /**
     * 内部类：一条 HUD 通知。
     *
     * 只保存渲染所需的不可变字段；生命周期由外层的 {@code activeToasts} 按创建时间淘汰。
     */
    private static class HudToast {
        /** 通知标题，非 null。 */
        private final Component title;

        /** 通知正文，允许为 null，为 null 时只画标题。 */
        private final Component message;

        /** 通知框宽度，单位为逻辑像素，由文本宽度与屏幕宽度共同决定。 */
        private final int width;

        /** 通知框高度，单位为逻辑像素，固定 32。 */
        private final int height;

        /** 创建时间，单位毫秒（System.currentTimeMillis），用于计算存活时长与动画进度。 */
        private final long startTime;

        private HudToast(Component title, Component message, int width, int height, long startTime) {
            this.title = title;
            this.message = message;
            this.width = width;
            this.height = height;
            this.startTime = startTime;
        }
    }

    /**
     * 显示一条 HUD 通知。
     *
     * 字体尚未就绪（游戏启动早期）时静默忽略，避免在窗口未创建时崩。
     *
     * @param title 通知标题，不能为 null
     * @param message 通知内容，允许为 null
     */
    public static void showToast(Component title, Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.font == null) {
            return;
        }
        int width = calculateToastWidth(title, message);
        int height = 32;
        long now = System.currentTimeMillis();
        activeToasts.add(new HudToast(title, message, width, height, now));
    }

    /**
     * 计算通知框宽度。
     *
     * 取标题与正文中较宽者加 16 像素内边距，再限制在屏幕宽度减 20、且不小于 100 像素的范围内。
     *
     * @param title 标题，不能为 null
     * @param message 内容，允许为 null
     * @return 通知框宽度，单位为逻辑像素；字体不可用时返回 220
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
     * 处理房间号通知逻辑。
     *
     * 拿到新房间号时触发一次 toast + 聊天栏提示 + 复制到剪贴板；与上一次相同则不再重复。
     *
     * 幂等性：由 {@code lastRoomCode} 去重，同一房间号只提示一次。
     *
     * @param roomCode 房间号，为 null 或空串时直接返回
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
     * 在聊天栏显示可点击复制的房间号。
     *
     * 房间号本身设为青色加粗，点击复制到剪贴板、悬停显示「点击复制」提示。
     *
     * @param roomCode 房间号，不能为 null
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
     * 客户端 Tick 事件回调。
     *
     * 玩家尚未进入世界（player 为 null）时不轮询；否则每累计 20 tick（约 1 秒）检查一次后端状态。
     *
     * @param event Tick 事件，不能为 null
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (Minecraft.getInstance().player == null) return;

        tickCounter++;
        if (tickCounter >= 20) {
            tickCounter = 0;
            checkBackendState();
        }
    }

    /**
     * 检查后端状态并更新 UI。
     *
     * 流程：
     * 1. 动态端口消失时视为房间已关闭，复位全部观察状态并返回。
     * 2. 否则拉取状态 JSON，记录状态码跳变。
     * 3. host-ok 时把房间号交给 {@link #handleRoomCodeNotification}；离开 host-ok 时清空房间号。
     * 4. 已连接时对比成员名单，把新增/退出（排除自己）转成 toast。
     *
     * 失败容忍：解析异常被静默忽略，下一轮再试——状态轮询是尽力而为。
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
     * 弹出一条房间号 toast。
     *
     * 与 {@link #showToast} 的区别是这里会显式把标题固定为「房间号」，便于玩家一眼识别。
     *
     * @param roomCode 房间号，不能为 null
     */
    private static void showRoomCodeToast(String roomCode) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.font == null) {
            return;
        }
        int width = calculateToastWidth(Component.literal("房间号"), Component.literal("房间号: " + roomCode));
        int height = 32;
        long now = System.currentTimeMillis();
        activeToasts.add(new HudToast(Component.literal("房间号"), Component.literal("房间号: " + roomCode), width, height, now));
    }

    /**
     * 房间号的三连提示：toast + 聊天栏 + 剪贴板。
     *
     * 全程在主线程执行；剪贴板写入失败（例如无输入焦点）时回落为一条手动复制的提示，不抛异常。
     *
     * @param roomCode 房间号，不能为 null
     */
    private static void showRoomCodeToasts(String roomCode) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            showRoomCodeToast(roomCode);
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
     * 渲染 HUD 通知。
     *
     * 在 {@code RenderGuiEvent.Post} 中绘制所有活跃 toast：右上角自上而下排列，带简单的滑入滑出动画。
     * 每条通知存活 2000 毫秒，进入与退出各 200 毫秒；渲染循环同时负责淘汰过期条目。
     *
     * @param event GUI 渲染事件，不能为 null
     */
    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (activeToasts.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.font == null) {
            return;
        }
        GuiGraphics guiGraphics = event.getGuiGraphics();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        long now = System.currentTimeMillis();
        int y = 5;
        int margin = 5;

        java.util.Iterator<HudToast> iterator = activeToasts.iterator();
        while (iterator.hasNext()) {
            HudToast toast = iterator.next();
            long age = now - toast.startTime;
            if (age >= 2000L) {
                iterator.remove();
                continue;
            }
            double inDuration = 200.0;
            double outDuration = 200.0;
            double offset = 0.0;
            if (age < inDuration) {
                double t = age / inDuration;
                offset = 1.0 - t;
            } else if (age > 2000.0 - outDuration) {
                double t = (age - (2000.0 - outDuration)) / outDuration;
                offset = t;
            }
            int x = screenWidth - toast.width - 5 + (int) (toast.width * offset);
            guiGraphics.fill(x, y, x + toast.width, y + toast.height, 0xCC000000);
            guiGraphics.drawString(mc.font, toast.title, x + 8, y + 8, 0xFFFFFF, false);
            if (toast.message != null) {
                guiGraphics.drawString(mc.font, toast.message, x + 8, y + 20, 0xFFFFFF, false);
            }
            y += toast.height + margin;
        }
    }

    /**
     * 客户端登出事件回调。
     *
     * 玩家退出世界时，若此前处于 host-ok 状态，则置空闲并异步停止后端进程，避免房间被遗留在托管态。
     * 只有房主会走到这段逻辑；访客登出不触发。
     *
     * @param event 登出事件，不能为 null
     */
    @SubscribeEvent
    public static void onClientLogout(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        if (wasHostOk) {
             LOGGER.info("Detected world disconnect, stopping hosting...");
             EnderApiClient.setIdle();
             new Thread(com.multiplayer.ender.logic.ProcessLauncher::stop, "Ender-Stopper").start();
             wasHostOk = false;
        }
    }

    /**
     * 屏幕初始化事件回调。
     *
     * 在标题界面第一次初始化时判断是否自动启动后端：受 {@link Config#AUTO_START_BACKEND} 控制，
     * 且已有动态端口时不重复启动。整个过程进程内只执行一次。
     *
     * @param event 屏幕初始化事件，不能为 null
     */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof TitleScreen && !hasAutoStarted) {
            hasAutoStarted = true;
            
            if (Config.AUTO_START_BACKEND.get() && !EnderApiClient.hasDynamicPort()) {
                Minecraft.getInstance().execute(() -> {
                     Minecraft.getInstance().setScreen(new StartupScreen(event.getScreen()));
                });
            }
        }
    }
}


