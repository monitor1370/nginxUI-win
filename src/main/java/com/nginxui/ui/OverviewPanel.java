package com.nginxui.ui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import com.nginxui.ext.NginxInstanceEntry;

/**
 * 概览页(对齐 nginxUI Web 版「总览」):
 *  - 顶部四张统计卡: 实例总数 / 运行中 / 已停止 / 已忽略;
 *  - 常用功能: 三张彩色大卡片(版本升级 / 安装实例 / HTTPS 证书更换);
 *  - 实例状态总览: 全部实例的运行状态与健康指标(点击行进入实例总览并定位);
 *  - 底部快捷键提示与最近检测时间.
 * 数据由 MainFrame 每轮刷新后推送, 页面本身不做采集.
 */
public class OverviewPanel extends JPanel {

	private final JLabel lblTotal = cardValue();
	private final JLabel lblRunning = cardValue();
	private final JLabel lblStopped = cardValue();
	private final JLabel lblIgnored = cardValue();
	private final JLabel lblRefresh = Theme.label("尚未检测", Theme.SMALL, Theme.TEXT_MUTED);

	private final Consumer<NginxInstanceEntry> onOpenInstance;

	private final InstanceTableModel tableModel = new InstanceTableModel();
	private final JTable table = new StatusTable();
	private final JLabel lblInstanceSummary = Theme.label("—", Theme.SMALL, Theme.TEXT_MUTED);
	private final CardLayout instanceCards = new CardLayout();
	private final JPanel instanceBody = new JPanel(instanceCards);

	/** 运行中实例默认最多展示 3 条, 其余通过下拉展开 */
	static final int RUNNING_PREVIEW_LIMIT = 3;

	private final JButton btnView = new ViewButton();
	private final JLabel lblEmpty = Theme.label("", Theme.SMALL, Theme.TEXT_MUTED);

	/** 完整数据源: 每轮刷新保存全量, 筛选/展开只改变可见视图, 不丢数据 */
	private List<InstanceStatus> allRows = new ArrayList<InstanceStatus>();
	private boolean showAllStatuses;
	private boolean expanded;

	private static final String CARD_TABLE = "table";
	private static final String CARD_EMPTY = "empty";
	private static final String[] COLUMNS = {
			"状态", "实例", "健康", "服务 / PID", "Worker", "版本", "监听端口"
	};
	private static final int[] COL_WIDTHS = { 82, 240, 78, 250, 66, 84, 210 };
	private static final int[] COL_MIN_WIDTHS = { 72, 120, 70, 110, 56, 70, 90 };

	public OverviewPanel(Runnable onInstall, Runnable onUpgrade, Runnable onCert,
			Consumer<NginxInstanceEntry> onOpenInstance) {
		super(new BorderLayout(0, Theme.GAP_M));
		this.onOpenInstance = onOpenInstance;
		setOpaque(true);
		setBackground(Theme.BG);
		setBorder(BorderFactory.createEmptyBorder(Theme.GAP_M, Theme.GAP_L, Theme.GAP_M, Theme.GAP_L));

		JPanel cards = new JPanel(new GridLayout(1, 4, Theme.GAP_M, 0));
		cards.setOpaque(false);
		cards.add(statCard("实例总数", lblTotal, Theme.PRIMARY));
		cards.add(statCard("运行中", lblRunning, Theme.SUCCESS));
		cards.add(statCard("已停止", lblStopped, Theme.TEXT_MUTED));
		cards.add(statCard("已忽略", lblIgnored, Theme.WARNING));

		JPanel body = new JPanel();
		body.setOpaque(false);
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

		JLabel lblQuick = Theme.label("常用功能", Theme.BODY_BOLD, Theme.TEXT);
		lblQuick.setAlignmentX(LEFT_ALIGNMENT);
		body.add(lblQuick);
		body.add(Box.createVerticalStrut(Theme.GAP_S));

		JPanel quick = new JPanel(new GridLayout(1, 3, Theme.GAP_M, 0));
		quick.setOpaque(false);
		quick.setAlignmentX(LEFT_ALIGNMENT);
		quick.setMaximumSize(new Dimension(Integer.MAX_VALUE, QuickAction.CARD_HEIGHT));

		QuickAction bUpgrade = new QuickAction("版本升级", "升级 / 回滚 nginx 版本", QuickAction.IC_UPGRADE,
				new Color(0x3B82F6), new Color(0x2563EB), onUpgrade);
		QuickAction bInstall = new QuickAction("安装实例", "安装新的 nginx 实例", QuickAction.IC_INSTALL,
				new Color(0x12B76A), new Color(0x039855), onInstall);
		QuickAction bCert = new QuickAction("HTTPS 证书更换", "更新站点证书文件", QuickAction.IC_CERT,
				new Color(0xF79009), new Color(0xDC6803), onCert);

		bUpgrade.setToolTipText("升级/回滚所选实例的 nginx 程序版本");
		bInstall.setToolTipText("在线下载或上传官方安装包，全新安装一个 nginx 实例");
		bCert.setToolTipText("证书库、实例证书与绑定关系总览，一键更换证书");
		quick.add(bUpgrade);
		quick.add(bInstall);
		quick.add(bCert);
		body.add(quick);

		// 中部可伸缩: 上方固定为常用功能, 下方实例状态表填满剩余空间
		JPanel center = new JPanel(new BorderLayout(0, Theme.GAP_M));
		center.setOpaque(false);
		center.add(body, BorderLayout.NORTH);
		center.add(buildInstanceSection(), BorderLayout.CENTER);

		add(cards, BorderLayout.NORTH);
		add(center, BorderLayout.CENTER);

		JPanel foot = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		foot.setOpaque(false);
		foot.add(lblRefresh);
		add(foot, BorderLayout.SOUTH);

		btnView.addActionListener(e -> showViewMenu());
	}

	/** 刷新后推送统计: -1 表示检测中(显示 —) */
	public void setStats(int total, int running, int stopped, int ignored) {
		lblTotal.setText(total < 0 ? "—" : String.valueOf(total));
		lblRunning.setText(running < 0 ? "—" : String.valueOf(running));
		lblStopped.setText(stopped < 0 ? "—" : String.valueOf(stopped));
		lblIgnored.setText(ignored < 0 ? "—" : String.valueOf(ignored));
	}

	public void setLastRefresh(String text) {
		lblRefresh.setText(text);
	}

	// ==================== 实例状态总览 ====================

	/** 全部实例的统一状态表: 状态/健康/服务与PID/Worker/版本/监听端口 */
	public void setInstances(List<InstanceStatus> rows) {
		allRows = rows == null ? new ArrayList<InstanceStatus>() : new ArrayList<InstanceStatus>(rows);

		int total = allRows.size();
		int running = runningCount(allRows);
		int ignored = 0;
		boolean detecting = false;
		for (InstanceStatus s : allRows) {
			if (s.ignored) {
				ignored++;
			}
			if (s.detecting) {
				detecting = true;
			}
		}
		if (detecting) {
			lblInstanceSummary.setText("检测中… 共 " + total + " 个实例");
		} else {
			lblInstanceSummary.setText("共 " + total + " 个 · 运行 " + running
					+ " · 停止 " + Math.max(0, total - running - ignored) + " · 忽略 " + ignored);
		}

		// 运行中回落到 3 条以内时收起展开态, 按钮文案与列表保持一致
		if (running <= RUNNING_PREVIEW_LIMIT) {
			expanded = false;
		}
		applyInstanceView();
	}

	/** 按"状态筛选 + 展开/收起"重新计算可见行, 每次刷新都调用, 保证状态变更实时同步 */
	private void applyInstanceView() {
		List<InstanceStatus> visible = selectVisible(allRows, showAllStatuses, expanded);
		tableModel.setRows(visible);
		table.clearSelection();

		if (allRows.isEmpty()) {
			lblEmpty.setText("尚未发现实例，点击上方「安装实例」开始添加");
		} else if (visible.isEmpty()) {
			lblEmpty.setText("当前没有运行中的实例，点击右上角切换为「全部状态」查看");
		}
		instanceCards.show(instanceBody, visible.isEmpty() ? CARD_EMPTY : CARD_TABLE);
		updateViewButton(runningCount(allRows));
	}

	/**
	 * 可见行计算: 默认只看运行中且最多 3 条; "全部状态"展示全部状态且不再截断.
	 * 抽成静态方法便于对 0/3/4+ 边界做独立校验.
	 */
	static List<InstanceStatus> selectVisible(List<InstanceStatus> all,
			boolean showAllStatuses, boolean expanded) {
		List<InstanceStatus> visible = new ArrayList<InstanceStatus>();
		for (InstanceStatus s : all) {
			if (showAllStatuses || s.running) {
				visible.add(s);
			}
		}
		if (!showAllStatuses && !expanded && visible.size() > RUNNING_PREVIEW_LIMIT) {
			return new ArrayList<InstanceStatus>(visible.subList(0, RUNNING_PREVIEW_LIMIT));
		}
		return visible;
	}

	private static int runningCount(List<InstanceStatus> rows) {
		int running = 0;
		for (InstanceStatus s : rows) {
			if (s.running && !s.ignored) {
				running++;
			}
		}
		return running;
	}

	/** 标题栏下拉: 切换"运行中/全部状态", 运行中超过 3 条时提供展开/收起 */
	private void showViewMenu() {
		JPopupMenu menu = Theme.popup();

		JMenuItem miStatus = Theme.menuItem(showAllStatuses ? "仅显示运行中" : "显示全部状态");
		miStatus.addActionListener(e -> {
			showAllStatuses = !showAllStatuses;
			expanded = false;
			applyInstanceView();
		});
		menu.add(miStatus);

		int running = runningCount(allRows);
		if (!showAllStatuses && running > RUNNING_PREVIEW_LIMIT) {
			JMenuItem miExpand = Theme.menuItem(expanded
					? "仅显示前 " + RUNNING_PREVIEW_LIMIT + " 条"
					: "展开全部运行实例（共 " + running + " 个）");
			miExpand.addActionListener(e -> {
				expanded = !expanded;
				applyInstanceView();
			});
			menu.add(miExpand);
		}

		menu.show(btnView, btnView.getWidth() - menu.getPreferredSize().width,
				btnView.getHeight() + 4);
	}

	private void updateViewButton(int running) {
		if (showAllStatuses) {
			btnView.setText("全部状态");
		} else if (!expanded && running > RUNNING_PREVIEW_LIMIT) {
			btnView.setText("运行中 " + RUNNING_PREVIEW_LIMIT + "/" + running);
		} else {
			btnView.setText("运行中");
		}
		btnView.setToolTipText(showAllStatuses
				? "当前显示全部状态，点击可恢复仅运行中"
				: "默认只显示运行中实例，最多 " + RUNNING_PREVIEW_LIMIT + " 条；点击切换显示范围");
	}

	private JPanel buildInstanceSection() {
		JPanel card = new JPanel(new BorderLayout());
		card.setBackground(Theme.SURFACE);
		card.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
		card.setAlignmentX(LEFT_ALIGNMENT);

		JPanel head = new JPanel(new BorderLayout());
		head.setOpaque(false);
		head.setBorder(new EmptyBorder(10, 14, 8, 14));
		head.add(Theme.label("实例状态总览", Theme.BODY_BOLD, Theme.TEXT), BorderLayout.WEST);

		JPanel headRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
		headRight.setOpaque(false);
		headRight.add(lblInstanceSummary);
		headRight.add(btnView);
		head.add(headRight, BorderLayout.EAST);
		card.add(head, BorderLayout.NORTH);

		setupTable();
		JScrollPane scroll = new JScrollPane(table);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getViewport().setBackground(Theme.SURFACE);
		scroll.getVerticalScrollBar().setUnitIncrement(14);

		JPanel empty = new JPanel(new BorderLayout());
		empty.setBackground(Theme.SURFACE);
		lblEmpty.setHorizontalAlignment(SwingConstants.CENTER);
		empty.add(lblEmpty, BorderLayout.CENTER);

		instanceBody.setOpaque(false);
		instanceBody.add(scroll, CARD_TABLE);
		instanceBody.add(empty, CARD_EMPTY);
		card.add(instanceBody, BorderLayout.CENTER);
		return card;
	}

	private void setupTable() {
		table.setFont(Theme.SMALL);
		table.setRowHeight(44);
		table.setShowVerticalLines(false);
		table.setShowHorizontalLines(true);
		// 极浅横线只做视线引导, 不形成表格边框; 组内信息靠列对齐与留白分隔
		table.setGridColor(Theme.GRID_LINE);
		table.setIntercellSpacing(new Dimension(0, 1));
		table.setFillsViewportHeight(true);
		table.setBackground(Theme.SURFACE);
		table.setForeground(Theme.TEXT);
		table.setSelectionBackground(Theme.SELECTED_BG);
		table.setSelectionForeground(Theme.TEXT);
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.setFocusable(false);
		table.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
		// 空提示触发 ToolTipManager 注册, 由 getToolTipText 按单元格返回完整内容
		table.setToolTipText("");
		table.getTableHeader().setReorderingAllowed(false);
		// 去掉灰底表头带: 列名用更小字号 + 弱色, 与数据行保持同一底色
		table.getTableHeader().setFont(Theme.SMALL.deriveFont(Font.PLAIN, 11f));
		table.getTableHeader().setBackground(Theme.SURFACE);
		table.getTableHeader().setForeground(Theme.TEXT_MUTED);
		table.getTableHeader().setPreferredSize(new Dimension(10, 32));

		CellRenderer renderer = new CellRenderer();
		for (int i = 0; i < COLUMNS.length; i++) {
			javax.swing.table.TableColumn col = table.getColumnModel().getColumn(i);
			col.setPreferredWidth(COL_WIDTHS[i]);
			col.setMinWidth(COL_MIN_WIDTHS[i]);
			col.setCellRenderer(renderer);
		}

		table.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				int row = table.rowAtPoint(e.getPoint());
				if (row < 0) {
					return;
				}
				InstanceStatus status = tableModel.rowAt(table.convertRowIndexToModel(row));
				if (status != null && status.entry != null && onOpenInstance != null) {
					onOpenInstance.accept(status.entry);
				}
			}
		});
	}

	private static String statusText(InstanceStatus s) {
		if (s.detecting) {
			return "检测中";
		}
		if (s.ignored) {
			return "已忽略";
		}
		return s.running ? "运行中" : "已停止";
	}

	private static String healthText(InstanceStatus s) {
		if (s.detecting) {
			return "检测中";
		}
		if (s.ignored) {
			return "已忽略";
		}
		if (!s.running) {
			return "未运行";
		}
		return s.workers != null && s.workers > 0 ? "正常" : "待确认";
	}

	private static String servicePidText(InstanceStatus s) {
		String svc = s.service == null ? "" : s.service.trim();
		String pid = s.pid == null ? "" : s.pid.trim();
		if (svc.isEmpty()) {
			return pid.isEmpty() ? "—" : "PID " + pid;
		}
		if (pid.isEmpty()) {
			return svc;
		}
		return svc + " · PID " + pid;
	}

	private static String versionText(String version) {
		if (version == null || version.isEmpty()) {
			return "—";
		}
		return version.startsWith("v") ? version : "v" + version;
	}

	private static String portsText(List<String> ports) {
		if (ports == null || ports.isEmpty()) {
			return "—";
		}
		StringBuilder sb = new StringBuilder();
		for (String port : ports) {
			if (port == null || port.trim().isEmpty()) {
				continue;
			}
			if (sb.length() > 0) {
				sb.append('、');
			}
			sb.append(port.trim());
		}
		return sb.length() == 0 ? "—" : sb.toString();
	}

	private static Color statusColor(InstanceStatus s) {
		if (s.ignored) {
			return Theme.WARNING;
		}
		if (s.detecting) {
			return Theme.TEXT_MUTED;
		}
		return Theme.statusColor(s.running);
	}

	private static Color healthColor(InstanceStatus s) {
		if (s.detecting) {
			return Theme.TEXT_MUTED;
		}
		if (s.ignored) {
			return Theme.WARNING;
		}
		if (!s.running) {
			return Theme.TEXT_MUTED;
		}
		return s.workers != null && s.workers > 0 ? Theme.STATUS_RUNNING_TEXT : Theme.WARNING;
	}

	private JLabel cardValue() {
		return Theme.label("—", new Font(Theme.BODY_BOLD.getFamily(), Font.BOLD, 26), Theme.TEXT);
	}

	/** 统计卡: 白底描边 + 上方标题 + 下方大号数值 */
	private static JPanel statCard(String title, JLabel value, Color valueColor) {
		JPanel card = new JPanel(new BorderLayout(0, 4));
		card.setBackground(Theme.SURFACE);
		card.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(Theme.BORDER),
				BorderFactory.createEmptyBorder(12, 16, 12, 16)));
		card.setPreferredSize(new Dimension(150, 88));

		JLabel lblTitle = Theme.label(title, Theme.SMALL, Theme.TEXT_MUTED);
		value.setForeground(valueColor);
		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		top.add(lblTitle, BorderLayout.WEST);
		JPanel center = new JPanel(new BorderLayout());
		center.setOpaque(false);
		value.setHorizontalAlignment(SwingConstants.LEFT);
		center.add(value, BorderLayout.CENTER);

		card.add(top, BorderLayout.NORTH);
		card.add(center, BorderLayout.CENTER);
		return card;
	}

	// ==================== 内部类型 ====================

	/** 概览实例状态行: 由主窗口按本轮采集结果构建, 页面只负责展示 */
	public static final class InstanceStatus {
		public final NginxInstanceEntry entry;
		public final boolean running;
		public final boolean detecting;
		public final boolean ignored;
		public final String service;
		public final String pid;
		public final Integer workers;
		public final String version;
		public final List<String> ports;

		public InstanceStatus(NginxInstanceEntry entry, boolean running, boolean detecting,
				boolean ignored, String service, String pid, Integer workers,
				String version, List<String> ports) {
			this.entry = entry;
			this.running = running;
			this.detecting = detecting;
			this.ignored = ignored;
			this.service = service;
			this.pid = pid;
			this.workers = workers;
			this.version = version;
			this.ports = ports;
		}
	}

	private static final class InstanceTableModel extends AbstractTableModel {
		private static final long serialVersionUID = 1L;

		private List<InstanceStatus> rows = new ArrayList<InstanceStatus>();

		void setRows(List<InstanceStatus> rows) {
			this.rows = rows;
			fireTableDataChanged();
		}

		InstanceStatus rowAt(int index) {
			return index >= 0 && index < rows.size() ? rows.get(index) : null;
		}

		@Override
		public int getRowCount() {
			return rows.size();
		}

		@Override
		public int getColumnCount() {
			return COLUMNS.length;
		}

		@Override
		public String getColumnName(int column) {
			return COLUMNS[column];
		}

		@Override
		public boolean isCellEditable(int row, int column) {
			return false;
		}

		@Override
		public Object getValueAt(int row, int column) {
			InstanceStatus s = rows.get(row);
			switch (column) {
				case 0:
					return s;
				case 1:
					return MainFrame.InstanceRow.displayName(s.entry);
				case 2:
					return healthText(s);
				case 3:
					return servicePidText(s);
				case 4:
					return s.workers == null ? "—" : String.valueOf(s.workers);
				case 5:
					return versionText(s.version);
				case 6:
					return portsText(s.ports);
				default:
					return "";
			}
		}
	}

	/** 模块标题右侧的小号下拉按钮: 弱化文字 + 下箭头, 悬停淡底, 与页面风格一致 */
	private static final class ViewButton extends JButton {
		private static final long serialVersionUID = 1L;

		ViewButton() {
			setOpaque(false);
			setContentAreaFilled(false);
			setBorderPainted(false);
			setFocusable(false);
			setRolloverEnabled(true);
			setFont(Theme.SMALL.deriveFont(Font.PLAIN, 11f));
			setForeground(Theme.TEXT_MUTED);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		}

		@Override
		public Dimension getPreferredSize() {
			FontMetrics fm = getFontMetrics(getFont());
			return new Dimension(fm.stringWidth(getText()) + 26, 22);
		}

		@Override
		protected void paintComponent(Graphics g0) {
			Graphics2D g = (Graphics2D) g0.create();
			try {
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
						RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
				int w = getWidth();
				int h = getHeight();
				boolean hover = getModel().isRollover();
				if (hover) {
					g.setColor(Theme.HOVER_BG);
					g.fillRoundRect(0, 0, w, h, 6, 6);
				}

				g.setFont(getFont());
				g.setColor(hover ? Theme.TEXT : Theme.TEXT_MUTED);
				FontMetrics fm = g.getFontMetrics();
				g.drawString(getText(), 6, (h - fm.getHeight()) / 2 + fm.getAscent());

				// 下箭头: 与文字同色, 提示可展开
				int ax = w - 12;
				int ay = h / 2 - 1;
				g.setStroke(new BasicStroke(1.4f));
				g.drawLine(ax - 4, ay, ax, ay + 4);
				g.drawLine(ax, ay + 4, ax + 4, ay);
			} finally {
				g.dispose();
			}
		}
	}

	/** 单元格渲染: 运行行淡绿底, 选中态加深; 状态/健康使用语义色 */
	private final class CellRenderer extends DefaultTableCellRenderer {
		private static final long serialVersionUID = 1L;

		@Override
		public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected,
				boolean hasFocus, int row, int column) {
			super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
			InstanceStatus s = tableModel.rowAt(t.convertRowIndexToModel(row));
			setOpaque(true);
			setBorder(new EmptyBorder(0, 10, 0, 10));
			setFont(column == 1 ? Theme.BODY_BOLD : Theme.SMALL);
			setHorizontalAlignment(column == 4 ? SwingConstants.CENTER : SwingConstants.LEFT);

			boolean running = s != null && s.running;
			Color bg = running ? Theme.RUNNING_BG : Theme.SURFACE;
			if (isSelected) {
				bg = running ? Theme.RUNNING_BG_SELECTED : Theme.SELECTED_BG;
			}
			setBackground(bg);

			Color fg = Theme.TEXT;
			if (s != null) {
				switch (column) {
					case 0:
						setText("● " + statusText(s));
						fg = statusColor(s);
						setFont(Theme.SMALL.deriveFont(Font.BOLD));
						break;
					case 2:
						fg = healthColor(s);
						setFont(Theme.SMALL.deriveFont(Font.BOLD));
						break;
					case 3:
					case 4:
					case 5:
						fg = Theme.TEXT_MUTED;
						break;
					default:
						break;
				}
			}
			setForeground(isSelected ? Theme.TEXT : fg);
			return this;
		}
	}

	/** 带完整内容提示的状态表 */
	private final class StatusTable extends JTable {
		private static final long serialVersionUID = 1L;

		StatusTable() {
			super(tableModel);
		}

		@Override
		public String getToolTipText(MouseEvent e) {
			int row = rowAtPoint(e.getPoint());
			int column = columnAtPoint(e.getPoint());
			if (row < 0 || column < 0) {
				return null;
			}
			Object value = getValueAt(row, column);
			if (value instanceof InstanceStatus) {
				InstanceStatus s = (InstanceStatus) value;
				return statusText(s) + " · " + MainFrame.InstanceRow.displayName(s.entry);
			}
			String text = value == null ? null : String.valueOf(value);
			return text == null || text.isEmpty() || "—".equals(text) ? null : text;
		}
	}

	/**
	 * 常用功能大卡片: 彩色渐变底 + 白色矢量图标 + 白色加粗标题 + 浅白副标题.
	 * 整卡为可点击区域, 悬停提亮、按下加深, 文案与图标随状态保持清晰对比.
	 */
	private static final class QuickAction extends JPanel {

		static final int CARD_HEIGHT = 76;
		static final int IC_UPGRADE = 1;
		static final int IC_INSTALL = 2;
		static final int IC_CERT = 3;

		private static final int ARC = 12;
		private static final int ICON_SIZE = 26;
		private static final int PAD_X = 18;
		private static final int TEXT_GAP = 14;

		private final String title;
		private final String subtitle;
		private final int icon;
		private final Color light;
		private final Color base;
		private final Runnable action;

		private boolean hovered;
		private boolean pressed;

		QuickAction(String title, String subtitle, int icon, Color light, Color base, Runnable action) {
			this.title = title;
			this.subtitle = subtitle;
			this.icon = icon;
			this.light = light;
			this.base = base;
			this.action = action;
			setOpaque(false);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			addMouseListener(new MouseAdapter() {
				@Override
				public void mouseEntered(MouseEvent e) {
					hovered = true;
					repaint();
				}

				@Override
				public void mouseExited(MouseEvent e) {
					hovered = false;
					pressed = false;
					repaint();
				}

				@Override
				public void mousePressed(MouseEvent e) {
					if (SwingUtilities.isLeftMouseButton(e)) {
						pressed = true;
						repaint();
					}
				}

				@Override
				public void mouseReleased(MouseEvent e) {
					boolean fire = pressed && SwingUtilities.isLeftMouseButton(e) && contains(e.getPoint());
					pressed = false;
					repaint();
					if (fire) {
						action.run();
					}
				}
			});
		}

		@Override
		public Dimension getPreferredSize() {
			Dimension d = super.getPreferredSize();
			return new Dimension(d.width, CARD_HEIGHT);
		}

		@Override
		public Dimension getMinimumSize() {
			return new Dimension(80, CARD_HEIGHT);
		}

		@Override
		protected void paintComponent(Graphics g0) {
			Graphics2D g = (Graphics2D) g0.create();
			try {
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
						RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				int w = getWidth();
				int h = getHeight();
				Color top = hovered ? brighten(light, 0.10f) : light;
				Color bottom = hovered ? brighten(base, 0.10f) : base;
				g.setPaint(new GradientPaint(0, 0, top, 0, h, bottom));
				g.fillRoundRect(0, 0, w - 1, h - 1, ARC, ARC);
				if (pressed) {
					g.setColor(new Color(0, 0, 0, 30));
					g.fillRoundRect(0, 0, w - 1, h - 1, ARC, ARC);
				}

				int cx = PAD_X + ICON_SIZE / 2;
				int cy = h / 2;
				g.setColor(Color.WHITE);
				g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				drawGlyph(g, icon, cx, cy);

				int tx = PAD_X + ICON_SIZE + TEXT_GAP;
				Font tf = new Font(Theme.BODY_BOLD.getFamily(), Font.BOLD, 15);
				g.setFont(tf);
				FontMetrics fmT = g.getFontMetrics();
				int titleY = h / 2 - 3;
				g.setColor(Color.WHITE);
				g.drawString(title, tx, titleY);

				g.setFont(new Font(Theme.SMALL.getFamily(), Font.PLAIN, 12));
				FontMetrics fmS = g.getFontMetrics();
				g.setColor(new Color(255, 255, 255, 210));
				g.drawString(subtitle, tx, titleY + 4 + fmS.getAscent() + 3);
			} finally {
				g.dispose();
			}
		}

		/** 白色矢量图标: 1=上箭头(升级) 2=下载(安装) 3=文档(证书) */
		private static void drawGlyph(Graphics2D g, int kind, int cx, int cy) {
			int s = ICON_SIZE / 2;
			if (kind == IC_UPGRADE) {
				g.drawLine(cx, cy + s - 1, cx, cy - s + 1);
				g.drawLine(cx - 7, cy - 2, cx, cy - s + 1);
				g.drawLine(cx + 7, cy - 2, cx, cy - s + 1);
			} else if (kind == IC_INSTALL) {
				g.drawLine(cx, cy - s + 1, cx, cy + 5);
				g.drawLine(cx - 7, cy - 1, cx, cy + 5);
				g.drawLine(cx + 7, cy - 1, cx, cy + 5);
				g.drawLine(cx - 10, cy + s, cx + 10, cy + s);
			} else {
				int rw = 16;
				int rh = 20;
				g.drawRoundRect(cx - rw / 2, cy - rh / 2, rw, rh, 4, 4);
				g.drawLine(cx - 5, cy - 3, cx + 5, cy - 3);
				g.drawLine(cx - 5, cy + 2, cx + 5, cy + 2);
				g.drawLine(cx - 5, cy + 7, cx + 1, cy + 7);
			}
		}

		private static Color brighten(Color c, float amount) {
			int r = (int) (c.getRed() + (255 - c.getRed()) * amount);
			int g = (int) (c.getGreen() + (255 - c.getGreen()) * amount);
			int b = (int) (c.getBlue() + (255 - c.getBlue()) * amount);
			return new Color(Math.min(255, r), Math.min(255, g), Math.min(255, b));
		}
	}
}
