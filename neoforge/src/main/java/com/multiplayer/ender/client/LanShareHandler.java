/*
 * 本文件属于 EnderOnline NeoForge 适配层。
 *
 * 职责：劫持「对局域网开放」屏幕，把原版开房动作与末影联机托管合并成一次点击。
 *
 * 关键约束：原版按钮被隐藏而非移除，新按钮必须先触发原版逻辑再启动托管，顺序不可颠倒。
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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * 局域网分享屏幕处理器。
 *
 * 在 {@link ShareToLanScreen} 上做两件事：加一个「末影联机: 开/关」开关，并把原版
 * 「开始局域网世界」按钮换成自己的版本——新按钮先执行原版动作，再（在开关打开时）启动远程托管。
 *
 * 设计约束：
 * 1. 原版按钮只被 {@code visible/active = false} 隐藏，仍会被新按钮显式调用；移除它会导致原版开房逻辑丢失。
 * 2. 登录原版按钮靠消息文本匹配（含 "LAN" 或 "局域网"），依赖原版文案——本地化改动可能让它匹配不到，
 *    匹配失败时新按钮不会创建，属静默降级。
 * 3. {@code enableEnder} 是静态开关，跨屏幕实例保留上次选择；这是有意的（玩家通常连续多次开房）。
 * 4. 访客权限由当前存档的默认游戏模式推导：旁观者 → 仅观战，冒险 → 仅聊天（当前分支为空，等同可交互）。
 *
 * 线程安全性：静态字段只在客户端主线程读写，不存在跨线程访问。
 *
 * @see EnderApiClient
 * @see ClientSetup
 */
@EventBusSubscriber(modid = "ender_online", value = Dist.CLIENT)
public class LanShareHandler {
    /** 是否开启末影联机，默认关闭；跨屏幕实例保留。 */
    private static boolean enableEnder = false;

    /**
     * 当屏幕初始化完成后调用。
     *
     * 仅对 {@link ShareToLanScreen} 生效。执行三步：
     * 1. 在右上角添加「末影联机: 开/关」开关按钮。
     * 2. 按文案查找原版「开始局域网世界」按钮并把它隐藏。
     * 3. 在原位置放一个新按钮：先调用原版逻辑，再按开关决定是否启动远程托管；
     *    后缀为空时说明存档未开放局域网，不启动托管。
     *
     * @param event 屏幕初始化后期事件，不能为 null
     */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof ShareToLanScreen screen) {
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
     * 启动末影联机托管服务。
     *
     * 无动态端口说明后端未启动，直接在聊天栏报错返回（不抛异常，玩家可先去多人菜单启动）；
     * 否则调用 {@link EnderApiClient#startHosting} 申请房间号，成功后交给
     * {@link ClientSetup#handleRoomCodeNotification} 做 toast/聊天栏/剪贴板提示。
     *
     * @param port 本地局域网世界监听的端口号，取值 1..65535
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
                    ClientSetup.handleRoomCodeNotification(roomCode);
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



