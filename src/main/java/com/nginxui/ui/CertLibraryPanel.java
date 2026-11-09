package com.nginxui.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import com.nginxui.ext.NginxInstanceEntry;
import com.nginxui.utils.CertificateStoreManager;
import com.nginxui.utils.CertificateStoreManager.ApplyResult;
import com.nginxui.utils.CertificateStoreManager.ImportResult;
import com.nginxui.utils.CertificateStoreManager.ManagedCert;
import com.nginxui.utils.CertificateStoreManager.Usage;
import com.nginxui.utils.NginxCertReplaceManager;
import com.nginxui.utils.NginxCertReplaceManager.CertEntry;
import com.nginxui.utils.NginxCertReplaceManager.CertItem;
import com.nginxui.utils.NginxCertReplaceManager.Inspect;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;

/**
 * 证书库面板: 以证书为核心的资源视图.
 *
 *  左侧证书列表, 右侧证书详情与关联端口列表;
 *  支持导入/更换/编辑/删除证书, 批量绑定端口, 解除绑定, 复制路径, 打开目录;
 *  证书更换后自动同步所有绑定端口(原子写入 + nginx -t 门禁 + 失败回滚).
 */
public class CertLibraryPanel extends JPanel {

	private static final long serialVersionUID = 1L;

	private static final String[] LIST_COLUMNS = { "证书名称", "状态", "到期日", "剩余", "引用", "备注" };
	private static final int LIST_NAME = 0;
	private static final int LIST_STATUS = 1;
	private static final int LIST_EXPIRY = 2;
	private static final int LIST_LEFT = 3;
	private static final int LIST_REFS = 4;
	private static final int LIST_NOTE = 5;

	private static final String[] USAGE_COLUMNS = { "关联", "实例", "server_name", "端口", "证书文件" };
	private static final int USAGE_STATE = 0;
	private static final int USAGE_INSTANCE = 1;
	private static final int USAGE_NAME = 2;
	private static final int USAGE_PORT = 3;
	private static final int USAGE_CERT = 4;

	private final MainFrame owner;
	private final CertificateStoreManager store;
	private final NginxCertReplaceManager scanner;
	/** 数据变更后通知外层刷新端口标签页 */
	private final Runnable onChanged;

	private JLabel lblReminder;
	private JButton btnImport, btnReplace, btnEdit, btnDelete, btnRefresh;
	private JButton btnBind, btnUnbind, btnOpenDir, btnCopyPath;

	private JTable certTable;
	private CertListModel certModel;
	private JTextArea taDetail;
	private JTable usageTable;
	private UsageModel usageModel;

	private List<ManagedCert> certs = new ArrayList<ManagedCert>();
	private List<CertEntry> scanned = new ArrayList<CertEntry>();
	private List<Usage> usage = new ArrayList<Usage>();
	/** certId → 真实引用次数(活配置 SHA-256 命中 + 绑定记录去重) */
	private Map<String, Integer> certUsageCounts = new HashMap<String, Integer>();
	private boolean busy;
	/** 是否已完成一次证书库读取(用于初始提示) */
	private boolean loaded;

	public CertLibraryPanel(MainFrame owner, CertificateStoreManager store, Runnable onChanged) {
		this.owner = owner;
		this.store = store;
		this.scanner = new NginxCertReplaceManager(new File(owner.appHome()));
		this.onChanged = onChanged;
		buildUi();
		updateReminder();
		OperationLock.addListener(this::updateButtons);
	}

	// ==================== 界面 ====================

	private void buildUi() {
		setLayout(new BorderLayout(0, 6));
		setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
		add(buildTop(), BorderLayout.NORTH);
		add(buildCenter(), BorderLayout.CENTER);
	}

	private JPanel buildTop() {
		JPanel p = new JPanel(new BorderLayout(0, 6));
		p.setOpaque(false);

		lblReminder = new JLabel(" ");
		lblReminder.setFont(Theme.SMALL);
		lblReminder.setForeground(Theme.TEXT_MUTED);
		p.add(lblReminder, BorderLayout.NORTH);

		JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
		bar.setOpaque(false);
		btnImport = Theme.toolButton("导入证书…");
		btnImport.setToolTipText("上传 PEM 证书与私钥，纳入证书库统一管理");
		btnImport.addActionListener(e -> importCert());

		btnReplace = Theme.toolButton("更换证书文件…");
		btnReplace.setToolTipText("更新证书内容，并自动同步到所有已绑定端口");
		btnReplace.addActionListener(e -> replaceCert());

		btnEdit = Theme.toolButton("编辑信息…");
		btnEdit.setToolTipText("修改证书名称与备注");
		btnEdit.addActionListener(e -> editMeta());

		btnDelete = Theme.toolButton("删除证书");
		btnDelete.setToolTipText("删除证书库记录，文件移入 certs/_deleted；端口现有证书不受影响");
		btnDelete.addActionListener(e -> deleteCert());

		btnRefresh = Theme.toolButton("刷新");
		btnRefresh.setToolTipText("重新读取证书库并扫描端口使用情况");
		btnRefresh.addActionListener(e -> refresh());

		bar.add(btnImport);
		bar.add(btnReplace);
		bar.add(btnEdit);
		bar.add(btnDelete);
		bar.add(btnRefresh);
		p.add(bar, BorderLayout.CENTER);
		return p;
	}

	private Component buildCenter() {
		certModel = new CertListModel();
		certTable = new JTable(certModel);
		certTable.setRowHeight(26);
		certTable.setFillsViewportHeight(true);
		certTable.setShowGrid(false);
		certTable.setIntercellSpacing(new Dimension(0, 0));
		certTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		certTable.getTableHeader().setReorderingAllowed(false);
		certTable.setDefaultRenderer(Object.class, new CertRenderer());
		certTable.getSelectionModel().addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting()) {
				updateDetail();
			}
		});
		int[] widths = { 160, 80, 96, 64, 48, 140 };
		for (int i = 0; i < widths.length; i++) {
			certTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
		}
		JScrollPane listScroll = new JScrollPane(certTable);
		listScroll.setBorder(BorderFactory.createTitledBorder(
				BorderFactory.createLineBorder(Theme.BORDER), "证书库"));
		listScroll.setPreferredSize(new Dimension(460, 0));
		listScroll.setMinimumSize(new Dimension(260, 0));

		Component rightSide = buildDetailSide();
		if (rightSide instanceof JComponent) {
			((JComponent) rightSide).setMinimumSize(new Dimension(320, 0));
		}

		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, rightSide);
		split.setResizeWeight(0.45);
		split.setDividerLocation(0.45);
		split.setDividerSize(8);
		split.setContinuousLayout(true);
		split.setBorder(BorderFactory.createEmptyBorder());
		return split;
	}

	private JPanel buildDetailSide() {
		JPanel p = new JPanel(new BorderLayout(0, 6));
		p.setOpaque(false);

		taDetail = new JTextArea();
		taDetail.setEditable(false);
		taDetail.setFont(Theme.BODY);
		taDetail.setBackground(new Color(252, 252, 252));
		taDetail.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		JScrollPane detailScroll = new JScrollPane(taDetail);
		detailScroll.setBorder(BorderFactory.createTitledBorder(
				BorderFactory.createLineBorder(Theme.BORDER), "证书详情"));
		detailScroll.setPreferredSize(new Dimension(0, 300));
		p.add(detailScroll, BorderLayout.NORTH);

		usageModel = new UsageModel();
		usageTable = new JTable(usageModel);
		usageTable.setRowHeight(26);
		usageTable.setFillsViewportHeight(true);
		usageTable.setShowGrid(false);
		usageTable.setIntercellSpacing(new Dimension(0, 0));
		usageTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		usageTable.getTableHeader().setReorderingAllowed(false);
		usageTable.setDefaultRenderer(Object.class, new UsageRenderer());
		usageTable.getSelectionModel().addListSelectionListener(e -> updateButtons());
		int[] widths = { 110, 130, 160, 90, 260 };
		for (int i = 0; i < widths.length; i++) {
			usageTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
		}
		JScrollPane usageScroll = new JScrollPane(usageTable);
		usageScroll.setBorder(BorderFactory.createTitledBorder(
				BorderFactory.createLineBorder(Theme.BORDER), "关联端口"));
		p.add(usageScroll, BorderLayout.CENTER);

		// 详情区宽度有限: 用等分网格保证 4 个操作按钮始终完整可见
		JPanel actions = new JPanel(new java.awt.GridLayout(1, 4, 8, 0));
		actions.setOpaque(false);
		btnBind = Theme.secondaryButton("绑定端口…");
		btnBind.setToolTipText("选择端口并批量绑定；写入后自动校验并重启生效，任一失败整体回滚");
		btnBind.addActionListener(e -> bindPorts());

		btnUnbind = Theme.secondaryButton("解除绑定");
		btnUnbind.setToolTipText("仅移除关联记录，不删除端口现有证书文件");
		btnUnbind.addActionListener(e -> unbindSelected());

		btnOpenDir = Theme.secondaryButton("打开证书目录");
		btnOpenDir.addActionListener(e -> openCertDir());

		btnCopyPath = Theme.secondaryButton("复制证书路径");
		btnCopyPath.addActionListener(e -> copyPath());

		actions.add(btnBind);
		actions.add(btnUnbind);
		actions.add(btnOpenDir);
		actions.add(btnCopyPath);
		p.add(actions, BorderLayout.SOUTH);
		return p;
	}

	// ==================== 刷新 ====================

	public void refresh() {
		if (busy) {
			return;
		}
		setBusy(true);
		String keepId = selectedCert() == null ? null : selectedCert().id;
		final List<NginxInstanceEntry> entries = owner.registryEntries();
		new SwingWorker<List<CertEntry>, Void>() {
			@Override
			protected List<CertEntry> doInBackground() {
				return scanner.scanAll(entries);
			}

			@Override
			protected void done() {
				try {
					scanned = get();
				} catch (Exception e) {
					scanned = new ArrayList<CertEntry>();
				}
				certs = store.list();
				loaded = true;
				// 预计算每张证书的真实引用次数(活配置 SHA-256 命中 + 绑定记录去重)
				certUsageCounts.clear();
				for (ManagedCert c : certs) {
					certUsageCounts.put(c.id, store.usageOf(c, scanned).size());
				}
				certModel.fireTableDataChanged();
				setBusy(false);
				updateReminder();
				restoreSelection(keepId);
				updateDetail();
			}
		}.execute();
	}

	private void restoreSelection(String keepId) {
		int index = certs.isEmpty() ? -1 : 0;
		if (StrUtil.isNotEmpty(keepId)) {
			for (int i = 0; i < certs.size(); i++) {
				if (keepId.equals(certs.get(i).id)) {
					index = i;
					break;
				}
			}
		}
		if (index >= 0) {
			certTable.setRowSelectionInterval(index, index);
		} else {
			certTable.clearSelection();
		}
	}

	private void updateReminder() {
		if (certs.isEmpty() && !loaded) {
			lblReminder.setText("正在读取证书库…");
			lblReminder.setForeground(Theme.TEXT_MUTED);
			return;
		}
		int expired = 0;
		int soon = 0;
		for (ManagedCert c : certs) {
			if (c.info == null) {
				continue;
			}
			if (c.info.expired) {
				expired++;
			} else if (c.info.expiringSoon) {
				soon++;
			}
		}
		if (expired + soon > 0) {
			StringBuilder sb = new StringBuilder("⚠ ");
			if (expired > 0) {
				sb.append(expired).append(" 张证书已过期");
			}
			if (soon > 0) {
				if (expired > 0) {
					sb.append("，");
				}
				sb.append(soon).append(" 张将在 ").append(CertificateStoreManager.REMIND_DAYS).append(" 天内到期");
			}
			sb.append("，请及时更换。");
			lblReminder.setText(sb.toString());
			lblReminder.setForeground(expired > 0 ? Theme.DANGER : Theme.WARNING);
		} else {
			lblReminder.setText("证书库共 " + certs.size() + " 张证书，均在有效期内。");
			lblReminder.setForeground(Theme.TEXT_MUTED);
		}
	}

	private void updateDetail() {
		ManagedCert c = selectedCert();
		if (c == null) {
			taDetail.setText("左侧选择一张证书查看详情。\n\n"
					+ "「导入证书…」把已有的 PEM 证书与私钥纳入证书库，随后可在「绑定端口…」中"
					+ "一次绑定多个端口，更换证书时自动同步到所有绑定端口。");
			usage = new ArrayList<Usage>();
			usageModel.fireTableDataChanged();
			updateButtons();
			return;
		}
		usage = store.usageOf(c, scanned);
		usageModel.fireTableDataChanged();
		taDetail.setText(detailText(c));
		taDetail.setCaretPosition(0);
		updateButtons();
	}

	private String detailText(ManagedCert c) {
		StringBuilder sb = new StringBuilder();
		sb.append("名称: ").append(c.displayName()).append('\n');
		sb.append("唯一标识: ").append(c.id).append('\n');
		sb.append("状态: ").append(c.statusText());
		if (c.info != null) {
			if (c.info.expired) {
				sb.append("（已过期 ").append(Math.abs(c.info.daysLeft)).append(" 天）");
			} else {
				sb.append("（剩余 ").append(c.info.daysLeft).append(" 天）");
			}
		}
		sb.append('\n');
		if (c.info != null) {
			sb.append("主题: ").append(c.info.subject).append('\n');
			sb.append("颁发者: ").append(c.info.issuer).append('\n');
			sb.append("可用域名: ").append(c.info.domains.isEmpty() ? "-" : String.join(", ", c.info.domains))
					.append('\n');
			sb.append("生效时间: ").append(c.info.notBeforeText()).append('\n');
			sb.append("到期时间: ").append(c.info.notAfterText()).append('\n');
		} else {
			sb.append("证书文件缺失或无法解析: ").append(store.certFile(c.id).getAbsolutePath()).append('\n');
		}
		sb.append("私钥算法: ").append(StrUtil.emptyToDefault(c.keyType, "-")).append('\n');
		sb.append("证书 SHA-256: ").append(StrUtil.emptyToDefault(c.sha256, "-")).append('\n');
		sb.append("来源: ").append(StrUtil.emptyToDefault(c.importedFrom, "本地上传")).append('\n');
		sb.append("导入时间: ").append(StrUtil.emptyToDefault(c.createdAt, "-"))
				.append("    更新时间: ").append(StrUtil.emptyToDefault(c.updatedAt, "-")).append('\n');
		sb.append("绑定端口: ").append(c.bindings.size()).append(" 个");
		if (!usage.isEmpty()) {
			sb.append("（关联端口 ").append(usage.size()).append(" 个，见下表）");
		}
		sb.append('\n');
		if (StrUtil.isNotEmpty(c.note)) {
			sb.append("备注: ").append(c.note).append('\n');
		}
		return sb.toString();
	}

	private void updateButtons() {
		ManagedCert c = selectedCert();
		boolean sel = c != null;
		boolean locked = busy || OperationLock.isBusy();
		String busyReason = busy ? "正在处理证书，请稍候" : OperationLock.reason();
		String selectReason = "先在证书库列表中选择一条证书";
		Theme.setEnabled(btnReplace, sel && !locked, busyReason != null ? busyReason
				: (sel ? null : selectReason));
		Theme.setEnabled(btnEdit, sel && !locked, busyReason != null ? busyReason
				: (sel ? null : selectReason));
		Theme.setEnabled(btnDelete, sel && !locked, busyReason != null ? busyReason
				: (sel ? null : selectReason));
		Theme.setEnabled(btnBind, sel && !locked && !scanned.isEmpty(),
				busyReason != null ? busyReason
						: (!sel ? selectReason
								: (scanned.isEmpty() ? "没有可绑定的站点：先扫描实例站点" : null)));
		Theme.setEnabled(btnOpenDir, sel, sel ? null : selectReason);
		Theme.setEnabled(btnCopyPath, sel, sel ? null : selectReason);
		boolean canUnbind = false;
		int row = usageTable.getSelectedRow();
		if (sel && row >= 0 && row < usage.size()) {
			canUnbind = usage.get(row).bound;
		}
		Theme.setEnabled(btnUnbind, canUnbind && !locked, busyReason != null ? busyReason
				: (canUnbind ? null : "先在下方关联列表中选择一个已绑定端口"));
		Theme.setEnabled(btnImport, !locked, busyReason);
		Theme.setEnabled(btnRefresh, !busy, busy ? "正在处理证书，请稍候" : null);
	}

	private void setBusy(boolean value) {
		busy = value;
		setCursor(value ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
		if (btnImport != null) {
			updateButtons();
		}
	}

	private ManagedCert selectedCert() {
		int row = certTable == null ? -1 : certTable.getSelectedRow();
		return row >= 0 && row < certs.size() ? certs.get(row) : null;
	}

	private Usage selectedUsage() {
		int row = usageTable == null ? -1 : usageTable.getSelectedRow();
		return row >= 0 && row < usage.size() ? usage.get(row) : null;
	}

	// ==================== 操作 ====================

	private void importCert() {
		CertEditDialog dlg = new CertEditDialog(owner, "导入证书", "", "", null);
		dlg.setVisible(true);
		if (!dlg.confirmed) {
			return;
		}
		ImportResult r = store.importCert(dlg.nameText(), dlg.noteText(), dlg.pemContent(), dlg.keyContent(),
				dlg.pemSource());
		if (!r.success) {
			JOptionPane.showMessageDialog(this, r.message, "nginxUI - 导入证书", JOptionPane.ERROR_MESSAGE);
			return;
		}
		String extra = r.cert != null && r.cert.info != null
				? "\n主题: " + r.cert.info.subject + "\n到期: " + r.cert.info.notAfterText()
				: "";
		if (!r.warnings.isEmpty()) {
			extra += "\n\n" + String.join("\n", r.warnings);
		}
		JOptionPane.showMessageDialog(this, r.message + extra,
				"nginxUI - 导入证书", JOptionPane.INFORMATION_MESSAGE);
		refresh();
		onChanged.run();
	}

	private void replaceCert() {
		final ManagedCert c = selectedCert();
		if (c == null) {
			return;
		}
		CertEditDialog dlg = new CertEditDialog(owner, "更换证书文件", c.displayName(), c.note, c);
		dlg.setVisible(true);
		if (!dlg.confirmed) {
			return;
		}
		int bound = c.bindings.size();
		String object = "证书「" + c.displayName() + "」"
				+ (bound == 0 ? "，尚未绑定端口" : "，已绑定 " + bound + " 个端口");
		String consequence = bound == 0
				? "将用新文件覆盖证书库中的证书与私钥。"
				: "将用新文件覆盖证书库中的证书与私钥，并同步写入 " + bound
						+ " 个已绑定端口，运行中的实例会自动重启。";
		String reversibility = "可撤销：更新前保留原有文件；任一端口校验或生效失败会整体回滚。";
		if (!ConfirmDialog.confirm(this, "更换证书", object, consequence,
				reversibility, "更换证书")) {
			return;
		}
		final String pem = dlg.pemContent();
		final String key = dlg.keyContent();
		final String newName = dlg.nameText();
		final String newNote = dlg.noteText();
		if (!OperationLock.tryAcquire("更换证书")) {
			JOptionPane.showMessageDialog(this, OperationLock.reason(),
					"nginxUI - 更换证书", JOptionPane.WARNING_MESSAGE);
			return;
		}
		setBusy(true);
		new SwingWorker<ApplyResult, Void>() {
			@Override
			protected ApplyResult doInBackground() {
				return store.updateCert(c.id, pem, key, owner.registryEntries());
			}

			@Override
			protected void done() {
				OperationLock.release();
				setBusy(false);
				ApplyResult r;
				try {
					r = get();
				} catch (Exception ex) {
					JOptionPane.showMessageDialog(CertLibraryPanel.this,
							com.nginxui.utils.ErrorReporter.uiText("更换证书", ex,
									"请检查证书文件与目标配置的访问权限后重试。"),
							"nginxUI - 更换证书", JOptionPane.ERROR_MESSAGE);
					return;
				}
				if (r.success && (!newName.equals(c.displayName()) || !newNote.equals(StrUtil.nullToEmpty(c.note)))) {
					store.updateMeta(c.id, newName, newNote);
				}
				showApplyResult("更换证书", r, true);
				refresh();
				onChanged.run();
			}
		}.execute();
	}

	private void editMeta() {
		ManagedCert c = selectedCert();
		if (c == null) {
			return;
		}
		JTextField tfName = new JTextField(c.displayName(), 26);
		JTextField tfNote = new JTextField(StrUtil.nullToEmpty(c.note), 26);
		JPanel p = new JPanel(new GridBagLayout());
		GridBagConstraints gc = new GridBagConstraints();
		gc.gridx = 0;
		gc.gridy = 0;
		gc.anchor = GridBagConstraints.WEST;
		gc.insets = new Insets(4, 0, 4, 8);
		p.add(new JLabel("名称:"), gc);
		gc.gridx = 1;
		p.add(tfName, gc);
		gc.gridx = 0;
		gc.gridy = 1;
		p.add(new JLabel("备注:"), gc);
		gc.gridx = 1;
		p.add(tfNote, gc);
		int r = JOptionPane.showConfirmDialog(this, p, "nginxUI - 编辑证书信息",
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (r != JOptionPane.OK_OPTION) {
			return;
		}
		if (store.updateMeta(c.id, tfName.getText(), tfNote.getText())) {
			refresh();
		} else {
			JOptionPane.showMessageDialog(this, "保存失败，请查看日志。",
					"nginxUI - 编辑证书信息", JOptionPane.ERROR_MESSAGE);
		}
	}

	private void deleteCert() {
		ManagedCert c = selectedCert();
		if (c == null) {
			return;
		}
		String object = "证书「" + c.displayName() + "」"
				+ (c.bindings.isEmpty() ? "" : "，已绑定 " + c.bindings.size() + " 个端口");
		String consequence = "证书文件将移入 certs/_deleted 目录并从证书库移除"
				+ (c.bindings.isEmpty() ? "。"
						: "；绑定端口关联被解除，端口上已写入的证书文件保持不变。");
		String reversibility = "可撤销：文件移入 certs/_deleted 目录，可手工复制回原位置恢复。";
		if (!ConfirmDialog.confirm(this, "删除证书", object, consequence,
				reversibility, "删除证书")) {
			return;
		}
		if (!OperationLock.tryAcquire("删除证书")) {
			JOptionPane.showMessageDialog(this, OperationLock.reason(),
					"nginxUI - 删除证书", JOptionPane.WARNING_MESSAGE);
			return;
		}
		try {
			ImportResult res = store.delete(c.id);
			JOptionPane.showMessageDialog(this, res.message, "nginxUI - 删除证书",
					res.success ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.ERROR_MESSAGE);
			refresh();
			onChanged.run();
		} finally {
			OperationLock.release();
		}
	}

	private void bindPorts() {
		ManagedCert c = selectedCert();
		if (c == null) {
			return;
		}
		if (scanned.isEmpty()) {
			JOptionPane.showMessageDialog(this, "未扫描到任何端口证书配置。",
					"nginxUI - 绑定端口", JOptionPane.WARNING_MESSAGE);
			return;
		}
		BindPortsDialog dlg = new BindPortsDialog(owner, c, scanned);
		dlg.setVisible(true);
		final List<CertEntry> targets = dlg.selected;
		if (!dlg.confirmed || targets.isEmpty()) {
			return;
		}
		String object = "证书「" + c.displayName() + "」，共 " + targets.size() + " 个端口";
		String consequence = "将把证书写入这些端口的配置并执行 nginx -t 校验；"
				+ "运行中的实例会自动重启使新证书立即生效。";
		String reversibility = "可撤销：任一端口失败会整体回滚，端口原有证书文件保持不变。";
		if (!ConfirmDialog.confirm(this, "绑定端口", object, consequence,
				reversibility, "绑定端口")) {
			return;
		}
		if (!OperationLock.tryAcquire("绑定证书端口")) {
			JOptionPane.showMessageDialog(this, OperationLock.reason(),
					"nginxUI - 绑定端口", JOptionPane.WARNING_MESSAGE);
			return;
		}
		setBusy(true);
		final String certId = c.id;
		new SwingWorker<ApplyResult, Void>() {
			@Override
			protected ApplyResult doInBackground() {
				return store.bind(certId, targets);
			}

			@Override
			protected void done() {
				OperationLock.release();
				setBusy(false);
				ApplyResult r;
				try {
					r = get();
				} catch (Exception ex) {
					JOptionPane.showMessageDialog(CertLibraryPanel.this,
							com.nginxui.utils.ErrorReporter.uiText("绑定端口", ex,
									"请确认端口可用且站点配置可写后重试。"),
							"nginxUI - 绑定端口", JOptionPane.ERROR_MESSAGE);
					return;
				}
				showApplyResult("绑定端口", r, false);
				refresh();
				onChanged.run();
			}
		}.execute();
	}

	private void showApplyResult(String title, ApplyResult r, boolean showAffected) {
		if (!r.success) {
			JOptionPane.showMessageDialog(this, r.message, "nginxUI - " + title, JOptionPane.ERROR_MESSAGE);
			return;
		}
		StringBuilder sb = new StringBuilder(r.message);
		if (showAffected && !r.affected.isEmpty()) {
			sb.append("\n\n已同步端口:\n");
			for (String a : r.affected) {
				sb.append("  · ").append(a).append('\n');
			}
		}
		if (!r.restarted.isEmpty()) {
			sb.append("\n已生效: ").append(String.join("，", r.restarted));
		}
		if (!r.warnings.isEmpty()) {
			sb.append("\n\n").append(String.join("\n", r.warnings));
		}
		JOptionPane.showMessageDialog(this, sb.toString(), "nginxUI - " + title,
				JOptionPane.INFORMATION_MESSAGE);
	}

	private void unbindSelected() {
		ManagedCert c = selectedCert();
		Usage u = selectedUsage();
		if (c == null || u == null || !u.bound) {
			return;
		}
		int r = JOptionPane.showConfirmDialog(this,
				"解除与「" + labelOf(u.entry) + "」的绑定？\n\n"
						+ "仅移除关联记录，端口现有证书文件保持不变。",
				"nginxUI - 解除绑定", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
		if (r != JOptionPane.YES_OPTION) {
			return;
		}
		store.unbind(c.id, CertificateStoreManager.identityOf(u.entry.item));
		refresh();
		onChanged.run();
	}

	private void openCertDir() {
		ManagedCert c = selectedCert();
		if (c == null) {
			return;
		}
		try {
			java.awt.Desktop.getDesktop().open(store.certDir(c.id));
		} catch (Exception e) {
			JOptionPane.showMessageDialog(this,
					com.nginxui.utils.ErrorReporter.uiText("打开目录", e,
							"请确认证书库目录存在且当前账户有访问权限。"),
					"nginxUI - 证书库", JOptionPane.ERROR_MESSAGE);
		}
	}

	private void copyPath() {
		ManagedCert c = selectedCert();
		if (c == null) {
			return;
		}
		try {
			java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
					new java.awt.datatransfer.StringSelection(store.certFile(c.id).getAbsolutePath()), null);
		} catch (Exception ignored) {
		}
	}

	private static String labelOf(CertEntry ce) {
		String name = StrUtil.isEmpty(ce.item.domain) ? "(未声明 server_name)" : ce.item.domain;
		String inst = ce.instance == null ? ""
				: StrUtil.emptyToDefault(ce.instance.getName(), ce.instance.getConfPath());
		return inst + " · " + name + (ce.item.ports.isEmpty() ? "" : ":" + ce.item.portsText());
	}

	// ==================== 证书列表 ====================

	private class CertListModel extends AbstractTableModel {
		private static final long serialVersionUID = 1L;

		@Override
		public int getRowCount() {
			return certs.size();
		}

		@Override
		public int getColumnCount() {
			return LIST_COLUMNS.length;
		}

		@Override
		public String getColumnName(int column) {
			return LIST_COLUMNS[column];
		}

		@Override
		public Object getValueAt(int rowIndex, int columnIndex) {
			ManagedCert c = certs.get(rowIndex);
			switch (columnIndex) {
				case LIST_NAME:
					return c.displayName();
				case LIST_STATUS:
					return c.statusText();
				case LIST_EXPIRY:
					return c.info == null ? "-" : c.info.notAfterText();
				case LIST_LEFT:
					if (c.info == null) {
						return "-";
					}
					return c.info.expired ? "过期 " + Math.abs(c.info.daysLeft) + " 天"
							: c.info.daysLeft + " 天";
				case LIST_REFS:
					return certUsageCounts.getOrDefault(c.id, 0);
				case LIST_NOTE:
					return c.note;
				default:
					return "";
			}
		}
	}

	private class CertRenderer extends DefaultTableCellRenderer {
		private static final long serialVersionUID = 1L;

		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
				boolean hasFocus, int row, int column) {
			Component comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			if (!isSelected && row >= 0 && row < certs.size()) {
				ManagedCert c = certs.get(row);
				if (column == LIST_STATUS || column == LIST_LEFT) {
					comp.setForeground(c.info == null || c.info.expired ? Theme.DANGER
							: c.info.expiringSoon ? Theme.WARNING : Theme.TEXT);
				} else {
					comp.setForeground(Theme.TEXT);
				}
			}
			return comp;
		}
	}

	// ==================== 关联端口列表 ====================

	private class UsageModel extends AbstractTableModel {
		private static final long serialVersionUID = 1L;

		@Override
		public int getRowCount() {
			return usage.size();
		}

		@Override
		public int getColumnCount() {
			return USAGE_COLUMNS.length;
		}

		@Override
		public String getColumnName(int column) {
			return USAGE_COLUMNS[column];
		}

		@Override
		public Object getValueAt(int rowIndex, int columnIndex) {
			Usage u = usage.get(rowIndex);
			switch (columnIndex) {
				case USAGE_STATE:
					return u.stateText();
				case USAGE_INSTANCE:
					return u.entry.instance == null ? ""
							: StrUtil.emptyToDefault(u.entry.instance.getName(),
									u.entry.instance.getConfPath());
				case USAGE_NAME:
					return StrUtil.isEmpty(u.entry.item.domain) ? "(未声明)" : u.entry.item.domain;
				case USAGE_PORT:
					return u.entry.item.portsText();
				case USAGE_CERT:
					return CertManagerPage.shortPath(u.entry.item.pemPath);
				default:
					return "";
			}
		}
	}

	private class UsageRenderer extends DefaultTableCellRenderer {
		private static final long serialVersionUID = 1L;

		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
				boolean hasFocus, int row, int column) {
			Component comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			// 证书路径等长文本被列宽截断时, 悬停可查看完整内容
			if (comp instanceof javax.swing.JComponent) {
				String tip = value == null ? null : String.valueOf(value);
				if (column == USAGE_CERT && row >= 0 && row < usage.size()
						&& usage.get(row).entry.item != null) {
					tip = usage.get(row).entry.item.pemPath;
				}
				((javax.swing.JComponent) comp).setToolTipText(tip);
			}
			if (!isSelected && row >= 0 && row < usage.size() && column == USAGE_STATE) {
				comp.setForeground(usage.get(row).bound ? Theme.TEXT : Theme.TEXT_MUTED);
			} else if (!isSelected) {
				comp.setForeground(Theme.TEXT);
			}
			return comp;
		}
	}

	// ==================== 导入/更换 对话框 ====================

	// ==================== 对话框键盘 ====================

	/** 统一 Esc 取消: 关闭对话框且不写入任何未保存内容 */
	private static void bindEscapeTo(final JDialog dialog) {
		dialog.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
				.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel-dialog");
		dialog.getRootPane().getActionMap().put("cancel-dialog", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent e) {
				dialog.dispose();
			}
		});
	}

	private static class CertEditDialog extends JDialog {
		private static final long serialVersionUID = 1L;

		private final JTextField tfNote = new JTextField(28);
		private final JTextField tfPem = new JTextField(28);
		private final JTextField tfKey = new JTextField(28);
		private final JTextArea taState = new JTextArea(6, 52);
		private String pemContent = "";
		private String keyContent = "";
		/** 系统根据证书 CN 自动解析的名称 */
		private String autoName = "";
		private boolean confirmed;

		CertEditDialog(java.awt.Window owner, String title, String name, String note, ManagedCert current) {
			super(owner, "nginxUI - " + title, ModalityType.APPLICATION_MODAL);
			autoName = StrUtil.nullToEmpty(name);
			tfNote.setText(StrUtil.nullToEmpty(note));
			setDefaultCloseOperation(DISPOSE_ON_CLOSE);
			buildUi(current);
			pack();
			setResizable(false);
			setLocationRelativeTo(owner);
			javax.swing.SwingUtilities.invokeLater(tfNote::requestFocusInWindow);
		}

		private void buildUi(ManagedCert current) {
			JPanel form = new JPanel(new GridBagLayout());
			form.setBorder(BorderFactory.createEmptyBorder(12, 12, 6, 12));
			GridBagConstraints gc = new GridBagConstraints();
			gc.gridx = 0;
			gc.gridy = 0;
			gc.anchor = GridBagConstraints.WEST;
			gc.insets = new Insets(4, 0, 4, 8);
			form.add(new JLabel("备注:"), gc);
			gc.gridx = 1;
			gc.gridwidth = 2;
			gc.fill = GridBagConstraints.HORIZONTAL;
			form.add(tfNote, gc);

			gc.gridy++;
			gc.gridx = 0;
			gc.gridwidth = 1;
			gc.fill = GridBagConstraints.NONE;
			form.add(new JLabel("证书 PEM:"), gc);
			gc.gridx = 1;
			gc.fill = GridBagConstraints.HORIZONTAL;
			tfPem.setEditable(false);
			form.add(tfPem, gc);
			JButton btnPem = new JButton("浏览…");
			btnPem.addActionListener(e -> choose(tfPem, true));
			gc.gridx = 2;
			gc.fill = GridBagConstraints.NONE;
			form.add(btnPem, gc);

			gc.gridy++;
			gc.gridx = 0;
			form.add(new JLabel("私钥 KEY:"), gc);
			gc.gridx = 1;
			gc.fill = GridBagConstraints.HORIZONTAL;
			tfKey.setEditable(false);
			form.add(tfKey, gc);
			JButton btnKey = new JButton("浏览…");
			btnKey.addActionListener(e -> choose(tfKey, false));
			gc.gridx = 2;
			gc.fill = GridBagConstraints.NONE;
			form.add(btnKey, gc);

			gc.gridy++;
			gc.gridx = 0;
			gc.gridwidth = 3;
			gc.fill = GridBagConstraints.BOTH;
			gc.weighty = 1;
			taState.setEditable(false);
			taState.setFont(Theme.BODY);
			taState.setBackground(new Color(252, 252, 252));
			taState.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
			JScrollPane scroll = new JScrollPane(taState);
			scroll.setBorder(BorderFactory.createTitledBorder(
					BorderFactory.createLineBorder(Theme.BORDER), "校验结果"));
			form.add(scroll, gc);

			JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
			buttons.setBorder(BorderFactory.createEmptyBorder(0, 12, 10, 12));
			JButton btnOk = Theme.primaryButton("保存");
			btnOk.addActionListener(e -> onOk());
			JButton btnCancel = new JButton("取消");
			btnCancel.addActionListener(e -> dispose());
			buttons.add(btnOk);
			buttons.add(btnCancel);
			getRootPane().setDefaultButton(btnOk);
			bindEscapeTo(this);

			getContentPane().setLayout(new BorderLayout());
			getContentPane().add(form, BorderLayout.CENTER);
			getContentPane().add(buttons, BorderLayout.SOUTH);

			if (current != null) {
				StringBuilder sb = new StringBuilder();
				sb.append("当前证书: ").append(current.displayName()).append('\n');
				if (current.info != null) {
					sb.append("主题: ").append(current.info.subject).append('\n');
					sb.append("到期: ").append(current.info.notAfterText()).append('\n');
				}
				sb.append("已绑定端口: ").append(current.bindings.size()).append(" 个\n\n");
				sb.append("请选择新的证书与私钥文件；保存后自动同步所有绑定端口。");
				taState.setText(sb.toString());
			} else {
				taState.setText("请选择 PEM 格式的服务器证书与未加密私钥。\n"
						+ "支持 fullchain(证书链)与 RSA/ECC 私钥；不支持 PFX/JKS/加密私钥。");
			}
		}

		private void choose(JTextField target, boolean cert) {
			JFileChooser fc = new JFileChooser();
			fc.setDialogTitle(cert ? "选择证书文件" : "选择私钥文件");
			fc.setFileFilter(new FileNameExtensionFilter(
					cert ? "证书文件 (*.pem, *.crt, *.cer)" : "私钥文件 (*.pem, *.key)",
					cert ? new String[] { "pem", "crt", "cer" } : new String[] { "pem", "key" }));
			if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
				return;
			}
			File f = fc.getSelectedFile();
			target.setText(f.getAbsolutePath());
			if (cert) {
				pemContent = readFile(f);
				Inspect ins = NginxCertReplaceManager.inspectPem(pemContent);
				if (!ins.ok()) {
					taState.setText("证书校验失败: " + ins.error);
					pemContent = "";
					return;
				}
				StringBuilder sb = new StringBuilder();
				sb.append("证书校验通过\n主题: ").append(ins.cert.subject).append('\n');
				sb.append("颁发者: ").append(ins.cert.issuer).append('\n');
				sb.append("可用域名: ").append(ins.cert.domains.isEmpty() ? "-"
						: String.join(", ", ins.cert.domains)).append('\n');
				sb.append("有效期: ").append(ins.cert.notBeforeText()).append(" 至 ")
						.append(ins.cert.notAfterText()).append('\n');
				if (!ins.warnings.isEmpty()) {
					sb.append('\n').append(String.join("\n", ins.warnings));
				}
				taState.setText(sb.toString());
				String cn = ins.cert.cn().isEmpty() ? f.getName() : ins.cert.cn();
				autoName = cn;
				sb.append("\n证书名称(自动): ").append(cn);
				taState.setText(sb.toString());
			} else {
				keyContent = readFile(f);
				Inspect ins = NginxCertReplaceManager.inspectKey(keyContent, null);
				if (!ins.ok()) {
					taState.setText("私钥校验失败: " + ins.error);
					keyContent = "";
					return;
				}
				taState.setText("私钥校验通过，算法: " + ins.keyType);
			}
		}

		private void onOk() {
			if (StrUtil.isEmpty(pemContent) || StrUtil.isEmpty(keyContent)) {
				JOptionPane.showMessageDialog(this, "请先选择并校验证书与私钥文件。",
						"nginxUI - 证书库", JOptionPane.WARNING_MESSAGE);
				return;
			}
			Inspect pc = NginxCertReplaceManager.inspectPem(pemContent);
			if (!pc.ok()) {
				JOptionPane.showMessageDialog(this, pc.error, "nginxUI - 证书库", JOptionPane.WARNING_MESSAGE);
				return;
			}
			Inspect kc = NginxCertReplaceManager.inspectKey(keyContent, null);
			if (!kc.ok()) {
				JOptionPane.showMessageDialog(this, kc.error, "nginxUI - 证书库", JOptionPane.WARNING_MESSAGE);
				return;
			}
			confirmed = true;
			dispose();
		}

		private static String readFile(File f) {
			try {
				if (f == null || !f.isFile()) {
					return "";
				}
				if (f.length() > 20L * 1024 * 1024) {
					return "";
				}
				return FileUtil.readString(f, StandardCharsets.UTF_8);
			} catch (Exception e) {
				return "";
			}
		}

		String nameText() {
			return autoName;
		}

		String noteText() {
			return tfNote.getText();
		}

		String pemSource() {
			return tfPem.getText();
		}

		String pemContent() {
			return pemContent;
		}

		String keyContent() {
			return keyContent;
		}
	}

	// ==================== 绑定端口 对话框 ====================

	private static class BindPortsDialog extends JDialog {
		private static final long serialVersionUID = 1L;

		private final List<CertEntry> rows;
		private final boolean[] checked;
		private final boolean[] bound;
		private final boolean[] live;
		private final boolean[] usable;
		private final BindModel model;
		private List<CertEntry> selected = new ArrayList<CertEntry>();
		private boolean confirmed;

		BindPortsDialog(java.awt.Window owner, ManagedCert cert, List<CertEntry> scanned) {
			super(owner, "nginxUI - 绑定端口", ModalityType.APPLICATION_MODAL);
			rows = scanned;
			int n = rows.size();
			checked = new boolean[n];
			bound = new boolean[n];
			live = new boolean[n];
			usable = new boolean[n];
			Set<String> boundIds = new HashSet<String>();
			for (CertificateStoreManager.Binding b : cert.bindings) {
				boundIds.add(b.identity());
			}
			for (int i = 0; i < n; i++) {
				CertItem item = rows.get(i).item;
				bound[i] = boundIds.contains(CertificateStoreManager.identityOf(item));
				live[i] = StrUtil.isNotEmpty(cert.sha256) && cert.sha256.equalsIgnoreCase(item.pemSha256);
				usable[i] = StrUtil.isNotEmpty(item.pemPath) && StrUtil.isNotEmpty(item.keyPath) && !bound[i];
				// 默认勾选正在使用同一证书的端口, 便于把已有部署纳入关联管理
				checked[i] = usable[i] && live[i];
			}
			model = new BindModel();
			buildUi(cert);
			pack();
			setSize(Math.max(820, getWidth()), 520);
			setLocationRelativeTo(owner);
		}

		private void buildUi(ManagedCert cert) {
			JLabel hint = new JLabel("勾选要绑定该证书的端口；已在使用同一证书的端口默认勾选。"
					+ "写入后统一 nginx -t，失败整体回滚。");
			hint.setFont(Theme.SMALL);
			hint.setForeground(Theme.TEXT_MUTED);
			hint.setBorder(BorderFactory.createEmptyBorder(8, 12, 4, 12));

			JTable table = new JTable(model);
			javax.swing.SwingUtilities.invokeLater(table::requestFocusInWindow);
			table.setRowHeight(26);
			table.setFillsViewportHeight(true);
			table.setShowGrid(false);
			table.setIntercellSpacing(new Dimension(0, 0));
			table.getTableHeader().setReorderingAllowed(false);
			table.getColumnModel().getColumn(0).setMaxWidth(50);
			table.getColumnModel().getColumn(1).setPreferredWidth(180);
			table.getColumnModel().getColumn(2).setPreferredWidth(200);
			table.getColumnModel().getColumn(3).setPreferredWidth(90);
			table.getColumnModel().getColumn(4).setPreferredWidth(280);
			table.getColumnModel().getColumn(5).setPreferredWidth(200);
			JScrollPane scroll = new JScrollPane(table);
			scroll.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));

			JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
			buttons.setBorder(BorderFactory.createEmptyBorder(8, 12, 10, 12));
			JButton btnOk = Theme.primaryButton("绑定");
			btnOk.addActionListener(e -> {
				selected = new ArrayList<CertEntry>();
				for (int i = 0; i < rows.size(); i++) {
					if (checked[i]) {
						selected.add(rows.get(i));
					}
				}
				if (selected.isEmpty()) {
					JOptionPane.showMessageDialog(this, "请至少勾选一个端口。",
							"nginxUI - 绑定端口", JOptionPane.WARNING_MESSAGE);
					return;
				}
				confirmed = true;
				dispose();
			});
			JButton btnCancel = new JButton("取消");
			btnCancel.addActionListener(e -> dispose());
			buttons.add(btnOk);
			buttons.add(btnCancel);
			getRootPane().setDefaultButton(btnOk);
			bindEscapeTo(this);

			getContentPane().setLayout(new BorderLayout());
			getContentPane().add(hint, BorderLayout.NORTH);
			getContentPane().add(scroll, BorderLayout.CENTER);
			getContentPane().add(buttons, BorderLayout.SOUTH);
		}

		private class BindModel extends AbstractTableModel {
			private static final long serialVersionUID = 1L;
			private final String[] cols = { "选择", "实例", "server_name", "端口", "证书文件", "状态" };

			@Override
			public int getRowCount() {
				return rows.size();
			}

			@Override
			public int getColumnCount() {
				return cols.length;
			}

			@Override
			public String getColumnName(int column) {
				return cols[column];
			}

			@Override
			public Class<?> getColumnClass(int columnIndex) {
				return columnIndex == 0 ? Boolean.class : String.class;
			}

			@Override
			public boolean isCellEditable(int rowIndex, int columnIndex) {
				return columnIndex == 0 && usable[rowIndex];
			}

			@Override
			public void setValueAt(Object value, int rowIndex, int columnIndex) {
				if (columnIndex == 0 && usable[rowIndex]) {
					checked[rowIndex] = Boolean.TRUE.equals(value);
					fireTableCellUpdated(rowIndex, columnIndex);
				}
			}

			@Override
			public Object getValueAt(int rowIndex, int columnIndex) {
				CertEntry ce = rows.get(rowIndex);
				switch (columnIndex) {
					case 0:
						return checked[rowIndex];
					case 1:
						return StrUtil.emptyToDefault(ce.instance.getName(), ce.instance.getConfPath());
					case 2:
						return StrUtil.isEmpty(ce.item.domain) ? "(未声明)" : ce.item.domain;
					case 3:
						return ce.item.portsText();
					case 4:
						return ce.item.pemPath;
					case 5:
						if (bound[rowIndex]) {
							return "已绑定";
						}
						if (StrUtil.isEmpty(ce.item.keyPath)) {
							return "缺少私钥";
						}
						if (!ce.item.pemExists) {
							return "证书文件缺失";
						}
						if (live[rowIndex]) {
							return "使用中";
						}
						return "可绑定";
					default:
						return "";
				}
			}
		}
	}
}

