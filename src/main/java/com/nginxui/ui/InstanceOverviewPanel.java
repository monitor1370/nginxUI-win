package com.nginxui.ui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JToggleButton;
import javax.swing.ListCellRenderer;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.border.AbstractBorder;
import javax.swing.border.EmptyBorder;

import com.nginxui.ext.NginxDashboardInstance;
import com.nginxui.ext.NginxInstanceEntry;
import com.nginxui.utils.NginxDashboardUtils;

import cn.hutool.core.util.StrUtil;

/**
 * 实例总览页(严格对齐 nginxUI Web 版「实例总览」):
 *  - 顶部: 标题 + 实例下拉选择器 + 实例管理 / 安装实例;
 *  - 状态行: 左运行状态标签(运行中 / Windows 服务 / PID / Worker / 版本),
 *    右实例动作(启动 / 停止 / 重启 / 配置检测);
 *  - 配置详情卡片: 网格化的实例配置与 stub_status 实时指标, 右上为监控开关.
 * 页面不做采集: 实例列表由 MainFrame 每轮刷新推送, 单实例明细在选中后异步加载.
 */
public class InstanceOverviewPanel extends JPanel {

	private static final Color PORT_BG = new Color(0xEFF6FF);
	private static final Color PORT_BORDER = new Color(0xBFDBFE);
	private static final Color GREEN_TEXT = new Color(0x52C41A);
	private static final Color GREEN_BG = new Color(0xF6FFED);
	private static final Color GREEN_BORDER = new Color(0xB7EB8F);
	private static final Color ORANGE = new Color(0xF59E0B);
	private static final Color RED = new Color(0xEF4444);
	private static final Color SOFT_BLUE = new Color(0xEFF6FF);
	private static final Color SOFT_BLUE_BORDER = new Color(0xBFDBFE);
	private static final Color DISABLED_FILL = new Color(0xF7F8FA);
	private static final Color DISABLED_TEXT = new Color(0xB9BEC7);
	private static final Color DISABLED_BLUE_TEXT = new Color(0x93C5FD);

	/** 网格列权重: 标签列固定, 值列按截图中比例分配 */
	private static final double[] COL_WEIGHT = { 0, 0.22, 0, 0.18, 0, 0.30 };
	private static final int ROW_HEIGHT = 40;
	/** 页头控件高度: 与 40px 数据行区分, 避免下拉框被撑高 */
	private static final int CONTROL_HEIGHT = 32;
	/** 配置详情分组之间的留白: 用空白分组替代网格线 */
	private static final int GROUP_GAP = 12;
	/** 分组首行的行号: 第 4 行(容量与端口)、第 5 行(实时指标)之前留白 */
	private static final java.util.Set<Integer> GROUP_STARTS = java.util.Set.of(4, 5);

	private final MainFrame mf;

	private final JComboBox<MainFrame.InstanceRow> combo = new JComboBox<>();
	private final InstanceComboRenderer comboRenderer = new InstanceComboRenderer();
	/** 页头唯一的次级入口: 只收纳页内独有的别名/目录/忽略, 其余操作归侧栏与专属页面 */
	private final JButton btnMore = new MoreButton();

	private final Tag tagStatus = new Tag("运行中", GREEN_TEXT, GREEN_BG, GREEN_BORDER);
	private final Tag tagService = new Tag("Windows 服务", Theme.PRIMARY, SOFT_BLUE, SOFT_BLUE_BORDER);
	private final JLabel lblPid = Theme.label("PID: —", Theme.BODY, Theme.TEXT_MUTED);
	private final JLabel lblWorker = Theme.label("Worker 进程: —", Theme.BODY, Theme.TEXT_MUTED);
	private final JLabel lblVersion = Theme.label("—", Theme.BODY, Theme.TEXT_MUTED);

	private final OutlineButton btnStart = new OutlineButton("启动", Theme.PRIMARY, true);
	private final OutlineButton btnStop = new OutlineButton("停止", RED, false);
	private final OutlineButton btnRestart = new OutlineButton("重启", ORANGE, false);
	private final OutlineButton btnTest = new OutlineButton("配置检测", Theme.TEXT_MUTED, false);
	private final MonitorSwitch monitorSwitch = new MonitorSwitch();

	private final WrapFlowPanel portCell = new WrapFlowPanel(6, 4);
	private JLabel vVersion, vMasterPid, vProcess, vConf, vExe, vCapacity, vStub, vActive,
			vReading, vWriting, vWaiting, vAccepts, vHandled, vRequests;

	private boolean syncing;
	/** 监控配置写入进行中: 禁用开关, 避免并发改写 nginx.conf 与重复重启 */
	private boolean monitorWriting;
	private MainFrame.InstanceRow currentRow;
	private NginxDashboardInstance detail;
	private int loadSeq;

	/** 顶部两行: 宽度变化需清除按旧宽度缓存的 preferredSize, 否则折行高度不生效 */
	private JPanel titleRow;
	private JPanel statusRow;

	public InstanceOverviewPanel(MainFrame mf) {
		this.mf = mf;
		setOpaque(true);
		setBackground(Theme.BG);
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(14, Theme.GAP_L, Theme.GAP_M, Theme.GAP_L));

		JPanel top = new JPanel();
		top.setOpaque(false);
		top.setLayout(new javax.swing.BoxLayout(top, javax.swing.BoxLayout.Y_AXIS));
		top.add(buildTitleRow());
		top.add(javax.swing.Box.createVerticalStrut(14));
		top.add(buildStatusRow());
		top.add(javax.swing.Box.createVerticalStrut(16));
		add(top, BorderLayout.NORTH);

		// 卡片高度贴合表格内容(与截图一致), 剩余区域保持页面底色
		JPanel cardWrap = new JPanel(new BorderLayout());
		cardWrap.setOpaque(false);
		cardWrap.add(buildDetailCard(), BorderLayout.NORTH);
		add(cardWrap, BorderLayout.CENTER);

		// 宽度变化时标题行/状态行可能折成两行, 直接 invalidate 行容器以清除
		// 按旧宽度缓存的 preferredSize, 再由 revalidate 重算顶部区域高度
		addComponentListener(new ComponentAdapter() {
			@Override
			public void componentResized(ComponentEvent e) {
				if (titleRow != null) {
					titleRow.invalidate();
				}
				if (statusRow != null) {
					statusRow.invalidate();
				}
				revalidate();
			}
		});

		bindActions();
		render(null, null);
	}

	// ==================== 顶部标题行 ====================

	private JPanel buildTitleRow() {
		// 窄窗口/界面缩放放不下时, 实例下拉与操作按钮自动折到第二行, 不覆盖标题
		JPanel row = new JPanel(new WrapRowLayout(Theme.GAP_L, 6));
		row.setOpaque(false);

		JLabel title = Theme.label("实例总览", Theme.TITLE.deriveFont(Font.BOLD, 20f), Theme.TEXT);
		row.add(title);

		combo.setFont(Theme.BODY);
		combo.setPreferredSize(new Dimension(220, CONTROL_HEIGHT));
		combo.setBackground(Theme.SELECTED_BG);
		combo.putClientProperty("JComponent.roundRect", true);
		combo.setRenderer(comboRenderer);
		// 当前选中实例用主色圆角描边强调, 与下拉项内的高亮底色/勾选标识形成双重辨识
		combo.setBorder(new RoundedLineBorder(Theme.PRIMARY, 10));

		btnMore.setToolTipText("添加已有实例、编辑别名、打开安装目录、忽略/恢复实例");

		JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.GAP_S, 0));
		right.setOpaque(false);
		right.add(combo);
		right.add(btnMore);
		row.add(right);
		titleRow = row;
		return row;
	}

	// ==================== 状态行 ====================

	private JPanel buildStatusRow() {
		// 状态信息与启停按钮同排; 放不下时按钮折到第二行, 避免压住 PID/服务标签
		JPanel row = new JPanel(new WrapRowLayout(Theme.GAP_M, 6));
		row.setOpaque(false);

		JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
		left.setOpaque(false);
		tagStatus.setFont(Theme.SMALL);
		tagService.setFont(Theme.SMALL);
		left.add(tagStatus);
		left.add(tagService);
		left.add(lblPid);
		left.add(lblWorker);
		left.add(lblVersion);
		row.add(left);

		JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		actions.setOpaque(false);
		actions.add(btnStart);
		actions.add(btnStop);
		actions.add(btnRestart);
		actions.add(btnTest);
		row.add(actions);
		statusRow = row;
		return row;
	}

	// ==================== 配置详情卡片 ====================

	private JPanel buildDetailCard() {
		JPanel card = new JPanel(new BorderLayout());
		card.setBackground(Theme.SURFACE);
		card.setBorder(BorderFactory.createLineBorder(Theme.BORDER));

		JPanel head = new JPanel(new BorderLayout());
		head.setOpaque(false);
		head.setBorder(new EmptyBorder(14, 16, 10, 16));
		head.add(Theme.label("配置详情", Theme.TITLE, Theme.TEXT), BorderLayout.WEST);

		JPanel headRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		headRight.setOpaque(false);
		JLabel icon = new JLabel(new MonitorIcon());
		icon.setToolTipText("stub_status 监控采集开关");
		headRight.add(icon);
		headRight.add(Theme.label("监控开关", Theme.SMALL, Theme.TEXT_MUTED));
		monitorSwitch.setToolTipText("开启后采集 stub_status 实时指标; 配置中未启用时会提示写入并重启实例");
		headRight.add(monitorSwitch);
		head.add(headRight, BorderLayout.EAST);
		card.add(head, BorderLayout.NORTH);

		JPanel body = new JPanel(new BorderLayout());
		body.setOpaque(false);
		body.setBorder(new EmptyBorder(0, 16, 16, 16));
		body.add(buildGrid(), BorderLayout.NORTH);
		card.add(body, BorderLayout.CENTER);
		return card;
	}

	private JPanel buildGrid() {
		GridPanel grid = new GridPanel();

		portCell.setBackground(Theme.SURFACE);
		portCell.setBorder(new EmptyBorder(0, 8, 0, 8));

		addCell(grid, labelCell("监听端口", 76), 0, 0, 1);
		addCell(grid, portCell, 1, 0, 5);

		vVersion = valueLabel();
		vMasterPid = valueLabel();
		vProcess = valueLabel();
		addCell(grid, labelCell("版本", 76), 0, 1, 1);
		addCell(grid, valueCell(vVersion), 1, 1, 1);
		addCell(grid, labelCell("Master PID", 92), 2, 1, 1);
		addCell(grid, valueCell(vMasterPid), 3, 1, 1);
		addCell(grid, labelCell("进程数", 76), 4, 1, 1);
		addCell(grid, valueCell(vProcess), 5, 1, 1);

		vConf = valueLabel();
		addCell(grid, labelCell("配置文件", 76), 0, 2, 1);
		addCell(grid, valueCell(vConf), 1, 2, 5);

		vExe = valueLabel();
		addCell(grid, labelCell("执行文件", 76), 0, 3, 1);
		addCell(grid, valueCell(vExe), 1, 3, 5);

		vCapacity = valueLabel();
		vStub = valueLabel();
		vActive = valueLabel();
		addCell(grid, labelCell("理论容量", 76), 0, 4, 1);
		addCell(grid, valueCell(vCapacity), 1, 4, 1);
		addCell(grid, labelCell("stub 端口", 92), 2, 4, 1);
		addCell(grid, valueCell(vStub), 3, 4, 1);
		addCell(grid, labelCell("活跃连接", 76), 4, 4, 1);
		addCell(grid, valueCell(vActive), 5, 4, 1);

		vReading = valueLabel();
		vWriting = valueLabel();
		vWaiting = valueLabel();
		addCell(grid, labelCell("读取请求数", 76), 0, 5, 1);
		addCell(grid, valueCell(vReading), 1, 5, 1);
		addCell(grid, labelCell("响应", 92), 2, 5, 1);
		addCell(grid, valueCell(vWriting), 3, 5, 1);
		addCell(grid, labelCell("等待处理", 76), 4, 5, 1);
		addCell(grid, valueCell(vWaiting), 5, 5, 1);

		vAccepts = valueLabel();
		vHandled = valueLabel();
		vRequests = valueLabel();
		addCell(grid, labelCell("握手总数", 76), 0, 6, 1);
		addCell(grid, valueCell(vAccepts), 1, 6, 1);
		addCell(grid, labelCell("连接总数", 92), 2, 6, 1);
		addCell(grid, valueCell(vHandled), 3, 6, 1);
		addCell(grid, labelCell("总请求数", 76), 4, 6, 1);
		addCell(grid, valueCell(vRequests), 5, 6, 1);
		return grid;
	}

	private static JLabel valueLabel() {
		// 数据值比标签大一档, 与标签列形成"说明 / 内容"的层级
		JLabel l = Theme.label("—", Theme.BODY, Theme.TEXT);
		l.setBorder(new EmptyBorder(0, 1, 0, 1));
		return l;
	}

	private static JPanel labelCell(String text, int width) {
		JPanel p = new JPanel(new BorderLayout());
		// 去掉标签列整块灰底, 改由字号/颜色区分, 避免形成老式表格的"标签栏"
		p.setBackground(Theme.SURFACE);
		p.setBorder(new EmptyBorder(0, 10, 0, 6));
		p.setPreferredSize(new Dimension(width, ROW_HEIGHT));
		p.add(Theme.label(text, Theme.SMALL.deriveFont(11f), Theme.TEXT_MUTED), BorderLayout.CENTER);
		return p;
	}

	private static JPanel valueCell(JLabel label) {
		JPanel p = new JPanel(new BorderLayout());
		p.setBackground(Theme.SURFACE);
		p.setBorder(new EmptyBorder(0, 10, 0, 10));
		p.setPreferredSize(new Dimension(10, ROW_HEIGHT));
		p.add(label, BorderLayout.CENTER);
		return p;
	}

	private static void addCell(GridPanel grid, JPanel cell, int x, int y, int width) {
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = x;
		c.gridy = y;
		c.gridwidth = width;
		c.gridheight = 1;
		c.weightx = weight(x, width);
		c.weighty = 0;
		c.fill = GridBagConstraints.BOTH;
		// 分组首行上方留白: 用空白代替横向分隔线, 分组关系更清楚
		c.insets = new Insets(GROUP_STARTS.contains(y) ? GROUP_GAP : 0, 0, 0, 0);
		grid.add(cell, c);
	}

	private static double weight(int x, int width) {
		double sum = 0;
		for (int i = x; i < x + width && i < COL_WEIGHT.length; i++) {
			sum += COL_WEIGHT[i];
		}
		return sum;
	}

	// ==================== 交互 ====================

	private void bindActions() {
		combo.addActionListener(e -> {
			if (syncing) {
				return;
			}
			Object value = combo.getSelectedItem();
			if (!(value instanceof MainFrame.InstanceRow)) {
				return;
			}
			MainFrame.InstanceRow row = (MainFrame.InstanceRow) value;
			if (row == currentRow) {
				return;
			}
			currentRow = row;
			detail = null;
			mf.selectInstanceRow(row);
			render(row, null);
			loadDetail(row);
		});

		btnMore.addActionListener(e -> showManageMenu());

		btnStart.addActionListener(e -> withCurrent(entry -> mf.controlInstanceEntry(entry, "start")));
		btnStop.addActionListener(e -> withCurrent(entry -> mf.controlInstanceEntry(entry, "stop")));
		btnRestart.addActionListener(e -> withCurrent(entry -> mf.controlInstanceEntry(entry, "restart")));
		btnTest.addActionListener(e -> withCurrent(mf::testInstanceEntry));

		monitorSwitch.addActionListener(e -> toggleMonitor(monitorSwitch.isSelected()));
		OperationLock.addListener(() -> {
			if (currentRow != null && !monitorWriting) {
				render(currentRow, detail);
			}
		});
	}

	/**
	 * 监控开关:
	 * 关闭只停采集/展示, 不删配置;
	 * 开启时若配置中尚无 stub_status, 提示后写入受标记块并重启生效, 与「Nginx 状态」页的启用监控一致.
	 */
	private void toggleMonitor(boolean on) {
		MainFrame.InstanceRow row = currentRow;
		if (row == null || row.ignored || monitorWriting) {
			return;
		}
		if (!on) {
			mf.setStubMonitor(row.entry, false);
			loadDetail(row);
			return;
		}
		boolean applicable = detail != null && Boolean.TRUE.equals(detail.getStubApplicable());
		if (applicable) {
			mf.setStubMonitor(row.entry, true);
			loadDetail(row);
			return;
		}

		String object = "实例「" + MainFrame.InstanceRow.displayName(row.entry) + "」的监控配置（stub_status）";
		String consequence = "将向 nginx.conf 写入 stub_status 块并重启实例使其生效，"
				+ "重启期间站点短暂无法访问。";
		String reversibility = "可撤销：写入前会做 nginx -t 配置检测，失败自动回滚；"
				+ "可随时关闭监控移除该配置。";
		if (!ConfirmDialog.confirm(this, "启用监控", object, consequence,
				reversibility, "启用监控")) {
			monitorSwitch.setSelected(isMonitorOn(row, detail));
			return;
		}

		if (!OperationLock.tryAcquire("启用监控")) {
			monitorSwitch.setSelected(isMonitorOn(row, detail));
			mf.notifyToast(OperationLock.reason());
			return;
		}
		monitorWriting = true;
		Theme.setEnabled(monitorSwitch, false, "正在写入监控配置，请稍候");
		new SwingWorker<NginxDashboardUtils.StubConfigResult, Void>() {
			@Override
			protected NginxDashboardUtils.StubConfigResult doInBackground() {
				return NginxDashboardUtils.writeStubConfig(row.entry);
			}

			@Override
			protected void done() {
				OperationLock.release();
				monitorWriting = false;
				NginxDashboardUtils.StubConfigResult result;
				try {
					result = get();
				} catch (Exception ex) {
					result = new NginxDashboardUtils.StubConfigResult();
					result.error = com.nginxui.utils.ErrorReporter.friendlyReason(ex);
				}
				if (result.error != null) {
					mf.notifyToast("启用监控失败: " + result.error);
				} else {
					mf.setStubMonitor(row.entry, true);
					String applied = result.applied
							? "，已通过 " + StrUtil.emptyToDefault(result.appliedBy, "restart") + " 生效"
							: "，实例启动后生效";
					mf.notifyToast("监控已启用: " + result.uri + " (端口 " + result.port + ")" + applied);
					mf.onConfigChanged();
				}
				if (currentRow != null) {
					Theme.setEnabled(monitorSwitch, !currentRow.ignored,
							currentRow.ignored ? "该实例已忽略：点击「恢复实例」后可操作" : null);
					if (result.error != null) {
						monitorSwitch.setSelected(isMonitorOn(currentRow, detail));
					}
					loadDetail(currentRow);
				}
			}
		}.execute();
	}

	/** 当前生效的监控开关状态: 有明细时以服务端判定为准, 否则回退注册表显式值 */
	private static boolean isMonitorOn(MainFrame.InstanceRow row, NginxDashboardInstance d) {
		return d != null ? Boolean.TRUE.equals(d.getStubMonitor())
				: Boolean.TRUE.equals(row.entry.getStubMonitor());
	}

	private void withCurrent(java.util.function.Consumer<NginxInstanceEntry> action) {
		if (currentRow == null || currentRow.ignored) {
			return;
		}
		action.accept(currentRow.entry);
	}

	/**
	 * 页头"更多"菜单: 「添加已有实例」是全局操作, 未选中实例时也可用(空列表时正是主要入口);
	 * 「编辑别名/打开目录/忽略」属于当前实例, 未选中时不出现在菜单里.
	 */
	private void showManageMenu() {
		MainFrame.InstanceRow row = currentRow;
		JPopupMenu menu = Theme.popup();
		JMenuItem miAdd = Theme.menuItem("添加已有实例…");
		miAdd.setToolTipText("手动添加未注册服务、也未运行的 nginx 实例");
		miAdd.addActionListener(e -> mf.promptAddInstance());
		menu.add(miAdd);

		if (row != null) {
			menu.addSeparator();
			JMenuItem miRename = Theme.menuItem("编辑别名…");
			JMenuItem miDir = Theme.menuItem("打开安装目录");
			JMenuItem miIgnore = Theme.menuItem(row.ignored ? "恢复实例" : "忽略实例");

			miRename.addActionListener(e -> mf.renameInstanceEntry(row.entry));
			miDir.addActionListener(e -> mf.openInstanceDirOf(row.entry));
			miIgnore.addActionListener(e -> mf.toggleIgnoreEntry(row.entry));

			menu.add(miRename);
			menu.add(miDir);
			menu.add(miIgnore);
		}
		// 右对齐到按钮右边缘, 避免 32px 图标按钮弹出时菜单偏出可视区
		menu.show(btnMore, btnMore.getWidth() - menu.getPreferredSize().width,
				btnMore.getHeight() + 4);
	}

	// ==================== 数据 ====================

	/** 实例列表(整轮刷新后)由主窗口推送: 重建下拉并保持当前选中 */
	public void onRowsChanged() {
		List<MainFrame.InstanceRow> rows = mf.instanceRows();
		String keepId = currentRow == null ? null : currentRow.entry.getId();

		syncing = true;
		try {
			combo.removeAllItems();
			for (MainFrame.InstanceRow row : rows) {
				combo.addItem(row);
			}
		} finally {
			syncing = false;
		}

		MainFrame.InstanceRow next = null;
		if (keepId != null) {
			for (MainFrame.InstanceRow row : rows) {
				if (keepId.equals(row.entry.getId())) {
					next = row;
					break;
				}
			}
		}
		if (next == null && !rows.isEmpty()) {
			next = rows.get(0);
		}

		syncing = true;
		combo.setSelectedItem(next);
		syncing = false;
		Theme.setEnabled(combo, !rows.isEmpty(), "暂无可操作实例");

		if (next == null) {
			currentRow = null;
			detail = null;
			render(null, null);
			return;
		}
		boolean changed = currentRow == null || !next.entry.getId().equals(currentRow.entry.getId());
		currentRow = next;
		mf.selectInstanceRow(next);
		if (changed) {
			detail = null;
			render(next, null);
		}
		if (isShowing()) {
			loadDetail(next);
		}
	}

	/** 页面切换到实例总览时刷新一次明细 */
	public void onShown() {
		if (currentRow == null) {
			onRowsChanged();
			return;
		}
		loadDetail(currentRow);
	}

	public NginxInstanceEntry selectedEntry() {
		return currentRow == null ? null : currentRow.entry;
	}

	/**
	 * 外部请求切换到指定实例(概览实例状态表点击进入):
	 * 同步下拉选中与主窗口选中态, 明细由 onShown 统一加载, 避免重复请求.
	 */
	public void selectEntry(NginxInstanceEntry entry) {
		if (entry == null) {
			return;
		}
		MainFrame.InstanceRow target = null;
		for (int i = 0; i < combo.getItemCount(); i++) {
			MainFrame.InstanceRow row = combo.getItemAt(i);
			if (row != null && entry.getId().equals(row.entry.getId())) {
				target = row;
				break;
			}
		}
		if (target == null) {
			return;
		}
		boolean changed = currentRow == null || !entry.getId().equals(currentRow.entry.getId());
		currentRow = target;
		syncing = true;
		try {
			combo.setSelectedItem(target);
		} finally {
			syncing = false;
		}
		mf.selectInstanceRow(target);
		if (changed) {
			detail = null;
			render(target, null);
		}
	}

	private void loadDetail(final MainFrame.InstanceRow row) {
		final NginxInstanceEntry entry = row.entry;
		final int seq = ++loadSeq;
		new SwingWorker<NginxDashboardInstance, Void>() {
			@Override
			protected NginxDashboardInstance doInBackground() {
				try {
					return NginxDashboardUtils.detailOf(entry, mf.currentRunning(entry),
							mf.currentLive(entry), mf.currentService(entry));
				} catch (Exception ex) {
					return null;
				}
			}

			@Override
			protected void done() {
				if (seq != loadSeq || currentRow == null || currentRow.entry != entry) {
					return;
				}
				NginxDashboardInstance d;
				try {
					d = get();
				} catch (Exception ex) {
					d = null;
				}
				detail = d;
				render(currentRow, d);
			}
		}.execute();
	}

	// ==================== 渲染 ====================

	private void render(MainFrame.InstanceRow row, NginxDashboardInstance d) {
		if (row == null) {
			renderEmpty();
			return;
		}
		boolean running = row.running;
		boolean ignored = row.ignored;

		if (ignored) {
			tagStatus.setStyle("已忽略", Theme.WARNING, Theme.WARNING_BG, Theme.WARNING_BORDER);
		} else if (row.detecting) {
			tagStatus.setStyle("检测中", Theme.TEXT_MUTED, new Color(0xF3F4F6), Theme.BORDER);
		} else if (running) {
			tagStatus.setStyle("运行中", GREEN_TEXT, GREEN_BG, GREEN_BORDER);
		} else {
			tagStatus.setStyle("已停止", RED, Theme.DANGER_BG, Theme.DANGER_BORDER);
		}

		String svc = d != null ? d.getWinServiceName() : row.entry.getWinServiceName();
		tagService.setVisible(StrUtil.isNotEmpty(svc));
		tagService.setToolTipText(svc);

		String pid = d != null && StrUtil.isNotEmpty(d.getMasterPid()) ? d.getMasterPid()
				: row.entry.getLastMasterPid();
		lblPid.setText("PID: " + StrUtil.emptyToDefault(pid, "—"));

		Integer workers = d == null ? null : d.getWorkerCount();
		lblWorker.setText("Worker 进程: " + (workers == null ? "—" : workers));

		String version = d == null ? null : d.getVersion();
		lblVersion.setText(version == null || version.isEmpty() ? "—"
				: (version.startsWith("v") ? version : "v" + version));

		boolean canControl = !ignored && !OperationLock.isBusy();
		String ignoredReason = "该实例已忽略：点击「恢复实例」后可操作";
		String lockReason = OperationLock.reason();
		Theme.setEnabled(btnMore, true, null);
		Theme.setEnabled(btnStart, canControl && !running,
				ignored ? ignoredReason : (lockReason != null ? lockReason
						: (running ? "实例正在运行：无需重复启动" : null)));
		Theme.setEnabled(btnStop, canControl && running,
				ignored ? ignoredReason : (lockReason != null ? lockReason
						: (running ? null : "实例未运行：启动后才可停止")));
		Theme.setEnabled(btnRestart, canControl && running,
				ignored ? ignoredReason : (lockReason != null ? lockReason
						: (running ? null : "实例未运行：启动后才可重启")));
		Theme.setEnabled(btnTest, canControl && !OperationLock.isBusy(),
				ignored ? ignoredReason : lockReason);

		boolean monitorOn = isMonitorOn(row, d);
		monitorSwitch.setSelected(monitorOn);
		Theme.setEnabled(monitorSwitch, canControl && !monitorWriting,
				ignored ? ignoredReason : (monitorWriting ? "正在写入监控配置，请稍候" : lockReason));

		// 监听端口标签
		portCell.removeAll();
		List<String> ports = d == null ? null : d.getListenPorts();
		if (ports == null || ports.isEmpty()) {
			portCell.add(Theme.label("—", Theme.BODY, Theme.TEXT));
		} else {
			for (String port : ports) {
				if (StrUtil.isEmpty(port)) {
					continue;
				}
				Tag tag = new Tag(port, Theme.PRIMARY, PORT_BG, PORT_BORDER);
				tag.setFont(Theme.SMALL.deriveFont(11f));
				portCell.add(tag);
			}
		}
		portCell.revalidate();
		portCell.repaint();

		vVersion.setText(text(version));
		vMasterPid.setText(text(pid));
		vProcess.setText(workers == null ? "—" : workers + " (worker " + workers + ")");
		String conf = d == null ? row.entry.getConfPath() : d.getNginxPath();
		vConf.setText(text(conf));
		vConf.setToolTipText(StrUtil.emptyToNull(conf));
		String exe = d == null ? row.entry.getNginxExe() : d.getNginxExe();
		vExe.setText(text(exe));
		vExe.setToolTipText(StrUtil.emptyToNull(exe));

		vCapacity.setText(d == null || d.getMaxConnections() == null ? "—"
				: number(d.getMaxConnections()) + " 连接");
		vStub.setText(d == null || d.getStubPort() == null ? "—" : String.valueOf(d.getStubPort()));
		vActive.setText(d == null ? "—" : number(d.getActiveConnections()));
		vReading.setText(d == null ? "—" : number(d.getReading()));
		vWriting.setText(d == null ? "—" : number(d.getWriting()));
		vWaiting.setText(d == null ? "—" : number(d.getWaiting()));
		vAccepts.setText(d == null ? "—" : number(d.getAccepts()));
		vHandled.setText(d == null ? "—" : number(d.getHandled()));
		vRequests.setText(d == null ? "—" : number(d.getRequests()));
	}

	private void renderEmpty() {
		tagStatus.setStyle("无实例", Theme.TEXT_MUTED, new Color(0xF3F4F6), Theme.BORDER);
		tagService.setVisible(false);
		lblPid.setText("PID: —");
		lblWorker.setText("Worker 进程: —");
		lblVersion.setText("—");

		String noRowReason = "先选择实例：从上方下拉列表中选择一个实例";
		// 「⋯」菜单含全局的「添加已有实例」, 无实例时仍需可用
		Theme.setEnabled(btnMore, true, null);
		Theme.setEnabled(btnStart, false, noRowReason);
		Theme.setEnabled(btnStop, false, noRowReason);
		Theme.setEnabled(btnRestart, false, noRowReason);
		Theme.setEnabled(btnTest, false, noRowReason);
		monitorSwitch.setSelected(false);
		Theme.setEnabled(monitorSwitch, false, noRowReason);

		portCell.removeAll();
		portCell.add(Theme.label("—", Theme.BODY, Theme.TEXT));
		portCell.revalidate();
		portCell.repaint();

		vVersion.setText("—");
		vMasterPid.setText("—");
		vProcess.setText("—");
		vConf.setText("—");
		vConf.setToolTipText(null);
		vExe.setText("—");
		vExe.setToolTipText(null);
		vCapacity.setText("—");
		vStub.setText("—");
		vActive.setText("—");
		vReading.setText("—");
		vWriting.setText("—");
		vWaiting.setText("—");
		vAccepts.setText("—");
		vHandled.setText("—");
		vRequests.setText("—");
	}

	private static String text(String value) {
		return StrUtil.isEmpty(value) ? "—" : value;
	}

	private static String number(Long value) {
		return value == null ? "—" : String.format("%,d", value);
	}

	// ==================== 自绘控件 ====================

	/**
	 * 实例下拉渲染: 状态圆点 + 名称 + 选中勾选标识.
	 * 收起状态(index&lt;0)同样按选中态高亮, 避免下拉框内当前实例显示模糊.
	 */
	private static final class InstanceComboRenderer extends JPanel
			implements ListCellRenderer<MainFrame.InstanceRow> {
		private static final long serialVersionUID = 1L;

		private final JLabel dot = new JLabel();
		private final JLabel name = new JLabel();
		private final JLabel mark = new JLabel(new CheckIcon());
		private boolean active;

		InstanceComboRenderer() {
			super(new BorderLayout(8, 0));
			setOpaque(false);
			setBorder(new EmptyBorder(0, 8, 0, 6));
			dot.setPreferredSize(new Dimension(12, 16));
			dot.setHorizontalAlignment(SwingConstants.CENTER);
			name.setFont(Theme.BODY);
			mark.setPreferredSize(new Dimension(16, 16));
			add(dot, BorderLayout.WEST);
			add(name, BorderLayout.CENTER);
			add(mark, BorderLayout.EAST);
		}

		@Override
		public Component getListCellRendererComponent(JList<? extends MainFrame.InstanceRow> list,
				MainFrame.InstanceRow row, int index, boolean isSelected, boolean cellHasFocus) {
			boolean closed = index < 0;
			active = closed || isSelected;
			if (row == null) {
				dot.setText("");
				name.setText("");
				mark.setVisible(false);
				return this;
			}
			if (row.ignored) {
				dot.setText("⊘");
				dot.setForeground(Theme.WARNING);
			} else if (row.detecting) {
				dot.setText("…");
				dot.setForeground(Theme.TEXT_MUTED);
			} else {
				dot.setText(row.running ? "●" : "○");
				dot.setForeground(Theme.statusColor(row.running));
			}
			dot.setFont(Theme.SMALL.deriveFont(Font.BOLD, 11f));
			name.setText(MainFrame.InstanceRow.displayName(row.entry)
					+ (row.ignored ? "（已忽略）" : ""));
			name.setFont(active ? Theme.BODY_BOLD : Theme.BODY);
			name.setForeground(active ? Theme.PRIMARY : Theme.TEXT);
			mark.setVisible(active);
			return this;
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(active ? Theme.SELECTED_BG : Theme.SURFACE);
				if (active) {
					g2.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 10, 10);
				} else {
					g2.fillRect(0, 0, getWidth(), getHeight());
				}
			} finally {
				g2.dispose();
			}
		}
	}

	/** 选中勾选标识: 矢量绘制, 不依赖字体字形 */
	private static final class CheckIcon implements Icon {
		@Override
		public void paintIcon(Component c, Graphics g, int x, int y) {
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(Theme.PRIMARY);
				g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g2.drawLine(x + 2, y + 7, x + 5, y + 10);
				g2.drawLine(x + 5, y + 10, x + 11, y + 3);
			} finally {
				g2.dispose();
			}
		}

		@Override
		public int getIconWidth() {
			return 14;
		}

		@Override
		public int getIconHeight() {
			return 13;
		}
	}

	/** 圆角描边: 强调当前实例下拉框, 形状与 FlatLaf 的圆角内框贴合 */
	private static final class RoundedLineBorder extends AbstractBorder {
		private static final long serialVersionUID = 1L;

		private final Color color;
		private final int arc;

		RoundedLineBorder(Color color, int arc) {
			this.color = color;
			this.arc = arc;
		}

		@Override
		public Insets getBorderInsets(Component c) {
			return new Insets(1, 8, 1, 4);
		}

		@Override
		public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(color);
				g2.setStroke(new BasicStroke(1.6f));
				g2.drawRoundRect(x + 1, y + 1, width - 3, height - 3, arc, arc);
			} finally {
				g2.dispose();
			}
		}
	}

	/** 圆角标签: 状态/服务方式/端口等短标记, 供配置编辑页复用 */
	static final class Tag extends JLabel {
		private static final long serialVersionUID = 1L;
		private Color fill;
		private Color line;

		Tag(String text, Color fg, Color fill, Color line) {
			super(text);
			setOpaque(false);
			setForeground(fg);
			setBorder(new EmptyBorder(2, 9, 2, 9));
			this.fill = fill;
			this.line = line;
		}

		void setStyle(String text, Color fg, Color fill, Color line) {
			setText(text);
			setForeground(fg);
			this.fill = fill;
			this.line = line;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(fill);
				g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 5, 5);
				g2.setColor(line);
				g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 5, 5);
			} finally {
				g2.dispose();
			}
			super.paintComponent(g);
		}
	}

	/** 描边动作按钮: 白底 + 语义色描边/文字, 禁用时统一灰化 */
	private static final class OutlineButton extends JButton {
		private static final long serialVersionUID = 1L;
		private final Color accent;
		private final boolean softFill;

		OutlineButton(String text, Color accent, boolean softFill) {
			super(text);
			this.accent = accent;
			this.softFill = softFill;
			setFont(Theme.SMALL);
			setOpaque(false);
			setContentAreaFilled(false);
			setBorderPainted(false);
			setFocusPainted(false);
			setRolloverEnabled(true);
			setMargin(new Insets(3, 10, 3, 10));
		}

		@Override
		public Dimension getPreferredSize() {
			Dimension d = super.getPreferredSize();
			return new Dimension(d.width, 28);
		}

		@Override
		public void setEnabled(boolean enabled) {
			super.setEnabled(enabled);
			setCursor(Cursor.getPredefinedCursor(enabled ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				int w = getWidth();
				int h = getHeight();
				boolean enabled = isEnabled();
				Color fill;
				Color line;
				Color fg;
				if (!enabled) {
					fill = softFill ? SOFT_BLUE : DISABLED_FILL;
					line = softFill ? new Color(0xDBEAFE) : Theme.BORDER;
					fg = softFill ? DISABLED_BLUE_TEXT : DISABLED_TEXT;
				} else if (getModel().isPressed()) {
					fill = blend(accent, Theme.SURFACE, 0.18f);
					line = accent;
					fg = softFill ? Theme.PRIMARY : blend(accent, Theme.TEXT, 0.25f);
				} else if (getModel().isRollover()) {
					fill = softFill ? new Color(0xDBEAFE) : blend(accent, Theme.SURFACE, 0.08f);
					line = accent;
					fg = softFill ? Theme.PRIMARY : blend(accent, Theme.TEXT, 0.25f);
				} else {
					fill = softFill ? SOFT_BLUE : Theme.SURFACE;
					line = softFill ? SOFT_BLUE_BORDER : accent;
					fg = softFill ? Theme.PRIMARY : blend(accent, Theme.TEXT, 0.25f);
				}
				g2.setColor(fill);
				g2.fillRoundRect(0, 0, w - 1, h - 1, 7, 7);
				g2.setColor(line);
				g2.drawRoundRect(0, 0, w - 1, h - 1, 7, 7);

				g2.setFont(getFont());
				g2.setColor(fg);
				FontMetrics fm = g2.getFontMetrics();
				String text = getText();
				int tx = (w - fm.stringWidth(text)) / 2;
				int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
				g2.drawString(text, Math.max(3, tx), ty);
			} finally {
				g2.dispose();
			}
		}
	}

	/** 页头"更多操作"图标按钮: 三点, 幽灵样式, 悬停浅灰底 */
	private static final class MoreButton extends JButton {
		private static final long serialVersionUID = 1L;

		MoreButton() {
			super();
			setOpaque(false);
			setContentAreaFilled(false);
			setBorderPainted(false);
			setFocusPainted(false);
			setRolloverEnabled(true);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		}

		@Override
		public Dimension getPreferredSize() {
			return new Dimension(CONTROL_HEIGHT, CONTROL_HEIGHT);
		}

		@Override
		public void setEnabled(boolean enabled) {
			super.setEnabled(enabled);
			setCursor(Cursor.getPredefinedCursor(enabled ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				int w = getWidth();
				int h = getHeight();
				boolean enabled = isEnabled();
				boolean hover = enabled && getModel().isRollover();
				g2.setColor(!enabled ? Theme.BG : (hover ? Theme.HOVER_BG : Theme.SURFACE));
				g2.fillRoundRect(0, 0, w - 1, h - 1, 8, 8);
				g2.setColor(enabled ? Theme.BORDER : Theme.GRID_LINE);
				g2.drawRoundRect(0, 0, w - 1, h - 1, 8, 8);

				g2.setColor(enabled ? Theme.TEXT_MUTED : DISABLED_TEXT);
				int cy = h / 2;
				int r = 2;
				int gap = 6;
				int cx = w / 2 - gap;
				for (int i = 0; i < 3; i++) {
					g2.fillOval(cx - r, cy - r, r * 2, r * 2);
					cx += gap;
				}
			} finally {
				g2.dispose();
			}
		}
	}

	/** 监控开关: 药丸开关, 开=主色+旋钮在右, 关=灰色+旋钮在左 */
	private static final class MonitorSwitch extends JToggleButton {
		private static final long serialVersionUID = 1L;

		MonitorSwitch() {
			setOpaque(false);
			setContentAreaFilled(false);
			setBorderPainted(false);
			setFocusPainted(false);
			setPreferredSize(new Dimension(46, 22));
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			setToolTipText("开启/关闭该实例的 stub_status 监控展示");
		}

		@Override
		public void setEnabled(boolean enabled) {
			super.setEnabled(enabled);
			setCursor(Cursor.getPredefinedCursor(enabled ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				int w = getWidth();
				int h = getHeight();
				boolean on = isSelected();
				Color pill = !isEnabled() ? new Color(0xE5E7EB)
						: (on ? Theme.PRIMARY : new Color(0xD1D5DB));
				g2.setColor(pill);
				g2.fillRoundRect(0, 0, w - 1, h - 1, h, h);

				int kd = h - 4;
				int kx = on ? w - kd - 2 : 2;
				g2.setColor(Color.WHITE);
				g2.fillOval(kx, 2, kd, kd);

				g2.setFont(Theme.SMALL.deriveFont(Font.BOLD, 11f));
				g2.setColor(Color.WHITE);
				String text = on ? "开" : "关";
				FontMetrics fm = g2.getFontMetrics();
				int tx = on ? 7 : w - fm.stringWidth(text) - 7;
				int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
				g2.drawString(text, tx, ty);
			} finally {
				g2.dispose();
			}
		}
	}

	/** 可换行的端口标签容器: 标准 FlowLayout 只在布局时换行, 首选高度需按实际宽度重算 */
	private static final class WrapFlowPanel extends JPanel {
		private static final long serialVersionUID = 1L;

		WrapFlowPanel(int hgap, int vgap) {
			super(new FlowLayout(FlowLayout.LEFT, hgap, vgap));
			setBackground(Theme.SURFACE);
		}

		@Override
		public Dimension getPreferredSize() {
			Dimension oneRow = super.getPreferredSize();
			int width = getWidth();
			if (width <= 0) {
				return oneRow;
			}
			return new Dimension(oneRow.width, heightForWidth(width));
		}

		/** 指定宽度下换行后的所需高度(供网格在布局前确定行高) */
		int heightForWidth(int width) {
			FlowLayout fl = (FlowLayout) getLayout();
			Insets in = getInsets();
			int hgap = fl.getHgap();
			int vgap = fl.getVgap();
			int avail = width - in.left - in.right - hgap * 2;
			if (avail <= 0) {
				return ROW_HEIGHT;
			}
			int rowWidth = 0;
			int rows = 1;
			int maxHeight = 0;
			for (Component c : getComponents()) {
				if (!c.isVisible()) {
					continue;
				}
				Dimension d = c.getPreferredSize();
				maxHeight = Math.max(maxHeight, d.height);
				int add = (rowWidth == 0 ? 0 : hgap) + d.width;
				if (rowWidth > 0 && rowWidth + add > avail) {
					rows++;
					rowWidth = d.width;
				} else {
					rowWidth += add;
				}
			}
			return rows * maxHeight + (rows - 1) * vgap + vgap * 2 + in.top + in.bottom;
		}
	}

	/** 详情网格: 子控件铺满后统一描边, 保证 1px 细线不重复 */
	private final class GridPanel extends JPanel {
		private static final long serialVersionUID = 1L;

		GridPanel() {
			super(new GridBagLayout());
			setBackground(Theme.SURFACE);
		}

		@Override
		public void doLayout() {
			// 端口标签在窄宽度下换行: 先按实际可用宽度算出行高, 再交给 GridBag 布局
			int portWidth = Math.max(80, getWidth() - 76);
			int needHeight = portCell.heightForWidth(portWidth);
			Dimension pref = portCell.getPreferredSize();
			if (pref.height != needHeight || pref.width != 10) {
				portCell.setPreferredSize(new Dimension(10, needHeight));
				// 行高变化后由父级重新计算卡片高度(延迟到下一个布局周期)
				revalidate();
			}
			super.doLayout();
		}

		@Override
		public void paint(Graphics g) {
			super.paint(g);
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				// 只保留极浅的行分隔线: 去掉外框与全部竖线, 分组之间靠 GROUP_GAP 留白
				g2.setColor(Theme.GRID_LINE);
				int[] rowTops = rowTops();
				for (int i = 0; i + 1 < rowTops.length; i++) {
					if (GROUP_STARTS.contains(i + 1)) {
						continue;
					}
					int bottom = rowBottom(rowTops[i]);
					if (bottom > rowTops[i]) {
						g2.drawLine(0, bottom - 1, getWidth(), bottom - 1);
					}
				}
			} finally {
				g2.dispose();
			}
		}

		/** 各行顶部 y 坐标(去重升序), 行号与 buildGrid 的 gridy 一致 */
		private int[] rowTops() {
			java.util.TreeSet<Integer> tops = new java.util.TreeSet<Integer>();
			for (Component c : getComponents()) {
				tops.add(c.getBounds().y);
			}
			int[] result = new int[tops.size()];
			int i = 0;
			for (Integer top : tops) {
				result[i++] = top;
			}
			return result;
		}

		/** 指定行内所有单元格的最大底边 */
		private int rowBottom(int top) {
			int bottom = top;
			for (Component c : getComponents()) {
				Rectangle r = c.getBounds();
				if (r.y == top) {
					bottom = Math.max(bottom, r.y + r.height);
				}
			}
			return bottom;
		}
	}

	/** 监控开关前的矢量小图标 */
	private static final class MonitorIcon implements javax.swing.Icon {
		@Override
		public void paintIcon(Component c, Graphics g, int x, int y) {
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(Theme.TEXT_MUTED);
				g2.setStroke(new BasicStroke(1.4f));
				g2.drawRoundRect(x + 1, y + 1, 12, 9, 2, 2);
				g2.drawLine(x + 5, y + 13, x + 9, y + 13);
				g2.drawLine(x + 7, y + 10, x + 7, y + 13);
			} finally {
				g2.dispose();
			}
		}

		@Override
		public int getIconWidth() {
			return 14;
		}

		@Override
		public int getIconHeight() {
			return 14;
		}
	}

	private static Color blend(Color base, Color other, float ratio) {
		float r = 1f - ratio;
		return new Color(
				Math.round(base.getRed() * r + other.getRed() * ratio),
				Math.round(base.getGreen() * r + other.getGreen() * ratio),
				Math.round(base.getBlue() * r + other.getBlue() * ratio));
	}
}
