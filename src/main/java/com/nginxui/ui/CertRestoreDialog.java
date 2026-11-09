package com.nginxui.ui;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;

import com.nginxui.ext.NginxInstanceEntry;
import com.nginxui.utils.ErrorReporter;
import com.nginxui.utils.NginxCertReplaceManager;
import com.nginxui.utils.NginxCertReplaceManager.BackupItem;
import com.nginxui.utils.NginxCertReplaceManager.BackupSession;
import com.nginxui.utils.NginxCertReplaceManager.RestoreResult;

import cn.hutool.core.util.StrUtil;

/**
 * 证书备份恢复入口:
 *  列出每次证书替换成功后生成的可恢复清单(时间 / 实例 / 替换方式 / 文件数),
 *  恢复前再次备份当前文件, 写回备份内容后执行 nginx -t, 运行中的实例自动重启,
 *  任一步失败回到恢复前状态.
 *
 * 恢复属于危险动作: 二次确认默认选中「取消」, 回车不会触发恢复.
 */
public class CertRestoreDialog extends JDialog {

	private final MainFrame owner;
	private final NginxCertReplaceManager manager;
	private final Runnable onChanged;

	private final List<BackupSession> sessions = new ArrayList<BackupSession>();
	private SessionTableModel tableModel;
	private JTable table;
	private JTextArea taDetail;
	private JLabel lblStatus;
	private JButton btnRestore;
	private JButton btnOpenDir;
	private JButton btnRefresh;
	private JButton btnClose;

	private boolean busy;

	public CertRestoreDialog(MainFrame owner, Runnable onChanged) {
		super(owner, "nginxUI - 恢复证书备份", true);
		this.owner = owner;
		this.onChanged = onChanged;
		this.manager = new NginxCertReplaceManager(new File(owner.appHome()));
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		setSize(920, 600);
		setMinimumSize(new Dimension(760, 520));
		buildUi();
		bindEscape();
		setLocationRelativeTo(owner);
		refreshData();
		// 初始焦点落在列表: 方向键即可选行, 回车进入恢复确认
		javax.swing.SwingUtilities.invokeLater(() -> {
			if (table != null) {
				table.requestFocusInWindow();
			}
		});
	}

	// ==================== 界面 ====================

	private void buildUi() {
		setLayout(new BorderLayout());
		add(buildHeader(), BorderLayout.NORTH);
		add(buildCenter(), BorderLayout.CENTER);
		add(buildButtons(), BorderLayout.SOUTH);
	}

	private JPanel buildHeader() {
		JLabel title = new JLabel("恢复证书备份");
		title.setFont(Theme.TITLE);
		JLabel hint = new JLabel("<html>仅列出证书替换成功后生成的备份清单。恢复会先备份当前文件，"
				+ "写入备份内容后执行 nginx -t；失败自动回到恢复前状态。</html>");
		hint.setFont(Theme.SMALL);
		hint.setForeground(Theme.TEXT_MUTED);

		JPanel text = new JPanel(new BorderLayout(0, 4));
		text.setOpaque(false);
		text.add(title, BorderLayout.NORTH);
		text.add(hint, BorderLayout.CENTER);

		JPanel p = new JPanel(new BorderLayout());
		p.setOpaque(false);
		p.setBorder(BorderFactory.createEmptyBorder(14, 14, 6, 14));
		p.add(text, BorderLayout.CENTER);
		return p;
	}

	private JPanel buildCenter() {
		tableModel = new SessionTableModel();
		table = new JTable(tableModel);
		table.setRowHeight(30);
		table.setFillsViewportHeight(true);
		table.setShowGrid(false);
		table.setIntercellSpacing(new Dimension(0, 0));
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.getTableHeader().setReorderingAllowed(false);
		table.setFont(Theme.BODY);
		table.getSelectionModel().addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting()) {
				updateDetail();
			}
		});
		table.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2 && table.getSelectedRow() >= 0) {
					restoreSelected();
				}
			}
		});

		int[] widths = { 150, 220, 130, 80, 110 };
		for (int i = 0; i < widths.length; i++) {
			TableColumn column = table.getColumnModel().getColumn(i);
			column.setPreferredWidth(widths[i]);
		}
		table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);

		JScrollPane tableScroll = new JScrollPane(table);
		tableScroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
		tableScroll.setMinimumSize(new Dimension(0, 140));

		taDetail = new JTextArea("在上方列表中选择一条备份记录以查看详情。");
		taDetail.setEditable(false);
		taDetail.setLineWrap(true);
		taDetail.setWrapStyleWord(true);
		taDetail.setFont(Theme.MONO);
		taDetail.setForeground(Theme.TEXT_MUTED);
		taDetail.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
		JScrollPane detailScroll = new JScrollPane(taDetail);
		detailScroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
		detailScroll.setMinimumSize(new Dimension(0, 120));
		detailScroll.getVerticalScrollBar().setUnitIncrement(16);

		JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, detailScroll);
		split.setResizeWeight(0.6);
		split.setDividerLocation(0.6);
		split.setDividerSize(8);
		split.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 14));

		lblStatus = new JLabel(" ");
		lblStatus.setFont(Theme.SMALL);
		lblStatus.setForeground(Theme.TEXT_MUTED);

		JPanel statusWrap = new JPanel(new BorderLayout());
		statusWrap.setOpaque(false);
		statusWrap.setBorder(BorderFactory.createEmptyBorder(6, 14, 0, 14));
		statusWrap.add(lblStatus, BorderLayout.WEST);

		JPanel p = new JPanel(new BorderLayout());
		p.setOpaque(false);
		p.add(split, BorderLayout.CENTER);
		p.add(statusWrap, BorderLayout.SOUTH);
		return p;
	}

	private JPanel buildButtons() {
		btnRestore = Theme.primaryButton("恢复此备份…");
		btnRestore.setToolTipText("把所选备份的证书、私钥与配置写回原路径，并通过 nginx -t 校验");
		Theme.setEnabled(btnRestore, false, "未选择备份：先在上方列表中选择一条备份记录");
		btnRestore.addActionListener(e -> restoreSelected());

		btnOpenDir = Theme.secondaryButton("打开备份目录");
		btnOpenDir.setToolTipText("在资源管理器中打开备份清单与备份文件目录");
		btnOpenDir.addActionListener(e -> openBackupDir());

		btnRefresh = Theme.secondaryButton("刷新列表");
		btnRefresh.addActionListener(e -> refreshData());

		btnClose = new JButton("关闭");
		btnClose.addActionListener(e -> {
			if (!busy) {
				dispose();
			}
		});

		JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		right.setOpaque(false);
		right.add(btnRestore);
		right.add(btnOpenDir);
		right.add(btnRefresh);
		right.add(btnClose);

		JPanel p = new JPanel(new BorderLayout());
		p.setOpaque(false);
		p.setBorder(BorderFactory.createEmptyBorder(8, 14, 12, 14));
		p.add(right, BorderLayout.EAST);
		return p;
	}

	private void bindEscape() {
		getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
				.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel-dialog");
		getRootPane().getActionMap().put("cancel-dialog", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent e) {
				if (!busy) {
					dispose();
				}
			}
		});
		// 回车触发恢复(恢复前仍有二次确认); 未选中时默认按钮自身禁用, 回车无动作
		getRootPane().setDefaultButton(btnRestore);
	}

	// ==================== 数据 ====================

	private void refreshData() {
		if (busy) {
			return;
		}
		setBusy(true);
		lblStatus.setText("正在读取备份清单…");
		new SwingWorker<List<BackupSession>, Void>() {
			@Override
			protected List<BackupSession> doInBackground() {
				return manager.listBackupSessions();
			}

			@Override
			protected void done() {
				setBusy(false);
				List<BackupSession> result;
				try {
					result = get();
				} catch (Exception ex) {
					lblStatus.setForeground(Theme.DANGER);
					lblStatus.setText("读取备份清单失败：" + ErrorReporter.friendlyReason(ex));
					return;
				}
				sessions.clear();
				sessions.addAll(result);
				tableModel.fireTableDataChanged();
				if (sessions.isEmpty()) {
					lblStatus.setForeground(Theme.TEXT_MUTED);
					lblStatus.setText("暂无可用备份：证书替换成功后会自动生成可恢复清单。");
				} else {
					lblStatus.setForeground(Theme.TEXT_MUTED);
					lblStatus.setText("共 " + sessions.size() + " 条可恢复备份（按时间倒序）。"
							+ "恢复前会再次备份当前文件。");
					table.setRowSelectionInterval(0, 0);
				}
				updateDetail();
			}
		}.execute();
	}

	private void setBusy(boolean value) {
		busy = value;
		String reason = value ? "正在读取备份清单，请稍候" : null;
		Theme.setEnabled(table, !value, reason);
		Theme.setEnabled(btnRefresh, !value, reason);
		Theme.setEnabled(btnOpenDir, !value, reason);
		Theme.setEnabled(btnClose, !value, reason);
		updateRestoreState();
		setCursor(Cursor.getPredefinedCursor(value ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));
	}

	/** 恢复按钮的唯一启用入口: 同时给出未选中/执行中的原因 */
	private void updateRestoreState() {
		String reason;
		if (busy) {
			reason = "正在读取备份清单，请稍候";
		} else if (selectedSession() == null) {
			reason = "未选择备份：先在上方列表中选择一条备份记录";
		} else {
			reason = null;
		}
		Theme.setEnabled(btnRestore, reason == null, reason);
	}

	private BackupSession selectedSession() {
		int view = table.getSelectedRow();
		if (view < 0) {
			return null;
		}
		int model = table.convertRowIndexToModel(view);
		return model >= 0 && model < sessions.size() ? sessions.get(model) : null;
	}

	private void updateDetail() {
		BackupSession session = selectedSession();
		updateRestoreState();
		if (session == null) {
			taDetail.setForeground(Theme.TEXT_MUTED);
			taDetail.setText("在上方列表中选择一条备份记录以查看详情。");
			return;
		}
		taDetail.setForeground(Theme.TEXT);
		taDetail.setText(detailText(session));
		taDetail.setCaretPosition(0);
	}

	private String detailText(BackupSession session) {
		StringBuilder sb = new StringBuilder();
		sb.append("实例: ").append(session.displayName()).append('\n');
		sb.append("替换方式: ").append(session.modeText()).append('\n');
		sb.append("备份时间: ").append(session.timeText()).append('\n');
		sb.append("配置文件: ").append(StrUtil.emptyToDefault(session.confPath, "-")).append('\n');
		sb.append("备份清单: ").append(session.manifest == null ? "-" : session.manifest.getAbsolutePath())
				.append("\n\n恢复文件（原路径 ← 备份）：\n");
		for (BackupItem item : session.items) {
			sb.append("  [").append(item.kindText()).append("] ");
			sb.append(item.originalPath);
			sb.append(new File(item.backupPath).isFile() ? "" : "  [备份缺失]");
			sb.append('\n');
			sb.append("      ← ").append(item.backupPath).append('\n');
		}
		return sb.toString();
	}

	private void openBackupDir() {
		File dir = new File(new File(owner.appHome(), "certbak"), "manifests");
		if (!dir.isDirectory()) {
			JOptionPane.showMessageDialog(this, "备份目录不存在: " + dir.getAbsolutePath(),
					"nginxUI - 证书备份", JOptionPane.WARNING_MESSAGE);
			return;
		}
		try {
			java.awt.Desktop.getDesktop().open(dir);
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(this,
					ErrorReporter.uiText("打开目录", ex, "请确认备份目录存在且当前账户有访问权限。"),
					"nginxUI - 证书备份", JOptionPane.ERROR_MESSAGE);
		}
	}

	// ==================== 恢复 ====================

	private void restoreSelected() {
		BackupSession session = selectedSession();
		if (session == null || busy) {
			return;
		}
		for (BackupItem item : session.items) {
			if (!new File(item.backupPath).isFile()) {
				JOptionPane.showMessageDialog(this,
						"备份文件缺失，无法恢复：\n" + item.backupPath,
						"nginxUI - 恢复证书备份", JOptionPane.ERROR_MESSAGE);
				return;
			}
		}

		StringBuilder object = new StringBuilder();
		object.append("「").append(session.displayName()).append("」在 ")
				.append(session.timeText()).append(" 的证书替换备份，共 ")
				.append(session.items.size()).append(" 个文件：\n");
		for (BackupItem item : session.items) {
			object.append("· [").append(item.kindText()).append("] ").append(item.originalPath).append('\n');
		}
		String consequence = "将用备份文件覆盖当前证书与私钥，随后执行 nginx -t 校验；"
				+ "实例运行中会自动重启使恢复内容生效。";
		String reversibility = "可撤销：恢复前会自动备份当前文件；校验或生效失败会回到恢复前状态。";

		// 危险动作: 默认焦点在「取消」, Esc 取消, 回车不会执行恢复
		if (!ConfirmDialog.confirm(this, "确认恢复证书备份", object.toString(),
				consequence, reversibility, "恢复")) {
			return;
		}

		if (!OperationLock.tryAcquire("恢复证书备份")) {
			showError("nginxUI - 恢复证书备份", OperationLock.reason());
			return;
		}
		final BackupSession target = session;
		setBusy(true);
		lblStatus.setForeground(Theme.TEXT_MUTED);
		lblStatus.setText("正在恢复备份并校验配置…");
		new SwingWorker<RestoreResult, Void>() {
			@Override
			protected RestoreResult doInBackground() {
				return manager.restore(target, currentEntryFor(target));
			}

			@Override
			protected void done() {
				OperationLock.release();
				setBusy(false);
				RestoreResult r;
				try {
					r = get();
				} catch (Exception ex) {
					lblStatus.setForeground(Theme.DANGER);
					lblStatus.setText("恢复执行失败：" + ErrorReporter.friendlyReason(ex));
					showError("nginxUI - 恢复证书备份失败",
							ErrorReporter.uiText("恢复证书备份", ex, "请确认备份文件完整，或重新选择其它备份后重试。"));
					return;
				}
				if (!r.success) {
					lblStatus.setForeground(Theme.DANGER);
					lblStatus.setText(StrUtil.emptyToDefault(r.message, "恢复失败"));
					showError("nginxUI - 恢复证书备份失败", r.message
							+ (r.rolledBack ? "\n\n已回到恢复前状态。" : "")
							+ (StrUtil.isEmpty(r.output) ? "" : "\n\nnginx -t 输出:\n" + r.output));
					return;
				}
				lblStatus.setForeground(Theme.statusColor(true));
				lblStatus.setText(r.message);
				owner.onUpgradeFinished();
				if (onChanged != null) {
					onChanged.run();
				}
				JOptionPane.showMessageDialog(CertRestoreDialog.this, r.message,
						"nginxUI - 恢复证书备份成功", JOptionPane.INFORMATION_MESSAGE);
				refreshData();
			}
		}.execute();
	}

	/** 优先复用当前已纳管实例(保证 conf/prefix/nginxExe 与运行状态一致), 否则按清单重建 */
	private NginxInstanceEntry currentEntryFor(BackupSession session) {
		for (NginxInstanceEntry entry : owner.registryEntries()) {
			boolean sameId = StrUtil.isNotEmpty(session.entryId)
					&& StrUtil.equals(session.entryId, entry.getId());
			boolean sameConf = StrUtil.isNotEmpty(session.confPath)
					&& StrUtil.equalsIgnoreCase(session.confPath, entry.getConfPath());
			if (sameId || sameConf) {
				return entry;
			}
		}
		return null;
	}

	private void showError(String title, String message) {
		JTextArea ta = new JTextArea(StrUtil.nullToEmpty(message));
		ta.setEditable(false);
		ta.setLineWrap(true);
		ta.setWrapStyleWord(true);
		ta.setFont(Theme.MONO);
		JScrollPane sp = new JScrollPane(ta);
		sp.setPreferredSize(new Dimension(600, 220));
		JOptionPane.showMessageDialog(this, sp, title, JOptionPane.ERROR_MESSAGE);
	}

	// ==================== 表格模型 ====================

	private class SessionTableModel extends AbstractTableModel {
		private static final long serialVersionUID = 1L;
		private final String[] columns = { "备份时间", "实例", "替换方式", "文件数", "备份完整性" };

		@Override
		public int getRowCount() {
			return sessions.size();
		}

		@Override
		public int getColumnCount() {
			return columns.length;
		}

		@Override
		public String getColumnName(int column) {
			return columns[column];
		}

		@Override
		public Object getValueAt(int rowIndex, int columnIndex) {
			BackupSession session = sessions.get(rowIndex);
			switch (columnIndex) {
				case 0:
					return session.timeText();
				case 1:
					return session.displayName();
				case 2:
					return session.modeText();
				case 3:
					return session.items.size();
				case 4:
					return integrity(session);
				default:
					return "";
			}
		}

		/** 备份文件全部存在才视为可恢复 */
		private String integrity(BackupSession session) {
			for (BackupItem item : session.items) {
				if (!new File(item.backupPath).isFile()) {
					return "备份文件缺失";
				}
			}
			return "可恢复";
		}
	}
}
