package com.nginxui.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.InputStream;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;
import javax.swing.UIManager;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

/**
 * 界面设计令牌与全局外观:
 * 颜色/字号/间距集中在此, 业务面板不再硬编码色值.
 * 采用 FlatLaf 的浅色主题, 保留完全离线(依赖打进 fat jar).
 */
public final class Theme {

	// ==================== 颜色 ====================

	public static final Color BG = new Color(0xF7F8FA);
	public static final Color SURFACE = Color.WHITE;
	public static final Color BORDER = new Color(0xE5E7EB);
	/** 表格行分隔线: 比卡片描边更浅, 仅用于对齐视线, 不形成"田字格" */
	public static final Color GRID_LINE = new Color(0xF1F3F5);
	public static final Color TEXT = new Color(0x111827);
	public static final Color TEXT_MUTED = new Color(0x6B7280);
	/** 主色: 选中/强调 */
	public static final Color PRIMARY = new Color(0x2563EB);
	public static final Color SUCCESS = new Color(0x16A34A);
	public static final Color WARNING = new Color(0xD97706);
	public static final Color DANGER = new Color(0xDC2626);

	/** 提示条底色/描边: 检测中(中性) / 在线可用(绿) / 不可用(琥珀) */
	public static final Color INFO_BG = new Color(0xEFF6FF);
	public static final Color SUCCESS_BG = new Color(0xECFDF5);
	public static final Color SUCCESS_BORDER = new Color(0xBBF7D0);
	public static final Color WARNING_BG = new Color(0xFFF7ED);
	public static final Color WARNING_BORDER = new Color(0xFDE68A);
	public static final Color DANGER_BG = new Color(0xFEF2F2);
	public static final Color DANGER_BORDER = new Color(0xFECACA);

	/** 状态基准色: 运行中绿 / 已停止红 */
	public static final Color STATUS_RUNNING = new Color(0x52C41A);
	public static final Color STATUS_STOPPED = new Color(0xFF4D4F);
	/**
	 * 暗色主题下的停止红: #FF4D4F 在 FlatLaf 暗底(#3C3F41)上仅 3.25:1, 提亮到该值后约 4.6:1.
	 * 运行绿 #52C41A 在暗底上约 4.6:1, 可直接使用, 保持指定色.
	 */
	public static final Color STATUS_STOPPED_ON_DARK = new Color(0xFF8A80);
	/**
	 * 亮色主题下的状态文字色: 同色系加深.
	 * 基准色在白色/浅底上对比度仅 2.27:1、3.27:1, 不满足 WCAG AA(4.5:1);
	 * 加深后白底为 7.9:1、5.6:1, 最深的选中运行行(#BFE9CE)上仍不低于 4.5:1.
	 */
	public static final Color STATUS_RUNNING_TEXT = new Color(0x1B5E20);
	public static final Color STATUS_STOPPED_TEXT = new Color(0xCF1322);
	public static final Color SELECTED_BG = new Color(0xE8F0FE);
	public static final Color HOVER_BG = new Color(0xF3F4F6);
	/** 侧栏父级分组底色: 浅灰底 + 加粗标题, 与其下缩进的子条目形成层级区分 */
	public static final Color GROUP_BG = new Color(0xEEF1F6);
	public static final Color GROUP_BG_HOVER = new Color(0xE3E8F0);
	/** 运行中实例的列表行底色(淡绿), 选中/悬停各加深一档 */
	public static final Color RUNNING_BG = new Color(0xE0F5E7);
	public static final Color RUNNING_BG_HOVER = new Color(0xD2EFDC);
	public static final Color RUNNING_BG_SELECTED = new Color(0xBFE9CE);

	// ==================== 字体 ====================

	private static final String UI_FAMILY = "Microsoft YaHei UI";

	public static final Font BODY = new Font(UI_FAMILY, Font.PLAIN, 13);
	public static final Font BODY_BOLD = new Font(UI_FAMILY, Font.BOLD, 13);
	public static final Font SMALL = new Font(UI_FAMILY, Font.PLAIN, 12);
	public static final Font TITLE = new Font(UI_FAMILY, Font.BOLD, 15);
	/** 等宽字体: 用 JDK 逻辑字体 Monospaced(带中文字形回退), 避免 Consolas 缺中文字形导致提示/日志显示为方块 */
	public static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 12);

	// ==================== 尺寸 ====================

	public static final int GAP_XS = 4;
	public static final int GAP_S = 8;
	public static final int GAP_M = 12;
	public static final int GAP_L = 16;
	public static final int BUTTON_HEIGHT = 28;
	public static final int LIST_ROW_HEIGHT = 48;

	private Theme() {
	}

	// ==================== 全局外观 ====================

	/** 安装 FlatLaf 浅色主题与统一控件参数(须在创建任何窗口之前调用) */
	public static void install() {
		// FlatLaf 在初始化时读取 defaultFont
		UIManager.put("defaultFont", BODY);
		// 显式启用窗口自绘装饰, 避免不同 FlatLaf 版本/系统默认值差异
		System.setProperty("flatlaf.useWindowDecorations", "true");
		FlatLightLaf.setup();

		// 标题栏由 FlatLaf 自绘: 字体/配色与应用一致, 窗口按钮不再与系统 caption 生硬拼接.
		// 必须在创建任何 JFrame/JDialog 之前调用, 因此放在 Theme.install() 中统一设置.
		JFrame.setDefaultLookAndFeelDecorated(true);
		JDialog.setDefaultLookAndFeelDecorated(true);

		// 焦点描边保留 1px: 键盘操作时需要看得见当前焦点, 0 会导致全站无焦点提示
		UIManager.put("Component.focusWidth", 1);
		UIManager.put("Component.innerFocusWidth", 0);
		UIManager.put("Button.arc", 8);
		UIManager.put("Component.arc", 8);
		UIManager.put("TextComponent.arc", 8);
		UIManager.put("CheckBox.arc", 4);
		UIManager.put("ProgressBar.arc", 8);
		UIManager.put("ScrollBar.width", 10);
		UIManager.put("ScrollBar.thumbArc", 999);
		UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
		UIManager.put("ScrollBar.trackArc", 999);
		UIManager.put("Panel.background", BG);
		UIManager.put("List.selectionBackground", SELECTED_BG);
		UIManager.put("List.selectionForeground", TEXT);
		UIManager.put("List.selectionInactiveBackground", SELECTED_BG);
		UIManager.put("List.selectionInactiveForeground", TEXT);
		UIManager.put("Table.selectionBackground", SELECTED_BG);
		UIManager.put("Table.selectionForeground", TEXT);
		UIManager.put("ToolTip.background", SURFACE);
		UIManager.put("ToolTip.border", BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(BORDER), BorderFactory.createEmptyBorder(4, 8, 4, 8)));
		// JSplitPane 分隔条: 显式加大把手宽度并允许内联拖拽反馈,
		// 避免窗口化时两侧 JScrollPane 把分隔条把手挤压到几乎不可点.
		UIManager.put("SplitPane.dividerSize", 8);
		UIManager.put("SplitPaneInlineBar.hoverColor", PRIMARY);
		UIManager.put("SplitPaneInlineBar.pressedColor", PRIMARY);
		UIManager.put("SplitPaneInlineBar.continuousLayout", true);
	}

	// ==================== 状态色 ====================

	/**
	 * 状态文字与圆点的唯一取色入口: 亮色主题用无障碍加深色, 暗色主题用基准色(停止红提亮).
	 * 文字与颜色必须由同一个状态布尔值调用本方法得到, 避免出现文字与颜色不匹配.
	 */
	public static Color statusColor(boolean running) {
		if (isDarkTheme()) {
			return running ? STATUS_RUNNING : STATUS_STOPPED_ON_DARK;
		}
		return running ? STATUS_RUNNING_TEXT : STATUS_STOPPED_TEXT;
	}

	/** 当前是否暗色主题(U3 深色模式接入后自动生效) */
	public static boolean isDarkTheme() {
		try {
			return FlatLaf.isLafDark();
		} catch (Throwable ignored) {
			Color bg = UIManager.getColor("Panel.background");
			return bg != null && luminance(bg) < 0.5;
		}
	}

	/** WCAG 相对亮度 */
	private static double luminance(Color c) {
		return 0.2126 * channel(c.getRed()) + 0.7152 * channel(c.getGreen())
				+ 0.0722 * channel(c.getBlue());
	}

	private static double channel(int v) {
		double c = v / 255.0;
		return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
	}

	/** 统一窗口/任务栏图标(资源打成 favicon.png, 不依赖外部文件) */
	public static void applyWindowIcon(Window window) {
		if (window == null) {
			return;
		}
		try (InputStream in = Theme.class.getResourceAsStream("/favicon.png")) {
			if (in == null) {
				return;
			}
			BufferedImage img = ImageIO.read(in);
			if (img != null) {
				window.setIconImages(java.util.Arrays.asList(
						img.getScaledInstance(16, 16, Image.SCALE_SMOOTH),
						img.getScaledInstance(32, 32, Image.SCALE_SMOOTH),
						img.getScaledInstance(48, 48, Image.SCALE_SMOOTH)));
			}
		} catch (Exception ignored) {
		}
	}

	// ==================== 禁用原因 ====================

	/** 客户端属性: 控件启用时的基础 tooltip, 禁用时暂存, 重新启用后还原 */
	private static final String BASE_TIP_PROP = "nginxui.baseToolTip";
	/** 客户端属性: 控件启用时的无障碍描述, 禁用时替换为不可用原因 */
	private static final String BASE_DESC_PROP = "nginxui.baseAccessibleDescription";

	/**
	 * 统一禁用入口: 禁用时必须说明"为什么不可用、怎样恢复可用".
	 * 原因同时写入 tooltip 与无障碍描述, 鼠标悬停与读屏都能得到解释;
	 * 重新启用时还原控件原本的 tooltip/描述, 不残留禁用文案.
	 * reason 为空(启用态)时等价于普通 setEnabled.
	 */
	public static void setEnabled(JComponent component, boolean enabled, String reason) {
		if (component == null) {
			return;
		}
		if (component.getClientProperty(BASE_TIP_PROP) == null) {
			String tip = component.getToolTipText();
			component.putClientProperty(BASE_TIP_PROP, tip == null ? "" : tip);
			String desc = component.getAccessibleContext() == null ? null
					: component.getAccessibleContext().getAccessibleDescription();
			component.putClientProperty(BASE_DESC_PROP, desc == null ? "" : desc);
		}
		component.setEnabled(enabled);
		boolean explain = !enabled && reason != null && !reason.isEmpty();
		String baseTip = (String) component.getClientProperty(BASE_TIP_PROP);
		String baseDesc = (String) component.getClientProperty(BASE_DESC_PROP);
		component.setToolTipText(explain ? reason : (baseTip == null || baseTip.isEmpty() ? null : baseTip));
		if (component.getAccessibleContext() != null) {
			String desc = explain ? reason : (baseDesc == null || baseDesc.isEmpty() ? null : baseDesc);
			component.getAccessibleContext().setAccessibleDescription(desc);
		}
	}

	// ==================== 控件工厂 ====================

	/**
	 * 工具栏按钮: 统一内边距/字重.
	 * 不固定尺寸: 跨屏 DPI 变化后字体度量会变, 固定宽度会把文字挤成省略号.
	 */
	public static JButton toolButton(String text) {
		JButton b = new JButton(text);
		b.setFont(BODY);
		b.setMargin(new Insets(4, 10, 4, 10));
		return b;
	}

	/** 紧凑按钮: 列表卡片内的从属操作(启停/别名/目录/忽略), 字号与内边距小一档 */
	public static JButton compactButton(String text) {
		JButton b = toolButton(text);
		b.setFont(SMALL);
		b.setMargin(new Insets(3, 8, 3, 8));
		return b;
	}

	/** 下拉菜单按钮: 文案 + 尾部小三角图标(不用 ▾ 字形, 避免字体缺字显示为方块) */
	public static JButton dropdownButton(String text) {
		JButton b = toolButton(text);
		b.setIcon(new ArrowIcon(TEXT_MUTED));
		b.setHorizontalTextPosition(SwingConstants.LEFT);
		b.setIconTextGap(6);
		return b;
	}

	/**
	 * 主操作按钮: 页面内唯一的核心动作入口.
	 * 增大点击区域(高 36) + 品牌色实底 + 轻阴影, 与普通次要按钮形成明确层级.
	 */
	public static JButton primaryButton(String text) {
		JButton b = new PrimaryButton(text);
		b.setFont(BODY_BOLD.deriveFont(14f));
		b.setBorder(BorderFactory.createEmptyBorder(8, 24, 11, 24));
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		return b;
	}

	/**
	 * 次要操作按钮: 白色底 + 主色描边/文字, 悬浮浅蓝、按下加深, 与主按钮同高同阴影.
	 * 用于「上一步」等需要明确可点击但不抢主操作焦点的动作.
	 */
	public static JButton secondaryButton(String text) {
		JButton b = new SecondaryButton(text);
		b.setFont(BODY_BOLD.deriveFont(14f));
		b.setBorder(BorderFactory.createEmptyBorder(8, 22, 11, 22));
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		return b;
	}

	/** 描边式按钮: 自绘圆角/描边/悬浮/按下/禁用状态 */
	private static final class SecondaryButton extends JButton {
		private static final int ARC = 10;
		private static final int SHADOW = 2;

		SecondaryButton(String text) {
			super(text);
			setOpaque(false);
			setContentAreaFilled(false);
			setBorderPainted(false);
			setFocusPainted(false);
			setRolloverEnabled(true);
		}

		@Override
		public void setEnabled(boolean enabled) {
			super.setEnabled(enabled);
			setCursor(Cursor.getPredefinedCursor(
					enabled ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
		}

		@Override
		public Dimension getPreferredSize() {
			Dimension d = super.getPreferredSize();
			return new Dimension(d.width, Math.max(36, d.height));
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
						RenderingHints.VALUE_ANTIALIAS_ON);
				int w = getWidth();
				int h = getHeight();
				int fillH = Math.max(1, h - SHADOW);
				boolean enabled = isEnabled();
				Color fill;
				Color border;
				Color fg;
				if (!enabled) {
					fill = new Color(0xF3F4F6);
					border = BORDER;
					fg = TEXT_MUTED;
				} else if (getModel().isPressed()) {
					fill = new Color(0xDBEAFE);
					border = new Color(0x1E40AF);
					fg = new Color(0x1E40AF);
				} else if (getModel().isRollover()) {
					fill = INFO_BG;
					border = new Color(0x1D4ED8);
					fg = new Color(0x1D4ED8);
				} else {
					fill = SURFACE;
					border = PRIMARY;
					fg = PRIMARY;
				}
				if (enabled) {
					g2.setColor(new Color(0, 0, 0, 20));
					g2.fillRoundRect(1, SHADOW, w - 2, fillH, ARC, ARC);
				}
				g2.setColor(fill);
				g2.fillRoundRect(0, 0, w - 1, fillH, ARC, ARC);

				g2.setStroke(new java.awt.BasicStroke(1.5f));
				g2.setColor(border);
				g2.drawRoundRect(0, 0, w - 2, Math.max(1, fillH - 1), ARC, ARC);

				g2.setFont(getFont());
				g2.setColor(fg);
				FontMetrics fm = g2.getFontMetrics();
				String text = getText();
				int tx = (w - fm.stringWidth(text)) / 2;
				int ty = (fillH - fm.getHeight()) / 2 + fm.getAscent();
				g2.drawString(text, Math.max(4, tx), ty);
				// 键盘焦点: 自绘按钮不依赖 LAF 的焦点绘制, 这里补一圈内描边
				if (isFocusOwner()) {
					g2.setColor(PRIMARY);
					g2.setStroke(new java.awt.BasicStroke(2f));
					g2.drawRoundRect(3, 3, Math.max(1, w - 7), Math.max(1, fillH - 7), ARC, ARC);
				}
			} finally {
				g2.dispose();
			}
		}
	}

	/** 品牌色实底按钮: 自绘圆角/阴影/文字色, 不依赖 FlatLaf 的按钮状态绘制 */
	private static final class PrimaryButton extends JButton {
		private static final int ARC = 10;
		private static final int SHADOW = 3;

		PrimaryButton(String text) {
			super(text);
			setOpaque(false);
			setContentAreaFilled(false);
			setBorderPainted(false);
			setFocusPainted(false);
			setRolloverEnabled(true);
		}

		@Override
		public void setEnabled(boolean enabled) {
			super.setEnabled(enabled);
			setCursor(Cursor.getPredefinedCursor(
					enabled ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
		}

		@Override
		public Dimension getPreferredSize() {
			Dimension d = super.getPreferredSize();
			return new Dimension(d.width, Math.max(36, d.height));
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
						RenderingHints.VALUE_ANTIALIAS_ON);
				int w = getWidth();
				int h = getHeight();
				int fillH = Math.max(1, h - SHADOW);
				boolean enabled = isEnabled();
				Color base;
				if (!enabled) {
					base = BORDER;
				} else if (getModel().isPressed()) {
					base = new Color(0x1E40AF);
				} else if (getModel().isRollover()) {
					base = new Color(0x1D4ED8);
				} else {
					base = PRIMARY;
				}
				if (enabled) {
					// 阴影留在按钮下方, 不参与点击区域
					g2.setColor(new Color(0, 0, 0, 26));
					g2.fillRoundRect(1, SHADOW, w - 2, fillH, ARC, ARC);
				}
				g2.setColor(base);
				g2.fillRoundRect(0, 0, w - 1, fillH, ARC, ARC);

				g2.setFont(getFont());
				g2.setColor(enabled ? Color.WHITE : TEXT_MUTED);
				FontMetrics fm = g2.getFontMetrics();
				String text = getText();
				int tx = (w - fm.stringWidth(text)) / 2;
				int ty = (fillH - fm.getHeight()) / 2 + fm.getAscent();
				g2.drawString(text, Math.max(4, tx), ty);
				// 键盘焦点: 品牌色实底上补一圈浅色内描边, 保证焦点可见
				if (isFocusOwner()) {
					g2.setColor(new Color(255, 255, 255, 190));
					g2.setStroke(new java.awt.BasicStroke(2f));
					g2.drawRoundRect(3, 3, Math.max(1, w - 7), Math.max(1, fillH - 7), ARC, ARC);
				}
			} finally {
				g2.dispose();
			}
		}
	}

	/** 下拉指示三角: 宽 7 / 高 5, 纯代码绘制, 不受字体影响 */
	private static final class ArrowIcon implements Icon {
		private static final int W = 7;
		private static final int H = 5;
		private final Color color;

		ArrowIcon(Color color) {
			this.color = color;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y) {
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
						RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(color);
				g2.fillPolygon(new int[] { x, x + W, x + W / 2 },
						new int[] { y + 1, y + 1, y + 1 + H }, 3);
			} finally {
				g2.dispose();
			}
		}

		@Override
		public int getIconWidth() {
			return W;
		}

		@Override
		public int getIconHeight() {
			return H;
		}
	}

	/** 下拉菜单项(低频操作收纳) */
	public static JMenuItem menuItem(String text) {
		JMenuItem item = new JMenuItem(text);
		item.setFont(BODY);
		return item;
	}

	/** 工具栏分组竖线 */
	public static JSeparator groupSeparator() {
		JSeparator sep = new JSeparator(SwingConstants.VERTICAL);
		sep.setPreferredSize(new Dimension(1, 20));
		sep.setForeground(BORDER);
		return sep;
	}

	public static JPopupMenu popup() {
		JPopupMenu menu = new JPopupMenu();
		menu.setFont(BODY);
		return menu;
	}

	public static JLabel label(String text, Font font, Color color) {
		JLabel l = new JLabel(text);
		l.setFont(font);
		l.setForeground(color);
		return l;
	}
}
