/*
 * 本文件属于 EnderOnline Forge 适配层。
 *
 * 职责：末影联机所有界面的基类，统一头部/尾部布局骨架与「关闭时返回父屏幕」的行为。
 *
 * 子类只声明中间内容区，标题与尾部排布由本类的布局管理器负责。
 */
package com.multiplayer.ender.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

/**
 * 末影联机界面基类。
 *
 * 为末影联机各 Screen 提供统一骨架：头部高 30 像素（放标题）、尾部高 30 像素（放操作按钮），
 * 中间内容区由子类通过 initContent() 填充；关闭时统一返回构造时传入的父屏幕。
 *
 * 设计约束：
 * 1. 子类必须在 initContent() 内把控件挂到 this.layout 上，而不是直接调用 addRenderableWidget，
 *    否则控件不会参与布局管理器的排布与重新定位。
 * 2. 父屏幕允许为 null（例如从标题界面直接进入），此时 onClose 会把当前屏幕置空，退回游戏界面。
 *
 * 线程安全性：Screen 只在客户端主线程使用，本类无可变静态状态。
 *
 * @see EnderDashboard
 */
public abstract class EnderBaseScreen extends Screen {
    /**
     * 父屏幕，用于关闭当前屏幕时返回。
     *
     * 允许为 null；为 null 时 onClose 会把当前屏幕置空并退回游戏界面。
     */
    protected final Screen parent;

    /**
     * 头部与尾部布局管理器。
     *
     * 在 init() 中创建，init 之前为 null；头部高度 30 像素，尾部高度 30 像素。
     */
    protected HeaderAndFooterLayout layout;

    /**
     * 构造界面。
     *
     * @param title 屏幕标题，会显示在头部标题区，不能为 null
     * @param parent 父屏幕，允许为 null；为 null 时关闭本屏幕将退回游戏界面
     */
    protected EnderBaseScreen(Component title, Screen parent) {
        super(title);
        this.parent = parent;
    }

    /**
     * 初始化屏幕，契约见 Screen#init。
     *
     * 调用顺序为：建立 30/30 的上下布局、添加标题、委托子类填充内容区、注册控件、重新定位。
     * 屏幕尺寸变化时本方法会被重复调用；外层 Screen 在调用前已清空控件列表，子类无需自行去重。
     */
    @Override
    protected void init() {
        this.layout = new HeaderAndFooterLayout(this, 30, 30);
        this.layout.addTitleHeader(this.title, this.font);
        this.initContent();
        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
    }

    /**
     * 填充中间内容区，由子类实现。
     *
     * 契约：实现方必须把控件挂到 this.layout 的 contents 上，使其参与布局与重新定位；
     * 执行时 this.font 与 this.minecraft 均已就绪，可安全访问。
     */
    protected abstract void initContent();

    /**
     * 重新排布控件，契约见 Screen#repositionElements。
     *
     * 屏幕尺寸变化时由外层调用，转交布局管理器统一排布。
     */
    @Override
    protected void repositionElements() {
        this.layout.arrangeElements();
    }

    /**
     * 渲染屏幕，契约见 Screen#render。
     *
     * 当前实现只转发给父类、未附加任何绘制；子类需要额外绘制时应重写本方法，
     * 并在首行调用 super.render 以保证控件正常绘制。
     */
    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    /**
     * 关闭屏幕，契约见 Screen#onClose。
     *
     * 返回构造时传入的父屏幕；父屏幕为 null 时会把当前屏幕置空，退回游戏界面。
     */
    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}

