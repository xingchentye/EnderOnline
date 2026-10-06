/*
 * 本文件属于 EnderOnline NeoForge 适配层。
 *
 * 职责：在暂停菜单（Esc）中注入房间入口按钮与房间信息悬浮窗。
 *
 * 关键约束：按钮初始不可见，可见性完全由内部 RoomStateWidget 按后端状态驱动；
 * 所有状态轮询结果必须回到主线程再改控件。
 */
package com.multiplayer.ender.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.multiplayer.ender.client.gui.EnderDashboard;
import com.multiplayer.ender.client.gui.StartupScreen;
import com.multiplayer.ender.network.EnderApiClient;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import java.util.concurrent.CompletableFuture;

/**
 * 游戏内菜单（暂停界面）处理器。
 *
 * 在 Esc 菜单右上角投放三个按钮与一个透明的状态挂件：
 * 1. 「显示信息 / 隐藏信息」切换房间信息悬浮窗。
 * 2. 「房间设置」（房主）或「房间信息」（访客）跳进仪表盘对应的视图。
 * 3. 「创建房间」——仅在单人且已开放局域网时出现。
 *
 * 设计约束：
 * 1. 三个按钮创建时一律 {@code visible = false}，可见性由 {@link RoomStateWidget#updateState()} 决定；
 *    不要在按钮回调里自行改 visible。
 * 2. 按钮坐标按 {@code screen.width} 右对齐（{@code width - buttonWidth - 5}），不写死绝对位置。
 * 3. {@link RoomStateWidget} 是个透明控件：它不画自己，只借用 render 回调做 1 秒节流的状态刷新与覆盖绘制。
 * 4. 「创建房间」有 300 毫秒双击保护（{@code lastCreateClickTime}）；去掉它会导致重复发起托管请求。
 *
 * 线程安全性：UI 字段在客户端主线程读写；异步状态回调一律经 {@code Minecraft.execute} 回到主线程，
 * {@code lastState} 是唯一被异步写入的字段，读它之前需假设可能为 null。
 *
 * @since 1.0
 * @see EnderApiClient
 */
@EventBusSubscriber(modid = "ender_online", value = Dist.CLIENT)
public class InGameMenuHandler {
    /** JSON 解析器，非 null，复用同一实例。 */
    private static final Gson GSON = new Gson();

    /** 是否显示房间信息悬浮窗，默认关闭。 */
    private static boolean showInfoOverlay = false;

    /** 最近一次解析到的后端状态；允许为 null，为 null 时不渲染悬浮窗。 */
    private static JsonObject lastState = null;

    /** 上次「创建房间」点击时间戳，单位毫秒，用于 300 毫秒双击保护。 */
    private static long lastCreateClickTime = 0;

    /**
     * 屏幕初始化事件回调。
     *
     * 仅对 {@link PauseScreen} 生效：创建三个按钮（初始不可见）、再挂上状态挂件并立刻强制刷新一次，
     * 避免打开菜单的瞬间按钮闪烁或缺失。
     *
     * @param event 屏幕初始化事件，不能为 null
     */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof PauseScreen screen) {
            int width = screen.width;
            int height = screen.height;
            int buttonWidth = 100;
            int x = width - buttonWidth - 5;
            int y = 5;

            // 预先创建按钮，初始可见性由 RoomStateWidget 接管
            Button infoBtn = Button.builder(Component.literal(showInfoOverlay ? "隐藏信息" : "显示信息"), button -> {
                showInfoOverlay = !showInfoOverlay;
                button.setMessage(Component.literal(showInfoOverlay ? "隐藏信息" : "显示信息"));
            }).bounds(x, y, buttonWidth, 20).build();
            infoBtn.visible = false;
            event.addListener(infoBtn);

            Button settingsBtn = Button.builder(Component.literal("房间设置"), button -> {
                if ("房间信息".equals(button.getMessage().getString())) {
                    Minecraft.getInstance().setScreen(new EnderDashboard(screen, EnderDashboard.ViewMode.INGAME_INFO));
                } else {
                    Minecraft.getInstance().setScreen(new EnderDashboard(screen, EnderDashboard.ViewMode.INGAME_SETTINGS));
                }
            }).bounds(x, y + 24, buttonWidth, 20).build();
            settingsBtn.visible = false;
            event.addListener(settingsBtn);

            Button createRoomBtn = Button.builder(Component.literal("创建房间"), button -> {
                long now = System.currentTimeMillis();
                if (now - lastCreateClickTime < 300) {
                    return;
                }
                lastCreateClickTime = now;
                Minecraft mc = Minecraft.getInstance();
                if (!EnderApiClient.hasDynamicPort()) {
                    mc.setScreen(new StartupScreen(screen, () -> {
                        mc.setScreen(screen);
                        String playerName = mc.getUser().getName();
                        // 确保使用正确端口
                        int port = 25565;
                        if (mc.getSingleplayerServer() != null) {
                            port = mc.getSingleplayerServer().getPort();
                        }
                        EnderApiClient.startHosting(port, playerName);
                    }));
                } else {
                    button.active = false;
                    CompletableFuture
                        .supplyAsync(() -> EnderApiClient.checkHealth().join())
                        .thenAccept(healthy -> {
                            if (!healthy) {
                                EnderApiClient.clearDynamicPort();
                                mc.execute(() -> {
                                    button.setMessage(Component.literal("创建房间"));
                                    button.active = true;
                                    mc.setScreen(new StartupScreen(screen, () -> {
                                        mc.setScreen(screen);
                                        String playerName = mc.getUser().getName();
                                        int port = 25565;
                                        if (mc.getSingleplayerServer() != null) {
                                            port = mc.getSingleplayerServer().getPort();
                                        }
                                        EnderApiClient.startHosting(port, playerName);
                                    }));
                                });
                            } else {
                                String playerName = mc.getUser().getName();
                                int port = 25565;
                                if (mc.getSingleplayerServer() != null) {
                                    port = mc.getSingleplayerServer().getPort();
                                }
                                EnderApiClient.startHosting(port, playerName);
                                mc.execute(() -> {
                                    button.setMessage(Component.literal("请求中..."));
                                    button.active = false;
                                });
                            }
                        });
                }
            }).bounds(x, y, buttonWidth, 20).build();
            createRoomBtn.visible = false;
            event.addListener(createRoomBtn);

            // 立即触发一次状态更新，避免按钮闪烁或消失
            RoomStateWidget stateWidget = new RoomStateWidget(0, 0, width, height, infoBtn, settingsBtn, createRoomBtn, Minecraft.getInstance().font);
            event.addListener(stateWidget);
            stateWidget.forceUpdate(); // 新增：强制立即更新状态
        }
    }

    /**
     * 内部组件：房间状态挂件。
     *
     * 覆盖整个屏幕的透明控件，用 render 回调充当「每帧钩子」：1 秒节流地轮询后端状态，
     * 据此控制三个按钮的可见性与文案，并可选地覆盖绘制房间信息悬浮窗。
     *
     * 设计约束：本控件不绘制自身，也不参与命中测试，因此不会遮挡其它控件。
     */
    private static class RoomStateWidget extends AbstractWidget {
        /** 信息开关按钮，非 null，由外层注入。 */
        private final Button infoBtn;

        /** 房间设置/信息入口按钮，非 null，由外层注入。 */
        private final Button settingsBtn;

        /** 创建房间按钮，非 null，由外层注入。 */
        private final Button createRoomBtn;

        /** 字体，非 null，用于绘制悬浮窗。 */
        private final Font font;

        /** 上次状态刷新时间戳，单位毫秒；构造时回拨 2000 毫秒以便立刻刷新一次。 */
        private long lastCheck = 0;

        public RoomStateWidget(int x, int y, int w, int h, Button infoBtn, Button settingsBtn, Button createRoomBtn, Font font) {
            super(x, y, w, h, Component.empty());
            this.infoBtn = infoBtn;
            this.settingsBtn = settingsBtn;
            this.createRoomBtn = createRoomBtn;
            this.font = font;
            this.lastCheck = System.currentTimeMillis() - 2000; 
        }

        /**
         * 强制立即执行一次状态更新。
         *
         * 打开暂停菜单时调用，跳过 1 秒节流，让按钮在首帧就处于正确状态。
         */
        public void forceUpdate() {
            this.lastCheck = System.currentTimeMillis();
            updateState();
        }

        /**
         * 渲染控件（实际不绘制自身）。
         *
         * 每帧检查距上次刷新是否超过 1 秒，是则触发一次状态更新；
         * 若开关打开且已有状态数据，则覆盖绘制房间信息悬浮窗。
         *
         * @param guiGraphics 绘图上下文，不能为 null
         * @param mouseX 鼠标 X 坐标，单位为逻辑像素
         * @param mouseY 鼠标 Y 坐标，单位为逻辑像素
         * @param partialTick 当前帧的部分刻进度，取值 0.0..1.0
         */
        @Override
        protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            long now = System.currentTimeMillis();
            if (now - lastCheck > 1000) {
                lastCheck = now;
                updateState();
            }

            if (showInfoOverlay && lastState != null && (infoBtn.visible || settingsBtn.visible)) {
                renderInfoOverlay(guiGraphics);
            }
        }

        /**
         * 渲染房间信息悬浮窗。
         *
         * 左上角半透明底框内依次显示标题、房间号与在线成员（房主加 [房主] 后缀）。
         * 调用前需保证 {@code lastState} 非 null。
         *
         * @param guiGraphics GUI 绘图上下文，不能为 null
         */
        private void renderInfoOverlay(GuiGraphics guiGraphics) {
            int startX = 10;
            int startY = 10;
            int lineHeight = 10;
            int padding = 5;
            
            int contentHeight = lineHeight * 3;
            if (lastState.has("profiles")) {
                contentHeight += lastState.getAsJsonArray("profiles").size() * lineHeight;
            }
            int boxWidth = 180;
            
            guiGraphics.fill(startX - padding, startY - padding, startX + boxWidth + padding, startY + contentHeight + padding, 0x40000000);
            
            int currentY = startY;
            
            guiGraphics.drawString(font, Component.literal(" 房间信息 ").withStyle(ChatFormatting.BOLD), startX, currentY, 0xFFFFFF);
            currentY += lineHeight;
            
            String roomCode = lastState.has("room") ? lastState.get("room").getAsString() : EnderApiClient.getLastRoomCode();
            if (roomCode == null || roomCode.isEmpty()) {
                roomCode = "未知";
            }
            guiGraphics.drawString(font, "房间号: " + roomCode, startX, currentY, 0xFFFF55);
            currentY += lineHeight;
            
            guiGraphics.drawString(font, Component.literal("在线成员:").withStyle(ChatFormatting.UNDERLINE), startX, currentY, 0xAAAAAA);
            currentY += lineHeight;
            
            if (lastState.has("profiles")) {
                JsonArray profiles = lastState.getAsJsonArray("profiles");
                for (JsonElement p : profiles) {
                    JsonObject profile = p.getAsJsonObject();
                    String name = profile.get("name").getAsString();
                    String kind = profile.has("kind") ? profile.get("kind").getAsString() : "";
                    String display = name;
                    if ("HOST".equals(kind)) {
                        display += " [房主]";
                    }
                    guiGraphics.drawString(font, display, startX + 5, currentY, 0xFFFFFF);
                    currentY += lineHeight;
                }
            }
        }

        /**
         * 更新当前状态。
         *
         * 请求后端状态并据此设置按钮可见性：
         * 已连接（host-ok / guest-ok）时显示信息与设置按钮，访客的设置按钮文案改为「房间信息」；
         * 否则回落到「本地已开放局域网则显示创建房间按钮」。
         *
         * 状态请求失败时会补一次健康检查，不健康则清掉动态端口，避免按钮停在错误状态。
         */
        private void updateState() {
            EnderApiClient.getState().whenComplete((stateJson, ex) -> {
                if (ex != null || stateJson == null) {
                    EnderApiClient.checkHealth().thenAccept(healthy -> {
                        if (!healthy) {
                            EnderApiClient.clearDynamicPort();
                        }
                        Minecraft.getInstance().execute(() -> {
                            infoBtn.visible = false;
                            settingsBtn.visible = false;
                            
                            if (Minecraft.getInstance().getSingleplayerServer() != null 
                                && Minecraft.getInstance().getSingleplayerServer().isPublished()) {
                                createRoomBtn.visible = true;
                                createRoomBtn.setMessage(Component.literal("创建房间"));
                                createRoomBtn.active = true;
                            } else {
                                createRoomBtn.visible = false;
                            }
                        });
                    });
                    return;
                }

                try {
                    JsonObject json = GSON.fromJson(stateJson, JsonObject.class);
                    lastState = json;

                    if (json.has("state")) {
                        String state = json.get("state").getAsString();
                        boolean isConnected = "host-ok".equals(state) || "guest-ok".equals(state);
                        
                        Minecraft.getInstance().execute(() -> {
                            if (isConnected) {
                                infoBtn.visible = true;
                                settingsBtn.visible = true;
                                if ("guest-ok".equals(state)) {
                                    settingsBtn.setMessage(Component.literal("房间信息"));
                                } else {
                                    settingsBtn.setMessage(Component.literal("房间设置"));
                                }
                                createRoomBtn.visible = false;
                            } else {
                                infoBtn.visible = false;
                                settingsBtn.visible = false;

                                if (Minecraft.getInstance().getSingleplayerServer() != null 
                                    && Minecraft.getInstance().getSingleplayerServer().isPublished()) {
                                    createRoomBtn.visible = true;
                                    if ("scanning".equals(state)) {
                                         createRoomBtn.setMessage(Component.literal("请求中..."));
                                         createRoomBtn.active = false;
                                    } else {
                                         createRoomBtn.setMessage(Component.literal("创建房间"));
                                         createRoomBtn.active = true;
                                    }
                                } else {
                                    createRoomBtn.visible = false;
                                }
                            }
                        });
                    } else if (json.has("status") && "IDLE".equals(json.get("status").getAsString())) {
                        lastState = null;
                        Minecraft.getInstance().execute(() -> {
                            infoBtn.visible = false;
                            settingsBtn.visible = false;
                            if (Minecraft.getInstance().getSingleplayerServer() != null
                                && Minecraft.getInstance().getSingleplayerServer().isPublished()) {
                                createRoomBtn.visible = true;
                                createRoomBtn.setMessage(Component.literal("创建房间"));
                                createRoomBtn.active = true;
                            } else {
                                createRoomBtn.visible = false;
                            }
                        });
                    }
                } catch (Exception ignored) {}
            });
        }

        /**
         * 无障碍朗读文本。
         *
         * 本控件纯装饰且不接收焦点，故不产出朗读内容。
         *
         * @param narrationElementOutput 朗读输出目标，不能为 null
         */
        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        }
    }
}

