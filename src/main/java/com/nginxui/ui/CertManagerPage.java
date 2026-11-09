package com.nginxui.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import com.nginxui.ext.NginxInstanceEntry;
import com.nginxui.utils.CertificateStoreManager;
import com.nginxui.utils.ErrorReporter;
import com.nginxui.utils.NginxCertReplaceManager;
import com.nginxui.utils.NginxCertReplaceManager.CertEntry;
import com.nginxui.utils.NginxCertReplaceManager.CertInfo;
import com.nginxui.utils.NginxCertReplaceManager.CertItem;

import cn.hutool.core.util.StrUtil;

/**
 * 证书管理: 跨实例总览所有 SSL 证书, 按到期状态筛选, 一键进入证书替换.
 *
 *  - 汇总每个实例配置(递归 include)中声明的 ssl_certificate / ssl_certificate_key;
 *  - 状态优先级: 证书缺失 &gt; 无法解析 &gt; 已过期 &gt; 未生效 &gt; 即将到期(≤30 天) &gt; 正常;
 *  - 支持关键词搜索、状态筛选、查看详情、打开证书目录、复制路径、刷新;
 *  - 「更换证书…」复用 CertReplaceDialog, 并自动预选当前证书, 关闭后自动重新扫描.
 */
public class CertManagerPage extends JPanel {

	private static final String F_ALL = "全部证书";
	private static final String F_EXPIRING = "即将到期";
	private static final String F_EXPIRED = "已过期";
	private static final String F_MISSING = "证书缺失";
	private static final String F_INVALID = "无法解析";
	private static final String F_OK = "正常";
	private static final String[] FILTERS = { F_ALL, F_EXPIRING, F_EXPIRED, F_MISSING, F_INVALID, F_OK };

	/** 展示字段对齐 nginxUI 实例证书页: 域名 / server_name / 端口 / 证书路径 / 来源配置文件 / 证书状态 / 到期时间 / 操作 */
	private static final String[] COLUMNS = { "域名", "server_name", "端口", "证书路径", "来源配置文件", "证书状态",
			"到期时间", "操作" };
	private static final int COL_DOMAIN = 0;
	private static final int COL_SERVER_NAME = 1;
	private static final int COL_PORT = 2;
	private static final int COL_CERT = 3;
	private static final int COL_CONF = 4;
	private static final int COL_STATUS = 5;
	private static final int COL_EXPIRY = 6;
	private static final int COL_ACTION = 7;

	private static final String CARD_TABLE = "table";
	private static final String CARD_EMPTY = "empty";

	private final MainFrame owner;
	private final NginxCertReplaceManager manager;
	/** 证书资源库(导入/绑定/生命周期) */
	private final CertificateStoreManager store;
	private CertLibraryPanel library;
	private CertRelationPanel relation;
	private JTabbedPane tabs;
	private JSplitPane split;

	/** 扫描结果(全量) */
	private final List<CertEntry> allEntries = new ArrayList<CertEntry>();
	/** 当前筛选后的行 */
	private final List<Row> rows = new ArrayList<Row>();
	private List<NginxInstanceEntry> lastEntries = new ArrayList<NginxInstanceEntry>();
	private String scanError;

	private JTextField txtSearch;
	private JComboBox<String> cbFilter;
	private JComboBox<String> cbInstance;
	private final List<NginxInstanceEntry> instanceOptions = new ArrayList<NginxInstanceEntry>();
	private boolean updatingInstanceCombo;
	private JLabel lblStats;
	private JButton btnRefresh;

	private JPanel centerCards;
	private CardLayout centerLayout;
	private JLabel lblEmptyTitle;
	private JLabel lblEmptyHint;

	private JTable table;
	private CertTableModel tableModel;

	private JTextArea taDetail;
	private JButton btnReplace;
	private JButton btnRestoreBackup;
	private JButton btnOpenDir;
	private JButton btnCopyPem;
	private JButton btnCopyKey;

	private boolean busy;

	public CertManagerPage(MainFrame owner) {
		this.owner = owner;
		this.manager = new NginxCertReplaceManager(new File(owner.appHome()));
		this.store = new CertificateStoreManager(new File(owner.appHome()));
		setOpaque(true);
		setBackground(Theme.BG);
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(14, Theme.GAP_L, Theme.GAP_M, Theme.GAP_L));
		buildUi();
	}

	/** 每次进入页面重新扫描实例证书, 保证与当前实例状态一致 */
	public void onShown() {
		refreshData();
		// 页面上方多出标题行, 按比例分配表格/详情高度, 避免详情区被压成一条
		if (split != null) {
			javax.swing.SwingUtilities.invokeLater(() -> split.setDividerLocation(0.55));
		}
	}

	// ==================== 界面 ====================

	private void buildUi() {
		JPanel portPanel = new JPanel(new BorderLayout());
		JPanel top = new JPanel(new BorderLayout(0, 6));
		top.setOpaque(false);
		top.setBorder(BorderFactory.createEmptyBorder(8, 12, 4, 12));
		top.add(buildToolbar(), BorderLayout.NORTH);
		top.add(buildStatsBar(), BorderLayout.SOUTH);
		portPanel.add(top, BorderLayout.NORTH);
		portPanel.add(buildCenter(), BorderLayout.CENTER);
		portPanel.add(buildButtons(), BorderLayout.SOUTH);

		library = new CertLibraryPanel(owner, store, this::onLibraryChanged);
		relation = new CertRelationPanel(owner);
		tabs = new JTabbedPane();
		tabs.addTab("实例证书", portPanel);
		tabs.addTab("证书库", library);
		tabs.addTab("绑定关系", relation);
		tabs.addChangeListener(e -> {
			if (tabs.getSelectedIndex() == 1) {
				library.refresh();
			} else if (tabs.getSelectedIndex() == 2) {
				relation.refresh();
			}
		});
		JPanel body = new JPanel(new BorderLayout());
		body.setOpaque(false);
		body.add(tabs, BorderLayout.CENTER);

		add(buildHeader(), BorderLayout.NORTH);
		add(body, BorderLayout.CENTER);
	}

	/** 页面标题行: 与实例总览页一致 */
	private JPanel buildHeader() {
		PageHeader header = new PageHeader("证书管理",
				"统一查看各实例证书、证书库与端口绑定关系");
		header.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));
		return header;
	}

	/** 证书库变更后同步刷新端口视图 */
	private void onLibraryChanged() {
		refreshData();
	}

	private JPanel buildToolbar() {
		JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
		bar.setOpaque(false);

		JLabel lblSearch = new JLabel("搜索:");
		lblSearch.setFont(Theme.BODY);
		txtSearch = new JTextField(22);
		txtSearch.setToolTipText("按域名、实例名、证书文件名或主题搜索");
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

		JLabel lblInstance = new JLabel("实例:");
		lblInstance.setFont(Theme.BODY);
		cbInstance = new JComboBox<String>();
		cbInstance.setFont(Theme.BODY);
		cbInstance.setPreferredSize(new Dimension(160, 26));
		cbInstance.addItem("全部实例");
		cbInstance.addActionListener(e -> {
			if (!updatingInstanceCombo) {
				applyFilter();
			}
		});

		JLabel lblFilter = new JLabel("状态:");
		lblFilter.setFont(Theme.BODY);
		cbFilter = new JComboBox<String>(FILTERS);
		cbFilter.setFont(Theme.BODY);
		cbFilter.addActionListener(e -> applyFilter());

		btnRefresh = Theme.toolButton("刷新");
		btnRefresh.setToolTipText("重新扫描所有实例配置中的 SSL 证书");
		btnRefresh.addActionListener(e -> refreshData());

		bar.add(lblSearch);
		bar.add(txtSearch);
		bar.add(lblInstance);
		bar.add(cbInstance);
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
		tableModel = new CertTableModel();
		table = new JTable(tableModel);
		table.setRowHeight(44);
		table.setFillsViewportHeight(true);
		table.setShowGrid(false);
		table.setIntercellSpacing(new Dimension(0, 0));
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.getTableHeader().setReorderingAllowed(false);
		table.setDefaultRenderer(Object.class, new CertCellRenderer());
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
				if (modelColumn == COL_ACTION && view >= 0) {
					table.setRowSelectionInterval(view, view);
					replaceSelected();
					return;
				}
				if (e.getClickCount() == 2 && table.getSelectedRow() >= 0) {
					replaceSelected();
				}
			}
		});
		// 「操作」列显示为可点击文字, 悬停时切换手型光标
		table.addMouseMotionListener(new MouseAdapter() {
			@Override
			public void mouseMoved(MouseEvent e) {
				int column = table.columnAtPoint(e.getPoint());
				int modelColumn = column < 0 ? -1 : table.convertColumnIndexToModel(column);
				table.setCursor(modelColumn == COL_ACTION ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
						: Cursor.getDefaultCursor());
			}
		});

		int[] widths = { 140, 170, 70, 270, 240, 84, 140, 96 };
		for (int i = 0; i < widths.length; i++) {
			table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
		}
		table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);

		JScrollPane tableScroll = new JScrollPane(table);
		tableScroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
		tableScroll.setMinimumSize(new Dimension(0, 160));

		JPanel detailPanel = buildDetailPanel();
		detailPanel.setMinimumSize(new Dimension(0, 120));

		split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, detailPanel);
		split.setResizeWeight(0.55);
		split.setDividerLocation(0.55);
		split.setDividerSize(8);
		split.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
		split.setContinuousLayout(true);

		centerCards = new JPanel(centerLayout = new CardLayout());
		centerCards.add(split, CARD_TABLE);
		centerCards.add(buildEmptyPanel(), CARD_EMPTY);
		return centerCards;
	}

	private JPanel buildDetailPanel() {
		taDetail = new JTextArea();
		taDetail.setEditable(false);
		// 详情含中文标签: 用界面正文字体, 避免等宽字体缺少中文字形显示为方块
		taDetail.setFont(Theme.BODY);
		taDetail.setBackground(new Color(252, 252, 252));
		taDetail.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		JScrollPane scroll = new JScrollPane(taDetail);
		scroll.setBorder(BorderFactory.createTitledBorder(
				BorderFactory.createLineBorder(Theme.BORDER), "证书详情"));

		JPanel p = new JPanel(new BorderLayout());
		p.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		p.setPreferredSize(new Dimension(0, 200));
		p.add(scroll, BorderLayout.CENTER);
		return p;
	}

	private JPanel buildEmptyPanel() {
		JPanel p = new JPanel(new GridBagLayout());
		p.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
		p.setBackground(Theme.SURFACE);

		lblEmptyTitle = new JLabel("暂未发现 SSL 证书");
		lblEmptyTitle.setFont(Theme.TITLE);
		lblEmptyTitle.setForeground(Theme.TEXT);

		lblEmptyHint = new JLabel("实例配置中声明 ssl_certificate 后, 证书会自动出现在这里。");
		lblEmptyHint.setFont(Theme.SMALL);
		lblEmptyHint.setForeground(Theme.TEXT_MUTED);

		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = 0;
		c.insets = new Insets(0, 0, 6, 0);
		p.add(lblEmptyTitle, c);
		c.gridy = 1;
		c.insets = new Insets(0, 0, 0, 0);
		p.add(lblEmptyHint, c);
		return p;
	}

	private JPanel buildButtons() {
		btnReplace = Theme.primaryButton("更换证书…");
		btnReplace.setToolTipText("对选中证书执行替换(备份 + nginx -t 校验, 失败自动回滚)");
		Theme.setEnabled(btnReplace, false, "未选择证书：先在上方列表中选择一条证书");
		btnReplace.addActionListener(e -> replaceSelected());

		btnRestoreBackup = Theme.secondaryButton("恢复备份…");
		btnRestoreBackup.setToolTipText("从证书替换时自动生成的备份清单恢复证书、私钥与配置");
		btnRestoreBackup.addActionListener(e -> openRestoreDialog());

		btnOpenDir = Theme.secondaryButton("打开证书目录");
		btnOpenDir.setToolTipText("在资源管理器中打开证书文件所在目录");
		Theme.setEnabled(btnOpenDir, false, "未选择证书：先在上方列表中选择一条证书");
		btnOpenDir.addActionListener(e -> openCertDir());

		btnCopyPem = Theme.secondaryButton("复制证书路径");
		btnCopyPem.setToolTipText("复制证书文件路径到剪贴板");
		Theme.setEnabled(btnCopyPem, false, "未选择证书：先在上方列表中选择一条证书");
		btnCopyPem.addActionListener(e -> copyPath(selectedItem() == null ? null : selectedItem().pemPath));

		btnCopyKey = Theme.secondaryButton("复制私钥路径");
		btnCopyKey.setToolTipText("复制私钥文件路径到剪贴板");
		Theme.setEnabled(btnCopyKey, false, "未选择证书：先在上方列表中选择一条证书");
		btnCopyKey.addActionListener(e -> copyPath(selectedItem() == null ? null : selectedItem().keyPath));

		JButton btnClose = new JButton("返回实例总览");
		btnClose.addActionListener(e -> owner.returnToInstances());

		JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		right.setOpaque(false);
		right.add(btnReplace);
		right.add(btnRestoreBackup);
		right.add(btnOpenDir);
		right.add(btnCopyPem);
		right.add(btnCopyKey);
		right.add(btnClose);

		JPanel p = new JPanel(new BorderLayout());
		p.setBorder(BorderFactory.createEmptyBorder(6, 12, 10, 12));
		p.add(right, BorderLayout.EAST);
		return p;
	}

	// ==================== 数据 ====================

	private void refreshData() {
		if (busy) {
			return;
		}
		lastEntries = owner.registryEntries();
		scanError = null;
		setBusy(true);
		lblStats.setText("正在扫描 " + lastEntries.size() + " 个实例的 SSL 证书…");
		new SwingWorker<List<CertEntry>, Void>() {
			@Override
			protected List<CertEntry> doInBackground() {
				return manager.scanAll(lastEntries);
			}

			@Override
			protected void done() {
				try {
					List<CertEntry> result = get();
					allEntries.clear();
					allEntries.addAll(result);
				} catch (Exception ex) {
					allEntries.clear();
					scanError = ErrorReporter.friendlyReason(ex);
					manager.log("ERROR", "证书总览扫描失败: " + scanError, ex);
				} finally {
					setBusy(false);
					updateInstanceOptions();
					applyFilter();
				}
			}
		}.execute();
	}

	private void setBusy(boolean value) {
		busy = value;
		Theme.setEnabled(btnRefresh, !value, value ? "正在扫描证书，请稍候" : null);
		setCursor(Cursor.getPredefinedCursor(value ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));
	}

	/** 实例下拉选项: 全部实例 + 已纳管实例, 刷新后保留原先选择 */
	private void updateInstanceOptions() {
		if (cbInstance == null) {
			return;
		}
		NginxInstanceEntry keep = selectedInstance();
		String keepId = keep == null ? null : keep.getId();
		updatingInstanceCombo = true;
		try {
			cbInstance.removeAllItems();
			cbInstance.addItem("全部实例");
			instanceOptions.clear();
			for (NginxInstanceEntry entry : lastEntries) {
				instanceOptions.add(entry);
				cbInstance.addItem(instanceName(entry));
			}
			int target = 0;
			if (keepId != null) {
				for (int i = 0; i < instanceOptions.size(); i++) {
					if (StrUtil.equals(keepId, instanceOptions.get(i).getId())) {
						target = i + 1;
						break;
					}
				}
			}
			cbInstance.setSelectedIndex(Math.min(target, cbInstance.getItemCount() - 1));
		} finally {
			updatingInstanceCombo = false;
		}
	}

	private NginxInstanceEntry selectedInstance() {
		if (cbInstance == null) {
			return null;
		}
		int index = cbInstance.getSelectedIndex();
		return index > 0 && index - 1 < instanceOptions.size() ? instanceOptions.get(index - 1) : null;
	}

	private void applyFilter() {
		if (txtSearch == null || cbFilter == null) {
			return;
		}
		String keyword = txtSearch.getText().trim().toLowerCase(Locale.ROOT);
		String filter = (String) cbFilter.getSelectedItem();
		NginxInstanceEntry onlyInstance = selectedInstance();
		rows.clear();
		for (CertEntry ce : allEntries) {
			if (onlyInstance != null && (ce.instance == null
					|| !StrUtil.equals(onlyInstance.getId(), ce.instance.getId()))) {
				continue;
			}
			int status = NginxCertReplaceManager.statusOf(ce.item);
			if (!matchFilter(status, filter)) {
				continue;
			}
			Row row = new Row(ce, status);
			if (!keyword.isEmpty() && !row.searchText().contains(keyword)) {
				continue;
			}
			rows.add(row);
		}
		rows.sort(new Comparator<Row>() {
			@Override
			public int compare(Row a, Row b) {
				if (a.status != b.status) {
					return a.status - b.status;
				}
				long dayA = a.ce.item.info == null ? Long.MAX_VALUE : a.ce.item.info.daysLeft;
				long dayB = b.ce.item.info == null ? Long.MAX_VALUE : b.ce.item.info.daysLeft;
				if (dayA != dayB) {
					return dayA < dayB ? -1 : 1;
				}
				int byInstance = instanceName(a.ce.instance).compareToIgnoreCase(instanceName(b.ce.instance));
				if (byInstance != 0) {
					return byInstance;
				}
				return domainOf(a.ce.item).compareToIgnoreCase(domainOf(b.ce.item));
			}
		});

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

	private void showEmptyState() {
		centerLayout.show(centerCards, CARD_EMPTY);
		table.clearSelection();
		if (scanError != null) {
			lblEmptyTitle.setText("扫描失败");
			lblEmptyHint.setText("无法读取实例证书: " + scanError);
		} else if (lastEntries.isEmpty()) {
			lblEmptyTitle.setText("暂无可管理实例");
			lblEmptyHint.setText("请先在主界面刷新或添加 Nginx 实例, 再回到本页管理证书。");
		} else if (!allEntries.isEmpty()) {
			lblEmptyTitle.setText("没有匹配的证书");
			lblEmptyHint.setText("试试调整关键词或切换状态筛选。");
		} else {
			lblEmptyTitle.setText("暂未发现 SSL 证书");
			lblEmptyHint.setText("实例配置中声明 ssl_certificate 后, 证书会自动出现在这里。");
		}
	}

	private void updateStats() {
		int total = allEntries.size();
		int ok = 0;
		int expiring = 0;
		int expired = 0;
		int missing = 0;
		int invalid = 0;
		int notYet = 0;
		Set<String> withCert = new HashSet<String>();
		for (CertEntry ce : allEntries) {
			int status = NginxCertReplaceManager.statusOf(ce.item);
			switch (status) {
				case NginxCertReplaceManager.ST_OK:
					ok++;
					break;
				case NginxCertReplaceManager.ST_EXPIRING:
					expiring++;
					break;
				case NginxCertReplaceManager.ST_EXPIRED:
					expired++;
					break;
				case NginxCertReplaceManager.ST_MISSING:
					missing++;
					break;
				case NginxCertReplaceManager.ST_NOT_YET:
					notYet++;
					break;
				default:
					invalid++;
					break;
			}
			if (ce.instance != null && ce.instance.getId() != null) {
				withCert.add(ce.instance.getId());
			}
		}
		int noCert = 0;
		for (NginxInstanceEntry entry : lastEntries) {
			if (entry != null && (entry.getId() == null || !withCert.contains(entry.getId()))) {
				noCert++;
			}
		}
		StringBuilder sb = new StringBuilder("<html>");
		sb.append("共 <b>").append(total).append("</b> 条证书声明　");
		sb.append("<span style='color:#16A34A'>正常 ").append(ok).append("</span>　");
		sb.append("<span style='color:#D97706'>即将到期 ").append(expiring).append("</span>　");
		sb.append("<span style='color:#DC2626'>已过期 ").append(expired).append("</span>　");
		sb.append("<span style='color:#DC2626'>缺失 ").append(missing).append("</span>　");
		sb.append("<span style='color:#DC2626'>无法解析 ").append(invalid).append("</span>　");
		if (notYet > 0) {
			sb.append("<span style='color:#D97706'>未生效 ").append(notYet).append("</span>　");
		}
		sb.append("<span style='color:#6B7280'>未配置证书的实例 ").append(noCert).append(" 个</span>");
		sb.append("</html>");
		lblStats.setText(sb.toString());
	}

	private boolean matchFilter(int status, String filter) {
		if (StrUtil.isEmpty(filter) || F_ALL.equals(filter)) {
			return true;
		}
		if (F_EXPIRING.equals(filter)) {
			return status == NginxCertReplaceManager.ST_EXPIRING;
		}
		if (F_EXPIRED.equals(filter)) {
			return status == NginxCertReplaceManager.ST_EXPIRED;
		}
		if (F_MISSING.equals(filter)) {
			return status == NginxCertReplaceManager.ST_MISSING;
		}
		if (F_INVALID.equals(filter)) {
			return status == NginxCertReplaceManager.ST_INVALID;
		}
		if (F_OK.equals(filter)) {
			return status == NginxCertReplaceManager.ST_OK;
		}
		return true;
	}

	// ==================== 详情与操作 ====================

	private Row selectedRow() {
		int view = table.getSelectedRow();
		if (view < 0) {
			return null;
		}
		int model = table.convertRowIndexToModel(view);
		return model >= 0 && model < rows.size() ? rows.get(model) : null;
	}

	private CertItem selectedItem() {
		Row row = selectedRow();
		return row == null ? null : row.ce.item;
	}

	private void updateDetail() {
		Row row = selectedRow();
		if (row == null) {
			taDetail.setText("在上方列表中选择一条证书以查看详情。");
			taDetail.setForeground(Theme.TEXT_MUTED);
		} else {
			taDetail.setForeground(Theme.TEXT);
			taDetail.setText(detailText(row));
			taDetail.setCaretPosition(0);
		}
		CertItem item = row == null ? null : row.ce.item;
		boolean pemExists = item != null && StrUtil.isNotEmpty(item.pemPath) && new File(item.pemPath).isFile();
		boolean hasPem = item != null && StrUtil.isNotEmpty(item.pemPath);
		boolean hasKey = item != null && StrUtil.isNotEmpty(item.keyPath);
		String selectReason = row == null ? "未选择证书：先在上方列表中选择一条证书" : null;
		Theme.setEnabled(btnReplace, row != null, selectReason);
		Theme.setEnabled(btnOpenDir, pemExists, selectReason != null ? selectReason
				: (hasPem ? "证书文件不存在（可能已被移动或删除）" : "配置未声明证书文件路径，无法打开目录"));
		Theme.setEnabled(btnCopyPem, hasPem, selectReason != null ? selectReason
				: "配置未声明证书文件路径，无可复制内容");
		Theme.setEnabled(btnCopyKey, hasKey, selectReason != null ? selectReason
				: "配置未声明私钥路径，无可复制内容");
	}

	private String detailText(Row row) {
		CertItem item = row.ce.item;
		CertInfo info = item.info;
		StringBuilder sb = new StringBuilder();
		sb.append("状态: ").append(NginxCertReplaceManager.statusText(row.status));
		if (info != null && !info.expired) {
			sb.append("（剩余 ").append(info.daysLeft).append(" 天）");
		}
		sb.append('\n');
		sb.append("实例: ").append(instanceName(row.ce.instance)).append('\n');
		sb.append("证书域名: ").append(info == null ? "-"
				: StrUtil.emptyToDefault(info.cn(), "-")).append('\n');
		sb.append("配置文件: ").append(StrUtil.nullToEmpty(item.sourceConf == null ? "" : item.sourceConf.getAbsolutePath())).append('\n');
		sb.append("server_name: ").append(domainOf(item)).append('\n');
		sb.append("监听端口: ").append(item.portsText()).append('\n');
		sb.append("证书文件: ").append(StrUtil.emptyToDefault(item.pemPath, "(未识别)"))
				.append(item.pemExists ? "" : "  [文件缺失]").append('\n');
		sb.append("私钥文件: ").append(StrUtil.isEmpty(item.keyPath) ? "(配置未声明)"
				: item.keyPath + (item.keyExists ? "" : "  [文件缺失]")).append('\n');
		if (info == null) {
			sb.append("证书内容: 无法解析为 X.509 PEM 证书\n");
		} else {
			sb.append("主题: ").append(StrUtil.nullToEmpty(info.subject)).append('\n');
			sb.append("颁发者: ").append(StrUtil.nullToEmpty(info.issuer)).append('\n');
			sb.append("有效期: ").append(info.notBeforeText()).append(" ~ ").append(info.notAfterText()).append('\n');
			sb.append("SAN: ").append(info.domains.isEmpty() ? "(无)" : String.join(", ", info.domains)).append('\n');
		}
		return sb.toString();
	}

	private void replaceSelected() {
		Row row = selectedRow();
		if (row == null || busy) {
			return;
		}
		try {
			NginxInstanceEntry entry = row.ce.instance;
			String pemPath = row.ce.item.pemPath;
			new CertReplaceDialog(owner, entry, pemPath).setVisible(true);
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(this,
					ErrorReporter.uiText("打开证书替换窗口", ex, "请关闭后重试；若持续失败，请查看日志目录。"),
					"nginxUI", JOptionPane.ERROR_MESSAGE);
			return;
		}
		refreshData();
	}

	/** 证书备份恢复入口: 有恢复动作时自动重新扫描证书与状态 */
	private void openRestoreDialog() {
		if (busy) {
			return;
		}
		try {
			new CertRestoreDialog(owner, this::refreshData).setVisible(true);
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(this,
					ErrorReporter.uiText("打开证书备份恢复窗口", ex, "请关闭后重试；若持续失败，请查看日志目录。"),
					"nginxUI", JOptionPane.ERROR_MESSAGE);
		}
	}

	private void openCertDir() {
		CertItem item = selectedItem();
		if (item == null || StrUtil.isEmpty(item.pemPath)) {
			return;
		}
		File dir = new File(item.pemPath).getParentFile();
		if (dir == null || !dir.isDirectory()) {
			JOptionPane.showMessageDialog(this, "证书目录不存在: " + StrUtil.nullToEmpty(item.pemPath),
					"nginxUI - 证书管理", JOptionPane.WARNING_MESSAGE);
			return;
		}
		try {
			java.awt.Desktop.getDesktop().open(dir);
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(this,
					ErrorReporter.uiText("打开目录", ex, "请确认目录存在且当前账户有访问权限。"),
					"nginxUI", JOptionPane.ERROR_MESSAGE);
		}
	}

	private void copyPath(String path) {
		if (StrUtil.isEmpty(path)) {
			return;
		}
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(path), null);
	}

	private static String instanceName(NginxInstanceEntry entry) {
		if (entry == null) {
			return "(未知实例)";
		}
		return StrUtil.emptyToDefault(entry.getName(), StrUtil.emptyToDefault(entry.getConfPath(), entry.getId()));
	}

	private static String domainOf(CertItem item) {
		if (item == null) {
			return "-";
		}
		if (StrUtil.isNotEmpty(item.domain)) {
			return item.domain;
		}
		if (item.info != null && StrUtil.isNotEmpty(item.info.cn())) {
			return item.info.cn();
		}
		return "(未声明 server_name)";
	}

	/** 证书路径单元格: 证书与私钥各占一行(完整路径见 tooltip) */
	private static String certCell(CertItem item) {
		if (item == null || StrUtil.isEmpty(item.pemPath)) {
			return "(无路径)";
		}
		StringBuilder sb = new StringBuilder("<html>").append(escapeHtml(shortPath(item.pemPath)));
		if (StrUtil.isNotEmpty(item.keyPath)) {
			sb.append("<br>").append(escapeHtml(shortPath(item.keyPath)));
		}
		return sb.append("</html>").toString();
	}

	/** 长路径压缩为「盘符\…\末两段」, 避免撑破列宽; 完整路径仍保留在 tooltip 中 */
	static String shortPath(String path) {
		if (StrUtil.isEmpty(path)) {
			return "";
		}
		String p = path.replace('/', '\\');
		if (p.length() <= 42) {
			return p;
		}
		String[] parts = p.split("\\\\");
		StringBuilder sb = new StringBuilder(parts.length > 0 && parts[0].endsWith(":") ? parts[0] + "\\…" : "…");
		int take = Math.min(2, parts.length - 1);
		for (int i = parts.length - take; i < parts.length; i++) {
			sb.append('\\').append(parts[i]);
		}
		return sb.toString();
	}

	/** 到期时间单元格: 日期 + 剩余/逾期天数 */
	private static String expiryCell(CertInfo info) {
		if (info == null) {
			return "-";
		}
		String left = info.expired ? "已过期 " + Math.abs(info.daysLeft) + " 天"
				: "剩余 " + info.daysLeft + " 天";
		return "<html>" + info.notAfterText() + "<br>" + left + "</html>";
	}

	private static String escapeHtml(String s) {
		return StrUtil.nullToEmpty(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	// ==================== 表格 ====================

	/** 表格行: 扫描条目 + 预计算状态 */
	private static final class Row {
		final CertEntry ce;
		final int status;

		Row(CertEntry ce, int status) {
			this.ce = ce;
			this.status = status;
		}

		String searchText() {
			CertItem item = ce.item;
			StringBuilder sb = new StringBuilder();
			sb.append(domainOf(item)).append(' ');
			sb.append(instanceName(ce.instance)).append(' ');
			if (item != null) {
				if (StrUtil.isNotEmpty(item.pemPath)) {
					sb.append(new File(item.pemPath).getName()).append(' ');
				}
				if (StrUtil.isNotEmpty(item.keyPath)) {
					sb.append(new File(item.keyPath).getName()).append(' ');
				}
				if (item.info != null) {
					sb.append(StrUtil.nullToEmpty(item.info.subject)).append(' ');
				}
				if (item.sourceConf != null) {
					sb.append(item.sourceConf.getName());
				}
			}
			return sb.toString().toLowerCase(Locale.ROOT);
		}
	}

	private final class CertTableModel extends AbstractTableModel {
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
			CertItem item = row.ce.item;
			CertInfo info = item.info;
			switch (columnIndex) {
				case COL_DOMAIN:
					return info == null ? "-" : StrUtil.emptyToDefault(info.cn(), "-");
				case COL_SERVER_NAME:
					return domainOf(item);
				case COL_PORT:
					return item.portsText();
				case COL_CERT:
					return certCell(item);
				case COL_CONF:
					return item.sourceConf == null ? "-" : shortPath(item.sourceConf.getAbsolutePath());
				case COL_STATUS:
					return NginxCertReplaceManager.statusText(row.status);
				case COL_EXPIRY:
					return expiryCell(info);
				case COL_ACTION:
					return "更换证书";
				default:
					return "";
			}
		}
	}

	/** 状态/剩余列按严重程度着色, 证书文件列给出完整路径提示 */
	private final class CertCellRenderer extends DefaultTableCellRenderer {
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
			if (column == COL_STATUS) {
				setFont(Theme.BODY_BOLD);
				setForeground(statusColor(data.status));
			} else if (column == COL_EXPIRY) {
				setForeground(data.status == NginxCertReplaceManager.ST_OK
						? Theme.TEXT : statusColor(data.status));
			} else if (column == COL_CERT) {
				setForeground(Theme.TEXT);
				CertItem item = data.ce.item;
				StringBuilder tip = new StringBuilder(StrUtil.nullToEmpty(item.pemPath));
				if (StrUtil.isNotEmpty(item.keyPath)) {
					tip.append("\n私钥: ").append(item.keyPath);
				}
				if (StrUtil.isNotEmpty(tip)) {
					setToolTipText(tip.toString());
				}
			} else if (column == COL_CONF) {
				setForeground(Theme.TEXT_MUTED);
				if (data.ce.item.sourceConf != null) {
					setToolTipText(data.ce.item.sourceConf.getAbsolutePath());
				}
			} else if (column == COL_ACTION) {
				setFont(Theme.BODY_BOLD);
				setForeground(Theme.PRIMARY);
				setToolTipText("点击更换该端口使用的证书");
			} else {
				setForeground(Theme.TEXT);
			}
			return this;
		}
	}

	private static Color statusColor(int status) {
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
}
