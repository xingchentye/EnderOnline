/*
 * 本文件属于 EnderOnline NeoForge 适配层。
 *
 * 职责：本模组所有屏幕的抽象基类，统一头部/底部布局与返回父屏幕的行为。
 *
 * 关键约束：子类只实现 initContent()，不得在 init() 中自行创建布局；关闭一律回到 parent。
 */
package com.multiplayer.ender.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

/**
 * 模组屏幕基类。
 *
 * 把「标题栏 + 内容区 + 底部按钮区」这套重复结构收敛到一处：{@link #init()} 建好
 * {@link HeaderAndFooterLayout}，再交给子类的 {@link #initContent()} 往内容区/底栏挂组件。
 *
 * 设计约束：
 * 1. 子类不得覆盖 {@link #init()}，只能实现 {@link #initContent()}，否则布局会失去一致性。
 * 2. {@link #layout} 在 {@link #init()} 之前为 null，子类只能在 {@code initContent} 及其后续阶段使用。
 * 3. 所有屏幕必须支持分辨率变化：{@link #repositionElements()} 会把布局重排，子类不要在这里写死坐标（触控命中区见 ADR-09/10）。
 *
 * 线程安全性：屏幕对象只在客户端主线程创建与渲染，{@code parent} 与 {@code layout} 均按该前提使用，无额外同步。
 *
 * @since 1.0
 */
public abstract class EnderBaseScreen extends Screen {
    /** 父屏幕，用于返回；允许为 null，为 null 时关闭后回到原版默认界面。 */
    protected final Screen parent;

    /** 头部与底部布局管理器；在 {@link #init()} 中创建，此前为 null。 */
    protected HeaderAndFooterLayout layout;

    /**
     * 构造屏幕基类。
     *
     * @param title 屏幕标题，不能为 null，会显示在头部
     * @param parent 父屏幕，允许为 null，关闭时返回该屏幕
     */
    protected EnderBaseScreen(Component title, Screen parent) {
        super(title);
        this.parent = parent;
    }

    /**
     * 初始化屏幕。
     *
     * 建立布局、添加标题、调用 {@link #initContent()}，最后把布局里的组件注册为可渲染控件并重排。
     * 每次窗口尺寸变化或状态刷新都会重新走一遍，因此 {@link #initContent()} 必须可重复执行且不叠加组件。
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
     * 初始化具体内容。
     *
     * 子类在此向 {@link #layout} 添加内容组件与底部按钮。
     *
     * 实现约束：本方法会被重复调用，实现必须幂等——只往布局里加元素，不要直接 addRenderableWidget。
     */
    protected abstract void initContent();

    /**
     * 重新排列元素。
     *
     * 屏幕尺寸变化时由原版调用，这里把布局重排一遍；子类若覆盖必须调用 super。
     */
    @Override
    protected void repositionElements() {
        this.layout.arrangeElements();
    }

    /**
     * 渲染屏幕，透传给原版实现。
     *
     * @param guiGraphics 绘图上下文，不能为 null
     * @param mouseX 鼠标 X 坐标，单位为逻辑像素
     * @param mouseY 鼠标 Y 坐标，单位为逻辑像素
     * @param partialTick 当前帧的部分刻进度，取值 0.0..1.0
     */
    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    /**
     * 关闭屏幕并返回父屏幕。
     *
     * 父屏幕为 null 时由原版回落到默认界面。子类若需在返回前断连，必须先完成清理再调用 super。
     */
    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}

