package com.nginxui.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import com.nginxui.ext.NginxInstanceEntry;
import com.nginxui.utils.NginxCertReplaceManager;
import com.nginxui.utils.NginxCertReplaceManager.CertEntry;
import com.nginxui.utils.NginxCertReplaceManager.CertInfo;
import com.nginxui.utils.NginxCertReplaceManager.CertItem;
import com.nginxui.utils.NginxDashboardUtils;
import com.nginxui.utils.NginxUtils;

import cn.hutool.core.util.StrUtil;

/**
 * 绑定关系: 以证书为维度的汇总视图(参考 nginxUI 绑定关系).
 *
 *  - 每个证书一行, 展示证书路径 / 文件状态 / 到期时间 / 被引用次数;
 *  - 展开后列出引用该证书的实例、server_name、端口与来源配置文件;
 *  - 引用次数按 server 块统计, 同一证书被多个端口复用时成组展示.
 */
public class CertRelationPanel extends JPanel {

	private static final long serialVersionUID = 1L;

	private static final String[] COLUMNS = { "", "域名", "证书路径", "文件状态", "到期时间", "引用次数" };
	private static final int C_EXPAND = 0;
	private static final int C_DOMAIN = 1;
	private static final int C_PATH = 2;
	private static final int C_STATE = 3;
	private static final int C_EXPIRY = 4;
	private static final int C_COUNT = 5;

	private final MainFrame owner;
	private final NginxCertReplaceManager scanner;

	private JLabel lblHint;
	private JLabel lblSummary;
	private JButton btnRefresh;
	private JTable table;
	private RelationModel model;

	private final List<Row> rows = new ArrayList<Row>();
	private List<Group> groups = new ArrayList<Group>();
	/** 已折叠的证书分组(默认全部展开) */
	private final Set<String> collapsed = new LinkedHashSet<String>();
	private final Map<String, Boolean> running = new HashMap<String, Boolean>();
	private List<CertEntry> scanned = new ArrayList<CertEntry>();
	private int instanceCount;
	private boolean busy;

	public CertRelationPanel(MainFrame owner) {
		this.owner = owner;
		this.scanner = new NginxCertReplaceManager(new File(owner.appHome()));
		buildUi();
		updateSummary();
	}

	// ==================== 界面 ====================

	private void buildUi() {
		setLayout(new BorderLayout(0, 6));
		setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
		add(buildTop(), BorderLayout.NORTH);
		add(buildTable(), BorderLayout.CENTER);
	}

	private JPanel buildTop() {
		JPanel p = new JPanel(new BorderLayout(0, 4));
		p.setOpaque(false);

		JPanel head = new JPanel(new BorderLayout());
		head.setOpaque(false);
		lblSummary = new JLabel(" ");
		lblSummary.setFont(Theme.BODY_BOLD);
		lblSummary.setForeground(Theme.TEXT);
		head.add(lblSummary, BorderLayout.WEST);

		JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		right.setOpaque(false);
		btnRefresh = Theme.toolButton("刷新");
		btnRefresh.setToolTipText("重新扫描所有实例配置, 刷新证书引用关系");
		btnRefresh.addActionListener(e -> refresh());
		right.add(btnRefresh);
		head.add(right, BorderLayout.EAST);
		p.add(head, BorderLayout.NORTH);

		lblHint = new JLabel("实时汇总所有纳管实例配置中声明的 SSL 证书，展开行可查看引用该证书的实例与 server 块");
		lblHint.setFont(Theme.SMALL);
		lblHint.setForeground(Theme.TEXT_MUTED);
		p.add(lblHint, BorderLayout.SOUTH);
		return p;
	}

	private JScrollPane buildTable() {
		model = new RelationModel();
		table = new JTable(model);
		table.setRowHeight(30);
		table.setFillsViewportHeight(true);
		table.setShowGrid(false);
		table.setIntercellSpacing(new Dimension(0, 0));
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.getTableHeader().setReorderingAllowed(false);
		table.setDefaultRenderer(Object.class, new RelationRenderer());
		// 固定列宽 + 横向滚动: 证书路径完整展示, 不因窗口宽度被压缩截断
		table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
		int[] widths = { 34, 220, 510, 88, 200, 80 };
		for (int i = 0; i < widths.length; i++) {
			table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
		}
		table.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				int view = table.rowAtPoint(e.getPoint());
				if (view < 0 || view >= rows.size()) {
					return;
				}
				Row row = rows.get(view);
				if (row.group != null) {
					toggle(row.group);
				}
			}
		});
		JScrollPane scroll = new JScrollPane(table);
		scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
		return scroll;
	}

	// ==================== 刷新 ====================

	public void refresh() {
		if (busy) {
			return;
		}
		busy = true;
		Theme.setEnabled(btnRefresh, false, "正在扫描实例证书，请稍候");
		setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
		lblSummary.setText("正在扫描实例证书…");
		final List<NginxInstanceEntry> entries = owner.registryEntries();
		new SwingWorker<Payload, Void>() {
			@Override
			protected Payload doInBackground() {
				Payload p = new Payload();
				p.entries = scanner.scanAll(entries);
				p.running = new HashMap<String, Boolean>();
				try {
					NginxDashboardUtils.LiveIndex live =
							NginxDashboardUtils.buildLiveIndex(NginxUtils.detectAll().getInstances());
					for (NginxInstanceEntry entry : entries) {
						p.running.put(StrUtil.nullToEmpty(entry.getId()), live.matches(entry));
					}
				} catch (Exception ignored) {
				}
				p.instanceCount = entries.size();
				return p;
			}

			@Override
			protected void done() {
				try {
					Payload p = get();
					scanned = p.entries;
					running.clear();
					running.putAll(p.running);
					instanceCount = p.instanceCount;
				} catch (Exception ex) {
					scanned = new ArrayList<CertEntry>();
				}
				busy = false;
				Theme.setEnabled(btnRefresh, true, null);
				setCursor(Cursor.getDefaultCursor());
				buildGroups();
				rebuildRows();
				updateSummary();
			}
		}.execute();
	}

	private void buildGroups() {
		Map<String, Group> map = new LinkedHashMap<String, Group>();
		for (CertEntry ce : scanned) {
			if (ce == null || ce.item == null) {
				continue;
			}
			CertItem item = ce.item;
			String key = StrUtil.nullToEmpty(item.pemPath) + "|" + StrUtil.nullToEmpty(item.keyPath);
			Group g = map.get(key);
			if (g == null) {
				g = new Group(key, item);
				map.put(key, g);
			}
			if (g.info == null) {
				g.info = item.info;
			}
			String domain = domainOf(item);
			if (StrUtil.isNotEmpty(domain) && !g.domains.contains(domain)) {
				g.domains.add(domain);
			}
			g.refs.add(ce);
		}
		groups = new ArrayList<Group>(map.values());
		groups.sort(new Comparator<Group>() {
			@Override
			public int compare(Group a, Group b) {
				long da = a.info == null ? Long.MAX_VALUE : a.info.daysLeft;
				long db = b.info == null ? Long.MAX_VALUE : b.info.daysLeft;
				if (da != db) {
					return da < db ? -1 : 1;
				}
				return a.domainText().compareToIgnoreCase(b.domainText());
			}
		});
	}

	private void rebuildRows() {
		rows.clear();
		for (Group g : groups) {
			rows.add(Row.group(g));
			if (!collapsed.contains(g.key)) {
				for (CertEntry ce : g.refs) {
					rows.add(Row.child(g, ce));
				}
			}
		}
		model.fireTableDataChanged();
	}

	private void toggle(Group g) {
		if (!collapsed.remove(g.key)) {
			collapsed.add(g.key);
		}
		rebuildRows();
		int row = 0;
		for (int i = 0; i < rows.size(); i++) {
			if (rows.get(i).group == g && rows.get(i).ce == null) {
				row = i;
				break;
			}
		}
		table.setRowSelectionInterval(row, row);
	}

	private void updateSummary() {
		int refs = 0;
		int missing = 0;
		for (Group g : groups) {
			refs += g.refs.size();
			if (!g.pemExists) {
				missing++;
			}
		}
		StringBuilder sb = new StringBuilder();
		sb.append("纳管实例 ").append(instanceCount).append(" 个，证书 ").append(groups.size())
				.append(" 张，引用 ").append(refs).append(" 处");
		if (missing > 0) {
			sb.append("，文件缺失 ").append(missing).append(" 张");
		}
		lblSummary.setText(sb.toString());
	}

	// ==================== 数据模型 ====================

	private static String domainOf(CertItem item) {
		if (item == null) {
			return "";
		}
		if (StrUtil.isNotEmpty(item.domain)) {
			return item.domain;
		}
		if (item.info != null && StrUtil.isNotEmpty(item.info.cn())) {
			return item.info.cn();
		}
		return "(未声明 server_name)";
	}

	private static String instanceName(NginxInstanceEntry entry) {
		if (entry == null) {
			return "(未知实例)";
		}
		return StrUtil.emptyToDefault(entry.getName(),
				StrUtil.emptyToDefault(entry.getConfPath(), entry.getId()));
	}

	/** 实例单元格: 名称 + 运行状态徽标 */
	private String instanceCell(CertEntry ce) {
		String name = instanceName(ce.instance);
		Boolean run = ce.instance == null ? null
				: running.get(StrUtil.nullToEmpty(ce.instance.getId()));
		if (run == null) {
			return escapeHtml(name);
		}
		return "<html>" + escapeHtml(name) + " <font color='" + (run ? "#16A34A" : "#DC2626") + "'>"
				+ (run ? "运行中" : "已停止") + "</font></html>";
	}

	private static String escapeHtml(String s) {
		return StrUtil.nullToEmpty(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static String expiryText(CertInfo info) {
		if (info == null) {
			return "无法解析";
		}
		if (info.expired) {
			return info.notAfterText() + "（已过期 " + Math.abs(info.daysLeft) + " 天）";
		}
		return info.notAfterText() + "（剩余 " + info.daysLeft + " 天）";
	}

	/** 一个证书分组 */
	private static final class Group {
		final String key;
		final String pemPath;
		final String keyPath;
		final boolean pemExists;
		CertInfo info;
		final List<String> domains = new ArrayList<String>();
		final List<CertEntry> refs = new ArrayList<CertEntry>();

		Group(String key, CertItem item) {
			this.key = key;
			this.pemPath = StrUtil.nullToEmpty(item.pemPath);
			this.keyPath = StrUtil.nullToEmpty(item.keyPath);
			this.pemExists = item.pemExists;
			this.info = item.info;
		}

		/** 分组行域名: 优先取证书信息中的域名(CN + SAN), 证书无法解析时回退到 server_name */
		String domainText() {
			if (info != null && !info.domains.isEmpty()) {
				return String.join(", ", info.domains);
			}
			return domains.isEmpty() ? "(未声明 server_name)" : String.join(", ", domains);
		}
	}

	/** 表格行: 分组行或引用明细行 */
	private static final class Row {
		final Group group;
		final CertEntry ce;

		private Row(Group group, CertEntry ce) {
			this.group = group;
			this.ce = ce;
		}

		static Row group(Group g) {
			return new Row(g, null);
		}

		static Row child(Group g, CertEntry ce) {
			return new Row(g, ce);
		}

		boolean isGroup() {
			return ce == null;
		}
	}

	private final class RelationModel extends AbstractTableModel {
		private static final long serialVersionUID = 1L;

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
		public Object getValueAt(int rowIndex, int columnIndex) {
			Row row = rows.get(rowIndex);
			Group g = row.group;
			if (row.isGroup()) {
				switch (columnIndex) {
					case C_EXPAND:
						return "";
					case C_DOMAIN:
						return g.domainText();
					case C_PATH:
						return g.pemPath;
					case C_STATE:
						return g.pemExists ? "存在" : "缺失";
					case C_EXPIRY:
						return expiryText(g.info);
					case C_COUNT:
						return g.refs.size();
					default:
						return "";
				}
			}
			CertEntry ce = row.ce;
			CertItem item = ce.item;
			switch (columnIndex) {
				case C_EXPAND:
					return "";
				case C_DOMAIN:
					return instanceCell(ce);
				case C_PATH:
					return domainOf(item);
				case C_STATE:
					return item == null ? "" : item.portsText();
				case C_EXPIRY:
					return CertManagerPage.shortPath(item == null || item.sourceConf == null
							? "" : item.sourceConf.getAbsolutePath());
				default:
					return "";
			}
		}

		@Override
		public boolean isCellEditable(int rowIndex, int columnIndex) {
			return false;
		}
	}

	private final class RelationRenderer extends DefaultTableCellRenderer {
		private static final long serialVersionUID = 1L;

		@Override
		public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected,
				boolean hasFocus, int row, int column) {
			super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
			int modelRow = t.convertRowIndexToModel(row);
			Row data = modelRow >= 0 && modelRow < rows.size() ? rows.get(modelRow) : null;
			setFont(Theme.BODY);
			setForeground(isSelected ? Theme.TEXT : Theme.TEXT);
			setToolTipText(null);
			setIcon(null);
			if (column == C_EXPAND) {
				// 展开列只用绘制的三角图标, 不依赖字体是否包含 ▼/▶ 字形
				setText("");
			}
			setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
			if (data == null) {
				return this;
			}
			Group g = data.group;
			if (!isSelected) {
				// 分组行用浅底区分, 子行保持表格底色
				setBackground(data.isGroup() ? Theme.BG : t.getBackground());
			}
			if (column == C_EXPAND) {
				setHorizontalAlignment(CENTER);
			} else if (column == C_COUNT) {
				setHorizontalAlignment(RIGHT);
			} else {
				setHorizontalAlignment(LEFT);
			}
			if (data.isGroup()) {
				setFont(column == C_DOMAIN ? Theme.BODY_BOLD : Theme.BODY);
				if (column == C_EXPAND) {
					setForeground(Theme.TEXT_MUTED);
					setIcon(collapsed.contains(g.key) ? ARROW_RIGHT : ARROW_DOWN);
				} else if (column == C_STATE) {
					setForeground(g.pemExists ? Theme.SUCCESS : Theme.DANGER);
				} else if (column == C_EXPIRY && g.info != null && g.info.expired) {
					setForeground(Theme.DANGER);
				} else if (column == C_COUNT) {
					setForeground(Theme.TEXT_MUTED);
				} else {
					setForeground(column == C_PATH ? Theme.TEXT_MUTED : Theme.TEXT);
				}
				if (column == C_PATH) {
					setToolTipText(g.pemPath + (StrUtil.isEmpty(g.keyPath) ? "" : "  |  " + g.keyPath));
				} else if (column == C_DOMAIN) {
					setToolTipText(g.domainText());
				}
			} else {
				setForeground(column == C_DOMAIN ? Theme.TEXT : Theme.TEXT_MUTED);
				setBorder(BorderFactory.createEmptyBorder(0, 18, 0, 4));
				if (column == C_EXPAND) {
					setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
				}
				if (column == C_DOMAIN) {
					setFont(Theme.BODY);
				}
				if (column == C_EXPIRY) {
					CertItem item = data.ce.item;
					if (item != null && item.sourceConf != null) {
						setToolTipText(item.sourceConf.getAbsolutePath());
					}
				}
			}
			return this;
		}
	}

	private static final class Payload {
		List<CertEntry> entries = new ArrayList<CertEntry>();
		Map<String, Boolean> running = new HashMap<String, Boolean>();
		int instanceCount;
	}

	/** 展开/收起箭头: 用绘制三角形代替字符, 避免字体缺少 ▼/▶ 字形时显示为方块 */
	private static final class ArrowIcon implements Icon {
		private final boolean down;

		ArrowIcon(boolean down) {
			this.down = down;
		}

		@Override
		public int getIconWidth() {
			return 9;
		}

		@Override
		public int getIconHeight() {
			return 9;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y) {
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(c == null ? Theme.TEXT_MUTED : c.getForeground());
				if (down) {
					g2.fillPolygon(new int[] { x, x + 9, x + 4 }, new int[] { y + 2, y + 2, y + 8 }, 3);
				} else {
					g2.fillPolygon(new int[] { x + 2, x + 8, x + 2 }, new int[] { y, y + 4, y + 9 }, 3);
				}
			} finally {
				g2.dispose();
			}
		}
	}

	private static final Icon ARROW_DOWN = new ArrowIcon(true);
	private static final Icon ARROW_RIGHT = new ArrowIcon(false);
}

