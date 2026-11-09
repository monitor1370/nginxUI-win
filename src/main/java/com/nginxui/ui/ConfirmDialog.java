package com.nginxui.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;

/**
 * 危险操作确认框: 统一保证「对象 + 后果 + 是否可撤销」三要素齐全,
 * 默认焦点与回车动作都落在「取消」, Esc 等同取消, 避免误触造成不可逆操作.
 */
public final class ConfirmDialog {

	/** 文本列宽: 保证长路径也能在框内换行, 不被横向截断 */
	private static final int MESSAGE_COLUMNS = 34;
	/** 文本区像素宽度: 按字体度量提前折行, 让 pack() 得到正确高度 */
	private static final int MESSAGE_WIDTH_PX = 380;

	private ConfirmDialog() {
	}

	/**
	 * 弹出危险操作确认.
	 *
	 * @param parent        父组件, 用于定位对话框(可为 null, 居中显示)
	 * @param title         标题, 自动加 "nginxUI - " 前缀
	 * @param object        对象: 对哪个实例/证书/文件执行操作
	 * @param consequence   后果: 执行后会发生什么
	 * @param reversibility 是否可撤销: 明确写出能否撤销及恢复方式
	 * @param confirmText   确认按钮文案(如「停止」「替换证书」), 不用「确定」
	 * @return true 表示用户确认执行
	 */
	public static boolean confirm(Component parent, String title, String object,
			String consequence, String reversibility, String confirmText) {
		Window owner = parent instanceof Window ? (Window) parent
				: SwingUtilities.getWindowAncestor(parent);
		final JDialog dialog = new JDialog(owner, "nginxUI - " + title,
				Dialog.ModalityType.APPLICATION_MODAL);
		dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

		final boolean[] confirmed = { false };

		JPanel body = new JPanel(new BorderLayout(Theme.GAP_M, 0));
		body.setBorder(BorderFactory.createEmptyBorder(
				Theme.GAP_L, Theme.GAP_L, Theme.GAP_M, Theme.GAP_L));

		Icon icon = UIManager.getIcon("OptionPane.warningIcon");
		if (icon != null) {
			JLabel iconLabel = new JLabel(icon);
			iconLabel.setVerticalAlignment(SwingConstants.TOP);
			body.add(iconLabel, BorderLayout.WEST);
		}

		JPanel rows = new JPanel(new java.awt.GridBagLayout());
		rows.setOpaque(false);
		java.awt.GridBagConstraints gc = new java.awt.GridBagConstraints();
		gc.gridx = 0;
		gc.gridy = 0;
		gc.anchor = java.awt.GridBagConstraints.NORTHWEST;
		gc.insets = new Insets(0, 0, Theme.GAP_S, Theme.GAP_M);
		addRow(rows, gc, "对象", object);
		addRow(rows, gc, "后果", consequence);
		addRow(rows, gc, "是否可撤销", reversibility);
		body.add(rows, BorderLayout.CENTER);

		JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.GAP_S, 0));
		footer.setBorder(BorderFactory.createEmptyBorder(
				0, Theme.GAP_L, Theme.GAP_M, Theme.GAP_L));

		JButton btnConfirm = new JButton(
				confirmText == null || confirmText.isEmpty() ? "继续" : confirmText);
		btnConfirm.setFont(Theme.BODY);
		btnConfirm.addActionListener(e -> {
			confirmed[0] = true;
			dialog.dispose();
		});

		JButton btnCancel = new JButton("取消");
		btnCancel.setFont(Theme.BODY);
		btnCancel.addActionListener(e -> dialog.dispose());

		// 确认动作在左、取消在右, 与 Windows 对话框习惯一致
		footer.add(btnConfirm);
		footer.add(btnCancel);

		dialog.getContentPane().setLayout(new BorderLayout());
		dialog.getContentPane().add(body, BorderLayout.CENTER);
		dialog.getContentPane().add(footer, BorderLayout.SOUTH);

		// 安全默认值: 回车等同取消, 初始焦点也在取消上
		dialog.getRootPane().setDefaultButton(btnCancel);
		bindEscapeToCancel(dialog);

		Theme.applyWindowIcon(dialog);
		dialog.pack();
		dialog.setResizable(false);
		dialog.setLocationRelativeTo(parent);
		SwingUtilities.invokeLater(btnCancel::requestFocusInWindow);
		dialog.setVisible(true);
		return confirmed[0];
	}

	private static void addRow(JPanel panel, java.awt.GridBagConstraints gc,
			String label, String text) {
		JLabel l = Theme.label(label, Theme.BODY_BOLD, Theme.TEXT_MUTED);
		gc.gridx = 0;
		gc.weightx = 0;
		gc.fill = java.awt.GridBagConstraints.NONE;
		panel.add(l, gc);

		JTextArea area = messageArea(text);
		gc.gridx = 1;
		gc.weightx = 1;
		gc.fill = java.awt.GridBagConstraints.HORIZONTAL;
		panel.add(area, gc);

		gc.gridy++;
		gc.gridx = 0;
		gc.weightx = 0;
	}

	/**
	 * 只读文本区: 按字体度量提前折行并显式设置行数.
	 * 直接用 setLineWrap 时 pack() 只按单行计算高度, 多行内容会被裁掉.
	 */
	private static JTextArea messageArea(String text) {
		JTextArea area = new JTextArea();
		area.setFont(Theme.BODY);
		area.setForeground(Theme.TEXT);
		area.setEditable(false);
		area.setFocusable(false);
		area.setOpaque(false);
		area.setLineWrap(false);
		area.setColumns(MESSAGE_COLUMNS);
		area.setBorder(BorderFactory.createEmptyBorder());

		java.util.List<String> lines = wrap(text == null ? "" : text,
				area.getFontMetrics(Theme.BODY));
		area.setText(String.join("\n", lines));
		area.setRows(Math.max(1, lines.size()));
		return area;
	}

	/** 按像素宽度逐字符折行, 保留原始换行(长路径等无空格文本也能折行) */
	private static java.util.List<String> wrap(String text, java.awt.FontMetrics fm) {
		java.util.List<String> lines = new java.util.ArrayList<String>();
		for (String raw : text.split("\n", -1)) {
			StringBuilder line = new StringBuilder();
			int width = 0;
			for (int i = 0; i < raw.length(); i++) {
				char ch = raw.charAt(i);
				int charWidth = fm.charWidth(ch);
				if (width + charWidth > MESSAGE_WIDTH_PX && line.length() > 0) {
					lines.add(line.toString());
					line.setLength(0);
					width = 0;
				}
				line.append(ch);
				width += charWidth;
			}
			lines.add(line.toString());
		}
		return lines;
	}

	/** Esc 与「取消」等价: 关闭对话框且不执行任何操作 */
	private static void bindEscapeToCancel(final JDialog dialog) {
		dialog.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
				.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel-confirm");
		dialog.getRootPane().getActionMap().put("cancel-confirm", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent e) {
				dialog.dispose();
			}
		});
	}
}
