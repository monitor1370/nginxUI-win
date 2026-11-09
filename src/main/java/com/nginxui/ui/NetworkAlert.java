package com.nginxui.ui;

import java.awt.BorderLayout;
import java.awt.Font;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 网络异常提示条: 在线安装/升级预检失败时, 在页面顶部醒目展示原因与操作建议,
 * 并提供「切换到离线方式」按钮.
 */
public class NetworkAlert extends JPanel {

	private final JLabel titleLabel;
	private final JLabel hintLabel;

	public NetworkAlert(String actionText, Runnable action) {
		super(new BorderLayout(12, 0));
		setOpaque(true);
		setBackground(Theme.WARNING_BG);
		setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(Theme.WARNING_BORDER),
				BorderFactory.createEmptyBorder(8, 12, 8, 10)));

		JLabel badge = Theme.label("网络", Theme.SMALL.deriveFont(Font.BOLD), Theme.WARNING);
		badge.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(Theme.WARNING_BORDER),
				BorderFactory.createEmptyBorder(1, 6, 1, 6)));

		titleLabel = Theme.label("", Theme.BODY_BOLD, Theme.WARNING);
		hintLabel = Theme.label("", Theme.SMALL, Theme.TEXT_MUTED);

		JPanel text = new JPanel(new BorderLayout(0, 2));
		text.setOpaque(false);
		text.add(titleLabel, BorderLayout.NORTH);
		text.add(hintLabel, BorderLayout.CENTER);

		JPanel left = new JPanel(new BorderLayout(8, 0));
		left.setOpaque(false);
		left.add(badge, BorderLayout.WEST);
		left.add(text, BorderLayout.CENTER);

		JButton go = Theme.secondaryButton(actionText);
		go.addActionListener(e -> action.run());

		add(left, BorderLayout.CENTER);
		add(go, BorderLayout.EAST);
		setVisible(false);
	}

	/** 展示异常原因与操作建议 */
	public void showAlert(String title, String hint) {
		titleLabel.setText(title);
		hintLabel.setText(hint);
		setVisible(true);
		revalidate();
	}

	public void hideAlert() {
		if (isVisible()) {
			setVisible(false);
			revalidate();
		}
	}
}
