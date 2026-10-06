/*
 * 本文件属于 EnderOnline Forge 适配层。
 *
 * 职责：改造「对局域网开放」屏幕，让玩家可以选择经末影联机托管本地端口。
 *
 * 入口通过 ScreenEvent.Init.Post 注入并替换原版按钮，用于替代已终止的 Fabric Mixin 方案。
 */
package com.multiplayer.ender.client;

import com.multiplayer.ender.client.gui.StartupScreen;
import com.multiplayer.ender.network.EnderApiClient;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ShareToLanScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 局域网分享屏幕入口处理器（Forge）。
 *
 * 属于「对局域网开放」页面：屏幕初始化后添加「末影联机: 开/关」切换按钮，
 * 并将原版「开放到局域网」按钮隐藏，用同尺寸同位置的新按钮取而代之。
 * 新按钮先执行原版逻辑（真正开放局域网并生成端口），再按开关状态把端口交给后端托管。
 *
 * 设计约束：
 * 1. 入口通过 ScreenEvent.Init.Post 注入，不使用 Mixin——Fabric 支持已终止（ADR-00），
 *    两端统一走加载器事件。
 * 2. 原版按钮靠文案匹配（含 "LAN" 或 "局域网"）定位，属于对原版文案的脆弱依赖：
 *    原版改文案或换语言即会静默失效，此时不替换按钮，只保留托管开关。
 * 3. 作弊权限通过反射读取，成因见 onScreenInit 内的兼容分支说明。
 *
 * 线程安全性：onScreenInit 在客户端主线程执行；startEnderHosting 的 thenAccept 回调
 * 在 EnderApiClient 的回调线程，聊天消息与界面切换均已用 execute 切回主线程。
 * enableEnder 为静态可变状态，只被主线程上的切换按钮与点击回调读写。
 *
 * @see ClientSetupForge#handleRoomCodeNotification(String)
 */
@Mod.EventBusSubscriber(modid = "ender_online", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class LanShareHandlerForge {
    /** 末影联机托管开关，默认 false；仅表达界面意图，点击开始后才真正生效。 */
    private static boolean enableEnder = false;

    /**
     * 屏幕初始化完成后的回调。
     *
     * 仅对 ShareToLanScreen 生效：注册托管开关按钮，并按文案定位原版「开放到局域网」
     * 按钮（找到才替换，找不到则保留原按钮且不做托管）。
     *
     * 替换按钮的点击流程：先调用原按钮逻辑真正开放局域网，再在开关打开且单人服务器
     * 已发布时读取作弊开关与默认游戏模式，换算成访客权限后交给后端托管。
     *
     * @param event 屏幕初始化事件，由 Forge 注入，不能为 null
     */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof ShareToLanScreen screen) {
            // 右上角对齐：按钮贴右边缘留 5px 边距，y=5 与多人菜单入口保持同一视觉基线
            int width = screen.width;
            int buttonWidth = 120;
            int x = width - buttonWidth - 5;
            int y = 5;

            Button toggleBtn = Button.builder(Component.literal("末影联机: " + (enableEnder ? "开" : "关")), button -> {
                enableEnder = !enableEnder;
                button.setMessage(Component.literal("末影联机: " + (enableEnder ? "开" : "关")));
            }).width(buttonWidth).bounds(x, y, buttonWidth, 20).build();

            event.addListener(toggleBtn);

            Button startBtn = null;
            for (GuiEventListener listener : event.getListenersList()) {
                if (listener instanceof Button btn) {
                    if (btn.getMessage().getString().contains("LAN") || btn.getMessage().getString().contains("局域网")) {
                        startBtn = btn;
                        break;
                    }
                }
            }

            if (startBtn != null) {
                startBtn.visible = false;
                startBtn.active = false;

                final Button originalBtn = startBtn;
                Button newStartBtn = Button.builder(originalBtn.getMessage(), button -> {
                    originalBtn.onPress();

                    Minecraft mc = Minecraft.getInstance();
                    if (enableEnder && mc.getSingleplayerServer() != null && mc.getSingleplayerServer().isPublished()) {
                        int port = mc.getSingleplayerServer().getPort();
                        
                        // NOTE: 这组兼容分支来自 Fabric 时期同时支持 Yarn 与 Mojmap 两套映射：
                        // 同一语义在 Yarn 下叫 areCheatsAllowed，在 Mojmap 下叫 isAllowCheatsForAllPlayers。
                        // Fabric 支持终止后两端已统一使用官方映射，回退链已无存在必要。
                        // TODO(ADR-03, 2026-09-30): 删除兼容分支，直接调用 Mojmap 方法并显式处理失败。
                        boolean allowCheats = false;
                        try {
                            Object playerList = mc.getSingleplayerServer().getPlayerList();
                            java.lang.reflect.Method m = playerList.getClass().getMethod("isAllowCheatsForAllPlayers");
                            allowCheats = (boolean) m.invoke(playerList);
                        } catch (Exception e) {
                            try {
                                Object playerList = mc.getSingleplayerServer().getPlayerList();
                                java.lang.reflect.Method m = playerList.getClass().getMethod("isAllowCommandsForAllPlayers");
                                allowCheats = (boolean) m.invoke(playerList);
                            } catch (Exception ignored) {}
                        }
                        
                        net.minecraft.world.level.GameType gameMode = mc.getSingleplayerServer().getDefaultGameType();
                        String visitorPermission = "可交互";
                        if (gameMode == net.minecraft.world.level.GameType.SPECTATOR) {
                            visitorPermission = "仅观战";
                        } else if (gameMode == net.minecraft.world.level.GameType.ADVENTURE) {
                            // 冒险模式不降级权限：保留默认的「可交互」，此分支为空是有意为之
                        }
                        
                        EnderApiClient.setLocalSettings(allowCheats, visitorPermission);

                        if (EnderApiClient.hasDynamicPort()) {
                            startEnderHosting(port);
                        } else {
                            mc.setScreen(new StartupScreen(null, () -> {
                                mc.setScreen(null);
                                startEnderHosting(port);
                            }));
                        }
                    }
                })
                .bounds(originalBtn.getX(), originalBtn.getY(), originalBtn.getWidth(), originalBtn.getHeight())
                .build();

                event.addListener(newStartBtn);
            }
        }
    }

    /**
     * 请求后端开始托管并处理结果。
     *
     * 后端未分配动态端口时直接输出错误提示并返回；否则异步发起托管，
     * 成功拿到房间号后切回主线程弹出提示，失败则在聊天栏输出错误原因。
     *
     * @param port 已开放到局域网的本地端口，取值来自 IntegratedServer#getPort
     */
    private static void startEnderHosting(int port) {
        Minecraft mc = Minecraft.getInstance();

        if (!EnderApiClient.hasDynamicPort()) {
            mc.gui.getChat().addMessage(Component.literal("[Ender Core] 错误：末影联机服务未启动或未连接。请先在多人游戏菜单中启动末影联机。").withStyle(ChatFormatting.RED));
            return;
        }

        String playerName = mc.getUser().getName();

        mc.gui.getChat().addMessage(Component.literal("[Ender Core] 正在尝试建立末影联机连接...").withStyle(ChatFormatting.GRAY));

        EnderApiClient.startHosting(port, playerName).thenAccept(roomCode -> {
            if (roomCode != null && !roomCode.isEmpty()) {
                Minecraft.getInstance().execute(() -> {
                    ClientSetupForge.handleRoomCodeNotification(roomCode);
                });
            } else {
                mc.gui.getChat().addMessage(Component.literal("[Ender Core] 启动失败: 未能生成房间号").withStyle(ChatFormatting.RED));
            }
        }).exceptionally(e -> {
             mc.gui.getChat().addMessage(Component.literal("[Ender Core] 启动失败，发生通信错误: " + e.getMessage()).withStyle(ChatFormatting.RED));
             return null;
        });
    }
}


