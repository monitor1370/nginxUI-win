package com.nginxui.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;

/**
 * 两段式行布局: 左侧信息与右侧操作能同排时左右对齐;
 * 界面缩放或窗口过窄放不下时自动折成两行(操作行右对齐), 避免控件互相覆盖。
 * 只识别前两个子组件: 第一个为左侧信息, 第二个为右侧操作。
 */
final class WrapRowLayout implements LayoutManager {

	private final int gap;
	private final int rowGap;

	WrapRowLayout(int gap, int rowGap) {
		this.gap = gap;
		this.rowGap = rowGap;
	}

	@Override
	public void addLayoutComponent(String name, Component comp) {
	}

	@Override
	public void removeLayoutComponent(Component comp) {
	}

	@Override
	public Dimension preferredLayoutSize(Container parent) {
		Insets in = parent.getInsets();
		int avail = parent.getWidth() - in.left - in.right;
		Dimension l = parent.getComponent(0).getPreferredSize();
		Dimension a = parent.getComponent(1).getPreferredSize();
		boolean single = avail <= 0 || l.width + gap + a.width <= avail;
		int w = single ? l.width + gap + a.width : Math.max(l.width, a.width);
		int h = single ? Math.max(l.height, a.height) : l.height + rowGap + a.height;
		return new Dimension(w + in.left + in.right, h + in.top + in.bottom);
	}

	@Override
	public Dimension minimumLayoutSize(Container parent) {
		return preferredLayoutSize(parent);
	}

	@Override
	public void layoutContainer(Container parent) {
		Insets in = parent.getInsets();
		int avail = parent.getWidth() - in.left - in.right;
		// 首次布局时容器宽度可能仍为 0: 此时不摆放, 否则右侧操作会被放到负坐标并一直不可见
		if (avail <= 0) {
			return;
		}
		Component left = parent.getComponent(0);
		Component actions = parent.getComponent(1);
		Dimension l = left.getPreferredSize();
		Dimension a = actions.getPreferredSize();
		if (l.width + gap + a.width <= avail) {
			left.setBounds(in.left, in.top, l.width, l.height);
			actions.setBounds(Math.max(in.left, in.left + avail - a.width), in.top, a.width, a.height);
		} else {
			left.setBounds(in.left, in.top, avail, l.height);
			actions.setBounds(Math.max(in.left, in.left + avail - a.width),
					in.top + l.height + rowGap, a.width, a.height);
		}
	}
}
