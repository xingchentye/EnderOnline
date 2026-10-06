/*
 * 本文件属于 EnderOnline Forge 适配层。
 *
 * 职责：玩家名单管理界面，维护白名单、黑名单与禁言列表并整份同步到后端。
 *
 * 本类持有后端状态的一份本地快照，增删都基于该快照整份回推。
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
 * 玩家名单管理界面。
 *
 * 属于名单管理页：由 EnderDashboard 的「权限与访客」页与 RoomInfoScreen 的「详细列表」
 * 按钮打开；可在白名单、黑名单、禁言列表之间切换，添加或移除玩家，每次改动立即推送后端。
 *
 * 设计约束：
 * 1. 三个名单共用同一份后端状态 JSON，本类只切换查看的键名（currentTab），不缓存三份副本。
 * 2. 增删都基于本地 stateJson 快照整份回推，后端不做字段级合并，
 *    因此与其它客户端的并发修改会互相覆盖（后推者胜）。
 * 3. 布局硬编码：列表区域自 y=32 到 屏幕高度-60，行宽 300 像素、行高 24 像素。
 *
 * 线程安全性：Screen 只在客户端主线程使用；loadState 的异步回调经 Minecraft#execute
 * 切回主线程后才写入 stateJson / isLoading 并刷新列表。
 *
 * @see AddListEntryScreen
 */
public class RoomListsScreen extends EnderBaseScreen {
    /** JSON 解析器，Gson 实例线程安全，可跨线程复用。 */
    private static final Gson GSON = new Gson();

    /** 当前查看的名单类型，取值 whitelist / blacklist / mute_list，默认 whitelist。 */
    private String currentTab = "whitelist"; 
    /** 后端状态快照，允许为 null（未加载或加载失败）；增删操作都基于它整份回推。 */
    private JsonObject stateJson;

    /** 是否仍在加载状态，默认 true；true 时列表区域显示「加载中...」。 */
    private boolean isLoading = true;

    /** 玩家名单列表组件，initContent 中创建；在此之前为 null。 */
    private PlayerList playerList;

    /** 「查看: X」切换按钮，用于在三个名单之间循环；initContent 中创建。 */
    private Button viewSwitcherButton;

    /**
     * 构造名单管理界面。
     *
     * @param parent 父屏幕，允许为 null；为 null 时关闭本屏幕会退回游戏界面
     */
    public RoomListsScreen(Screen parent) {
        super(Component.literal("名单管理"), parent);
    }

    /**
     * 填充内容区，契约见 EnderBaseScreen#initContent。
     *
     * 打开时若仍处于加载态会先触发一次加载；列表覆盖 y=32 到 屏幕高度-60 的区域。
     * 尾部件依次为「查看: X」切换按钮、「添加玩家」、「刷新」、「返回」。
     *
     * 本方法会被刷新与状态变更间接重复调用，必须保持幂等（每次重建控件）。
     */
    @Override
    protected void initContent() {
        if (isLoading) {
            loadState();
        }

        // 列表区域：顶部让出 32 像素给表头，底部让出 60 像素给尾部件
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
     * 渲染屏幕，契约见 EnderBaseScreen#render。
     *
     * 在父类绘制后追加表头「玩家名 / 操作」（表头行 y=20），加载中时在屏幕中央绘制「加载中...」。
     */
    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        
        
        // 表头：行宽 300 居中，玩家名列左缩进 10，操作列右移 240
        int headerY = 20; 
        int rowWidth = 300;
        int left = (this.width - rowWidth) / 2;
        
        guiGraphics.drawString(this.font, Component.literal("玩家名").withStyle(net.minecraft.ChatFormatting.YELLOW), left + 10, headerY, 0xFFFFFF);
        guiGraphics.drawString(this.font, Component.literal("操作").withStyle(net.minecraft.ChatFormatting.YELLOW), left + 240, headerY, 0xFFFFFF);
        
        if (isLoading) {
             guiGraphics.drawCenteredString(this.font, "加载中...", this.width / 2, this.height / 2, 0xAAAAAA);
        }
    }
    
    

    private String getTabName(String tab) {
        switch (tab) {
            case "whitelist": return "白名单";
            case "blacklist": return "黑名单";
            case "mute_list": return "禁言列表";
            default: return tab;
        }
    }

    /**
     * 切换当前查看的名单类型。
     *
     * 会同步更新切换按钮文案，并立即按新类型重建列表内容。
     *
     * @param tab 目标名单类型，取值 whitelist / blacklist / mute_list
     */
    private void switchTab(String tab) {
        this.currentTab = tab;
        if (this.viewSwitcherButton != null) {
            this.viewSwitcherButton.setMessage(Component.literal("查看: " + getTabName(this.currentTab)));
        }
        reloadPlayerList();
    }

    /**
     * 按当前名单类型重建列表条目。
     *
     * 列表尚未创建时不做事；状态为 null 时列表会被清空。
     */
    private void reloadPlayerList() {
        if (this.playerList == null) {
            return;
        }
        this.playerList.reloadFromState(this.stateJson, this.currentTab);
    }

    /**
     * 从后端拉取房间管理状态并刷新界面。
     *
     * 请求失败时只结束加载态；请求成功时用 Gson 解析并重建列表。
     * 解析异常会把堆栈打印到标准错误，但同样会结束加载态并刷新列表。
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
     * 从当前名单中移除指定玩家，并把整份状态回推后端。
     *
     * 未命中该玩家时不产生任何写入与刷新。
     *
     * @param name 玩家名；stateJson 为 null 时直接返回
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
     * 打开「添加名单条目」界面，并把回调结果写入对应名单。
     */
    private void openAddDialog() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new AddListEntryScreen(this, this.currentTab, (name, type) -> {
                addPlayer(name, type);
            }));
        }
    }
    
    
    /**
     * 供列表条目回调的移除入口，转发给 removePlayer。
     *
     * 保留为 protected 是为了让内部类 Entry 复用同一条移除路径。
     *
     * @param name 玩家名
     */
    protected void removePlayerFromList(String name) {
        removePlayer(name);
    }

    /**
     * 向指定名单添加玩家，并把整份状态回推后端。
     *
     * 已存在同名玩家时不重复添加；添加成功后弹出 Toast，并把当前查看的名单切到目标类型。
     *
     * @param name 玩家名，为 null 或空白时直接返回
     * @param type 目标名单类型，取值 whitelist / blacklist / mute_list
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
        
        
        com.multiplayer.ender.client.ClientSetupForge.showToast(Component.literal("提示"), Component.literal("已添加玩家: " + name));

        
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
     * 玩家名单滚动列表。
     *
     * 继承 ObjectSelectionList，每行显示一个玩家名与一个「移除」按钮；行宽固定 300 像素、
     * 行高 24 像素（由构造参数传入）。列表内容只在 reloadFromState 中被整体重建。
     *
     * 线程安全性：只在客户端主线程使用。
     */
    class PlayerList extends ObjectSelectionList<PlayerList.Entry> {
        /**
         * 构造名单列表。
         *
         * @param mc 客户端实例，由外层传入，不能为 null
         * @param width 列表宽度，单位像素
         * @param height 列表高度，单位像素，决定可见条目数
         * @param top 列表顶部 Y 坐标，单位像素
         */
        public PlayerList(Minecraft mc, int width, int height, int top) {
            super(mc, width, height, top, 24);
        }

        /**
         * 追加一行玩家条目。
         *
         * @param name 玩家名，不能为 null
         */
        public void addPlayer(String name) {
            this.addEntry(new Entry(name));
        }

        /**
         * 按名单类型重建全部条目。
         *
         * 会先清空现有条目；状态为 null 时列表保持为空。
         *
         * @param state 后端状态对象，允许为 null
         * @param tab 名单类型键名，取值 whitelist / blacklist / mute_list
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
         * 行宽，固定 300 像素，契约见 ObjectSelectionList#getRowWidth。
         */
        @Override
        public int getRowWidth() {
            return 300;
        }

        /**
         * 滚动条 X 坐标，贴行区域右缘再外扩 6 像素，契约见 ObjectSelectionList#getScrollbarPosition。
         */
        @Override
        protected int getScrollbarPosition() {
            return (this.width / 2) + (getRowWidth() / 2) + 6;
        }

        /**
         * 单行玩家条目。
         *
         * 左侧绘制玩家名，右侧绘制「移除」按钮。点击按钮会回调外层的移除逻辑；
         * 点击行本身不做任何事，仅把事件交给父类。
         */
        public class Entry extends ObjectSelectionList.Entry<Entry> {
            /** 玩家名，不能为 null。 */
            private final String name;

            /** 「移除」按钮，宽 50 像素；每帧渲染前按其所在行重新定位。 */
            private final Button removeBtn;

            /**
             * 构造条目。
             *
             * @param name 玩家名，不能为 null
             */
            public Entry(String name) {
                this.name = name;
                this.removeBtn = Button.builder(Component.literal("移除"), b -> RoomListsScreen.this.removePlayerFromList(name)).width(50).build();
            }

            /**
             * 绘制条目，契约见 ObjectSelectionList.Entry#render。
             *
             * 玩家名绘制在行内左侧（左内边距 10）；「移除」按钮贴行右缘内缩 55 像素处垂直居中。
             */
            @Override
            public void render(GuiGraphics guiGraphics, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovered, float partialTick) {
                guiGraphics.drawString(font, name, left + 10, top + (height - 8) / 2, 0xFFFFFF);
                this.removeBtn.setX(left + width - 55);
                this.removeBtn.setY(top + (height - 20) / 2);
                this.removeBtn.render(guiGraphics, mouseX, mouseY, partialTick);
            }

            /**
             * 处理点击，契约见 ObjectSelectionList.Entry#mouseClicked。
             *
             * 优先转发给「移除」按钮，未命中时再交给父类。
             */
            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (this.removeBtn.mouseClicked(mouseX, mouseY, button)) {
                    return true;
                }
                return super.mouseClicked(mouseX, mouseY, button);
            }

            /**
             * 朗读文本，直接使用玩家名，契约见 ObjectSelectionList.Entry#getNarration。
             */
            @Override
            public Component getNarration() {
                return Component.literal(name);
            }
        }
    }
}
