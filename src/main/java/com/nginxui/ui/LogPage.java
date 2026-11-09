package com.nginxui.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import com.nginxui.ext.NginxInstanceEntry;
import com.nginxui.utils.NginxLogUtils;

import cn.hutool.core.util.StrUtil;

/**
 * 日志查看页(替代原「实例日志」弹窗):
 *  - 顶部实例下拉, 切换查看对象;
 *  - 错误日志 / 访问日志切换, 自动跟随每 3 秒读取新增内容;
 *  - 保留右键复制/全选、跳到最新与打开目录.
 */
public class LogPage extends JPanel {

	/** 日志面板保留的最大行数(与读取行数一致) */
	private static final int MAX_LOG_LINES = 500;
	private static final int FOLLOW_MS = 3000;

	private final MainFrame mf;
	private final InstancePicker picker;

	private final JComboBox<String> cbLogType;
	private final JCheckBox chkFollow = new JCheckBox("自动跟随(3s)");
	private final JLabel lblLogPath = Theme.label(" ", Theme.SMALL, Theme.TEXT_MUTED);
	private final JTextPane tpLog = new JTextPane();
	private final JScrollPane logScroll;
	private final Timer followTimer;

	private NginxLogUtils.Logs logs;
	/** 有状态尾部读取: 跟随模式下只读新增字节 */
	private final NginxLogUtils.TailReader logReader = new NginxLogUtils.TailReader(MAX_LOG_LINES);
	/** 面板中每行日志的起始偏移, 用于裁掉超过 MAX_LOG_LINES 的旧行 */
	private final List<Integer> logLineStarts = new ArrayList<Integer>();
	/** 日志加载代次: 丢弃过期的异步结果 */
	private int logSeq;
	/** 读取进行中: 期间的点击只记一次待读, 避免连点叠加后台 IO */
	private boolean loading;
	private boolean pendingLoad;
	/** 日志区内容已失效(清空后尚未重绘): 下一次读取必须整页重读, 否则会因"文件未变化"跳过渲染而空白 */
	private boolean docStale;
	/** 已请求跳到最新: 读取完成后滚动到末尾 */
	private boolean jumpPending;

	private NginxInstanceEntry currentEntry;

	public LogPage(MainFrame mf) {
		super(new BorderLayout(0, Theme.GAP_S));
		this.mf = mf;
		setOpaque(true);
		setBackground(Theme.BG);
		setBorder(BorderFactory.createEmptyBorder(Theme.GAP_S, 12, Theme.GAP_M, 12));

		// 自动跟随: 只在日志页可见时读取, 避免切走/最小化后空转
		followTimer = new Timer(FOLLOW_MS, e -> {
			if (isShowing() && currentEntry != null) {
				loadLog();
			}
		});

		picker = new InstancePicker(mf, 260);
		picker.setOnChanged(this::onEntryChanged);

		PageHeader header = new PageHeader("日志查看", "错误日志与访问日志");
		header.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
		header.addAction(picker);

		cbLogType = new JComboBox<String>(new String[] { "错误日志 error.log", "访问日志 access.log" });
		cbLogType.setFont(Theme.BODY);
		cbLogType.setPreferredSize(new Dimension(170, Theme.BUTTON_HEIGHT));
		cbLogType.addActionListener(e -> loadLog());

		JButton btnRefresh = Theme.toolButton("刷新");
		btnRefresh.setToolTipText("立即读取最新日志");
		btnRefresh.addActionListener(e -> loadLog());
		chkFollow.setOpaque(false);
		chkFollow.setFont(Theme.BODY);
		chkFollow.addActionListener(e -> {
			if (chkFollow.isSelected()) {
				followTimer.start();
			} else {
				followTimer.stop();
			}
		});
		JButton btnBottom = Theme.toolButton("跳到最新");
		btnBottom.setToolTipText("读取最新日志并滚动到末尾");
		btnBottom.addActionListener(e -> jumpToLatest());
		JButton btnOpenDir = Theme.toolButton("打开目录");
		btnOpenDir.addActionListener(e -> openLogDir());

		JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		bar.setOpaque(false);
		bar.add(cbLogType);
		bar.add(btnRefresh);
		bar.add(chkFollow);
		bar.add(btnBottom);
		bar.add(btnOpenDir);

		tpLog.setEditable(false);
		tpLog.setFont(Theme.MONO);
		tpLog.setBackground(Theme.SURFACE);
		tpLog.setToolTipText("右键复制选中日志(Ctrl+C 同样可用)");
		tpLog.setComponentPopupMenu(buildLogMenu());
		logScroll = new JScrollPane(tpLog);
		logScroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));

		JPanel top = new JPanel(new BorderLayout(0, 4));
		top.setOpaque(false);
		top.add(bar, BorderLayout.NORTH);
		top.add(lblLogPath, BorderLayout.SOUTH);

		JPanel card = new JPanel(new BorderLayout(0, 6));
		card.setBackground(Theme.SURFACE);
		card.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(Theme.BORDER),
				BorderFactory.createEmptyBorder(10, 12, 12, 12)));
		card.add(top, BorderLayout.NORTH);
		card.add(logScroll, BorderLayout.CENTER);

		add(header, BorderLayout.NORTH);
		add(card, BorderLayout.CENTER);

		showEmpty();
	}

	/** 页面显示时重建实例下拉; preferred 非空则定位到该实例 */
	public void onShown() {
		onShown(null);
	}

	public void onShown(NginxInstanceEntry preferred) {
		picker.refresh(preferred == null ? null : preferred.getId());
		onEntryChanged();
	}

	/** 后台刷新后同步下拉(实例增减、状态变化)并刷新当前日志 */
	public void onRowsChanged() {
		picker.refresh(currentEntry == null ? null : currentEntry.getId());
		if (isShowing()) {
			onEntryChanged();
		}
	}

	private void onEntryChanged() {
		MainFrame.InstanceRow row = picker.selected();
		NginxInstanceEntry entry = row == null ? null : row.entry;
		// 按实例 id 判断, 不能用对象引用: 每次后台刷新都会重建实例对象(同一实例), 引用比较会误判为切换而清空日志
		boolean changed = !sameEntry(currentEntry, entry);
		currentEntry = entry;
		logs = entry == null ? null : NginxLogUtils.resolve(entry);
		if (changed) {
			// 切换实例后清空上一条日志, 避免误读; 读取器状态同时失效, 保证下一次读取整页重绘
			clearLog(tpLog.getStyledDocument());
			lblLogPath.setText(" ");
		}
		if (entry == null) {
			followTimer.stop();
			chkFollow.setSelected(false);
			showEmpty();
			return;
		}
		loadLog();
	}

	/** 两个实例条目是否代表同一实例(id 相同即视为同一实例, 忽略刷新重建的对象差异) */
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

	/** 读取最新日志并滚动到末尾 */
	private void jumpToLatest() {
		if (currentEntry == null) {
			return;
		}
		jumpPending = true;
		loadLog();
	}

	private void showEmpty() {
		followTimer.stop();
		chkFollow.setSelected(false);
		jumpPending = false;
		clearLog(tpLog.getStyledDocument());
		lblLogPath.setText(" ");
		insertLog(tpLog.getStyledDocument(), "当前没有可查看的 Nginx 实例", Theme.TEXT_MUTED);
	}

	/** 日志区右键菜单: 复制/全选/跳到最新, 便于直接取错误行 */
	private JPopupMenu buildLogMenu() {
		JPopupMenu menu = Theme.popup();
		JMenuItem miCopy = Theme.menuItem("复制选中");
		miCopy.addActionListener(e -> tpLog.copy());
		JMenuItem miAll = Theme.menuItem("全选");
		miAll.addActionListener(e -> tpLog.selectAll());
		JMenuItem miBottom = Theme.menuItem("跳到最新");
		miBottom.addActionListener(e -> jumpToLatest());
		menu.add(miCopy);
		menu.add(miAll);
		menu.addSeparator();
		menu.add(miBottom);
		return menu;
	}

	private void loadLog() {
		if (loading) {
			// 读取进行中: 合并为一次待读, 连点不会叠加后台读取
			pendingLoad = true;
			return;
		}
		final NginxInstanceEntry entry = currentEntry;
		if (entry == null || logs == null) {
			renderMessage("请在上方选择 Nginx 实例");
			return;
		}
		final boolean access = cbLogType.getSelectedIndex() == 1;
		final File file = access ? logs.accessLog : logs.errorLog;
		final String note = access ? logs.accessNote : null;

		if (file == null) {
			renderMessage(StrUtil.emptyToDefault(note, "配置中未找到该日志路径"));
			return;
		}
		final int seq = ++logSeq;
		// 内容已失效或用户点了「跳到最新」: 强制整页重读, 保证有内容可绘制、能滚到真正的末尾
		final boolean fullReload = docStale || jumpPending;
		final boolean nearBottom = isNearBottom();
		loading = true;
		new SwingWorker<NginxLogUtils.Snapshot, Void>() {
			@Override
			protected NginxLogUtils.Snapshot doInBackground() {
				try {
					if (fullReload) {
						logReader.reset();
					}
					return logReader.read(file);
				} catch (IOException e) {
					return null;
				}
			}

			@Override
			protected void done() {
				loading = false;
				if (seq == logSeq && sameEntry(currentEntry, entry)) {
					NginxLogUtils.Snapshot snap;
					try {
						snap = get();
					} catch (Exception e) {
						snap = null;
					}
					renderSnapshot(snap, file, access, nearBottom);
				}
				// 读取期间被合并的点击: 补读一次, 多个点击只产生一次额外读取
				if (pendingLoad) {
					pendingLoad = false;
					loadLog();
				}
			}
		}.execute();
	}

	private void renderSnapshot(NginxLogUtils.Snapshot snap, File file, boolean access, boolean nearBottom) {
		StyledDocument doc = tpLog.getStyledDocument();
		boolean scroll = nearBottom || jumpPending;
		jumpPending = false;

		if (snap == null) {
			// 读取失败: 保留失效标记, 下一次读取仍整页重试, 避免停在空白状态
			clearLog(doc);
			lblLogPath.setText("读取失败: " + file.getAbsolutePath());
			insertLog(doc, "读取失败: " + file.getAbsolutePath(), Theme.DANGER);
			return;
		}
		if (snap.unchanged) {
			if (docStale) {
				// 兜底: 内容已被清空却没读到新数据, 立即整页重读(重置后必然返回整页快照)
				logReader.reset();
				loadLog();
				return;
			}
			if (scroll) {
				scrollToEnd();
			}
			return;
		}

		if (snap.reset) {
			clearLog(doc);
			boolean exists = file.isFile();
			lblLogPath.setText((exists ? "文件: " : "文件不存在: ") + file.getAbsolutePath());
			if (!exists) {
				insertLog(doc, "日志文件尚未生成，实例运行并产生日志后会自动出现", Theme.TEXT_MUTED);
			} else if (snap.lines.isEmpty()) {
				insertLog(doc, "暂无日志内容", Theme.TEXT_MUTED);
			} else {
				for (String line : snap.lines) {
					appendLogLine(doc, line, logColor(line, access));
				}
			}
		} else {
			// 只追加新增行, 再裁掉超出上限的旧行, 保证面板内存固定
			for (String line : snap.lines) {
				appendLogLine(doc, line, logColor(line, access));
			}
			trimLogDocument(doc);
		}

		lblLogPath.setText((file.isFile() ? "文件: " : "文件不存在: ") + file.getAbsolutePath()
				+ "  (显示最新 " + logLineStarts.size() + " 行)");
		docStale = false;
		if (scroll) {
			scrollToEnd();
		}
	}

	/** 滚动到日志末尾: 光标置于最后一行(跳过末尾换行造成的空行), 窗口较小时也不会停在空白行 */
	private void scrollToEnd() {
		StyledDocument doc = tpLog.getStyledDocument();
		int len = doc.getLength();
		int pos = len;
		try {
			if (len > 1 && "\n".equals(doc.getText(len - 1, 1))) {
				pos = len - 1;
			}
		} catch (Exception ignored) {
		}
		tpLog.setCaretPosition(pos);
	}

	/** 清空日志区并重置行偏移记录, 同时标记内容失效 */
	private void clearLog(StyledDocument doc) {
		try {
			doc.remove(0, doc.getLength());
		} catch (Exception ignored) {
		}
		logLineStarts.clear();
		docStale = true;
	}

	/** 追加一行并记录起始偏移 */
	private void appendLogLine(StyledDocument doc, String line, Color color) {
		logLineStarts.add(doc.getLength());
		insertLog(doc, line + "\n", color);
	}

	/** 超过 MAX_LOG_LINES 时从文档头部删除多余行 */
	private void trimLogDocument(StyledDocument doc) {
		int extra = logLineStarts.size() - MAX_LOG_LINES;
		if (extra <= 0) {
			return;
		}
		int cut = logLineStarts.get(extra);
		try {
			doc.remove(0, cut);
		} catch (Exception e) {
			return;
		}
		logLineStarts.subList(0, extra).clear();
		for (int i = 0; i < logLineStarts.size(); i++) {
			logLineStarts.set(i, logLineStarts.get(i) - cut);
		}
	}

	/** 无日志文件/未选中实例时的提示文本 */
	private void renderMessage(String text) {
		StyledDocument doc = tpLog.getStyledDocument();
		jumpPending = false;
		clearLog(doc);
		lblLogPath.setText(" ");
		insertLog(doc, StrUtil.nullToEmpty(text), Theme.TEXT_MUTED);
	}

	private static void insertLog(StyledDocument doc, String text, Color color) {
		SimpleAttributeSet attr = new SimpleAttributeSet();
		StyleConstants.setForeground(attr, color);
		try {
			doc.insertString(doc.getLength(), text, attr);
		} catch (Exception ignored) {
		}
	}

	/** error 级标红, warn 级标橙; 访问日志使用常规正文色 */
	private static Color logColor(String line, boolean access) {
		if (access) {
			return Theme.TEXT;
		}
		String s = StrUtil.nullToEmpty(line);
		if (s.contains("[emerg]") || s.contains("[alert]") || s.contains("[crit]") || s.contains("[error]")) {
			return Theme.DANGER;
		}
		if (s.contains("[warn]")) {
			return Theme.WARNING;
		}
		return Theme.TEXT;
	}

	private boolean isNearBottom() {
		javax.swing.JScrollBar bar = logScroll.getVerticalScrollBar();
		return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 20;
	}

	private void openLogDir() {
		File dir = null;
		if (logs != null) {
			File f = cbLogType.getSelectedIndex() == 1 ? logs.accessLog : logs.errorLog;
			if (f != null) {
				dir = f.getParentFile();
			}
		}
		if ((dir == null || !dir.isDirectory()) && currentEntry != null) {
			String prefix = StrUtil.emptyToDefault(currentEntry.getPrefixDir(), currentEntry.getNginxDir());
			if (StrUtil.isNotEmpty(prefix)) {
				dir = new File(prefix, "logs");
			}
		}
		if (dir == null || !dir.isDirectory()) {
			JOptionPane.showMessageDialog(this, "日志目录不存在", "nginxUI", JOptionPane.WARNING_MESSAGE);
			return;
		}
		try {
			java.awt.Desktop.getDesktop().open(dir);
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(this,
					com.nginxui.utils.ErrorReporter.uiText("打开目录", ex,
							"请确认日志目录存在且当前账户有访问权限。"),
					"nginxUI", JOptionPane.ERROR_MESSAGE);
		}
	}
}
