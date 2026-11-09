package com.nginxui.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Rectangle;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.ScrollPaneConstants;
import javax.swing.Scrollable;

/**
 * 内容区页面标题行: 与「实例总览」页保持一致的排版(20px 加粗标题 + 右侧操作区).
 * 版本升级 / 安装实例 / 证书管理三个页面共用, 替代各自的弹窗标题栏.
 */
public class PageHeader extends JPanel {

	private final JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.GAP_S, 0));
	private final JLabel titleLabel;
	private final JLabel subtitleLabel;

	public PageHeader(String title, String subtitle) {
		super(new BorderLayout(Theme.GAP_L, 0));
		setOpaque(false);

		JPanel text = new JPanel(new BorderLayout(0, 2));
		text.setOpaque(false);
		titleLabel = Theme.label(title, Theme.TITLE.deriveFont(Font.BOLD, 20f), Theme.TEXT);
		text.add(titleLabel, BorderLayout.NORTH);
		subtitleLabel = Theme.label(subtitle == null ? " " : subtitle, Theme.SMALL, Theme.TEXT_MUTED);
		text.add(subtitleLabel, BorderLayout.SOUTH);
		add(text, BorderLayout.WEST);

		right.setOpaque(false);
		add(right, BorderLayout.EAST);
	}

	public void addAction(JComponent c) {
		right.add(c);
	}

	public void setTitle(String title) {
		titleLabel.setText(title == null ? " " : title);
	}

	public void setSubtitle(String subtitle) {
		subtitleLabel.setText(subtitle == null ? " " : subtitle);
	}

	/**
	 * 把页面主体包进纵向滚动区: 窗口偏矮时滚动查看, 不会裁掉底部内容.
	 * 宽度跟随视口, 避免出现横向滚动条.
	 */
	public static JScrollPane scroll(JComponent content) {
		JScrollPane sp = new JScrollPane(new ScrollBody(content));
		sp.setBorder(BorderFactory.createEmptyBorder());
		sp.setOpaque(false);
		sp.getViewport().setOpaque(false);
		sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		sp.getVerticalScrollBar().setUnitIncrement(24);
		return sp;
	}

	private static final class ScrollBody extends JPanel implements Scrollable {

		ScrollBody(JComponent inner) {
			super(new BorderLayout());
			setOpaque(false);
			add(inner, BorderLayout.CENTER);
		}

		@Override
		public Dimension getPreferredScrollableViewportSize() {
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
			return 24;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
			return 160;
		}

		@Override
		public boolean getScrollableTracksViewportWidth() {
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight() {
			// 内容不足一屏时铺满视口, 避免背景割裂
			return getParent() instanceof JViewport
					&& getParent().getHeight() > getPreferredSize().height;
		}
	}
}
