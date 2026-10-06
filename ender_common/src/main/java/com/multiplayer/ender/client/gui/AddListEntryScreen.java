/*
 * 本文件属于 EnderOnline Forge 适配层。
 *
 * 职责：名单条目录入界面，把「玩家名 + 目标列表类型」回交给调用方。
 *
 * 本类不访问后端，也不做重复与合法性校验，校验责任在 RoomListsScreen。
 */
package com.multiplayer.ender.client.gui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import java.util.function.BiConsumer;

/**
 * 添加名单条目界面。
 *
 * 属于名单管理的子页面：由 RoomListsScreen 打开，用于向白名单、黑名单或禁言列表中添加玩家名。
 * 界面只有一个输入框和一个「添加到: X」按钮，后者同时承担切换目标列表类型的职责，
 * 点击顺序为 whitelist 到 blacklist 到 mute_list 再回到 whitelist。
 *
 * 设计约束：
 * 1. 通过构造参数 onAdd 回调把「玩家名 + 列表类型」交回调用方，本类不直接访问后端。
 * 2. 输入的玩家名原样回传，不做去重与合法性校验，重复判定由 RoomListsScreen 负责。
 * 3. 玩家名输入上限为 32 字符。
 *
 * 线程安全性：Screen 只在客户端主线程使用；onAdd 回调在主线程同步触发。
 *
 * @see RoomListsScreen
 */
public class AddListEntryScreen extends EnderBaseScreen {
    /** 添加回调，接收 (玩家名, 列表类型)；允许为 null，为 null 时点击「添加」只关闭界面。 */
    private final BiConsumer<String, String> onAdd;

    /** 玩家名输入框，initContent 中创建；设为初始焦点，初始化前为 null。 */
    private EditBox nameField;

    /** 当前选中的列表类型，取值 whitelist / blacklist / mute_list，默认 whitelist。 */
    private String selectedType = "whitelist"; 
    /** 可选的列表类型顺序，切换按钮按此顺序循环；长度固定为 3。 */
    private String[] types = new String[]{"whitelist", "blacklist", "mute_list"};

    /**
     * 构造添加名单条目界面。
     *
     * @param parent 父屏幕，允许为 null；为 null 时关闭本屏幕会退回游戏界面
     * @param initialType 初始选中的列表类型，取值 whitelist / blacklist / mute_list；
     *                    不能为 null，否则构建切换按钮时会因 switch 空指针而失败
     * @param onAdd 添加回调，接收 (玩家名, 列表类型)；允许为 null
     */
    public AddListEntryScreen(Screen parent, String initialType, BiConsumer<String, String> onAdd) {
        super(Component.literal("添加名单"), parent);
        this.onAdd = onAdd;
        this.selectedType = initialType;
    }

    /**
     * 填充内容区，契约见 EnderBaseScreen#initContent。
     *
     * 自上而下为：玩家名输入框、列表类型切换按钮、并排的「添加」与「取消」按钮。
     * 输入框会被设为初始焦点。
     */
    @Override
    protected void initContent() {
        LinearLayout content = LinearLayout.vertical().spacing(8);
        content.defaultCellSetting().alignHorizontallyCenter();

        // 输入框宽 200、高 20；x/y 传 0 由布局管理器接管
        this.nameField = new EditBox(this.font, 0, 0, 200, 20, Component.literal("玩家名称"));
        this.nameField.setMaxLength(32);
        content.addChild(this.nameField);

        content.addChild(Button.builder(Component.literal("添加到: " + getTypeName(selectedType)), b -> {
            
            int idx = 0;
            for(int i=0; i<types.length; i++) {
                if(types[i].equals(selectedType)) {
                    idx = i;
                    break;
                }
            }
            selectedType = types[(idx + 1) % types.length];
            b.setMessage(Component.literal("添加到: " + getTypeName(selectedType)));
        }).width(200).build());

        LinearLayout buttons = LinearLayout.horizontal().spacing(8);
        buttons.addChild(Button.builder(Component.literal("添加"), b -> {
            if (this.onAdd != null) {
                this.onAdd.accept(this.nameField.getValue(), this.selectedType);
            }
            this.onClose();
        }).width(100).build());
        
        buttons.addChild(Button.builder(Component.literal("取消"), b -> this.onClose()).width(100).build());
        content.addChild(buttons);

        this.layout.addToContents(content);
        
        this.setInitialFocus(this.nameField);
    }

    /**
     * 把列表类型标识符映射为界面显示名。
     *
     * @param type 列表类型标识符；未知取值原样返回
     * @return 中文显示名，未知类型时返回入参本身
     */
    private String getTypeName(String type) {
        return switch (type) {
            case "whitelist" -> "白名单";
            case "blacklist" -> "黑名单";
            case "mute_list" -> "禁言列表";
            default -> type;
        };
    }
}
