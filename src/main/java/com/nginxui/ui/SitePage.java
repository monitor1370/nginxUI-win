package com.nginxui.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.DefaultListCellRenderer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import com.nginxui.ext.NginxInstanceEntry;
import com.nginxui.utils.ErrorReporter;
import com.nginxui.utils.NginxCertReplaceManager;
import com.nginxui.utils.NginxCertReplaceManager.CertInfo;
import com.nginxui.utils.NginxCertReplaceManager.CertItem;
import com.nginxui.utils.NginxSite;
import com.nginxui.utils.NginxSiteScanner;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;

/**
 * 站点管理: 以站点(server 块 = 域名 + 监听端口)为单位查看 HTTP/HTTPS 状态.
 *
 *  - 站点是 HTTPS 配置的主维度, 端口只作为站点属性与冲突检测点;
 *  - HTTP 站点提供「启用 HTTPS」入口, 已是 HTTPS 的站点提供「更换证书」;
 *  - 证书详情与到期状态复用证书模块的解析与判定.
 */
public class SitePage extends JPanel {

	private static final String F_ALL = "全部站点";
	private static final String F_HTTP = "仅 HTTP";
	private static final String F_HTTPS = "仅 HTTPS";
	private static final String F_CERT = "证书异常";
	private static final String[] FILTERS = { F_ALL, F_HTTP, F_HTTPS, F_CERT };

	private static final String[] COLUMNS = { "站点域名", "监听端口", "HTTPS", "证书状态", "到期时间",
			"来源配置", "操作" };
	private static final int COL_DOMAIN = 0;
	private static final int COL_PORTS = 1;
	private static final int COL_HTTPS = 2;
	private static final int COL_CERT = 3;
	private static final int COL_EXPIRY = 4;
	private static final int COL_CONF = 5;
	private static final int COL_ACTION = 6;

	private static final String CARD_TABLE = "table";
	private static final String CARD_EMPTY = "empty";

	private static final Object ALL_INSTANCES = new Object() {
		@Override
		public String toString() {
			return "全部实例";
		}
	};

	private final MainFrame owner;
	private final NginxSiteScanner scanner = new NginxSiteScanner();

	private JComboBox<Object> instanceCombo;
	private JTextField txtSearch;
	private JComboBox<String> cbFilter;
	private JLabel lblStats;
	private JButton btnRefresh;
	private JButton btnEnable;
	private JButton btnReplace;
	private JButton btnViewConf;

	private JPanel centerCards;
	private CardLayout centerLayout;
	private JLabel lblEmptyTitle;
	private JLabel lblEmptyHint;

	private JTable table;
	private SiteTableModel tableModel;
	private JTextArea taDetail;

	private final List<NginxSite> sites = new ArrayList<NginxSite>();
	private final List<Row> rows = new ArrayList<Row>();
	private String scanError;
	private boolean busy;

	public SitePage(MainFrame owner) {
		this.owner = owner;
		setOpaque(true);
		setBackground(Theme.BG);
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(14, Theme.GAP_L, Theme.GAP_M, Theme.GAP_L));
		buildUi();
	}

	/** 每次进入页面重新扫描当前实例的站点 */
	public void onShown() {
		refreshInstanceCombo();
		refreshSites();
	}

	// ==================== 界面 ====================

	private void buildUi() {
		JPanel content = new JPanel(new BorderLayout());
		content.setOpaque(false);
		content.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));

		JPanel top = new JPanel(new BorderLayout(0, 6));
		top.setOpaque(false);
		top.add(buildToolbar(), BorderLayout.NORTH);
		top.add(buildStatsBar(), BorderLayout.SOUTH);
		content.add(top, BorderLayout.NORTH);

		JPanel body = new JPanel(new BorderLayout(0, 8));
		body.setOpaque(false);
		body.add(buildCenter(), BorderLayout.CENTER);
		body.add(buildDetailPanel(), BorderLayout.SOUTH);
		content.add(body, BorderLayout.CENTER);

		add(buildHeader(), BorderLayout.NORTH);
		add(content, BorderLayout.CENTER);
	}

	private JPanel buildHeader() {
		PageHeader header = new PageHeader("站点管理",
				"以站点（域名 + 监听端口）为单位管理 HTTP/HTTPS；证书为全局资产，按站点启用与更换");
		header.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));

		btnEnable = Theme.primaryButton("启用 HTTPS…");
		btnEnable.setToolTipText("为选中的 HTTP 站点补充 SSL 监听与证书，执行前自动备份配置");
		Theme.setEnabled(btnEnable, false, "未选择站点：先在下方列表中选择一个站点");
		btnEnable.addActionListener(e -> openEnableDialog());

		btnReplace = Theme.secondaryButton("更换证书…");
		btnReplace.setToolTipText("替换选中 HTTPS 站点正在使用的证书");
		Theme.setEnabled(btnReplace, false, "未选择站点：先在下方列表中选择一个站点");
		btnReplace.addActionListener(e -> openReplaceDialog());

		btnViewConf = Theme.toolButton("查看配置");
		Theme.setEnabled(btnViewConf, false, "未选择站点：先在下方列表中选择一个站点");
		btnViewConf.addActionListener(e -> viewConfig());

		header.addAction(btnViewConf);
		header.addAction(btnReplace);
		header.addAction(btnEnable);
		return header;
	}

	private JPanel buildToolbar() {
		JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
		bar.setOpaque(false);

		JLabel lblInstance = new JLabel("实例:");
		lblInstance.setFont(Theme.BODY);
		instanceCombo = new JComboBox<Object>();
		instanceCombo.setFont(Theme.BODY);
		instanceCombo.setPreferredSize(new Dimension(190, Theme.BUTTON_HEIGHT));
		instanceCombo.setRenderer(new DefaultListCellRenderer() {
			private static final long serialVersionUID = 1L;
			@Override
			public java.awt.Component getListCellRendererComponent(JList<?> list, Object value,
					int index, boolean isSelected, boolean cellHasFocus) {
				super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				if (value == ALL_INSTANCES) {
					setText("全部实例");
				} else if (value instanceof MainFrame.InstanceRow) {
					setText(MainFrame.InstanceRow.displayName(((MainFrame.InstanceRow) value).entry));
				}
				return this;
			}
		});
		instanceCombo.addActionListener(e -> refreshSites());

		JLabel lblSearch = new JLabel("搜索:");
		lblSearch.setFont(Theme.BODY);
		txtSearch = new JTextField(16);
		txtSearch.setToolTipText("按域名、端口或配置文件名过滤站点");
		txtSearch.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				applyFilter();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				applyFilter();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				applyFilter();
			}
		});

		JLabel lblFilter = new JLabel("状态:");
		lblFilter.setFont(Theme.BODY);
		cbFilter = new JComboBox<String>(FILTERS);
		cbFilter.setFont(Theme.BODY);
		cbFilter.addActionListener(e -> applyFilter());

		btnRefresh = Theme.toolButton("刷新");
		btnRefresh.setToolTipText("重新解析选中实例(或全部实例)配置中的 server 块");
		btnRefresh.addActionListener(e -> refreshSites());

		bar.add(lblInstance);
		bar.add(instanceCombo);
		bar.add(lblSearch);
		bar.add(txtSearch);
		bar.add(lblFilter);
		bar.add(cbFilter);
		bar.add(btnRefresh);
		return bar;
	}

	private JPanel buildStatsBar() {
		lblStats = new JLabel(" ");
		lblStats.setFont(Theme.SMALL);
		lblStats.setForeground(Theme.TEXT_MUTED);
		JPanel p = new JPanel(new BorderLayout());
		p.setOpaque(false);
		p.add(lblStats, BorderLayout.WEST);
		return p;
	}

	private Component buildCenter() {
		tableModel = new SiteTableModel();
		table = new JTable(tableModel);
		table.setRowHeight(42);
		table.setFillsViewportHeight(true);
		table.setShowGrid(false);
		table.setIntercellSpacing(new Dimension(0, 0));
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.getTableHeader().setReorderingAllowed(false);
		table.setDefaultRenderer(Object.class, new SiteCellRenderer());
		table.getSelectionModel().addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting()) {
				updateDetail();
			}
		});
		table.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				int view = table.rowAtPoint(e.getPoint());
				int column = table.columnAtPoint(e.getPoint());
				int modelColumn = column < 0 ? -1 : table.convertColumnIndexToModel(column);
				if (view < 0) {
					return;
				}
				table.setRowSelectionInterval(view, view);
				if (modelColumn == COL_ACTION) {
					Row row = selectedRow();
					if (row != null) {
						if (row.site.hasHttps() && row.site.cert != null) {
							openReplaceDialog();
						} else {
							openEnableDialog();
						}
					}
					return;
				}
				if (e.getClickCount() == 2) {
					Row row = selectedRow();
					if (row != null && row.site.hasHttps() && row.site.cert != null) {
						openReplaceDialog();
					}
				}
			}
		});
		table.addMouseMotionListener(new MouseAdapter() {
			@Override
			public void mouseMoved(MouseEvent e) {
				int column = table.columnAtPoint(e.getPoint());
				int modelColumn = column < 0 ? -1 : table.convertColumnIndexToModel(column);
				table.setCursor(modelColumn == COL_ACTION
						? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
						: Cursor.getDefaultCursor());
			}
		});

		int[] widths = { 190, 150, 90, 110, 150, 220, 110 };
		for (int i = 0; i < widths.length; i++) {
			table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
		}
		table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);

		JScrollPane tableScroll = new JScrollPane(table);
		tableScroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));

		centerCards = new JPanel(centerLayout = new CardLayout());
		centerCards.add(tableScroll, CARD_TABLE);
		centerCards.add(buildEmptyPanel(), CARD_EMPTY);
		return centerCards;
	}

	private JPanel buildDetailPanel() {
		taDetail = new JTextArea();
		taDetail.setEditable(false);
		taDetail.setFont(Theme.BODY);
		taDetail.setBackground(new Color(252, 252, 252));
		taDetail.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		JScrollPane scroll = new JScrollPane(taDetail);
		scroll.setBorder(BorderFactory.createTitledBorder(
				BorderFactory.createLineBorder(Theme.BORDER), "站点详情"));

		JPanel p = new JPanel(new BorderLayout());
		p.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		p.setPreferredSize(new Dimension(0, 150));
		p.add(scroll, BorderLayout.CENTER);
		return p;
	}

	private JPanel buildEmptyPanel() {
		JPanel p = new JPanel(new java.awt.GridBagLayout());
		p.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
		p.setBackground(Theme.SURFACE);

		lblEmptyTitle = new JLabel("暂未发现站点");
		lblEmptyTitle.setFont(Theme.TITLE);
		lblEmptyTitle.setForeground(Theme.TEXT);

		lblEmptyHint = new JLabel("请确认所选实例的配置中已声明 server 块。");
		lblEmptyHint.setFont(Theme.SMALL);
		lblEmptyHint.setForeground(Theme.TEXT_MUTED);

		java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
		c.gridx = 0;
		c.gridy = 0;
		c.insets = new Insets(0, 0, 6, 0);
		p.add(lblEmptyTitle, c);
		c.gridy = 1;
		c.insets = new Insets(0, 0, 0, 0);
		p.add(lblEmptyHint, c);
		return p;
	}

	// ==================== 数据 ====================

	private void refreshInstanceCombo() {
		if (instanceCombo == null) {
			return;
		}
		Object prev = instanceCombo.getSelectedItem();
		instanceCombo.removeAllItems();
		instanceCombo.addItem(ALL_INSTANCES);
		for (MainFrame.InstanceRow r : owner.instanceRows()) {
			instanceCombo.addItem(r);
		}
		// 保持上次选择: 优先 ALL_INSTANCES(默认), 其次上次选的实例, 再其次 ALL
		if (prev == null) {
			instanceCombo.setSelectedItem(ALL_INSTANCES);
		} else {
			instanceCombo.setSelectedItem(prev);
			if (instanceCombo.getSelectedItem() != prev) {
				instanceCombo.setSelectedItem(ALL_INSTANCES);
			}
		}
	}

	private void refreshSites() {
		if (busy) {
			return;
		}
		Object sel = instanceCombo == null ? null : instanceCombo.getSelectedItem();
		if (sel == null || sel == ALL_INSTANCES) {
			// 全部实例: 合并扫描
			List<MainFrame.InstanceRow> rows = owner.instanceRows();
			if (rows.isEmpty()) {
				sites.clear();
				applyFilter();
				return;
			}
			scanError = null;
			setBusy(true);
			lblStats.setText("正在扫描全部实例配置中的站点…");
			new SwingWorker<List<NginxSite>, Void>() {
				@Override
				protected List<NginxSite> doInBackground() {
					List<NginxSite> merged = new ArrayList<NginxSite>();
					for (MainFrame.InstanceRow r : rows) {
						try {
							// scanner.scan(entry) 内部会自动填 site.instance = entry,
							// 每个站点的来源实例天然正确
							merged.addAll(scanner.scan(r.entry));
						} catch (Exception ignore) {
							// 单个实例扫描失败不影响整体, 继续扫下一个
						}
					}
					return merged;
				}

				@Override
				protected void done() {
					try {
						sites.clear();
						sites.addAll(get());
					} catch (Exception ex) {
						sites.clear();
						scanError = ErrorReporter.friendlyReason(ex);
					} finally {
						setBusy(false);
						applyFilter();
					}
				}
			}.execute();
			return;
		}
		// 单实例扫描
		MainFrame.InstanceRow row = (MainFrame.InstanceRow) sel;
		NginxInstanceEntry entry = row.entry;
		if (entry == null) {
			sites.clear();
			applyFilter();
			return;
		}
		scanError = null;
		setBusy(true);
		lblStats.setText("正在扫描实例配置中的站点…");
		new SwingWorker<List<NginxSite>, Void>() {
			@Override
			protected List<NginxSite> doInBackground() {
				return scanner.scan(entry);
			}

			@Override
			protected void done() {
				try {
					sites.clear();
					sites.addAll(get());
				} catch (Exception ex) {
					sites.clear();
					scanError = ErrorReporter.friendlyReason(ex);
				} finally {
					setBusy(false);
					applyFilter();
				}
			}
		}.execute();
	}

	private void setBusy(boolean value) {
		busy = value;
		if (btnRefresh != null) {
			Theme.setEnabled(btnRefresh, !value, value ? "正在扫描站点，请稍候" : null);
		}
		setCursor(Cursor.getPredefinedCursor(value ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));
	}

	private void applyFilter() {
		if (txtSearch == null || cbFilter == null) {
			return;
		}
		String keyword = txtSearch.getText().trim().toLowerCase(Locale.ROOT);
		String filter = (String) cbFilter.getSelectedItem();
		rows.clear();
		for (NginxSite site : sites) {
			if (!matchFilter(site, filter)) {
				continue;
			}
			Row row = new Row(site);
			if (!keyword.isEmpty() && !row.searchText().contains(keyword)) {
				continue;
			}
			rows.add(row);
		}
		tableModel.fireTableDataChanged();
		updateStats();
		if (!rows.isEmpty()) {
			centerLayout.show(centerCards, CARD_TABLE);
			table.setRowSelectionInterval(0, 0);
		} else {
			showEmptyState();
		}
		updateDetail();
	}

	private boolean matchFilter(NginxSite site, String filter) {
		if (StrUtil.isEmpty(filter) || F_ALL.equals(filter)) {
			return true;
		}
		if (F_HTTP.equals(filter)) {
			return !site.hasHttps();
		}
		if (F_HTTPS.equals(filter)) {
			return site.hasHttps();
		}
		return site.hasHttps() && certStatus(site) != NginxCertReplaceManager.ST_OK;
	}

	private void showEmptyState() {
		centerLayout.show(centerCards, CARD_EMPTY);
		table.clearSelection();
		if (scanError != null) {
			lblEmptyTitle.setText("扫描失败");
			lblEmptyHint.setText("无法读取实例配置: " + scanError);
		} else if (sites.isEmpty()) {
			lblEmptyTitle.setText("暂未发现站点");
			lblEmptyHint.setText("请确认所选实例的配置中已声明 server 块。");
		} else {
			lblEmptyTitle.setText("没有匹配的站点");
			lblEmptyHint.setText("试试调整关键词或切换状态筛选。");
		}
	}

	private void updateStats() {
		int total = sites.size();
		int https = 0;
		int http = 0;
		int certIssues = 0;
		for (NginxSite site : sites) {
			if (site.hasHttps()) {
				https++;
			} else {
				http++;
			}
			if (certStatus(site) != NginxCertReplaceManager.ST_OK && site.hasHttps()) {
				certIssues++;
			}
		}
		StringBuilder sb = new StringBuilder("<html>");
		sb.append("共 <b>").append(total).append("</b> 个站点　");
		sb.append("<span style='color:#2563EB'>HTTPS ").append(https).append("</span>　");
		sb.append("<span style='color:#6B7280'>HTTP ").append(http).append("</span>");
		if (certIssues > 0) {
			sb.append("　<span style='color:#DC2626'>证书异常 ").append(certIssues).append("</span>");
		}
		sb.append("</html>");
		lblStats.setText(sb.toString());
	}

	private void updateDetail() {
		Row row = selectedRow();
		if (row == null) {
			taDetail.setText("在上方列表中选择一个站点以查看详情。");
			taDetail.setForeground(Theme.TEXT_MUTED);
		} else {
			taDetail.setForeground(Theme.TEXT);
			taDetail.setText(detailText(row.site));
			taDetail.setCaretPosition(0);
		}
		NginxSite site = row == null ? null : row.site;
		boolean canEnable = site != null && (!site.hasHttps() || site.cert == null);
		boolean canReplace = site != null && site.cert != null;
		String busyReason = busy ? "正在扫描站点，请稍候" : null;
		Theme.setEnabled(btnEnable, canEnable && !busy, busyReason != null ? busyReason
				: (row == null ? "未选择站点：先在下方列表中选择一个站点"
						: (canEnable ? null : "该站点已启用 HTTPS 且证书已就绪，无需重复启用")));
		Theme.setEnabled(btnReplace, canReplace && !busy, busyReason != null ? busyReason
				: (row == null ? "未选择站点：先在下方列表中选择一个站点"
						: (canReplace ? null : "该站点未声明 HTTPS 证书，无证书可更换")));
		Theme.setEnabled(btnViewConf, site != null,
				row == null ? "未选择站点：先在下方列表中选择一个站点" : null);
		btnEnable.setText(site != null && site.hasHttps() ? "补全 HTTPS 证书…" : "启用 HTTPS…");
	}

	private String detailText(NginxSite site) {
		StringBuilder sb = new StringBuilder();
		sb.append("实例: ").append(instanceName(site.instance)).append('\n');
		sb.append("站点域名: ").append(site.displayDomain()).append("　server_name: ")
				.append(site.serverNamesText()).append('\n');
		sb.append("监听: ").append(site.portsWithSslText()).append("　HTTPS: ")
				.append(site.hasHttps() ? "已启用" : "未启用").append('\n');
		sb.append("来源配置: ").append(StrUtil.nullToEmpty(
				site.sourceConf == null ? "" : site.sourceConf.getAbsolutePath())).append('\n');
		CertItem cert = site.cert;
		if (cert == null) {
			sb.append("证书: 配置中未声明 ssl_certificate");
		} else {
			sb.append("证书: ").append(StrUtil.emptyToDefault(cert.pemPath, "(未识别)")
					+ (cert.pemExists ? "" : "  [文件缺失]")).append('\n');
			sb.append("私钥: ").append(StrUtil.isEmpty(cert.keyPath) ? "(配置未声明)"
					: cert.keyPath + (cert.keyExists ? "" : "  [文件缺失]")).append('\n');
			CertInfo info = cert.info;
			if (info == null) {
				sb.append("证书内容: 无法解析为 X.509 PEM 证书");
			} else {
				sb.append("状态: ").append(NginxCertReplaceManager.statusText(
						NginxCertReplaceManager.statusOf(cert)));
				if (!info.expired) {
					sb.append("（剩余 ").append(info.daysLeft).append(" 天）");
				}
				sb.append('\n');
				sb.append("有效期: ").append(info.notBeforeText()).append(" ~ ")
						.append(info.notAfterText()).append('\n');
				sb.append("证书域名: ")
						.append(info.domains.isEmpty() ? "(无)" : String.join(", ", info.domains));
			}
		}
		return sb.toString();
	}

	private Row selectedRow() {
		int view = table.getSelectedRow();
		if (view < 0) {
			return null;
		}
		int model = table.convertRowIndexToModel(view);
		return model >= 0 && model < rows.size() ? rows.get(model) : null;
	}

	// ==================== 操作 ====================

	private void openEnableDialog() {
		Row row = selectedRow();
		if (row == null || busy) {
			return;
		}
		NginxInstanceEntry entry = row.site.instance;
		try {
			new EnableHttpsDialog(owner, entry, sites, row.site).setVisible(true);
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(this,
					ErrorReporter.uiText("打开 HTTPS 启用向导", ex, "请关闭后重试；若持续失败，请查看日志目录。"),
					"nginxUI", JOptionPane.ERROR_MESSAGE);
			return;
		}
		// 启用流程可能改写了配置, 回来后重新扫描
		if (!busy) {
			refreshSites();
		}
	}

	private void openReplaceDialog() {
		Row row = selectedRow();
		NginxSite site = row == null ? null : row.site;
		if (site == null || site.cert == null || busy) {
			return;
		}
		try {
			new CertReplaceDialog(owner, site.instance, site.cert.pemPath).setVisible(true);
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(this,
					ErrorReporter.uiText("打开证书替换窗口", ex, "请关闭后重试；若持续失败，请查看日志目录。"),
					"nginxUI", JOptionPane.ERROR_MESSAGE);
			return;
		}
		if (!busy) {
			refreshSites();
		}
	}

	private void viewConfig() {
		Row row = selectedRow();
		if (row == null || row.site.sourceConf == null) {
			return;
		}
		NginxSite site = row.site;
		String text;
		try {
			String all = FileUtil.readString(site.sourceConf, java.nio.charset.StandardCharsets.UTF_8);
			text = site.blockOpen >= 0 && site.blockClose < all.length()
					? all.substring(site.blockStart, site.blockClose + 1)
					: all;
		} catch (Exception e) {
			JOptionPane.showMessageDialog(this,
					ErrorReporter.uiText("读取配置", e, "请确认配置路径存在且当前账户有访问权限。"),
					"nginxUI", JOptionPane.ERROR_MESSAGE);
			return;
		}
		JTextArea area = new JTextArea(text);
		area.setEditable(false);
		area.setFont(Theme.MONO);
		JScrollPane scroll = new JScrollPane(area);
		scroll.setPreferredSize(new Dimension(760, 420));
		JOptionPane.showMessageDialog(this, scroll,
				"站点配置 - " + site.sourceConf.getAbsolutePath(), JOptionPane.PLAIN_MESSAGE);
	}

	// ==================== 表格模型 ====================

	private static final class Row {
		final NginxSite site;

		Row(NginxSite site) {
			this.site = site;
		}

		String searchText() {
			StringBuilder sb = new StringBuilder();
			sb.append(site.displayDomain()).append(' ');
			for (String name : site.serverNames) {
				sb.append(name).append(' ');
			}
			if (site.cert != null && site.cert.info != null) {
				for (String name : site.cert.info.domains) {
					sb.append(name).append(' ');
				}
			}
			sb.append(site.portsText()).append(' ');
			if (site.sourceConf != null) {
				sb.append(site.sourceConf.getName());
			}
			return sb.toString().toLowerCase(Locale.ROOT);
		}
	}

	private final class SiteTableModel extends AbstractTableModel {
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
			NginxSite site = row.site;
			CertItem cert = site.cert;
			CertInfo info = cert == null ? null : cert.info;
			switch (columnIndex) {
				case COL_DOMAIN:
					return site.displayDomain();
				case COL_PORTS:
					return site.portsWithSslText();
				case COL_HTTPS:
					return site.hasHttps() ? "已启用" : "未启用";
				case COL_CERT:
					return cert == null ? "—"
							: NginxCertReplaceManager.statusText(NginxCertReplaceManager.statusOf(cert));
				case COL_EXPIRY:
					if (info == null) {
						return "-";
					}
					return "<html>" + info.notAfterText() + "<br>"
							+ (info.expired ? "已过期 " + Math.abs(info.daysLeft) + " 天"
									: "剩余 " + info.daysLeft + " 天") + "</html>";
				case COL_CONF:
					return site.sourceConf == null ? "-"
							: CertManagerPage.shortPath(site.sourceConf.getAbsolutePath());
				case COL_ACTION:
					return site.hasHttps() && cert != null ? "更换证书" : "启用 HTTPS";
				default:
					return "";
			}
		}
	}

	private final class SiteCellRenderer extends DefaultTableCellRenderer {
		private static final long serialVersionUID = 1L;

		@Override
		public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected,
				boolean hasFocus, int row, int column) {
			super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
			int modelRow = t.convertRowIndexToModel(row);
			Row data = modelRow >= 0 && modelRow < rows.size() ? rows.get(modelRow) : null;
			setFont(Theme.BODY);
			setToolTipText(null);
			if (data == null) {
				return this;
			}
			NginxSite site = data.site;
			if (column == COL_HTTPS) {
				setFont(Theme.BODY_BOLD);
				setForeground(site.hasHttps() ? Theme.SUCCESS : Theme.TEXT_MUTED);
			} else if (column == COL_CERT) {
				int status = certStatus(site);
				setFont(Theme.BODY_BOLD);
				setForeground(certColor(status));
			} else if (column == COL_EXPIRY) {
				setForeground(certColor(certStatus(site)));
			} else if (column == COL_DOMAIN) {
				setFont(Theme.BODY_BOLD);
				setForeground(Theme.TEXT);
				// HTTPS 站点展示证书域名, 鼠标悬停时给出配置中的 server_name 便于核对
				if (!site.displayDomain().equals(site.domain())) {
					setToolTipText("<html>server_name: " + escape(site.serverNamesText())
							+ "<br>证书域名: " + escape(certDomainsText(site)) + "</html>");
				}
			} else if (column == COL_CONF) {
				setForeground(Theme.TEXT_MUTED);
				if (site.sourceConf != null) {
					setToolTipText(site.sourceConf.getAbsolutePath());
				}
			} else if (column == COL_ACTION) {
				setFont(Theme.BODY_BOLD);
				setForeground(Theme.PRIMARY);
				setToolTipText(site.hasHttps() ? "更换该站点使用的证书" : "为站点启用 HTTPS");
			} else {
				setForeground(Theme.TEXT);
			}
			return this;
		}
	}

	private static int certStatus(NginxSite site) {
		return site.cert == null ? NginxCertReplaceManager.ST_MISSING
				: NginxCertReplaceManager.statusOf(site.cert);
	}

	private static Color certColor(int status) {
		switch (status) {
			case NginxCertReplaceManager.ST_OK:
				return Theme.SUCCESS;
			case NginxCertReplaceManager.ST_EXPIRING:
			case NginxCertReplaceManager.ST_NOT_YET:
				return Theme.WARNING;
			default:
				return Theme.DANGER;
		}
	}

	private static String instanceName(NginxInstanceEntry entry) {
		if (entry == null) {
			return "(未知实例)";
		}
		return StrUtil.emptyToDefault(entry.getName(),
				StrUtil.emptyToDefault(entry.getConfPath(), entry.getId()));
	}

	/** 证书域名展示: 无证书或无法解析时为「(无)」 */
	private static String certDomainsText(NginxSite site) {
		if (site.cert == null || site.cert.info == null || site.cert.info.domains.isEmpty()) {
			return "(无)";
		}
		return String.join(", ", site.cert.info.domains);
	}

	private static String escape(String text) {
		return StrUtil.nullToEmpty(text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
