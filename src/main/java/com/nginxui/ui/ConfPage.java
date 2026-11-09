package com.nginxui.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.undo.UndoManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nginxui.ext.NginxDashboardInstance;
import com.nginxui.ext.NginxInfo;
import com.nginxui.ext.NginxInstanceEntry;
import com.nginxui.utils.NginxDashboardUtils;
import com.nginxui.utils.WindowsNginxServiceUtils;

import cn.hutool.core.util.StrUtil;

/**
 * 配置文件编辑页(替代原来的「用系统编辑器打开 nginx.conf」):
 *  - 顶部实例下拉 + 运行状态标签(PID / Worker / 版本);
 *  - 只读查看 nginx.conf, 点击「编辑」后可修改;
 *  - 保存前先用 nginx -t 检测待写入内容, 未通过不写盘;
 *  - 编辑期支持撤销/重做(Ctrl+Z / Ctrl+Y)与「还原」, 离开页面前未保存修改会确认;
 *  - 写盘先留档 .nguibak 并原子替换, 写失败用原内容回滚;
 *  - 「重新启动」直接复用主窗口的重启流程(含二次确认与防重复触发).
 */
public class ConfPage extends JPanel {

	private static final Logger LOG = LoggerFactory.getLogger(ConfPage.class);
	private static final Color GREEN_TEXT = Theme.STATUS_RUNNING_TEXT;
	private static final Color GREEN_BG = Theme.SUCCESS_BG;
	private static final Color GREEN_BORDER = Theme.SUCCESS_BORDER;
	private static final Color BLUE_BG = new Color(0xEFF6FF);
	private static final Color BLUE_BORDER = new Color(0xBFDBFE);
	private static final Color FIELD_BG = new Color(0xFAFBFC);
	private static final int FIELD_HEIGHT = 28;

	private final MainFrame mf;
	private final InstancePicker picker;
	/** 标题行左右两块: 用于按可用宽度自适应收起次要信息 */
	private JPanel headerLeft;
	private JPanel headerActions;

	private final InstanceOverviewPanel.Tag tagStatus = new InstanceOverviewPanel.Tag(
			"—", Theme.TEXT_MUTED, Theme.BG, Theme.BORDER);
	private final InstanceOverviewPanel.Tag tagService = new InstanceOverviewPanel.Tag(
			"Windows 服务", Theme.PRIMARY, BLUE_BG, BLUE_BORDER);
	private final JLabel lblPid = Theme.label("PID: —", Theme.BODY, Theme.TEXT_MUTED);
	private final JLabel lblWorker = Theme.label("Worker 进程: —", Theme.BODY, Theme.TEXT_MUTED);
	private final JLabel lblVersion = Theme.label("—", Theme.BODY, Theme.TEXT_MUTED);

	private final JButton btnRead = Theme.toolButton("读取文件");
	/** 配置测试: 对当前实例执行 nginx -t(只检测, 不写盘) */
	private final JButton btnTestConfig = Theme.toolButton("配置测试");
	private final JButton btnEdit = Theme.toolButton("编辑");
	private final JButton btnRestart = Theme.toolButton("重新启动");
	/** 编辑态下的「还原」: 回到进入编辑时的磁盘内容 */
	private final JButton btnRevert = Theme.compactButton("还原");
	/** 编辑期撤销/重做栈: Ctrl+Z / Ctrl+Y 回退上一步 */
	private final UndoManager undo = new UndoManager();

	private final JTextField tfConf = field();
	private final JTextField tfDir = field();
	private final JTextField tfExe = field();
	private final InstanceOverviewPanel.Tag tagRun = new InstanceOverviewPanel.Tag(
			"—", Theme.TEXT_MUTED, Theme.BG, Theme.BORDER);

	private final JLabel lblMode = Theme.label("当前为只读查看，点击「编辑」可修改", Theme.SMALL, Theme.TEXT_MUTED);
	private final JLabel lblMsg = Theme.label(" ", Theme.SMALL, Theme.TEXT_MUTED);
	private final JTextArea taConf = new JTextArea();

	private NginxInstanceEntry currentEntry;
	/** 磁盘上的内容: 用于判断是否有未保存修改 */
	private String loadedText = "";
	/** 编辑态 */
	private boolean editing;
	/** 读取/保存/重启执行中: 期间忽略重复触发 */
	private boolean busy;
	/** 读取期间又有新的读取请求(切换实例/点击读取): 完成后补读一次 */
	private boolean pendingRead;
	/** 配置文件不存在: 此时不允许进入编辑态 */
	private boolean fileMissing;
	private int seq;

	public ConfPage(MainFrame mf) {
		super(new BorderLayout(0, Theme.GAP_S));
		this.mf = mf;
		setOpaque(true);
		setBackground(Theme.BG);
		setBorder(BorderFactory.createEmptyBorder(Theme.GAP_S, 12, Theme.GAP_M, 12));

		picker = new InstancePicker(mf, 150);
		picker.setNameOnly();
		picker.setOnChanged(this::onEntryChanged);

		add(buildHeader(), BorderLayout.NORTH);
		add(buildCard(), BorderLayout.CENTER);
		// 窄窗口时逐级收起次要信息, 避免标题行与右侧实例选择器互相挤压
		addComponentListener(new ComponentAdapter() {
			@Override
			public void componentResized(ComponentEvent e) {
				adaptHeaderWidth(getWidth());
			}

			@Override
			public void componentShown(ComponentEvent e) {
				// 页面首次显示时宽度才确定(异步状态回填可能早于布局完成)
				adaptHeaderWidth(getWidth());
			}
		});
		showEmpty();
	}

	// ==================== 布局 ====================

	private JPanel buildHeader() {
		JPanel header = new JPanel(new WrapRowLayout(4, 6));
		header.setOpaque(false);
		header.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));

		JLabel title = Theme.label("配置文件编辑", Theme.TITLE.deriveFont(Font.BOLD, 18f), Theme.TEXT);
		JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		left.setOpaque(false);
		tagStatus.setFont(Theme.SMALL);
		tagService.setFont(Theme.SMALL);
		left.add(title);
		left.add(tagStatus);
		left.add(tagService);
		left.add(lblPid);
		left.add(lblWorker);
		left.add(lblVersion);

		btnTestConfig.setToolTipText("对当前实例执行 nginx -t 配置检测（只检测，不写盘）");
		btnTestConfig.addActionListener(e -> {
			if (currentEntry != null && !busy && !editing) {
				mf.testInstanceEntry(currentEntry);
			}
		});
		btnRead.setToolTipText("重新从磁盘读取当前配置文件");
		btnEdit.setToolTipText("进入编辑模式；保存前会执行 nginx -t 检测");
		btnRestart.setToolTipText("重启该实例使配置生效");
		btnRead.addActionListener(e -> readFile(true));
		btnEdit.addActionListener(e -> toggleEdit());
		btnRestart.addActionListener(e -> restartInstance());
		// 标题行按钮压缩左右内边距, 让 940 内容宽下仍能完整容纳状态信息
		Insets pad = new Insets(4, 6, 4, 6);
		btnTestConfig.setMargin(pad);
		btnRead.setMargin(pad);
		btnEdit.setMargin(pad);
		btnRestart.setMargin(pad);
		stylePrimary(btnEdit);
		styleWarning(btnRestart);

		JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		actions.setOpaque(false);
		actions.add(picker);
		actions.add(btnTestConfig);
		actions.add(btnRead);
		actions.add(btnEdit);
		actions.add(btnRestart);

		headerLeft = left;
		headerActions = actions;
		header.add(left);
		header.add(actions);
		return header;
	}

	/**
	 * 标题行自适应:
	 *  1) 能同排放下时, 依次收起 版本 → Worker → PID 保持单行;
	 *  2) 界面缩放/窗口过窄导致单行必然放不下时, 折叠为两行(操作行右对齐),
	 *     左行独占整行宽度后重新显示全部信息, 避免标签被下拉框压住。
	 */
	private void adaptHeaderWidth(int width) {
		if (headerLeft == null || headerActions == null) {
			return;
		}
		// 可用宽度不含页面内边距
		int avail = width - getInsets().left - getInsets().right;
		if (avail <= 0) {
			return;
		}
		int actionsW = headerActions.getPreferredSize().width;
		boolean[] vis = { true, true, true };
		int leftW = applyInfoVisible(vis);
		// 单行: 依次收起版本 / Worker / PID
		boolean single = leftW + actionsW + 4 <= avail;
		for (int i = 2; i >= 0 && !single; i--) {
			vis[i] = false;
			leftW = applyInfoVisible(vis);
			single = leftW + actionsW + 4 <= avail;
		}
		if (!single) {
			// 折成两行: 左行可用整行宽度, 先把信息全部恢复, 仍放不下再按同序收起
			vis = new boolean[] { true, true, true };
			leftW = applyInfoVisible(vis);
			for (int i = 2; i >= 0 && leftW > avail; i--) {
				vis[i] = false;
				leftW = applyInfoVisible(vis);
			}
		}
		headerLeft.revalidate();
		revalidate();
		repaint();
	}

	private int applyInfoVisible(boolean[] vis) {
		lblPid.setVisible(vis[0]);
		lblWorker.setVisible(vis[1]);
		lblVersion.setVisible(vis[2]);
		headerLeft.invalidate();
		return headerLeft.getPreferredSize().width;
	}

	private JPanel buildCard() {
		JPanel card = new JPanel(new BorderLayout(0, 10));
		card.setBackground(Theme.SURFACE);
		card.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(Theme.BORDER),
				BorderFactory.createEmptyBorder(12, 14, 12, 14)));

		JLabel notice = Theme.label("  保存前将执行 nginx -t 检测，未通过不会写入",
				Theme.SMALL, Theme.TEXT_MUTED);
		notice.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));

		JPanel top = new JPanel(new BorderLayout(0, 8));
		top.setOpaque(false);
		top.add(notice, BorderLayout.NORTH);
		top.add(buildInfoGrid(), BorderLayout.CENTER);

		card.add(top, BorderLayout.NORTH);
		card.add(buildEditor(), BorderLayout.CENTER);
		card.add(lblMsg, BorderLayout.SOUTH);
		return card;
	}

	/** 两列信息: nginx.conf 路径 / nginx 目录, nginx 执行命令 / nginx 运行状态 */
	private JPanel buildInfoGrid() {
		JPanel grid = new JPanel(new GridBagLayout());
		grid.setOpaque(false);

		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(3, 0, 3, 8);
		c.anchor = GridBagConstraints.WEST;

		// 第 1 行
		c.gridy = 0;
		c.gridx = 0;
		c.weightx = 0;
		grid.add(label("nginx.conf 路径"), c);
		c.gridx = 1;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		grid.add(tfConf, c);
		c.gridx = 2;
		c.weightx = 0;
		c.fill = GridBagConstraints.NONE;
		c.insets = new Insets(3, 16, 3, 8);
		grid.add(label("nginx 目录"), c);
		c.gridx = 3;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.insets = new Insets(3, 0, 3, 0);
		grid.add(tfDir, c);

		// 第 2 行
		c.gridy = 1;
		c.gridx = 0;
		c.weightx = 0;
		c.fill = GridBagConstraints.NONE;
		c.insets = new Insets(3, 0, 3, 8);
		grid.add(label("nginx 执行命令"), c);
		c.gridx = 1;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		grid.add(tfExe, c);
		c.gridx = 2;
		c.weightx = 0;
		c.fill = GridBagConstraints.NONE;
		c.insets = new Insets(3, 16, 3, 8);
		grid.add(label("nginx 运行状态"), c);
		c.gridx = 3;
		c.weightx = 0;
		c.fill = GridBagConstraints.NONE;
		c.insets = new Insets(3, 0, 3, 0);
		tagRun.setFont(Theme.SMALL);
		grid.add(tagRun, c);
		return grid;
	}

	private JPanel buildEditor() {
		JLabel section = Theme.label("读取文件", Theme.BODY_BOLD, Theme.TEXT);

		btnRevert.setVisible(false);
		btnRevert.setToolTipText("放弃未保存的修改，恢复到修改前的内容");
		btnRevert.addActionListener(e -> revertEdits());
		JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		modeRow.setOpaque(false);
		modeRow.add(lblMode);
		modeRow.add(btnRevert);

		JPanel head = new JPanel(new BorderLayout());
		head.setOpaque(false);
		head.setBorder(BorderFactory.createEmptyBorder(4, 0, 6, 0));
		head.add(section, BorderLayout.WEST);
		head.add(modeRow, BorderLayout.EAST);

		taConf.setEditable(false);
		taConf.setFont(Theme.MONO);
		taConf.setTabSize(4);
		taConf.setLineWrap(false);
		taConf.setBackground(Theme.SURFACE);
		taConf.setForeground(Theme.TEXT);
		taConf.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
		taConf.getDocument().addUndoableEditListener(e -> undo.addEdit(e.getEdit()));
		taConf.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				updateRevert();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				updateRevert();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				updateRevert();
			}
		});
		installUndoKeys();
		JScrollPane scroll = new JScrollPane(taConf);
		scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
		scroll.getVerticalScrollBar().setUnitIncrement(16);

		JPanel editor = new JPanel(new BorderLayout());
		editor.setOpaque(false);
		editor.add(head, BorderLayout.NORTH);
		editor.add(scroll, BorderLayout.CENTER);
		return editor;
	}

	/** 编辑区撤销/重做快捷键: 只在编辑态生效, 避免只读时误改 */
	private void installUndoKeys() {
		javax.swing.InputMap im = taConf.getInputMap(JComponent.WHEN_FOCUSED);
		javax.swing.ActionMap am = taConf.getActionMap();
		im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "conf-undo");
		im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "conf-redo");
		im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z,
				InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "conf-redo");
		am.put("conf-undo", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent e) {
				if (editing && undo.canUndo()) {
					undo.undo();
					setMsg("已回退上一步修改", Theme.TEXT_MUTED);
				}
			}
		});
		am.put("conf-redo", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent e) {
				if (editing && undo.canRedo()) {
					undo.redo();
					setMsg("已重做修改", Theme.TEXT_MUTED);
				}
			}
		});
	}

	/** 「还原」显隐/可用: 仅编辑态且内容与磁盘不一致时可用 */
	private void updateRevert() {
		btnRevert.setVisible(editing);
		boolean dirty = editing && !taConf.getText().equals(loadedText);
		Theme.setEnabled(btnRevert, dirty, dirty ? null : "没有未保存的修改，无需还原");
	}

	private static JLabel label(String text) {
		JLabel l = Theme.label(text, Theme.SMALL, Theme.TEXT_MUTED);
		l.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
		return l;
	}

	private static JTextField field() {
		JTextField tf = new JTextField();
		tf.setEditable(false);
		tf.setFont(Theme.BODY);
		tf.setForeground(Theme.TEXT);
		tf.setBackground(FIELD_BG);
		tf.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(Theme.BORDER),
				BorderFactory.createEmptyBorder(2, 8, 2, 8)));
		tf.setPreferredSize(new Dimension(120, FIELD_HEIGHT));
		return tf;
	}

	private static void stylePrimary(JButton b) {
		b.setBackground(Theme.PRIMARY);
		b.setForeground(Color.WHITE);
		b.setOpaque(true);
	}

	private static void styleWarning(JButton b) {
		b.setBackground(Theme.WARNING);
		b.setForeground(Color.WHITE);
		b.setOpaque(true);
	}

	// ==================== 页面生命周期 ====================

	public void onShown() {
		onShown(null);
	}

	public void onShown(NginxInstanceEntry preferred) {
		picker.refresh(preferred == null ? null : preferred.getId());
		onEntryChanged();
	}

	/** 后台刷新后同步下拉与状态; 编辑中不改动用户已修改的文本 */
	public void onRowsChanged() {
		picker.refresh(currentEntry == null ? null : currentEntry.getId());
		if (isShowing()) {
			onEntryChanged();
		}
	}

	private void onEntryChanged() {
		MainFrame.InstanceRow row = picker.selected();
		NginxInstanceEntry entry = row == null ? null : row.entry;
		// 按 id 判断实例切换: 后台刷新会重建实例对象, 引用比较会误判
		boolean changed = !sameEntry(currentEntry, entry);
		if (changed && editing) {
			// 切换实例会丢弃未保存的编辑, 避免把 A 的内容写进 B 的配置; 先征得确认
			if (!confirmDiscard("切换实例")) {
				// 撤销下拉选择, 保持停留在原实例
				picker.refresh(currentEntry == null ? null : currentEntry.getId());
				return;
			}
			discardEdits();
		}
		currentEntry = entry;
		if (entry == null) {
			showEmpty();
			return;
		}
		updatePaths(entry);
		if (changed) {
			readFile(false);
		}
		refreshStatus();
	}

	private void updatePaths(NginxInstanceEntry entry) {
		tfConf.setText(StrUtil.emptyToDefault(entry.getConfPath(), "—"));
		String dir = StrUtil.emptyToDefault(entry.getNginxDir(), entry.getPrefixDir());
		tfDir.setText(StrUtil.emptyToDefault(dir, "—"));
		tfExe.setText(StrUtil.emptyToDefault(entry.getNginxExe(), "—"));
	}

	private void showEmpty() {
		busy = false;
		editing = false;
		loadedText = "";
		fileMissing = false;
		pendingRead = false;
		tagStatus.setStyle("—", Theme.TEXT_MUTED, Theme.BG, Theme.BORDER);
		tagRun.setStyle("—", Theme.TEXT_MUTED, Theme.BG, Theme.BORDER);
		tagService.setVisible(false);
		lblPid.setText("PID: —");
		lblWorker.setText("Worker 进程: —");
		lblVersion.setText("—");
		tfConf.setText("");
		tfDir.setText("");
		tfExe.setText("");
		taConf.setText("当前没有可查看的 Nginx 实例");
		taConf.setEditable(false);
		String reason = "当前没有可查看的实例：先在实例列表中选择一个实例";
		Theme.setEnabled(btnRead, false, reason);
		Theme.setEnabled(btnEdit, false, reason);
		btnEdit.setText("编辑");
		Theme.setEnabled(btnRestart, false, reason);
		Theme.setEnabled(btnTestConfig, false, reason);
		lblMode.setText("当前为只读查看，点击「编辑」可修改");
		undo.discardAllEdits();
		updateRevert();
		revalidate();
	}

	// ==================== 读取 ====================

	private void readFile(boolean userInitiated) {
		final NginxInstanceEntry entry = currentEntry;
		if (entry == null) {
			return;
		}
		if (busy) {
			// 读取进行中: 记一次待读, 完成后按最新实例补读, 避免切换实例后停在旧内容
			pendingRead = true;
			return;
		}
		if (userInitiated && editing && !taConf.getText().equals(loadedText)) {
			if (!ConfirmDialog.confirm(this, "读取文件", "当前实例的未保存修改",
					"重新读取会丢弃编辑区中尚未保存的修改，回到磁盘上的内容。",
					"不可撤销：未保存的修改不会保留，也无法找回。", "重新读取")) {
				return;
			}
			exitEditMode();
		}
		final File file = new File(StrUtil.nullToEmpty(entry.getConfPath()));
		fileMissing = !file.isFile();
		if (fileMissing) {
			taConf.setEditable(false);
			taConf.setText("配置文件不存在: " + file.getAbsolutePath());
			editing = false;
			btnEdit.setText("编辑");
			lblMode.setText("文件不存在");
			undo.discardAllEdits();
			updateRevert();
			updateButtons();
			return;
		}

		busy = true;
		updateButtons();
		lblMode.setText("读取中…");
		new SwingWorker<String, Void>() {
			@Override
			protected String doInBackground() {
				try {
					// 保留原文件换行与缩进, 不做任何格式化
					return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
				} catch (Exception ex) {
					LOG.warn("读取配置文件失败: {}", file, ex);
					return null;
				}
			}

			@Override
			protected void done() {
				busy = false;
				updateButtons();
				if (sameEntry(currentEntry, entry)) {
					String text;
					try {
						text = get();
					} catch (Exception ex) {
						text = null;
					}
					if (text == null) {
						setMsg("读取失败: 无法读取 " + file.getAbsolutePath(), Theme.DANGER);
						lblMode.setText("读取失败");
					} else {
						loadedText = text;
						taConf.setText(text);
						taConf.setCaretPosition(0);
						undo.discardAllEdits();
						updateRevert();
						lblMode.setText(editing ? "编辑中，保存前将执行 nginx -t 检测"
								: "当前为只读查看，点击「编辑」可修改");
						setMsg(" ", Theme.TEXT_MUTED);
					}
				}
				// 读取期间被合并的请求: 按当前实例补读一次
				if (pendingRead) {
					pendingRead = false;
					readFile(false);
				}
			}
		}.execute();
	}

	// ==================== 编辑/保存 ====================

	private void toggleEdit() {
		if (busy || currentEntry == null) {
			return;
		}
		if (!editing) {
			editing = true;
			taConf.setEditable(true);
			taConf.setCaretColor(Theme.TEXT);
			taConf.requestFocusInWindow();
			btnEdit.setText("保存");
			lblMode.setText("编辑中，保存前将执行 nginx -t 检测");
			undo.discardAllEdits();
			updateRevert();
			updateButtons();
			setMsg(" ", Theme.TEXT_MUTED);
		} else {
			save();
		}
	}

	private void save() {
		final NginxInstanceEntry entry = currentEntry;
		if (entry == null || busy) {
			return;
		}
		final String text = taConf.getText();
		if (text.equals(loadedText)) {
			exitEditMode();
			setMsg("内容未修改，未写盘", Theme.TEXT_MUTED);
			return;
		}
		final File file = new File(StrUtil.nullToEmpty(entry.getConfPath()));
		busy = true;
		updateButtons();
		lblMode.setText("正在执行 nginx -t 检测…");
		setMsg(" ", Theme.TEXT_MUTED);

		new SwingWorker<SaveResult, Void>() {
			@Override
			protected SaveResult doInBackground() {
				SaveResult r = new SaveResult();
				// 1) 门禁: 待写入内容先做 nginx -t, 未通过不写盘
				NginxDashboardUtils.ConfigTestResult test = NginxDashboardUtils.testConfigContent(entry, text);
				r.testOutput = test.output;
				if (!test.success) {
					r.error = StrUtil.emptyToDefault(test.message, "nginx -t 未通过");
					return r;
				}
				// 2) 写盘: 先留档原内容, 再原子替换; 任一步失败都回滚到原内容
				byte[] origin = null;
				try {
					if (file.isFile()) {
						origin = Files.readAllBytes(file.toPath());
					}
				} catch (Exception ex) {
					r.error = "读取原文件失败，未写入：" + com.nginxui.utils.ErrorReporter.friendlyReason(ex);
					return r;
				}
				try {
					if (origin != null) {
						// 最近一次可回退的磁盘版本(与 nginx 自身备份习惯一致)
						Files.write(new File(file.getAbsolutePath() + ".nguibak").toPath(), origin);
					}
					atomicWrite(file, text);
				} catch (Exception ex) {
					boolean rolled = origin == null || rollback(file, origin);
					r.error = "写入失败：" + com.nginxui.utils.ErrorReporter.friendlyReason(ex)
							+ (rolled ? "（已回滚原内容）"
									: "（回滚失败，请检查 " + file.getAbsolutePath() + ".nguibak）");
					return r;
				}
				r.saved = true;
				return r;
			}

			@Override
			protected void done() {
				busy = false;
				updateButtons();
				SaveResult r;
				try {
					r = get();
				} catch (Exception ex) {
					r = new SaveResult();
					r.error = "检测/写入失败：" + com.nginxui.utils.ErrorReporter.friendlyReason(ex);
				}
				if (!r.saved) {
					setMsg("未写入: " + r.error, Theme.DANGER);
					lblMode.setText("编辑中，修改后再次保存");
					showTestOutput(r);
					return;
				}
				loadedText = text;
				exitEditMode();
				setMsg("已保存并通过 nginx -t 检测；重启实例后生效", Theme.SUCCESS);
			}
		}.execute();
	}

	private void exitEditMode() {
		editing = false;
		taConf.setEditable(false);
		btnEdit.setText("编辑");
		lblMode.setText("当前为只读查看，点击「编辑」可修改");
		undo.discardAllEdits();
		updateRevert();
		updateButtons();
	}

	/** 「还原」: 放弃未保存的修改, 回到进入编辑时的磁盘内容 */
	private void revertEdits() {
		if (editing && !taConf.getText().equals(loadedText)) {
			if (!ConfirmDialog.confirm(this, "还原", "当前实例的未保存修改",
					"将丢弃编辑区中的修改，恢复到进入编辑时的内容。",
					"不可撤销：未保存的修改不会写入磁盘，丢弃后无法找回。", "还原")) {
				return;
			}
		}
		restoreLoadedText();
		setMsg("已恢复到修改前的内容", Theme.TEXT_MUTED);
	}


	/** 有未保存修改时确认是否放弃(离开页面/关闭窗口) */
	public boolean confirmLeave() {
		if (!isDirty()) {
			return true;
		}
		if (!confirmDiscard("离开当前页面")) {
			return false;
		}
		discardEdits();
		return true;
	}

	private boolean confirmDiscard(String action) {
		return ConfirmDialog.confirm(this, "配置文件编辑", "当前实例的未保存修改",
				action + "将丢弃这些尚未写入磁盘的修改。",
				"不可撤销：未保存的修改不会保留，丢弃后无法找回。", "丢弃修改");
	}

	/** 丢弃编辑内容: 文本回到磁盘版本并退出编辑态 */
	private void discardEdits() {
		restoreLoadedText();
		if (editing) {
			exitEditMode();
		}
		setMsg(" ", Theme.TEXT_MUTED);
	}

	private void restoreLoadedText() {
		taConf.setText(loadedText);
		taConf.setCaretPosition(0);
		undo.discardAllEdits();
		updateRevert();
	}

	private boolean isDirty() {
		return editing && currentEntry != null && !taConf.getText().equals(loadedText);
	}

	/** 原子写: 同目录临时文件写好后整体替换, 中断时不会留下半截配置 */
	private static void atomicWrite(File target, String text) throws IOException {
		Path targetPath = target.toPath().toAbsolutePath();
		Path tmp = Files.createTempFile(targetPath.getParent(),
				target.getName() + ".", ".ngui-tmp");
		try {
			Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
			try {
				Files.move(tmp, targetPath, StandardCopyOption.REPLACE_EXISTING,
						StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException ex) {
				Files.move(tmp, targetPath, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	/** 写盘失败时用内存中的原内容覆盖回去 */
	private static boolean rollback(File target, byte[] origin) {
		try {
			Files.write(target.toPath(), origin);
			return true;
		} catch (Exception ex) {
			LOG.warn("配置回滚失败: {}", target, ex);
			return false;
		}
	}

	private void showTestOutput(SaveResult r) {
		if (StrUtil.isEmpty(r.testOutput)) {
			return;
		}
		String out = r.testOutput;
		if (out.length() > 4000) {
			out = out.substring(0, 4000) + "\n… (输出已截断)";
		}
		JTextArea area = new JTextArea(out, 12, 70);
		area.setEditable(false);
		area.setFont(Theme.MONO);
		area.setLineWrap(true);
		JScrollPane sp = new JScrollPane(area);
		JOptionPane.showMessageDialog(this, sp, "nginxUI - 配置检测未通过", JOptionPane.ERROR_MESSAGE);
	}

	// ==================== 重启 ====================

	private void restartInstance() {
		final NginxInstanceEntry entry = currentEntry;
		if (entry == null || busy) {
			return;
		}
		// 复用主窗口重启流程: 二次确认 + 防重复触发 + 完成后刷新状态
		mf.controlInstanceEntry(entry, "restart");
		// 重启是异步的: 稍后再取一次状态, 让顶部标签反映最新结果
		Timer once = new Timer(2500, e -> refreshStatus());
		once.setRepeats(false);
		once.start();
	}

	// ==================== 状态 ====================

	private void refreshStatus() {
		final NginxInstanceEntry entry = currentEntry;
		if (entry == null) {
			return;
		}
		final int my = ++seq;
		final NginxInfo live = mf.currentLive(entry);
		final WindowsNginxServiceUtils.WinNginxService svc = mf.currentService(entry);
		final boolean running = mf.currentRunning(entry);
		new SwingWorker<NginxDashboardInstance, Void>() {
			@Override
			protected NginxDashboardInstance doInBackground() {
				try {
					return NginxDashboardUtils.detailOf(entry, running, live, svc);
				} catch (Exception ex) {
					LOG.warn("实例状态采集失败", ex);
					return null;
				}
			}

			@Override
			protected void done() {
				if (my != seq || !sameEntry(currentEntry, entry)) {
					return;
				}
				NginxDashboardInstance d;
				try {
					d = get();
				} catch (Exception ex) {
					d = null;
				}
				populateStatus(d, entry, running);
			}
		}.execute();
	}

	private void populateStatus(NginxDashboardInstance d, NginxInstanceEntry entry, boolean fallbackRunning) {
		boolean running = d != null && d.getRunning() != null ? Boolean.TRUE.equals(d.getRunning())
				: fallbackRunning;
		tagStatus.setStyle(running ? "运行中" : "已停止",
				running ? GREEN_TEXT : Theme.STATUS_STOPPED_TEXT,
				running ? GREEN_BG : Theme.DANGER_BG,
				running ? GREEN_BORDER : Theme.DANGER_BORDER);
		tagRun.setStyle(running ? "运行中" : "已停止",
				running ? GREEN_TEXT : Theme.STATUS_STOPPED_TEXT,
				running ? GREEN_BG : Theme.DANGER_BG,
				running ? GREEN_BORDER : Theme.DANGER_BORDER);

		String svc = d != null ? d.getWinServiceName() : entry.getWinServiceName();
		boolean service = StrUtil.isNotEmpty(svc);
		tagService.setStyle(service ? "Windows 服务" : "独立进程",
				Theme.PRIMARY, BLUE_BG, BLUE_BORDER);
		tagService.setVisible(true);
		tagService.setToolTipText(service ? svc : null);

		String pid = d != null && StrUtil.isNotEmpty(d.getMasterPid()) ? d.getMasterPid()
				: entry.getLastMasterPid();
		lblPid.setText("PID: " + StrUtil.emptyToDefault(pid, "—"));

		Integer workers = d == null ? null : d.getWorkerCount();
		lblWorker.setText("Worker 进程: " + (workers == null ? "—" : workers));

		String version = d == null ? null : d.getVersion();
		if (d != null && StrUtil.isEmpty(d.getNginxPath())) {
			tfConf.setText(StrUtil.emptyToDefault(entry.getConfPath(), "—"));
		} else if (d != null) {
			tfConf.setText(StrUtil.emptyToDefault(d.getNginxPath(), entry.getConfPath()));
		}
		lblVersion.setText(StrUtil.isEmpty(version) ? "—"
				: (version.startsWith("v") ? version : "v" + version));
		updateButtons();
		adaptHeaderWidth(getWidth());
		revalidate();
	}

	private void updateButtons() {
		boolean has = currentEntry != null;
		String baseReason = !has ? "当前没有可查看的实例：先在实例列表中选择一个实例"
				: (busy ? "正在读取或保存配置，请稍候" : null);
		Theme.setEnabled(btnRead, baseReason == null, baseReason);
		// 配置文件不存在时不允许进入编辑态, 避免把新建内容写到错误路径
		String editReason = baseReason != null ? baseReason
				: (fileMissing ? "配置文件不存在：先保存一次以创建配置文件" : null);
		Theme.setEnabled(btnEdit, editReason == null, editReason);
		Theme.setEnabled(btnRestart, baseReason == null, baseReason);
		// 编辑中检测的是磁盘旧内容, 容易误判, 保存时才做门禁, 因此编辑态禁用
		String testReason = baseReason != null ? baseReason
				: (editing ? "正在编辑：先保存或取消编辑后再检测配置" : null);
		Theme.setEnabled(btnTestConfig, testReason == null, testReason);
	}

	private void setMsg(String text, Color color) {
		lblMsg.setText(StrUtil.emptyToDefault(text, " "));
		lblMsg.setForeground(color);
	}

	/** 两个实例条目是否代表同一实例(id 相同即可, 忽略刷新重建的对象差异) */
	private static boolean sameEntry(NginxInstanceEntry a, NginxInstanceEntry b) {
		if (a == b) {
			return true;
		}
		if (a == null || b == null) {
			return false;
		}
		String ida = a.getId();
		String idb = b.getId();
		return StrUtil.isNotEmpty(ida) && StrUtil.isNotEmpty(idb) && ida.equals(idb);
	}

	/** 保存结果: 检测输出保留给失败弹窗 */
	private static final class SaveResult {
		boolean saved;
		String error;
		String testOutput;
	}
}
