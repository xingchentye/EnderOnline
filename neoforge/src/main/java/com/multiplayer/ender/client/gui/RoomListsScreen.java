/*
 * 本文件属于 EnderOnline NeoForge 适配层。
 *
 * 职责：名单管理界面，编辑白名单 / 黑名单 / 禁言列表并写回后端房间管理状态。
 *
 * 关键约束：每次改动都必须立刻把整份房间管理状态推回后端，界面不保留未提交的本地改动。
 */
package com.multiplayer.ender.client.gui;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.multiplayer.ender.network.EnderApiClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

/**
 * 名单管理界面。
 *
 * 由房间信息或权限页的「详细名单管理」入口跳入。三个标签页共用同一份后端状态对象，
 * 通过切换 {@code currentTab} 决定渲染与编辑哪一个列表。
 *
 * 设计约束：
 * 1. 每次增删都调用 {@link EnderApiClient#updateRoomManagementState} 推送整份状态；改动是即时落地的，没有「保存」按钮。
 * 2. 同一玩家名在同一列表内不重复添加（忽略大小写），移除按首次匹配执行。
 * 3. {@code isLoading} 为真时列表显示「加载中...」，此时禁止依赖列表内容。
 * 4. 渲染前必须保证 {@code stateJson} 非 null，否则列表为空——进入界面时先 loadState。
 *
 * 线程安全性：字段在客户端主线程读写；异步回调通过 {@code minecraft.execute} 回到主线程后再刷新列表。
 *
 * @since 1.0
 * @see AddListEntryScreen
 * @see RoomInfoScreen
 */
public class RoomListsScreen extends EnderBaseScreen {
    /** JSON 解析器，非 null，复用同一实例。 */
    private static final Gson GSON = new Gson();

    /** 当前选中的标签页，取值 whitelist / blacklist / mute_list，默认 whitelist。 */
    private String currentTab = "whitelist"; 

    /** 房间管理状态；允许为 null，为 null 时列表为空且增删操作直接返回。 */
    private JsonObject stateJson;

    /** 是否正在加载状态，初始 true，首次加载完成后置 false。 */
    private boolean isLoading = true;

    /** 玩家列表控件；在 {@link #initContent()} 中创建，之前为 null。 */
    private PlayerList playerList;

    /** 标签页切换按钮；在 {@link #initContent()} 中创建，之前为 null。 */
    private Button viewSwitcherButton;

    /**
     * 构造名单管理界面。
     *
     * @param parent 父屏幕，用于返回，允许为 null
     */
    public RoomListsScreen(Screen parent) {
        super(Component.literal("名单管理"), parent);
    }

    /**
     * 初始化界面内容。
     *
     * 先按需拉取状态，再创建列表控件（顶部 32 像素起、底部留 60 像素给按钮），最后放底栏按钮组。
     */
    @Override
    protected void initContent() {
        if (isLoading) {
            loadState();
        }

        int listTop = 32;
        int listBottom = this.height - 60;
        this.playerList = new PlayerList(this.minecraft, this.width, listBottom - listTop, listTop);
        
        if (stateJson != null) {
            reloadPlayerList();
        }
        this.addRenderableWidget(this.playerList);

        
        LinearLayout footer = LinearLayout.horizontal().spacing(10);
        
        
        this.viewSwitcherButton = Button.builder(Component.literal("查看: " + getTabName(currentTab)), b -> {
            if ("whitelist".equals(currentTab)) {
                switchTab("blacklist");
            } else if ("blacklist".equals(currentTab)) {
                switchTab("mute_list");
            } else {
                switchTab("whitelist");
            }
        }).width(110).build();
        footer.addChild(this.viewSwitcherButton);

        footer.addChild(Button.builder(Component.literal("添加玩家"), b -> openAddDialog()).width(80).build());
        footer.addChild(Button.builder(Component.literal("刷新"), b -> {
            this.isLoading = true;
            loadState();
        }).width(60).build());
        footer.addChild(Button.builder(Component.literal("返回"), b -> this.onClose()).width(60).build());
        this.layout.addToFooter(footer);
    }

    /**
     * 渲染界面。
     *
     * 在原版渲染之上画列表表头（玩家名 / 操作），加载中时额外画一行提示。
     *
     * @param guiGraphics 图形上下文，不能为 null
     * @param mouseX 鼠标 X 坐标，单位为逻辑像素
     * @param mouseY 鼠标 Y 坐标，单位为逻辑像素
     * @param partialTick 当前帧的部分刻进度，取值 0.0..1.0
     */
    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        
        
        int headerY = 20; 
        int rowWidth = 300;
        int left = (this.width - rowWidth) / 2;
        
        guiGraphics.drawString(this.font, Component.literal("玩家名").withStyle(net.minecraft.ChatFormatting.YELLOW), left + 10, headerY, 0xFFFFFF);
        guiGraphics.drawString(this.font, Component.literal("操作").withStyle(net.minecraft.ChatFormatting.YELLOW), left + 240, headerY, 0xFFFFFF);
        
        if (isLoading) {
             guiGraphics.drawCenteredString(this.font, "加载中...", this.width / 2, this.height / 2, 0xAAAAAA);
        }
    }
    
    

    /**
     * 把标签页标识翻成界面显示名。
     *
     * @param tab 标签页标识，取值 whitelist / blacklist / mute_list；其它值原样返回
     * @return 显示名称，永不为 null
     */
    private String getTabName(String tab) {
        switch (tab) {
            case "whitelist": return "白名单";
            case "blacklist": return "黑名单";
            case "mute_list": return "禁言列表";
            default: return tab;
        }
    }

    /**
     * 切换标签页。
     *
     * 更新当前标签、刷新切换按钮文案并重新加载列表；状态对象不在本方法中重新拉取。
     *
     * @param tab 目标标签页，取值 whitelist / blacklist / mute_list
     */
    private void switchTab(String tab) {
        this.currentTab = tab;
        if (this.viewSwitcherButton != null) {
            this.viewSwitcherButton.setMessage(Component.literal("查看: " + getTabName(this.currentTab)));
        }
        reloadPlayerList();
    }

    /**
     * 重新加载玩家列表。
     *
     * 用当前状态与当前标签页重建列表条目；列表控件尚未创建时直接返回。
     */
    private void reloadPlayerList() {
        if (this.playerList == null) {
            return;
        }
        this.playerList.reloadFromState(this.stateJson, this.currentTab);
    }

    /**
     * 从后端加载房间管理状态。
     *
     * 无论成功或失败都会把 {@code isLoading} 置 false，并在主线程重载列表；
     * 解析失败只打印堆栈，不改变已有状态。
     */
    private void loadState() {
        EnderApiClient.getRoomManagementState().whenComplete((jsonStr, throwable) -> {
            if (throwable != null) {
                this.isLoading = false;
            } else {
                try {
                    this.stateJson = GSON.fromJson(jsonStr, JsonObject.class);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    this.isLoading = false;
                }
            }
            if (this.minecraft != null) {
                this.minecraft.execute(this::reloadPlayerList);
            }
        });
    }

    /**
     * 移除玩家。
     *
     * 从当前标签页对应的数组中删除首个同名条目（精确匹配，区分大小写），
     * 确有删除时才推回后端并刷新列表。
     *
     * @param name 玩家名称，不能为 null
     */
    private void removePlayer(String name) {
        if (stateJson == null) return;
        JsonArray list = stateJson.has(currentTab) ? stateJson.getAsJsonArray(currentTab) : new JsonArray();
        JsonArray newList = new JsonArray();
        boolean changed = false;
        for (JsonElement el : list) {
            if (!el.getAsString().equals(name)) {
                newList.add(el);
            } else {
                changed = true;
            }
        }
        
        if (changed) {
            stateJson.add(currentTab, newList);
            EnderApiClient.updateRoomManagementState(stateJson.toString());
            reloadPlayerList();
        }
    }

    /**
     * 打开添加玩家对话框。
     *
     * 跳转到 {@link AddListEntryScreen}，回调里再调用 {@link #addPlayer}。
     */
    private void openAddDialog() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new AddListEntryScreen(this, this.currentTab, (name, type) -> {
                addPlayer(name, type);
            }));
        }
    }
    
    /**
     * 从列表中移除指定玩家的回调入口。
     *
     * 供 {@link PlayerList.Entry} 的「移除」按钮调用，避免内部类直接持有私有方法引用。
     *
     * @param name 玩家名称，不能为 null
     */
    protected void removePlayerFromList(String name) {
        removePlayer(name);
    }

    /**
     * 添加玩家。
     *
     * 忽略大小写去重；已存在时不重复添加。新增成功后把整份状态推回后端、弹一条 toast，
     * 并在类型与当前标签页不一致时把界面切到该类型。
     *
     * @param name 玩家名称，为 null 或空白时直接返回
     * @param type 目标列表类型，取值 whitelist / blacklist / mute_list
     */
    private void addPlayer(String name, String type) {
        if (stateJson == null || name == null || name.isBlank()) return;
        JsonArray list = stateJson.has(type) ? stateJson.getAsJsonArray(type) : new JsonArray();
        
        for (JsonElement el : list) {
            if (el.getAsString().equals(name)) return;
        }
        
        list.add(name);
        stateJson.add(type, list);
        EnderApiClient.updateRoomManagementState(stateJson.toString());
        
        
        com.multiplayer.ender.client.ClientSetup.showToast(Component.literal("提示"), Component.literal("已添加玩家: " + name));

        
        if (!this.currentTab.equals(type)) {
            this.currentTab = type;
            if (this.viewSwitcherButton != null) {
                this.viewSwitcherButton.setMessage(Component.literal("查看: " + getTabName(this.currentTab)));
            }
        }

        if (this.minecraft != null) {
            reloadPlayerList();
        }
    }

    /**
     * 玩家列表控件。
     *
     * 固定行高 24、行宽 300，滚动条贴右侧；条目内嵌「移除」按钮。
     */
    class PlayerList extends ObjectSelectionList<PlayerList.Entry> {
        /**
         * 构造玩家列表。
         *
         * @param mc Minecraft 实例，不能为 null
         * @param width 列表宽度，单位为逻辑像素
         * @param height 列表可见高度，单位为逻辑像素
         * @param top 列表顶部 Y 坐标，单位为逻辑像素
         */
        public PlayerList(Minecraft mc, int width, int height, int top) {
            super(mc, width, height, top, 24);
        }
        
        /**
         * 追加一个玩家条目。
         *
         * @param name 玩家名称，不能为 null
         */
        public void addPlayer(String name) {
            this.addEntry(new Entry(name));
        }

        /**
         * 从状态重新加载列表。
         *
         * 先清空现有条目；状态为 null 或缺少对应标签页字段时列表为空。
         *
         * @param state 房间管理状态，允许为 null
         * @param tab 当前标签页字段名，取值 whitelist / blacklist / mute_list
         */
        public void reloadFromState(JsonObject state, String tab) {
            this.clearEntries();
            if (state == null) {
                return;
            }
            JsonArray list = state.has(tab) ? state.getAsJsonArray(tab) : new JsonArray();
            for (JsonElement el : list) {
                if (el != null && el.isJsonPrimitive()) {
                    this.addPlayer(el.getAsString());
                }
            }
        }
        
        /**
         * 行宽，固定 300 逻辑像素。
         *
         * @return 行宽
         */
        @Override
        public int getRowWidth() {
            return 300;
        }

        /**
         * 滚动条位置，贴列表右缘内侧 6 像素。
         *
         * @return 滚动条 X 坐标，单位为逻辑像素
         */
        @Override
        protected int getScrollbarPosition() {
            return (this.width / 2) + (getRowWidth() / 2) + 6;
        }

        /**
         * 列表条目，左侧显示玩家名，右侧是「移除」按钮。
         *
         * 不响应整行点击：点击热区只交给「移除」按钮，避免误删。
         */
        public class Entry extends ObjectSelectionList.Entry<Entry> {
            /** 玩家名称，非 null。 */
            private final String name;

            /** 移除按钮，非 null；点击后回调外层的移除逻辑。 */
            private final Button removeBtn;
            
            /**
             * 构造条目。
             *
             * @param name 玩家名称，不能为 null
             */
            public Entry(String name) {
                this.name = name;
                this.removeBtn = Button.builder(Component.literal("移除"), b -> RoomListsScreen.this.removePlayerFromList(name)).width(50).build();
            }

            /**
             * 渲染条目。
             *
             * 玩家名垂直居中靠左，移除按钮贴行右缘。
             *
             * @param guiGraphics 绘图上下文，不能为 null
             * @param index 条目在列表中的索引
             * @param top 行顶部 Y 坐标，单位为逻辑像素
             * @param left 行左缘 X 坐标，单位为逻辑像素
             * @param width 行宽，单位为逻辑像素
             * @param height 行高，单位为逻辑像素
             * @param mouseX 鼠标 X 坐标，单位为逻辑像素
             * @param mouseY 鼠标 Y 坐标，单位为逻辑像素
             * @param hovered 鼠标是否悬停在本行
             * @param partialTick 当前帧的部分刻进度，取值 0.0..1.0
             */
            @Override
            public void render(GuiGraphics guiGraphics, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovered, float partialTick) {
                guiGraphics.drawString(font, name, left + 10, top + (height - 8) / 2, 0xFFFFFF);
                this.removeBtn.setX(left + width - 55);
                this.removeBtn.setY(top + (height - 20) / 2);
                this.removeBtn.render(guiGraphics, mouseX, mouseY, partialTick);
            }

            /**
             * 鼠标点击处理。
             *
             * 先给移除按钮，未被消费时才交给父类；整行本身不产生动作。
             *
             * @param mouseX 鼠标 X 坐标，单位为逻辑像素
             * @param mouseY 鼠标 Y 坐标，单位为逻辑像素
             * @param button 鼠标按键编号
             * @return 事件被消费时返回 true
             */
            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (this.removeBtn.mouseClicked(mouseX, mouseY, button)) {
                    return true;
                }
                return super.mouseClicked(mouseX, mouseY, button);
            }
            
            /**
             * 无障碍朗读文本。
             *
             * @return 玩家名称，永不为 null
             */
            @Override
            public Component getNarration() {
                return Component.literal(name);
            }
        }
    }
}
