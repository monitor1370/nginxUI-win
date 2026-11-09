package com.nginxui.ui;

import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

import cn.hutool.core.util.StrUtil;

/** GridBag 式键值对表单: 左侧灰标签, 右侧只读值. 供 Nginx 状态页复用. */
class FieldGrid extends JPanel {

	private final Insets labelInset = new Insets(3, 6, 3, 12);
	private final Insets valueInset = new Insets(3, 6, 3, 6);
	private int row;

	FieldGrid() {
		super(new GridBagLayout());
		setOpaque(false);
	}

	void clear() {
		removeAll();
		row = 0;
	}

	/** 分组标题行: 占满两列, 与上方内容留出间距 */
	void addSection(String title) {
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.gridy = row;
		gbc.gridx = 0;
		gbc.gridwidth = 2;
		gbc.insets = new Insets(row == 0 ? 2 : 12, 6, 2, 6);
		gbc.anchor = GridBagConstraints.WEST;
		gbc.fill = GridBagConstraints.HORIZONTAL;

		JLabel l = new JLabel(title);
		l.setFont(Theme.BODY_BOLD);
		l.setForeground(Theme.TEXT_MUTED);
		add(l, gbc);
		row++;
	}

	void add(String label, String value) {
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.gridy = row;
		gbc.insets = labelInset;
		gbc.anchor = GridBagConstraints.WEST;
		gbc.weightx = 0;

		JLabel l = new JLabel(label);
		l.setForeground(Theme.TEXT_MUTED);
		l.setFont(Theme.BODY);
		add(l, gbc);

		gbc = new GridBagConstraints();
		gbc.gridy = row;
		gbc.gridx = 1;
		gbc.insets = valueInset;
		gbc.anchor = GridBagConstraints.WEST;
		gbc.weightx = 1;
		gbc.fill = GridBagConstraints.HORIZONTAL;

		JTextField tf = new JTextField(StrUtil.emptyToDefault(value, "—"));
		tf.setEditable(false);
		tf.setOpaque(false);
		tf.setBorder(null);
		tf.setForeground(Theme.TEXT);
		tf.setFont(Theme.BODY);
		tf.setPreferredSize(new Dimension(tf.getPreferredSize().width,
				tf.getPreferredSize().height + 2));
		add(tf, gbc);

		row++;
	}
}
