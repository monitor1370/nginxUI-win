package com.nginxui.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;

/**
 * 左侧导航栏(对齐 nginxUI Web 版侧边栏):
 *  - 分组 + 条目, 分组可折叠(条目收起), 整栏固定展开;
 *  - 选中态: 浅蓝底 + 左侧主色指示条 + 主色图标/文字;
 *  - 图标全部用 Graphics2D 矢量绘制, 不依赖外部资源与字体, 任意 DPI 清晰;
 *  - 每项可绑定可用性(BooleanSupplier), 跟随实例选中状态自动置灰.
 */
public class SideNav extends JPanel {

	public static final int EXPANDED_WIDTH = 226;
	private static final int ITEM_HEIGHT = 34;
	private static final int GROUP_HEIGHT = 34;
	private static final int PAD_TOP = 10;
	private static final int RADIUS = 7;
	/** 子级条目相对父级分组的缩进; 分组之间留白; 层级引导线距子项的偏移 */
	private static final int CHILD_INDENT = 16;
	private static final int GROUP_GAP = 8;
	private static final int GUIDE_OFFSET = 7;

	// 图标编号
	public static final int IC_OVERVIEW = 1;
	public static final int IC_INSTANCES = 2;
	public static final int IC_UPGRADE = 3;
	public static final int IC_INSTALL = 4;
	public static final int IC_CERT = 9;
	public static final int IC_CONFIG = 10;
	public static final int IC_TEST = 11;
	public static final int IC_MONITOR = 12;
	public static final int IC_LOG = 13;
	public static final int IC_INFO = 14;
	public static final int IC_ABOUT = 15;
	public static final int IC_SITE = 16;

	private static class Item {
		String id;
		String text;
		int icon;
		BooleanSupplier enabled;
		Runnable action;
		Rectangle bounds = new Rectangle();
	}

	private static class Group {
		String title;
		final List<Item> items = new ArrayList<Item>();
		boolean collapsed;
		Rectangle bounds = new Rectangle();
	}

	private final List<Group> groups = new ArrayList<Group>();
	private final List<Item> items = new ArrayList<Item>();
	private final Map<String, Item> itemById = new LinkedHashMap<String, Item>();
	private String selectedId;
	private Item hover;
	private Group hoverGroup;
	/** 键盘焦点所在条目: 与选中态解耦, 方向键移动, 回车切换页面 */
	private String focusId;
	/** 内容总高度: 由 layoutItems 计算, 供滚动容器取用 */
	private int contentHeight = 100;

	public SideNav() {
		setOpaque(true);
		setBackground(Theme.BG);
		setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.BORDER));
		setFocusable(true);
		MouseAdapter mouse = new MouseAdapter() {
			@Override
			public void mouseMoved(MouseEvent e) {
				Item it = itemAt(e.getPoint());
				Group gp = it == null ? groupAt(e.getPoint()) : null;
				if (it != hover || gp != hoverGroup) {
					hover = it;
					hoverGroup = gp;
					setCursor(it != null || gp != null ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
							: Cursor.getDefaultCursor());
					repaint();
				}
			}

			@Override
			public void mouseExited(MouseEvent e) {
				hover = null;
				hoverGroup = null;
				repaint();
			}

			@Override
			public void mousePressed(MouseEvent e) {
				requestFocusInWindow();
				Group gp = groupAt(e.getPoint());
				if (gp != null) {
					gp.collapsed = !gp.collapsed;
					revalidate();
					repaint();
					return;
				}
				Item it = itemAt(e.getPoint());
				if (it != null && isEnabled(it)) {
					selectedId = it.id;
					focusId = it.id;
					repaint();
					it.action.run();
				}
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
		javax.swing.ToolTipManager.sharedInstance().registerComponent(this);
		installKeyboard();
	}

	/** F7: 侧栏可用 Tab 聚焦, 方向键移动, 回车/空格切换页面(仅对可用条目生效) */
	private void installKeyboard() {
		addFocusListener(new FocusAdapter() {
			@Override
			public void focusGained(FocusEvent e) {
				ensureFocusItem();
				repaint();
			}

			@Override
			public void focusLost(FocusEvent e) {
				repaint();
			}
		});
		getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "nav-down");
		getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "nav-up");
		getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "nav-activate");
		getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "nav-activate");
		getActionMap().put("nav-down", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				moveFocus(1);
			}
		});
		getActionMap().put("nav-up", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				moveFocus(-1);
			}
		});
		getActionMap().put("nav-activate", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				activateFocus();
			}
		});
	}

	private List<Item> visibleEnabledItems() {
		layoutItems();
		List<Item> usable = new ArrayList<Item>();
		for (Item it : items) {
			if (!it.bounds.isEmpty() && isEnabled(it)) {
				usable.add(it);
			}
		}
		return usable;
	}

	private void ensureFocusItem() {
		List<Item> usable = visibleEnabledItems();
		if (usable.isEmpty()) {
			focusId = null;
			return;
		}
		for (Item it : usable) {
			if (it.id.equals(focusId)) {
				return;
			}
		}
		for (Item it : usable) {
			if (it.id.equals(selectedId)) {
				focusId = it.id;
				return;
			}
		}
		focusId = usable.get(0).id;
	}

	private void moveFocus(int delta) {
		List<Item> usable = visibleEnabledItems();
		if (usable.isEmpty()) {
			return;
		}
		int index = -1;
		for (int i = 0; i < usable.size(); i++) {
			if (usable.get(i).id.equals(focusId)) {
				index = i;
				break;
			}
		}
		Item target = usable.get(index < 0 ? (delta > 0 ? 0 : usable.size() - 1)
				: (index + delta + usable.size()) % usable.size());
		focusId = target.id;
		repaint();
		scrollRectToVisible(target.bounds);
	}

	private void activateFocus() {
		if (focusId == null) {
			moveFocus(1);
			return;
		}
		Item it = itemById.get(focusId);
		if (it == null) {
			return;
		}
		layoutItems();
		if (it.bounds.isEmpty() || !isEnabled(it)) {
			moveFocus(1);
			return;
		}
		selectedId = it.id;
		repaint();
		it.action.run();
	}

	// ==================== 品牌区 ====================

	/** 标题栏品牌图标: 与窗口/exe 图标同源(favicon.png), 打包进 jar, 不依赖外部文件 */
	private static final Image BRAND_ICON = loadBrandIcon();

	private static Image loadBrandIcon() {
		try (InputStream in = SideNav.class.getResourceAsStream("/favicon.png")) {
			return in == null ? null : ImageIO.read(in);
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * 标题栏品牌条: 应用图标 + 拉丁字标, 由 MainFrame 嵌入 FlatLaf 自绘标题栏左侧.
	 * "nginxUI" 是纯拉丁字符, 使用 Segoe UI Semibold, 不触发中文回退,
	 * 因此不会出现系统标题栏那种"宋体/黑体混合"的观感.
	 */
	public static JPanel createBrandStrip() {
		JPanel panel = new JPanel() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void paintComponent(Graphics g0) {
				Graphics2D g = (Graphics2D) g0.create();
				try {
					g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
					g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
							RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

					// 应用图标: 与窗口/exe 图标一致(favicon.png), 保持正方形比例
					int size = 22;
					int x = 14;
					int y = (getHeight() - size) / 2;
					if (BRAND_ICON != null) {
						g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
								RenderingHints.VALUE_INTERPOLATION_BICUBIC);
						g.drawImage(BRAND_ICON, x, y, size, size, null);
					} else {
						g.setColor(Theme.PRIMARY);
						g.fillRoundRect(x, y, size, size, 7, 7);
					}

					// 字标: nginx(深色) + UI(主色), 单行与图标垂直居中对齐
					Font nameFont = brandFont(14);
					FontMetrics fmName = g.getFontMetrics(nameFont);
					int tx = x + size + 8;
					int baseline = (getHeight() - fmName.getHeight()) / 2 + fmName.getAscent();
					g.setFont(nameFont);
					g.setColor(Theme.TEXT);
					g.drawString("nginx", tx, baseline);
					g.setColor(Theme.PRIMARY);
					g.drawString("UI", tx + fmName.stringWidth("nginx"), baseline);
				} finally {
					g.dispose();
				}
			}
		};
		panel.setOpaque(false);
		panel.setPreferredSize(new Dimension(110, 28));
		panel.setMinimumSize(new Dimension(110, 28));
		panel.setBorder(BorderFactory.createEmptyBorder());
		return panel;
	}

	/** 品牌字标字体: 优先 Segoe UI Semibold(Latin), 回退 Segoe UI Bold / 系统无衬线 */
	private static Font brandFont(int size) {
		Font semibold = new Font("Segoe UI Semibold", Font.PLAIN, size);
		if ("Segoe UI Semibold".equalsIgnoreCase(semibold.getFamily())) {
			return semibold;
		}
		Font segoe = new Font("Segoe UI", Font.BOLD, size);
		if ("Segoe UI".equalsIgnoreCase(segoe.getFamily())) {
			return segoe;
		}
		return new Font(Font.SANS_SERIF, Font.BOLD, size);
	}

	// ==================== 构建 ====================

	/** 顶部无分组条目(如「总览」) */
	public Item addTopItem(String id, String text, int icon, BooleanSupplier enabled, Runnable action) {
		Group g = new Group();
		groups.add(g);
		return addTo(g, id, text, icon, enabled, action);
	}

	/** 新建分组; 同一次构建里后续 addItem 均属于该分组 */
	public void addGroup(String title) {
		Group g = new Group();
		g.title = title;
		groups.add(g);
	}

	public void addItem(String id, String text, int icon, BooleanSupplier enabled, Runnable action) {
		if (groups.isEmpty()) {
			addGroup(null);
		}
		addTo(groups.get(groups.size() - 1), id, text, icon, enabled, action);
	}

	private Item addTo(Group g, String id, String text, int icon, BooleanSupplier enabled, Runnable action) {
		Item it = new Item();
		it.id = id;
		it.text = text;
		it.icon = icon;
		it.enabled = enabled == null ? () -> true : enabled;
		it.action = action;
		g.items.add(it);
		items.add(it);
		itemById.put(id, it);
		return it;
	}

	/** 程序化选中(不触发动作), 用于页面切换/快捷键 */
	public void select(String id) {
		if (itemById.containsKey(id) && !id.equals(selectedId)) {
			selectedId = id;
			if (hasFocus()) {
				focusId = id;
			}
			repaint();
		}
	}

	/** 可用性变化(实例选中/取消)后刷新外观 */
	public void refreshEnabledState() {
		ensureFocusItem();
		repaint();
	}

	private boolean isEnabled(Item it) {
		try {
			return it.enabled.getAsBoolean();
		} catch (Exception e) {
			return true;
		}
	}

	/** 悬停提示: 未选中实例时说明不可用的原因 */
	@Override
	public String getToolTipText(MouseEvent e) {
		Item it = itemAt(e.getPoint());
		if (it == null) {
			return null;
		}
		String state = isEnabled(it) ? "" : "（需先选择实例）";
		return it.text.replace("…", "") + state;
	}

	// ==================== 布局与绘制 ====================

	/** 计算条目/分组坐标, 供绘制与命中检测共用 */
	private void layoutItems() {
		int x = 8;
		int w = getWidth() - 16;
		int y = PAD_TOP;
		boolean firstGroup = true;
		for (Group g : groups) {
			boolean nested = g.title != null;
			if (nested) {
				// 父级分组独立成行, 分组之间留白, 与子级条目形成明显层级
				if (!firstGroup) {
					y += GROUP_GAP;
				}
				g.bounds.setBounds(x, y, w, GROUP_HEIGHT);
				y += GROUP_HEIGHT + 2;
			} else {
				g.bounds.setBounds(0, 0, 0, 0);
			}
			firstGroup = false;
			if (!g.collapsed) {
				int ix = nested ? x + CHILD_INDENT : x;
				for (Item it : g.items) {
					it.bounds.setBounds(ix, y, w - (ix - x), ITEM_HEIGHT);
					y += ITEM_HEIGHT + 1;
				}
			} else {
				// 分组收起: 必须把子条目的 bounds 清空, 否则键盘焦点边框 / 悬停态
				// 会在折叠后仍残留在上次的坐标上.
				for (Item it : g.items) {
					it.bounds.setBounds(0, 0, 0, 0);
				}
			}
		}
		contentHeight = y + PAD_TOP;
	}

	/** 分组/条目高度随折叠状态变化, 用内容高度作为首选高度, 矮窗口下可滚动而不是裁掉末尾条目 */
	@Override
	public Dimension getPreferredSize() {
		layoutItems();
		return new Dimension(EXPANDED_WIDTH, contentHeight);
	}

	private Item itemAt(java.awt.Point p) {
		layoutItems();
		for (Item it : items) {
			if (!it.bounds.isEmpty() && it.bounds.contains(p)) {
				return it;
			}
		}
		return null;
	}

	private Group groupAt(java.awt.Point p) {
		layoutItems();
		for (Group g : groups) {
			if (g.title != null && !g.bounds.isEmpty() && g.bounds.contains(p)) {
				return g;
			}
		}
		return null;
	}

	@Override
	protected void paintComponent(Graphics g0) {
		super.paintComponent(g0);
		Graphics2D g = (Graphics2D) g0.create();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			// 侧栏文字用 LCD 次像素抗锯齿并关闭小数度量: 字形对齐整像素, 避免发虚
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
					RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
			g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
					RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
			g.setRenderingHint(RenderingHints.KEY_TEXT_LCD_CONTRAST, 160);
			layoutItems();
			for (Group gp : groups) {
				if (gp.title != null) {
					drawGroupTitle(g, gp);
				}
				if (gp.collapsed) {
					continue;
				}
				if (gp.title != null && !gp.items.isEmpty()) {
					drawChildGuide(g, gp);
				}
				for (Item it : gp.items) {
					drawItem(g, it);
				}
			}
			// 键盘焦点: 只在侧栏获得焦点时绘制, 与鼠标悬停态互不干扰
			if (hasFocus() && focusId != null) {
				Item focus = itemById.get(focusId);
				if (focus != null && !focus.bounds.isEmpty()) {
					g.setColor(Theme.PRIMARY);
					g.setStroke(new BasicStroke(2f));
					g.drawRoundRect(focus.bounds.x + 1, focus.bounds.y + 1,
							Math.max(1, focus.bounds.width - 3), Math.max(1, focus.bounds.height - 3), 8, 8);
				}
			}
		} finally {
			g.dispose();
		}
	}

	/** 父级分组: 浅灰底整行 + 加粗深色标题 + 折叠箭头, 与子级条目形成层级对比 */
	private void drawGroupTitle(Graphics2D g, Group gp) {
		Rectangle b = gp.bounds;
		if (b.isEmpty()) {
			return;
		}
		g.setColor(hoverGroup == gp ? Theme.GROUP_BG_HOVER : Theme.GROUP_BG);
		g.fillRoundRect(b.x, b.y, b.width, b.height, RADIUS, RADIUS);

		g.setFont(Theme.BODY_BOLD);
		g.setColor(Theme.TEXT);
		FontMetrics fm = g.getFontMetrics();
		int ty = b.y + (b.height - fm.getHeight()) / 2 + fm.getAscent();
		g.drawString(gp.title, b.x + 10, ty);

		// 折叠箭头: 展开=▾(向下), 折叠=▸(向右), 用矢量三角避免字体缺字形
		int ax = b.x + b.width - 14;
		int ay = b.y + (b.height - 6) / 2;
		g.setColor(hoverGroup == gp ? Theme.TEXT : Theme.TEXT_MUTED);
		if (gp.collapsed) {
			g.fillPolygon(new int[] { ax, ax, ax + 6 }, new int[] { ay, ay + 7, ay + 3 }, 3);
		} else {
			g.fillPolygon(new int[] { ax, ax + 7, ax + 3 }, new int[] { ay, ay, ay + 6 }, 3);
		}
	}

	/** 父级到子级的层级引导线, 强化从属关系 */
	private void drawChildGuide(Graphics2D g, Group gp) {
		Item first = gp.items.get(0);
		Item last = gp.items.get(gp.items.size() - 1);
		int gx = first.bounds.x - GUIDE_OFFSET;
		int top = gp.bounds.y + gp.bounds.height + 4;
		int bottom = last.bounds.y + last.bounds.height / 2;
		if (bottom <= top) {
			return;
		}
		g.setColor(Theme.BORDER);
		g.setStroke(new BasicStroke(1f));
		g.drawLine(gx, top, gx, bottom);
	}

	private void drawItem(Graphics2D g, Item it) {
		boolean selected = it.id.equals(selectedId);
		boolean enabled = isEnabled(it);
		boolean hovered = it == hover && enabled;
		Rectangle b = it.bounds;
		if (b.isEmpty()) {
			return;
		}
		if (selected) {
			g.setColor(Theme.SELECTED_BG);
			g.fillRoundRect(b.x, b.y, b.width, b.height, RADIUS, RADIUS);
			g.setColor(Theme.PRIMARY);
			g.fillRoundRect(b.x, b.y + 8, 3, b.height - 16, 3, 3);
		} else if (hovered) {
			g.setColor(Theme.HOVER_BG);
			g.fillRoundRect(b.x, b.y, b.width, b.height, RADIUS, RADIUS);
		}
		Color fg = !enabled ? new Color(0xB9BEC7) : (selected ? Theme.PRIMARY : Theme.TEXT);
		int iconSize = 16;
		int ix = b.x + 12;
		int iy = b.y + (b.height - iconSize) / 2;
		drawIcon(g, it.icon, ix, iy, iconSize, fg);
		g.setFont(selected ? Theme.BODY_BOLD : Theme.BODY);
		g.setColor(fg);
		FontMetrics fm = g.getFontMetrics();
		int ty = b.y + (b.height - fm.getHeight()) / 2 + fm.getAscent();
		g.drawString(it.text, b.x + 12 + iconSize + 10, ty);
	}

	// ==================== 矢量图标 ====================

	private static void drawIcon(Graphics2D g, int icon, int x, int y, int s, Color c) {
		Color old = g.getColor();
		g.setColor(c);
		g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		int h = s / 2;
		switch (icon) {
		case IC_OVERVIEW: // 四宫格
			g.drawRoundRect(x + 1, y + 1, h - 2, h - 2, 3, 3);
			g.drawRoundRect(x + h + 1, y + 1, h - 2, h - 2, 3, 3);
			g.drawRoundRect(x + 1, y + h + 1, h - 2, h - 2, 3, 3);
			g.drawRoundRect(x + h + 1, y + h + 1, h - 2, h - 2, 3, 3);
			break;
		case IC_INSTANCES: // 列表
			for (int i = 0; i < 3; i++) {
				int yy = y + 2 + i * 5;
				g.drawLine(x + 1, yy + 1, x + 3, yy + 1);
				g.drawLine(x + 6, yy + 1, x + s - 1, yy + 1);
			}
			break;
		case IC_UPGRADE: // 上升箭头
			g.drawLine(x + h, y + s - 2, x + h, y + 4);
			g.drawLine(x + h, y + 3, x + h - 4, y + 7);
			g.drawLine(x + h, y + 3, x + h + 4, y + 7);
			break;
		case IC_INSTALL: // 方块+加号
			g.drawRoundRect(x + 1, y + 1, s - 2, s - 2, 3, 3);
			g.drawLine(x + h, y + 5, x + h, y + s - 5);
			g.drawLine(x + 5, y + h, x + s - 5, y + h);
			break;
		case IC_CERT: // 盾牌轮廓
			java.awt.geom.Path2D.Float shield = new java.awt.geom.Path2D.Float();
			shield.moveTo(x + h, y + 1);
			shield.lineTo(x + s - 2, y + 4);
			shield.lineTo(x + s - 2, y + h + 2);
			shield.quadTo(x + h, y + s + 1, x + 2, y + h + 2);
			shield.lineTo(x + 2, y + 4);
			shield.closePath();
			g.draw(shield);
			break;
		case IC_CONFIG: // 文件+行
			g.drawRoundRect(x + 2, y + 1, s - 4, s - 2, 2, 2);
			g.drawLine(x + 4, y + 6, x + s - 4, y + 6);
			g.drawLine(x + 4, y + 10, x + s - 4, y + 10);
			break;
		case IC_TEST: // 对勾
			g.drawLine(x + 2, y + h + 1, x + h - 1, y + s - 3);
			g.drawLine(x + h - 1, y + s - 3, x + s - 3, y + 3);
			break;
		case IC_MONITOR: // 柱状图
			g.drawLine(x + 1, y + s - 2, x + s - 1, y + s - 2);
			g.drawLine(x + 3, y + s - 2, x + 3, y + h + 2);
			g.drawLine(x + h, y + s - 2, x + h, y + 5);
			g.drawLine(x + s - 3, y + s - 2, x + s - 3, y + h);
			break;
		case IC_LOG: // 文档多行
			g.drawRoundRect(x + 2, y + 1, s - 4, s - 2, 2, 2);
			for (int i = 0; i < 3; i++) {
				g.drawLine(x + 5, y + 5 + i * 3, x + s - 5, y + 5 + i * 3);
			}
			break;
		case IC_SITE: // 地球: 站点/域名的通用意象
			g.drawOval(x + 1, y + 1, s - 2, s - 2);
			g.drawLine(x + 1, y + h, x + s - 1, y + h);
			g.drawOval(x + h / 2, y + 1, h, s - 2);
			break;
		case IC_ABOUT:
		case IC_INFO: // 信息圈
			g.drawOval(x + 1, y + 1, s - 2, s - 2);
			g.drawLine(x + h, y + 5, x + h, y + h + 2);
			g.drawLine(x + h, y + s - 5, x + h, y + s - 5);
			break;
		default:
			g.drawOval(x + 1, y + 1, s - 2, s - 2);
			break;
		}
		g.setColor(old);
	}
}
