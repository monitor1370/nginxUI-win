package com.nginxui.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.File;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nginxui.ext.NginxDashboardInstance;
import com.nginxui.ext.NginxInfo;
import com.nginxui.ext.NginxInstanceEntry;
import com.nginxui.utils.NginxDashboardUtils;
import com.nginxui.utils.WindowsNginxServiceUtils;

import cn.hutool.core.util.StrUtil;

/**
 * Nginx 状态页(替代原「实例监控」弹窗):
 *  - 顶部实例下拉, 可切换查看对象;
 *  - stub_status 实时指标, 运行中的实例每 3 秒自动刷新;
 *  - 支持启用/移除本工具写入的监控块, 并展示编译参数.
 * 数据复用主窗口本轮采集结果, 不触发全量检测.
 */
public class MonitorPage extends JPanel {

	private static final Logger LOG = LoggerFactory.getLogger(MonitorPage.class);
	private static final int AUTO_REFRESH_MS = 3000;

	private final MainFrame mf;
	private final InstancePicker picker;
	private final PageHeader header;

	private final JLabel lblBadge = new JLabel("—");
	private final JLabel lblUpdated = Theme.label("", Theme.SMALL, Theme.TEXT_MUTED);
	private final JLabel lblNotice = Theme.label("", Theme.SMALL, Theme.TEXT_MUTED);
	private final JButton btnStubOn = Theme.toolButton("启用监控");
	private final JButton btnStubOff = Theme.toolButton("移除监控");
	private final FieldGrid gridStub = new FieldGrid();
	private final JLabel lblHint = new JLabel(" ", SwingConstants.CENTER);
	private final JTextArea taArgs = new JTextArea(3, 60);

	private NginxInstanceEntry currentEntry;
	private int seq;
	private boolean busy;
	/** 正在写/移除监控配置: 期间禁止再次触发, 也不允许自动刷新把按钮恢复成可点 */
	private boolean writing;

	public MonitorPage(MainFrame mf) {
		super(new BorderLayout(0, Theme.GAP_S));
		this.mf = mf;
		setOpaque(true);
		setBackground(Theme.BG);
		setBorder(BorderFactory.createEmptyBorder(Theme.GAP_S, 12, Theme.GAP_M, 12));

		picker = new InstancePicker(mf, 260);
		picker.setOnChanged(this::load);
		header = new PageHeader("Nginx 状态", "stub_status 实时指标；运行中的实例每 3 秒自动刷新");
		header.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
		header.addAction(picker);

		add(header, BorderLayout.NORTH);
		add(buildCard(), BorderLayout.CENTER);

		Timer timer = new Timer(AUTO_REFRESH_MS, e -> {
			if (isShowing() && currentEntry != null && !busy) {
				load();
			}
		});
		timer.start();
		OperationLock.addListener(() -> {
			if (isShowing() && currentEntry != null && !busy) {
				load();
			}
		});
	}

	private JPanel buildCard() {
		JPanel card = new JPanel(new BorderLayout(0, Theme.GAP_S));
		card.setBackground(Theme.SURFACE);
		card.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(Theme.BORDER),
				BorderFactory.createEmptyBorder(12, 14, 12, 14)));

		lblBadge.setFont(Theme.BODY_BOLD);
		lblBadge.setForeground(Theme.TEXT_MUTED);
		JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, Theme.GAP_S, 0));
		left.setOpaque(false);
		left.add(lblBadge);
		left.add(lblUpdated);

		btnStubOn.setToolTipText("向 nginx.conf 写入 stub_status 监控块(自动分配空闲端口)");
		btnStubOff.setToolTipText("移除本工具写入的监控块, 手工声明的块不受影响");
		Theme.setEnabled(btnStubOn, false, "先选择一个实例：从实例列表中选择");
		Theme.setEnabled(btnStubOff, false, "先选择一个实例：从实例列表中选择");
		btnStubOn.addActionListener(e -> writeStub(true));
		btnStubOff.addActionListener(e -> writeStub(false));
		JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		actions.setOpaque(false);
		actions.add(btnStubOn);
		actions.add(btnStubOff);

		JPanel head = new JPanel(new BorderLayout(Theme.GAP_S, 0));
		head.setOpaque(false);
		head.add(left, BorderLayout.WEST);
		head.add(actions, BorderLayout.EAST);

		lblNotice.setVisible(false);
		JPanel top = new JPanel(new BorderLayout(0, 4));
		top.setOpaque(false);
		top.add(head, BorderLayout.NORTH);
		top.add(lblNotice, BorderLayout.CENTER);

		lblHint.setForeground(Theme.TEXT_MUTED);
		lblHint.setFont(Theme.BODY);
		lblHint.setVerticalAlignment(SwingConstants.TOP);
		lblHint.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
		gridStub.setVisible(false);
		JPanel body = new JPanel(new BorderLayout());
		body.setOpaque(false);
		body.add(gridStub, BorderLayout.NORTH);
		body.add(lblHint, BorderLayout.CENTER);

		taArgs.setLineWrap(true);
		taArgs.setWrapStyleWord(true);
		taArgs.setEditable(false);
		taArgs.setBackground(Theme.BG);
		taArgs.setForeground(Theme.TEXT);
		taArgs.setFont(Theme.MONO);
		JScrollPane argsScroll = new JScrollPane(taArgs);
		argsScroll.setPreferredSize(new Dimension(10, 74));
		argsScroll.setBorder(BorderFactory.createEmptyBorder());
		JPanel argsPanel = new JPanel(new BorderLayout());
		argsPanel.setOpaque(false);
		argsPanel.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createEmptyBorder(8, 0, 0, 0),
				BorderFactory.createTitledBorder(
						BorderFactory.createLineBorder(Theme.BORDER),
						"configure arguments (nginx -V)")));
		argsPanel.add(argsScroll, BorderLayout.CENTER);

		card.add(top, BorderLayout.NORTH);
		card.add(body, BorderLayout.CENTER);
		card.add(argsPanel, BorderLayout.SOUTH);
		return card;
	}

	/** 页面显示时重建实例下拉; preferred 非空则定位到该实例(来自实例总览的快捷入口) */
	public void onShown() {
		onShown(null);
	}

	public void onShown(NginxInstanceEntry preferred) {
		picker.refresh(preferred == null ? null : preferred.getId());
		load();
	}

	/** 后台刷新后同步下拉(实例增减、状态变化)并刷新当前内容 */
	public void onRowsChanged() {
		picker.refresh(currentEntry == null ? null : currentEntry.getId());
		if (isShowing()) {
			load();
		}
	}

	private void load() {
		if (writing) {
			// 写配置(含重启)进行中: 跳过刷新, 避免 populate 把监控按钮重新启用
			return;
		}
		MainFrame.InstanceRow row = picker.selected();
		NginxInstanceEntry entry = row == null ? null : row.entry;
		boolean changed = currentEntry != entry;
		currentEntry = entry;
		if (changed) {
			setNotice("", Theme.TEXT_MUTED);
		}
		if (entry == null) {
			showEmpty("当前没有可查看的 Nginx 实例");
			return;
		}

		final int my = ++seq;
		busy = true;
		lblUpdated.setText("加载中…");
		final NginxInfo live = mf.currentLive(entry);
		final WindowsNginxServiceUtils.WinNginxService svc = mf.currentService(entry);
		final boolean running = mf.currentRunning(entry);

		new SwingWorker<NginxDashboardInstance, Void>() {
			@Override
			protected NginxDashboardInstance doInBackground() {
				try {
					return NginxDashboardUtils.detailOf(entry, running, live, svc);
				} catch (Exception ex) {
					LOG.warn("实例实时明细采集失败", ex);
					return null;
				}
			}

			@Override
			protected void done() {
				busy = false;
				if (my != seq || currentEntry != entry) {
					return;
				}
				NginxDashboardInstance inst;
				try {
					inst = get();
				} catch (Exception ex) {
					inst = null;
				}
				if (inst == null) {
					showEmpty("未找到该实例的实时数据");
					return;
				}
				populate(inst);
			}
		}.execute();
	}

	private void populate(NginxDashboardInstance d) {
		boolean running = Boolean.TRUE.equals(d.getRunning()) || "running".equals(d.getStatus());
		lblBadge.setText(running ? "● 运行中" : "○ 已停止");
		lblBadge.setForeground(Theme.statusColor(running));
		lblUpdated.setText(d.getLastUpdate() != null ? "更新于 " + d.getLastUpdate() : "");

		boolean stubOk = running && Boolean.TRUE.equals(d.getStubEnabled());
		gridStub.setVisible(stubOk);
		lblHint.setVisible(!stubOk);

		boolean hasConf = currentEntry != null
				&& new File(StrUtil.nullToEmpty(currentEntry.getConfPath())).isFile();
		boolean locked = writing || OperationLock.isBusy();
		String writingReason = writing ? "正在写入监控配置，请稍候" : OperationLock.reason();
		Theme.setEnabled(btnStubOn, hasConf && !stubOk && !locked,
				writingReason != null ? writingReason
						: (!hasConf ? "当前实例没有可用的 nginx.conf，无法写入监控块"
								: "监控块已存在，无需重复写入"));
		Theme.setEnabled(btnStubOff, hasConf && Boolean.TRUE.equals(d.getStubManaged()) && !locked,
				writingReason != null ? writingReason
						: (!hasConf ? "当前实例没有可用的 nginx.conf，无法移除监控块"
								: "当前监控块不是本工具写入的，无需移除"));

		if (!stubOk) {
			String reason = !running ? "实例未运行"
					: d.getStubApplicable() != null && !d.getStubApplicable()
							? "该 nginx 版本未编译 stub_status"
							: "未启用 stub_status 模块 (需在配置中添加 stub_status on)";
			lblHint.setText("  ⓘ  " + reason + "  —  实时数据暂不可用  ");
			gridStub.clear();
		} else {
			gridStub.clear();
			gridStub.add("stub_status URL", firstNonNull(d.getStubUrl(), "—"));
			gridStub.add("Active connections", formatNumber(d.getActiveConnections()));
			gridStub.add("accepts (总握手)", formatNumber(d.getAccepts()));
			gridStub.add("handled (总连接)", formatNumber(d.getHandled()));
			gridStub.add("requests (总请求)", formatNumber(d.getRequests()));
			gridStub.add("Reading", formatNumber(d.getReading()));
			gridStub.add("Writing", formatNumber(d.getWriting()));
			gridStub.add("Waiting", formatNumber(d.getWaiting()));
			if (d.getReqPerConn() != null) {
				gridStub.add("平均请求/连接", String.format("%.2f", d.getReqPerConn()));
			}
			if (d.getMaxConnections() != null && d.getActiveConnections() != null) {
				long max = d.getMaxConnections();
				long act = d.getActiveConnections();
				double pct = max > 0 ? act * 100.0 / max : 0;
				gridStub.add("相对理论上限", String.format("%.2f%%", pct));
			}
		}

		taArgs.setText(d.getConfigureArgs() != null ? d.getConfigureArgs() : "—");
		taArgs.setCaretPosition(0);
		revalidate();
	}

	private void showEmpty(String msg) {
		lblBadge.setText("—");
		lblBadge.setForeground(Theme.TEXT_MUTED);
		lblUpdated.setText("");
		gridStub.clear();
		gridStub.setVisible(false);
		lblHint.setText("  " + msg + "  ");
		lblHint.setVisible(true);
		Theme.setEnabled(btnStubOn, false, "先在实例列表中选择一个实例");
		Theme.setEnabled(btnStubOff, false, "先在实例列表中选择一个实例");
		taArgs.setText("");
		revalidate();
	}

	/** 启用/移除 stub_status 监控块: 写配置由 utils 完成, 失败自动回滚 */
	private void writeStub(boolean enable) {
		if (writing) {
			// 上一次写/移除监控配置尚未结束, 忽略重复触发(避免并发改写 nginx.conf 与重复重启)
			return;
		}
		final NginxInstanceEntry entry = currentEntry;
		if (entry == null) {
			return;
		}
		String object = "实例「" + MainFrame.InstanceRow.displayName(entry) + "」的监控配置（stub_status）";
		String consequence = (enable
				? "将向 nginx.conf 写入 stub_status 块并重启实例使其生效，重启期间站点短暂无法访问。"
				: "将删除本工具写入的 stub_status 块并重启实例使其生效，重启期间站点短暂无法访问。");
		String reversibility = "可撤销：操作前会做配置检测，失败不改动配置；"
				+ "写入的内容可通过再次执行相反操作移除。";
		if (!ConfirmDialog.confirm(this, enable ? "启用监控" : "移除监控",
				object, consequence, reversibility, enable ? "启用监控" : "移除监控")) {
			return;
		}
		if (!OperationLock.tryAcquire(enable ? "启用监控" : "移除监控")) {
			setNotice(OperationLock.reason(), Theme.TEXT_MUTED);
			return;
		}
		writing = true;
		Theme.setEnabled(btnStubOn, false, "正在写入监控配置，请稍候");
		Theme.setEnabled(btnStubOff, false, "正在写入监控配置，请稍候");
		setNotice("正在处理…", Theme.TEXT_MUTED);
		new SwingWorker<NginxDashboardUtils.StubConfigResult, Void>() {
			@Override
			protected NginxDashboardUtils.StubConfigResult doInBackground() {
				return enable ? NginxDashboardUtils.writeStubConfig(entry)
						: NginxDashboardUtils.removeStubConfig(entry);
			}

			@Override
			protected void done() {
				OperationLock.release();
				writing = false;
				NginxDashboardUtils.StubConfigResult r;
				try {
					r = get();
				} catch (Exception ex) {
					r = new NginxDashboardUtils.StubConfigResult();
					r.error = com.nginxui.utils.ErrorReporter.friendlyReason(ex);
				}
				if (r.error != null) {
					setNotice((enable ? "启用监控失败: " : "移除监控失败: ") + r.error, Theme.DANGER);
				} else {
					String applied = r.applied
							? "，已通过 " + StrUtil.emptyToDefault(r.appliedBy, "restart") + " 生效"
							: "，实例启动后生效";
					setNotice(enable
							? "监控已启用: " + r.uri + " (端口 " + r.port + ")" + applied
							: "监控配置已移除" + applied, Theme.SUCCESS);
					if (mf != null) {
						mf.onConfigChanged();
					}
				}
				load();
			}
		}.execute();
	}

	private void setNotice(String text, java.awt.Color color) {
		lblNotice.setText(StrUtil.nullToEmpty(text));
		lblNotice.setForeground(color);
		lblNotice.setVisible(StrUtil.isNotEmpty(text));
		revalidate();
	}

	private static String firstNonNull(String... vals) {
		if (vals == null) {
			return "—";
		}
		for (String s : vals) {
			if (s != null && !s.isEmpty()) {
				return s;
			}
		}
		return "—";
	}

	private static String formatNumber(Number n) {
		return n == null ? "—" : String.format("%,d", n.longValue());
	}
}
