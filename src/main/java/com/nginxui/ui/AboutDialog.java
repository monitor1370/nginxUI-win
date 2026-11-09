package com.nginxui.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.InputStream;
import java.util.Properties;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JTextField;
import javax.swing.KeyStroke;

/**
 * 关于窗口: 版本、运行环境、组件与目录信息.
 * 参考主流桌面工具的关于页: 产品标识 + 可复制的版本信息 + 组件清单 + 版权说明.
 */
public class AboutDialog extends JDialog {

	private static final long serialVersionUID = 1L;

	private final String home;

	public AboutDialog(MainFrame owner) {
		super(owner, "nginxUI - 关于", true);
		this.home = owner == null ? System.getProperty("user.dir") : owner.appHome();
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);

		JPanel content = new JPanel(new BorderLayout(0, Theme.GAP_M));
		content.setBackground(Theme.SURFACE);
		content.setBorder(BorderFactory.createEmptyBorder(
				Theme.GAP_L, Theme.GAP_L, Theme.GAP_M, Theme.GAP_L));
		content.add(buildHeader(), BorderLayout.NORTH);
		content.add(buildInfo(), BorderLayout.CENTER);
		content.add(buildFooter(), BorderLayout.SOUTH);
		setContentPane(content);

		Theme.applyWindowIcon(this);
		pack();
		setResizable(false);
		setLocationRelativeTo(owner);
	}

	// ==================== 头部: 图标 + 名称 + 版本 ====================

	private JPanel buildHeader() {
		JPanel header = new JPanel(new BorderLayout(Theme.GAP_M, 0));
		header.setOpaque(false);

		JLabel icon = new JLabel();
		ImageIcon img = loadIcon(56);
		if (img != null) {
			icon.setIcon(img);
		}
		icon.setVerticalAlignment(JLabel.TOP);

		JPanel text = new JPanel();
		text.setLayout(new javax.swing.BoxLayout(text, javax.swing.BoxLayout.Y_AXIS));
		text.setOpaque(false);
		JLabel title = Theme.label("nginxUI", Theme.TITLE.deriveFont(20f), Theme.TEXT);
		JLabel sub = Theme.label("Nginx 本地管理 · Windows x64 桌面版", Theme.BODY, Theme.TEXT_MUTED);
		JLabel ver = Theme.label("版本 " + appVersion(), Theme.SMALL, Theme.TEXT_MUTED);
		sub.setBorder(BorderFactory.createEmptyBorder(4, 0, 2, 0));
		text.add(title);
		text.add(sub);
		text.add(ver);

		header.add(icon, BorderLayout.WEST);
		header.add(text, BorderLayout.CENTER);
		return header;
	}

	private static ImageIcon loadIcon(int size) {
		try (InputStream in = AboutDialog.class.getResourceAsStream("/favicon.png")) {
			if (in == null) {
				return null;
			}
			Image img = ImageIO.read(in);
			if (img == null) {
				return null;
			}
			return new ImageIcon(img.getScaledInstance(size, size, Image.SCALE_SMOOTH));
		} catch (Exception ignored) {
			return null;
		}
	}

	// ==================== 中部: 环境与组件 ====================

	private JPanel buildInfo() {
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setOpaque(false);
		panel.setBorder(BorderFactory.createEmptyBorder(Theme.GAP_S, 0, 0, 0));

		GridBagConstraints gc = new GridBagConstraints();
		gc.gridy = 0;
		gc.insets = new Insets(4, 0, 4, Theme.GAP_M);
		gc.anchor = GridBagConstraints.NORTHWEST;
		gc.fill = GridBagConstraints.NONE;

		addRow(panel, gc, "运行环境", javaRuntime());
		addRow(panel, gc, "操作系统", System.getProperty("os.name") + " "
				+ System.getProperty("os.version") + " / " + System.getProperty("os.arch"));
		addRow(panel, gc, "程序目录", home);
		addRow(panel, gc, "数据文件", home + File.separator + "instances.json");

		// ---- Git 信息 (构建时注入, 开发态显示 N/A) ----
		GitInfo git = GitInfo.load();
		if (git.available()) {
			addRow(panel, gc, "仓库", git.url);
			addRow(panel, gc, "提交", git.commitShort + " (" + git.branch + ")");
			addRow(panel, gc, "构建", git.buildTime);
		} else {
			addRow(panel, gc, "仓库", "(开发态, 未构建)");
		}

		addRow(panel, gc, "第三方组件",
				"FlatLaf 3.5.4 · Hutool 5.8.34 · OSHI 6.4.2 · SLF4J 1.7.36 · Logback 1.2.13");

		// 版权与商标说明
		JSeparator sep = new JSeparator();
		gc.gridx = 0;
		gc.gridwidth = 2;
		gc.weightx = 1;
		gc.fill = GridBagConstraints.HORIZONTAL;
		gc.insets = new Insets(Theme.GAP_M, 0, 2, 0);
		panel.add(sep, gc);
		gc.gridy++;
		gc.weighty = 1;
		gc.fill = GridBagConstraints.BOTH;

		JLabel note = Theme.label("本工具为 nginx 的本地图形化外壳，不包含 nginx 程序本体；"
				+ "nginx 名称归其权利人所有，本工具与其无隶属关系。", Theme.SMALL, Theme.TEXT_MUTED);
		panel.add(note, gc);
		return panel;
	}

	/** 一行: 左标签 + 右值(目录类用不可编辑文本框, 便于选中复制) */
	private void addRow(JPanel panel, GridBagConstraints gc, String label, String value) {
		gc.gridx = 0;
		gc.gridwidth = 1;
		gc.weightx = 0;
		gc.fill = GridBagConstraints.NONE;
		gc.insets = new Insets(4, 0, 4, Theme.GAP_M);
		JLabel l = Theme.label(label, Theme.BODY, Theme.TEXT_MUTED);
		l.setPreferredSize(new Dimension(72, 20));
		panel.add(l, gc);

		gc.gridx = 1;
		gc.weightx = 1;
		gc.fill = GridBagConstraints.HORIZONTAL;
		gc.insets = new Insets(4, 0, 4, 0);
		JTextField tf = new JTextField(value);
		tf.setEditable(false);
		tf.setFont(Theme.BODY);
		tf.setForeground(Theme.TEXT);
		tf.setOpaque(false);
		tf.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
		tf.setColumns(44);
		tf.setCaretPosition(0);
		tf.setToolTipText(value);
		panel.add(tf, gc);

		gc.gridy++;
	}

	private static String javaRuntime() {
		return "Java " + System.getProperty("java.version")
				+ " (" + System.getProperty("java.vendor") + ")";
	}

	// ==================== 底部: 动作按钮 ====================

	private JPanel buildFooter() {
		JPanel footer = new JPanel(new BorderLayout());
		footer.setOpaque(false);
		footer.setBorder(BorderFactory.createEmptyBorder(Theme.GAP_M, 0, 0, 0));

		JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.GAP_S, 0));
		right.setOpaque(false);

		JButton btnCopy = new JButton("复制版本信息");
		btnCopy.setFont(Theme.BODY);
		btnCopy.addActionListener(e -> copySummary());

		JButton btnDir = new JButton("打开程序目录");
		btnDir.setFont(Theme.BODY);
		btnDir.addActionListener(e -> openHome());

		JButton btnClose = new JButton("关闭");
		btnClose.setFont(Theme.BODY);
		btnClose.addActionListener(e -> dispose());
		getRootPane().setDefaultButton(btnClose);
		// Esc 与关闭按钮等价: 关于窗口无未保存内容, 直接关闭
		getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
				.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close-dialog");
		getRootPane().getActionMap().put("close-dialog", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent e) {
				dispose();
			}
		});

		right.add(btnCopy);
		right.add(btnDir);
		right.add(btnClose);
		footer.add(right, BorderLayout.EAST);
		return footer;
	}

	private void copySummary() {
		StringBuilder sb = new StringBuilder();
		sb.append("nginxUI ").append(appVersion()).append(" - Nginx 本地管理 (Windows x64)\n");
		sb.append("运行环境: ").append(javaRuntime()).append('\n');
		sb.append("操作系统: ").append(System.getProperty("os.name")).append(' ')
				.append(System.getProperty("os.version")).append(" / ")
				.append(System.getProperty("os.arch")).append('\n');
		sb.append("程序目录: ").append(home).append('\n');
		GitInfo git = GitInfo.load();
		if (git.available()) {
			sb.append("仓库: ").append(git.url).append('\n');
			sb.append("提交: ").append(git.commitShort).append(" (").append(git.branch).append(")\n");
			sb.append("构建: ").append(git.buildTime).append('\n');
		}
		try {
			Toolkit.getDefaultToolkit().getSystemClipboard()
					.setContents(new StringSelection(sb.toString()), null);
			JOptionPane.showMessageDialog(this, "版本信息已复制到剪贴板。",
					"nginxUI - 关于", JOptionPane.INFORMATION_MESSAGE);
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(this,
					com.nginxui.utils.ErrorReporter.uiText("复制内容", ex, "请手动选择文本后复制。"),
					"nginxUI", JOptionPane.ERROR_MESSAGE);
		}
	}

	private void openHome() {
		try {
			java.awt.Desktop.getDesktop().open(new File(home));
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(this,
					com.nginxui.utils.ErrorReporter.uiText("打开目录", ex,
							"请确认目录存在且当前账户有访问权限。"),
					"nginxUI", JOptionPane.ERROR_MESSAGE);
		}
	}

	/** 版本号: 读取打包时写入的 pom.properties, 开发态回退到 1.1.0 */
	private static String appVersion() {
		try (InputStream in = AboutDialog.class.getResourceAsStream(
				"/META-INF/maven/com.nginxui/nginxui-win/pom.properties")) {
			if (in != null) {
				Properties p = new Properties();
				p.load(in);
				String v = p.getProperty("version");
				if (v != null && !v.trim().isEmpty()) {
					return v.trim();
				}
			}
		} catch (Exception ignored) {
		}
		return "1.1.0";
	}

	// ==================== Git 信息 (构建时注入) ====================

	/**
	 * 从 /git-info.properties 读取 Git 元信息.
	 * 构建脚本 (build.ps1) 在 maven package 前生成此文件.
	 * 开发态 (IDE 直接运行) 不存在此文件, available() 返回 false.
	 */
	static class GitInfo {
		final String url;
		final String commit;
		final String commitShort;
		final String branch;
		final String buildTime;
		final boolean present;

		private GitInfo(Properties p, boolean present) {
			this.present = present;
			this.url = p.getProperty("url", "");
			this.commit = p.getProperty("commit", "");
			this.commitShort = p.getProperty("commit.short", "");
			this.branch = p.getProperty("branch", "");
			this.buildTime = p.getProperty("build.time", "");
		}

		boolean available() {
			return present && !commit.isEmpty();
		}

		static GitInfo load() {
			try (InputStream in = AboutDialog.class.getResourceAsStream("/git-info.properties")) {
				if (in != null) {
					Properties p = new Properties();
					p.load(in);
					return new GitInfo(p, true);
				}
			} catch (Exception ignored) {
			}
			return new GitInfo(new Properties(), false);
		}
	}
}
