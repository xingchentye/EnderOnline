/*
 * 本文件属于 EnderOnline NeoForge 适配层。
 *
 * 职责：添加名单条目的输入界面。
 *
 * 关键约束：结果只通过构造器注入的回调返回，本屏幕不直接访问后端。
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
 * 由 {@link RoomListsScreen} 的「添加玩家」按钮跳转而来，输入玩家名称后选择目标名单（白名单/黑名单/禁言列表），
 * 确认时把「名称 + 类型」通过回调交回调用方，由调用方负责写后端。
 *
 * 设计约束：
 * 1. 本屏幕不持有任何后端状态，也不做名称合法性校验，校验责任在调用方的 {@code onAdd} 实现里。
 * 2. 类型按钮按 {@code types} 数组循环切换，顺序即界面顺序，改动数组会同时改变交互。
 *
 * 线程安全性：只在客户端主线程读写字段，无并发保护。
 *
 * @since 1.0
 * @see RoomListsScreen
 */
public class AddListEntryScreen extends EnderBaseScreen {
    /** 添加回调，入参依次为玩家名称与名单类型；允许为 null，为 null 时确认按钮只关界面不提交。 */
    private final BiConsumer<String, String> onAdd;

    /** 名称输入框；在 {@link #initContent()} 中创建，之前为 null，最大 32 字符。 */
    private EditBox nameField;

    /** 当前选中的名单类型，取值为 {@link #types} 中的元素，初始由构造器入参决定。 */
    private String selectedType = "whitelist"; 

    /** 可切换的名单类型集合，取值与后端房间管理状态的字段名一致：whitelist/blacklist/mute_list。 */
    private String[] types = new String[]{"whitelist", "blacklist", "mute_list"};

    /**
     * 构造添加名单界面。
     *
     * @param parent 父屏幕，用于返回，允许为 null
     * @param initialType 初始选中的名单类型，不能为 null；不在 {@link #types} 中时界面显示原始字符串
     * @param onAdd 确认回调，允许为 null；不为 null 时在关闭前以「名称, 类型」调用一次
     */
    public AddListEntryScreen(Screen parent, String initialType, BiConsumer<String, String> onAdd) {
        super(Component.literal("添加名单"), parent);
        this.onAdd = onAdd;
        this.selectedType = initialType;
    }

    /**
     * 初始化界面内容。
     *
     * 按从上到下的顺序放置：名称输入框、类型切换按钮、以及「添加 / 取消」按钮行。
     */
    @Override
    protected void initContent() {
        LinearLayout content = LinearLayout.vertical().spacing(8);
        content.defaultCellSetting().alignHorizontallyCenter();

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
     * 把后端用的类型标识翻成界面显示名。
     *
     * @param type 类型标识，取值 whitelist/blacklist/mute_list；其它值原样返回
     * @return 显示名称，永不为 null
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
