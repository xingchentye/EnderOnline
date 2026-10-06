/*
 * 本文件属于 EnderOnline Forge 适配层。
 *
 * 职责：在游戏暂停菜单注入房间信息/设置/创建房间入口，并驱动一个零尺寸的状态轮询组件。
 *
 * 入口通过 ScreenEvent.Init.Post 注入，用于替代已终止的 Fabric Mixin 方案。
 */
package com.multiplayer.ender.client;

import java.util.concurrent.CompletableFuture;

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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 游戏内菜单入口处理器（Forge）。
 *
 * 属于暂停菜单（ESC）页面：屏幕初始化后注册「显示信息」「房间设置 / 房间信息」「创建房间」
 * 三个按钮（初始全部隐藏），并追加一个覆盖全屏的 RoomStateWidget 负责每秒轮询后端状态、
 * 据此切换按钮可见性与文案。三个按钮的可见性互斥，具体规则见 RoomStateWidget#updateState。
 *
 * 设计约束：
 * 1. 入口通过 ScreenEvent.Init.Post 注入，不使用 Mixin——Fabric 支持已终止（ADR-00），
 *    两端统一走加载器事件。
 * 2. 轮询结果由 EnderApiClient 的回调线程送达，所有控件读写必须经
 *    Minecraft.getInstance().execute 切回客户端主线程后才能进行。
 * 3. 「创建房间」用 300ms 去抖防止重复点击；去抖状态是静态的，与屏幕实例无关，
 *    因此重新打开暂停菜单不会重置。
 *
 * 线程安全性：onScreenInit 与 renderWidget 在客户端主线程；updateState 的 thenAccept
 * 回调在 EnderApiClient 回调线程，跨线程写入的静态状态只有经 execute 包裹的主线程任务。
 *
 * @see EnderDashboard
 * @see StartupScreen
 */
@Mod.EventBusSubscriber(modid = "ender_online", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class InGameMenuHandlerForge {
    /** JSON 解析器，Gson 实例线程安全，可跨线程复用。 */
    private static final Gson GSON = new Gson();

    /** 信息浮层是否展开，默认 false；仅由客户端主线程读写。 */
    private static boolean showInfoOverlay = false;

    /** 最近一次解析成功的后端状态 JSON，允许为 null（未连接或无数据）。 */
    private static JsonObject lastState = null;

    /** 上一次点击「创建房间」的时间戳，单位毫秒；用于 300ms 去抖。 */
    private static long lastCreateClickTime = 0;

    /**
     * 屏幕初始化完成后的回调。
     *
     * 仅对 PauseScreen 生效：注册三个初始不可见的按钮，并追加 RoomStateWidget 承担轮询。
     * 「创建房间」点击后先做 300ms 本地去抖，再分两条路径：后端未就绪时经 StartupScreen
     * 启动后开始托管；后端已就绪时先做健康检查，检查失败则清除动态端口并重回启动流程。
     *
     * @param event 屏幕初始化事件，由 Forge 注入，不能为 null
     */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof PauseScreen screen) {
            // 右上角对齐：按钮贴右边缘留 5px 边距；y 起点与相邻按钮间距 24（按钮高 20 + 4 间隙）
            int width = screen.width;
            int height = screen.height;
            int buttonWidth = 100;
            int x = width - buttonWidth - 5;
            int y = 5;

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
                        EnderApiClient.startHosting(0, playerName);
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
                                        EnderApiClient.startHosting(0, playerName);
                                    }));
                                });
                            } else {
                                String playerName = mc.getUser().getName();
                                EnderApiClient.startHosting(0, playerName);
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

            event.addListener(new RoomStateWidget(0, 0, width, height, infoBtn, settingsBtn, createRoomBtn, Minecraft.getInstance().font));
        }
    }

    /**
     * 零尺寸状态轮询组件。
     *
     * 组件自身不绘制任何内容，只借渲染回调充当每秒一次的轮询节拍器：查询后端状态、
     * 切换三个按钮的可见性与文案；当浮层已展开且信息/设置按钮可见时，才绘制左上角信息浮层。
     *
     * 设计约束：轮询间隔硬编码为 1000ms，且完全依赖渲染回调驱动，屏幕不可见时不会轮询；
     * 因此状态刷新存在最多约 1 秒的延迟，且暂停菜单关闭期间不会更新。
     *
     * 线程安全性：renderWidget / renderInfoOverlay 在客户端主线程执行；
     * updateState 的异步回调在 EnderApiClient 回调线程，控件写入全部经 execute 切回主线程。
     */
    private static class RoomStateWidget extends AbstractWidget {
        /** 「显示信息 / 隐藏信息」按钮，由 updateState 切换可见性。 */
        private final Button infoBtn;

        /** 「房间设置 / 房间信息」按钮，由 updateState 切换可见性与文案。 */
        private final Button settingsBtn;

        /** 「创建房间」按钮，仅在单人世界已开放到局域网且未处于联机状态时可见。 */
        private final Button createRoomBtn;

        /** 浮层文本绘制所用字体，由外层注入，不能为 null。 */
        private final Font font;

        /** 上次轮询的时间戳，单位毫秒；构造时回拨 2 秒，使首次渲染立即拉取一次状态。 */
        private long lastCheck = 0;

        /**
         * 构造轮询组件。
         *
         * @param x 组件左上角 X，组件不绘制自身，仅参与命中判定
         * @param y 组件左上角 Y，组件不绘制自身，仅参与命中判定
         * @param w 组件宽度，传入屏幕宽度
         * @param h 组件高度，传入屏幕高度
         * @param infoBtn 信息浮层开关按钮，不能为 null
         * @param settingsBtn 房间设置 / 房间信息按钮，不能为 null
         * @param createRoomBtn 创建房间按钮，不能为 null
         * @param font 浮层文本字体，不能为 null
         */
        public RoomStateWidget(int x, int y, int w, int h, Button infoBtn, Button settingsBtn, Button createRoomBtn, Font font) {
            super(x, y, w, h, Component.empty());
            this.infoBtn = infoBtn;
            this.settingsBtn = settingsBtn;
            this.createRoomBtn = createRoomBtn;
            this.font = font;
            this.lastCheck = System.currentTimeMillis() - 2000;
        }

        /**
         * 渲染回调，同时充当每秒一次的轮询节拍器。
         *
         * 距上次轮询超过 1000ms 时触发 updateState；随后在浮层展开且两个入口按钮
         * 至少一个可见时绘制信息浮层。
         *
         * @param guiGraphics 绘制上下文，不能为 null
         * @param mouseX 鼠标 X 坐标
         * @param mouseY 鼠标 Y 坐标
         * @param partialTick 当前帧的部分刻
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
         * 绘制左上角信息浮层。
         *
         * 内容自上而下为：标题、房间号、在线成员列表。浮层高度随成员数增长，
         * 成员较多时会超出屏幕底部，此处不做裁剪也不做滚动。
         *
         * @param guiGraphics 绘制上下文，不能为 null
         */
        private void renderInfoOverlay(GuiGraphics guiGraphics) {
            // 浮层固定在左上角：起点 (10,10)，行高 10，内边距 5，宽度固定 180
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
         * 拉取并应用一次后端状态。
         *
         * 请求失败或返回 null 时退化为健康检查：健康检查不通过即清除动态端口，
         * 并把三个按钮重置为「未连接」形态。请求成功时按 state 字段分流：
         * host-ok / guest-ok 显示信息与设置入口（访客看到「房间信息」，房主看到「房间设置」）；
         * 其余状态在单人世界已开放到局域网时显示「创建房间」，scanning 时置灰并改文案。
         *
         * 幂等性：每次调用都会覆盖三个按钮的可见性、文案与可用状态，重复执行结果一致。
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
         * 无障碍朗读内容。
         *
         * 本组件只承载轮询职责、自身不绘制内容，故留空实现。
         */
        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        }
    }
}

