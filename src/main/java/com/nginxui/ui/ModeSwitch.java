package com.nginxui.ui;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.function.IntConsumer;

import javax.swing.JPanel;
import javax.swing.JToggleButton;

/**
 * 顶部分段切换按钮组(互斥): 用于在线/离线安装、升级方式切换.
 * 外观与系统按钮规范一致: 白底描边胶囊 + 选中段主色填充.
 */
public class ModeSwitch extends JPanel {

	private static final int HEIGHT = 30;
	private static final int GAP = 3;
	/** 分段按钮高度: 胶囊内边距 3px 上下各留 3px */
	private static final int SEG_HEIGHT = 24;
	/** 胶囊描边: 比通用边框更深, 提升控件在页面中的辨识度 */
	private static final Color OUTLINE = new Color(0xC3CBD8);
	/** 未选中段底色 / 悬停底色: 与选中段的实心主色形成强对比 */
	private static final Color IDLE_BG = new Color(0xEDF0F4);
	private static final Color IDLE_BG_HOVER = new Color(0xDDE3EB);

	private final Seg[] segments;
	private final IntConsumer onChange;
	private boolean notifying;
	private int selected;

	public ModeSwitch(String[] labels, IntConsumer onChange) {
		super(new FlowLayout(FlowLayout.LEFT, 0, 0));
		this.onChange = onChange;
		setOpaque(false);
		setBorder(javax.swing.BorderFactory.createEmptyBorder(GAP, GAP, GAP, GAP));
		segments = new Seg[labels.length];
		for (int i = 0; i < labels.length; i++) {
			segments[i] = new Seg(labels[i], i);
			add(segments[i]);
		}
		select(0, false);
	}

	/** 当前选中下标(0 = 第一个选项) */
	public int getSelectedIndex() {
		return selected;
	}

	/** 外部切换(会触发回调) */
	public void setSelected(int index) {
		select(index, true);
	}

	private void select(int index, boolean notify) {
		if (index < 0 || index >= segments.length) {
			return;
		}
		int old = selected;
		selected = index;
		notifying = !notify;
		try {
			for (int i = 0; i < segments.length; i++) {
				segments[i].setSelected(i == index);
			}
		} finally {
			notifying = false;
		}
		updateColors();
		if (notify && old != index && onChange != null) {
			onChange.accept(index);
		}
	}

	private void updateColors() {
		for (Seg seg : segments) {
			seg.setForeground(seg.isSelected() ? Color.WHITE : Theme.TEXT);
			seg.repaint();
		}
	}

	@Override
	protected void paintComponent(Graphics g0) {
		Graphics2D g = (Graphics2D) g0.create();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(Theme.SURFACE);
			g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
			g.setColor(OUTLINE);
			g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
		} finally {
			g.dispose();
		}
	}

	@Override
	public Dimension getPreferredSize() {
		int width = GAP * 2;
		for (Seg seg : segments) {
			width += seg.getPreferredSize().width;
		}
		return new Dimension(width, HEIGHT);
	}

	/** 单个分段: 选中实心主色+白字, 未选中浅灰底+深色字(强对比) */
	private final class Seg extends JToggleButton {

		private final int index;

		Seg(String text, int index) {
			super(text);
			this.index = index;
			setFont(Theme.BODY_BOLD);
			setFocusable(false);
			setFocusPainted(false);
			setBorderPainted(false);
			setContentAreaFilled(false);
			setOpaque(false);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			setPreferredSize(new Dimension(96, SEG_HEIGHT));
			addActionListener(e -> {
				if (!notifying) {
					select(index, true);
				}
			});
		}

		@Override
		public Dimension getPreferredSize() {
			Dimension d = super.getPreferredSize();
			return new Dimension(d.width + 2, SEG_HEIGHT);
		}

		@Override
		protected void paintComponent(Graphics g0) {
			Graphics2D g = (Graphics2D) g0.create();
			try {
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				if (isSelected()) {
					g.setColor(Theme.PRIMARY);
					g.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
				} else {
					g.setColor(getModel().isRollover() ? IDLE_BG_HOVER : IDLE_BG);
					g.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
				}
			} finally {
				g.dispose();
			}
			super.paintComponent(g0);
		}
	}
}
