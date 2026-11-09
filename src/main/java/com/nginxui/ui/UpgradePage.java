package com.nginxui.ui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.net.URI;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.border.TitledBorder;
import javax.swing.filechooser.FileNameExtensionFilter;

import com.nginxui.ext.NginxInstanceEntry;
import com.nginxui.ext.NginxUpgradeRecord;
import com.nginxui.utils.NginxPackageManager;
import com.nginxui.utils.NginxUpgradeManager;

import cn.hutool.core.util.StrUtil;

/**
 * 实例升级/回滚对话框.
 *
 * 界面划分为两个独立模块:
 *  - 在线升级: 连通性检测(超时 10 秒) → 选择版本 → 一键下载升级;
 *    安装包下载超过 180 秒判定失败, 并停用在线升级全部入口.
 *  - 离线升级: 前往官方渠道下载完整安装包 → 上传校验 → 本地升级;
 *    在线升级不可用时仍保持可用.
 * 升级阶段与下一步操作以文字呈现(不展示估算进度条), 避免进度跳变误导用户.
 */
public class UpgradePage extends JPanel {

	private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
	/** 官方下载页(离线升级的指定渠道) */
	private static final String OFFICIAL_PAGE = "https://nginx.org/en/download.html";

	private final MainFrame owner;
	private final NginxInstanceEntry entry;
	private final NginxUpgradeManager manager;

	private JLabel lblCurrent;

	// 在线升级模块
	private JPanel onlinePanel;
	private JLabel lblOnlineStatus;
	private JTextArea taOnlineDesc;
	private JComboBox<String> cbVersion;
	private JButton btnRetry, btnOnlineUpgrade;

	// 离线升级模块
	private JTextField tfZip;
	private JButton btnUpload, btnClearZip, btnOfficial, btnLocalUpgrade;
	private JLabel lblZipState;

	// 升级方式切换(在线/离线互斥) + 网络异常提示
	private ModeSwitch modeSwitch;
	private JPanel modeHost;
	private JPanel onlineModule;
	private JPanel offlineModule;
	private NetworkAlert networkAlert;
	/** 当前方式: 0=在线升级, 1=离线升级 */
	private int mode;
	/** 在线预检失败原因(null=正常或检测中), 用于顶部提示条 */
	private String onlineError;

	// 升级前置: 目标实例选择(明确本次升级作用对象)
	private JComboBox<String> cbInstance;
	private final List<NginxInstanceEntry> instanceChoices;
	/** 程序化设置下拉选中时不触发页面重建 */
	private boolean switchingInstance;

	// 状态 / 日志
	private StatusCard statusCard;
	private PillLabel lblStage;
	private PillLabel lblNext;
	private StageIcon stageIcon;
	private NextIcon nextIcon;
	private JTextArea taGuide, taLog;
	private JButton btnRollback, btnClose;

	private volatile boolean busy;
	private NginxUpgradeRecord rollbackRecord;

	/** 在线升级是否可用(连通性检测通过) */
	private boolean online;
	/** 连通性检测进行中 */
	private boolean checkingOnline;
	/** 安装包下载超时(>180 秒)后停用在线升级入口 */
	private boolean onlineTimeoutDisabled;
	/** 已上传安装包的校验版本; null 表示当前没有可用本地包 */
	private String zipVersion;
	/** 安装包校验进行中 */
	private boolean checkingZip;
	/** 安装包校验代次: 丢弃被清除/替换后的过期结果 */
	private int zipSeq;
	/** 全局操作锁变化时刷新按钮状态; 页面销毁时需移除, 避免实例切换后累积监听 */
	private final Runnable lockListener = this::updateControls;

	public UpgradePage(MainFrame owner, NginxInstanceEntry entry) {
		this.owner = owner;
		this.entry = entry;
		this.manager = new NginxUpgradeManager(new File(owner.appHome()), owner.registryEntries());
		this.instanceChoices = new ArrayList<NginxInstanceEntry>();
		boolean found = false;
		for (NginxInstanceEntry e : owner.upgradableEntries()) {
			instanceChoices.add(e);
			if (e.getId() != null && e.getId().equals(entry.getId())) {
				found = true;
			}
		}
		// 当前实例可能刚被忽略/不在可升级列表: 仍保证下拉里能显示它
		if (!found) {
			instanceChoices.add(0, entry);
		}
		setOpaque(true);
		setBackground(Theme.BG);
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(14, Theme.GAP_L, Theme.GAP_M, Theme.GAP_L));

		buildUi();

		appendLog("实例: " + MainFrame.InstanceRow.displayName(entry));
		appendLog("程序: " + StrUtil.nullToEmpty(entry.getNginxExe()));
		OperationLock.addListener(lockListener);
		loadCurrentState();
		checkUpgradeSource();
	}

	/** 实例切换/页面销毁时解除全局锁监听 */
	public void dispose() {
		OperationLock.removeListener(lockListener);
	}

	// ==================== 界面 ====================

	private void buildUi() {
		JPanel body = new JPanel(new BorderLayout());
		body.setOpaque(false);

		modeSwitch = new ModeSwitch(new String[] { "在线升级", "离线升级" }, this::onModeChanged);
		networkAlert = new NetworkAlert("切换到离线升级", () -> modeSwitch.setSelected(1));

		JPanel top = new JPanel(new GridBagLayout());
		top.setBorder(BorderFactory.createEmptyBorder(8, 12, 4, 12));
		GridBagConstraints gc = new GridBagConstraints();
		gc.gridx = 0;
		gc.weightx = 1;
		gc.fill = GridBagConstraints.HORIZONTAL;
		gc.insets = new Insets(0, 0, 8, 0);
		gc.gridy = 0;
		top.add(networkAlert, gc);
		gc.gridy = 1;
		top.add(buildTargetRow(), gc);
		gc.gridy = 2;
		top.add(buildModeRow(), gc);
		gc.gridy = 3;
		gc.insets = new Insets(0, 0, 0, 0);
		top.add(buildModeHost(), gc);
		JPanel content = new JPanel(new BorderLayout());
		content.setOpaque(false);
		content.add(top, BorderLayout.NORTH);

		taLog = new JTextArea();
		taLog.setEditable(false);
		taLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		taLog.setBackground(new java.awt.Color(252, 252, 252));
		JScrollPane scroll = new JScrollPane(taLog);
		scroll.setBorder(BorderFactory.createTitledBorder(
				BorderFactory.createLineBorder(Theme.BORDER), "升级日志"));
		JPanel center = new JPanel(new BorderLayout());
		center.setOpaque(false);
		center.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
		center.add(scroll, BorderLayout.CENTER);
		center.setPreferredSize(new Dimension(10, 180));
		content.add(center, BorderLayout.CENTER);
		body.add(PageHeader.scroll(content), BorderLayout.CENTER);

		stageIcon = new StageIcon(Theme.SUCCESS);
		lblStage = new PillLabel("");
		lblStage.setFont(Theme.BODY_BOLD);
		lblStage.setIcon(stageIcon);
		lblStage.setIconTextGap(6);
		lblStage.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 12));

		nextIcon = new NextIcon(Theme.PRIMARY);
		lblNext = new PillLabel("下一步");
		lblNext.setFont(Theme.SMALL.deriveFont(Font.BOLD));
		lblNext.setIcon(nextIcon);
		lblNext.setIconTextGap(5);
		lblNext.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 10));
		lblNext.setForeground(Theme.PRIMARY);
		lblNext.setColors(Theme.INFO_BG, Theme.BORDER);

		taGuide = hintArea(2, 30);
		taGuide.setForeground(Theme.TEXT);
		taGuide.setFont(Theme.BODY);

		// “下一步”徽标 + 说明文字: 文字列自适应宽度并换行, 窄窗口不截断
		JPanel stageRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		stageRow.setOpaque(false);
		stageRow.add(lblStage);
		// “下一步”徽标与首行文字顶部对齐(文字区固定预留两行, 不能让徽标按两行居中)
		JPanel guideRow = new JPanel(new GridBagLayout());
		guideRow.setOpaque(false);
		GridBagConstraints nrc = new GridBagConstraints();
		nrc.gridx = 0;
		nrc.gridy = 0;
		nrc.anchor = GridBagConstraints.NORTHWEST;
		nrc.insets = new Insets(0, 0, 0, 8);
		guideRow.add(lblNext, nrc);
		nrc.gridx = 1;
		nrc.weightx = 1;
		nrc.fill = GridBagConstraints.HORIZONTAL;
		nrc.insets = new Insets(0, 0, 0, 0);
		guideRow.add(taGuide, nrc);

		JPanel statusText = new JPanel(new BorderLayout(0, 5));
		statusText.setOpaque(false);
		statusText.add(stageRow, BorderLayout.NORTH);
		statusText.add(guideRow, BorderLayout.CENTER);

		// 白底状态卡 + 左侧状态色竖条: 正常绿 / 异常红
		statusCard = new StatusCard();
		statusCard.setBorder(BorderFactory.createEmptyBorder(9, 12, 9, 12));
		statusCard.add(statusText, BorderLayout.CENTER);
		setStage("准备中");

		btnRollback = Theme.toolButton("回滚");
		Theme.setEnabled(btnRollback, false, "没有可回滚的升级记录");
		btnRollback.addActionListener(e -> startRollback());
		btnClose = Theme.toolButton("返回实例总览");
		btnClose.addActionListener(e -> {
			if (!busy) {
				owner.returnToInstances();
			}
		});

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		buttons.add(btnRollback);
		buttons.add(btnClose);

		JPanel bottom = new JPanel(new BorderLayout(0, 8));
		bottom.setBorder(BorderFactory.createEmptyBorder(6, 12, 10, 12));
		bottom.add(statusCard, BorderLayout.NORTH);
		bottom.add(buttons, BorderLayout.SOUTH);
		body.add(bottom, BorderLayout.SOUTH);

		add(buildHeader(), BorderLayout.NORTH);
		add(body, BorderLayout.CENTER);
	}

	/** 页面标题行: 与实例总览页一致(标题 + 目标实例说明) */
	private JPanel buildHeader() {
		PageHeader header = new PageHeader("版本升级",
				"目标实例：" + MainFrame.InstanceRow.displayName(entry)
						+ "（升级前自动 nginx -t 预检，失败自动回滚）");
		header.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));
		return header;
	}

	/** 升级前置区域: 目标实例下拉, 明确本次升级的作用对象 */
	private JPanel buildTargetRow() {
		JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
		row.setOpaque(false);
		row.add(Theme.label("目标实例", Theme.BODY_BOLD, Theme.TEXT));

		DefaultComboBoxModel<String> model = new DefaultComboBoxModel<String>();
		int selectedIndex = 0;
		for (int i = 0; i < instanceChoices.size(); i++) {
			NginxInstanceEntry e = instanceChoices.get(i);
			model.addElement(MainFrame.InstanceRow.displayName(e));
			if (e.getId() != null && e.getId().equals(entry.getId())) {
				selectedIndex = i;
			}
		}
		cbInstance = new JComboBox<String>(model);
		cbInstance.setFont(Theme.BODY);
		cbInstance.setPreferredSize(new Dimension(260, Theme.BUTTON_HEIGHT));
		switchingInstance = true;
		cbInstance.setSelectedIndex(selectedIndex);
		switchingInstance = false;
		cbInstance.addActionListener(e -> onInstanceChanged());
		row.add(cbInstance);
		row.add(Theme.label("升级只作用于该实例，配置与站点保持不变", Theme.SMALL, Theme.TEXT_MUTED));
		return row;
	}

	/** 升级方式切换(在线/离线): 位于内容区左侧, 与其他控件左对齐 */
	private JPanel buildModeRow() {
		JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		row.setOpaque(false);
		row.add(Theme.label("升级方式", Theme.BODY_BOLD, Theme.TEXT));
		row.add(javax.swing.Box.createHorizontalStrut(8));
		row.add(modeSwitch);
		return row;
	}

	/** 切换目标实例: 按所选实例重建升级页面(版本列表、预检与日志全部随实例刷新) */
	private void onInstanceChanged() {
		if (switchingInstance || cbInstance == null) {
			return;
		}
		if (busy) {
			// 升级进行中不允许更换目标实例, 恢复为当前实例
			switchingInstance = true;
			cbInstance.setSelectedIndex(currentInstanceIndex());
			switchingInstance = false;
			return;
		}
		int idx = cbInstance.getSelectedIndex();
		if (idx < 0 || idx >= instanceChoices.size()) {
			return;
		}
		owner.openUpgradePageFor(instanceChoices.get(idx));
	}

	/** 当前目标实例在下拉中的下标 */
	private int currentInstanceIndex() {
		for (int i = 0; i < instanceChoices.size(); i++) {
			NginxInstanceEntry e = instanceChoices.get(i);
			if (e.getId() != null && e.getId().equals(entry.getId())) {
				return i;
			}
		}
		return 0;
	}

	/** 在线/离线互斥内容区: 同一位置只放当前方式的模块, 切换时整体替换 */
	private JPanel buildModeHost() {
		onlineModule = buildOnlineModule();
		offlineModule = buildOfflineModule();
		modeHost = new JPanel(new BorderLayout());
		modeHost.setOpaque(false);
		modeHost.add(onlineModule, BorderLayout.CENTER);
		return modeHost;
	}

	/** 切换升级方式: 仅展示对应内容, 不触发网络请求(切换即时响应) */
	private void onModeChanged(int index) {
		mode = index;
		if (modeHost != null) {
			modeHost.removeAll();
			modeHost.add(index == 0 ? onlineModule : offlineModule, BorderLayout.CENTER);
			modeHost.revalidate();
			modeHost.repaint();
		}
		refreshNetworkAlert();
	}

	/** 在线预检失败且处于在线方式时, 顶部展示网络异常与切换建议 */
	private void refreshNetworkAlert() {
		if (networkAlert == null) {
			return;
		}
		if (mode == 0 && !checkingOnline && onlineError != null) {
			networkAlert.showAlert("在线升级当前不可用",
					onlineError + "。请切换到「离线升级」，上传本地安装包完成升级。");
		} else {
			networkAlert.hideAlert();
		}
	}

	/** 在线升级模块: 连通性状态 + 目标版本 + 一键在线升级 */
	private JPanel buildOnlineModule() {
		onlinePanel = new JPanel(new BorderLayout(0, 4));

		lblOnlineStatus = Theme.label("正在检测 nginx.org 连通性…", Theme.BODY_BOLD, Theme.TEXT_MUTED);
		taOnlineDesc = hintArea(4, 30);
		JPanel textBox = new JPanel(new BorderLayout(0, 2));
		textBox.setOpaque(false);
		textBox.add(lblOnlineStatus, BorderLayout.NORTH);
		textBox.add(taOnlineDesc, BorderLayout.CENTER);

		cbVersion = new JComboBox<String>();
		cbVersion.setPreferredSize(new Dimension(160, 26));
		cbVersion.addActionListener(e -> {
			if (busy) {
				return;
			}
			updateControls();
			refreshGuide();
		});
		lblCurrent = new JLabel("读取中…");
		lblCurrent.setFont(Theme.BODY_BOLD);
		lblCurrent.setToolTipText("升级前会用新版本执行 nginx -t 预检，通过后才停止实例并替换程序文件。");
		btnRetry = Theme.toolButton("重新检测");
		btnRetry.setToolTipText("重新检测 nginx.org 连通性并刷新稳定版列表");
		btnRetry.addActionListener(e -> checkUpgradeSource());
		btnOnlineUpgrade = Theme.primaryButton("一键在线升级");
		btnOnlineUpgrade.setToolTipText("下载官方安装包并升级当前实例");
		Theme.setEnabled(btnOnlineUpgrade, false, "先在版本列表中选择一个稳定版");
		btnOnlineUpgrade.addActionListener(e -> startOnlineUpgrade());

		JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		left.setOpaque(false);
		left.add(new JLabel("当前版本:"));
		left.add(lblCurrent);
		left.add(new JLabel("目标版本:"));
		left.add(cbVersion);
		left.add(btnRetry);
		JPanel nav = new JPanel(new BorderLayout(6, 0));
		nav.setOpaque(false);
		nav.add(left, BorderLayout.WEST);
		nav.add(btnOnlineUpgrade, BorderLayout.EAST);

		onlinePanel.add(textBox, BorderLayout.CENTER);
		onlinePanel.add(nav, BorderLayout.SOUTH);
		applyOnlineState("checking", "正在检测升级地址连通性…（超时上限 10 秒）",
				"目标地址：" + NginxPackageManager.DOWNLOAD_BASE + "\n"
						+ "长时间无响应即判定为不可访问，请改用下方「离线升级」。");
		return onlinePanel;
	}

	/** 离线升级模块: 官方渠道下载 + 上传校验 + 本地升级, 与在线模块完全独立 */
	private JPanel buildOfflineModule() {
		JPanel panel = new JPanel(new BorderLayout(0, 4));
		panel.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(Theme.BORDER),
				BorderFactory.createEmptyBorder(2, 6, 6, 6)));

		JTextArea desc = hintArea(2, 30);
		desc.setText("在线升级不可用时，请前往官方指定渠道 " + OFFICIAL_PAGE
				+ " 下载对应版本的完整安装包（nginx-x.y.z.zip），上传并校验后执行离线升级。");

		tfZip = new JTextField();
		tfZip.setEditable(false);
		tfZip.setPreferredSize(new Dimension(220, 26));
		tfZip.setMinimumSize(new Dimension(120, 26));
		btnUpload = Theme.toolButton("上传安装包…");
		btnUpload.setToolTipText("选择从 " + OFFICIAL_PAGE + " 下载的完整 nginx-x.y.z.zip 安装包");
		btnUpload.addActionListener(e -> chooseZip());
		btnClearZip = Theme.toolButton("清除");
		Theme.setEnabled(btnClearZip, false, "尚未选择安装包");
		btnClearZip.addActionListener(e -> clearZip("已清除本地安装包"));
		btnOfficial = Theme.toolButton("打开官方下载页");
		btnOfficial.addActionListener(e -> openOfficialPage());

		// GridBag: 安装包路径吸收剩余宽度, 窄窗口下按钮不换行/不截断
		JPanel row = new JPanel(new GridBagLayout());
		row.setOpaque(false);
		GridBagConstraints rc = new GridBagConstraints();
		rc.gridy = 0;
		rc.anchor = GridBagConstraints.WEST;
		rc.insets = new Insets(0, 0, 0, 6);
		rc.gridx = 0;
		row.add(new JLabel("安装包:"), rc);
		rc.gridx = 1;
		rc.weightx = 1;
		rc.fill = GridBagConstraints.HORIZONTAL;
		row.add(tfZip, rc);
		rc.weightx = 0;
		rc.fill = GridBagConstraints.NONE;
		rc.gridx = 2;
		row.add(btnUpload, rc);
		rc.gridx = 3;
		row.add(btnClearZip, rc);
		rc.gridx = 4;
		rc.insets = new Insets(0, 0, 0, 0);
		row.add(btnOfficial, rc);

		lblZipState = Theme.label("未选择安装包", Theme.SMALL, Theme.TEXT_MUTED);
		btnLocalUpgrade = Theme.primaryButton("使用本地安装包升级");
		btnLocalUpgrade.setToolTipText("使用已上传并校验通过的本地安装包升级当前实例");
		Theme.setEnabled(btnLocalUpgrade, false, "尚未选择安装包：先上传并校验本地安装包");
		btnLocalUpgrade.addActionListener(e -> startLocalUpgrade());
		JPanel stateRow = new JPanel(new BorderLayout(6, 0));
		stateRow.setOpaque(false);
		stateRow.add(lblZipState, BorderLayout.CENTER);
		stateRow.add(btnLocalUpgrade, BorderLayout.EAST);

		panel.add(desc, BorderLayout.NORTH);
		panel.add(row, BorderLayout.CENTER);
		panel.add(stateRow, BorderLayout.SOUTH);
		return panel;
	}

	private JTextArea hintArea(int rows, int cols) {
		JTextArea area = new JTextArea(rows, cols);
		area.setEditable(false);
		area.setFocusable(false);
		area.setOpaque(false);
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setFont(Theme.SMALL);
		area.setForeground(Theme.TEXT_MUTED);
		area.setBorder(null);
		return area;
	}

	private void applyOnlineState(String style, String title, String desc) {
		Color bg;
		Color border;
		Color titleColor;
		if ("online".equals(style)) {
			bg = Theme.SUCCESS_BG;
			border = Theme.SUCCESS_BORDER;
			titleColor = Theme.SUCCESS;
		} else if ("checking".equals(style)) {
			bg = Theme.INFO_BG;
			border = Theme.BORDER;
			titleColor = Theme.TEXT_MUTED;
		} else {
			bg = Theme.WARNING_BG;
			border = Theme.WARNING_BORDER;
			titleColor = Theme.WARNING;
		}
		onlinePanel.setOpaque(true);
		onlinePanel.setBackground(bg);
		onlinePanel.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(border),
				BorderFactory.createEmptyBorder(2, 6, 6, 6)));
		lblOnlineStatus.setText(title);
		lblOnlineStatus.setForeground(titleColor);
		taOnlineDesc.setText(desc);
		taOnlineDesc.setForeground(Theme.TEXT_MUTED);
		onlinePanel.revalidate();
		onlinePanel.repaint();
	}

	// ==================== 数据加载 ====================

	private void loadCurrentState() {
		new SwingWorker<String, Void>() {
			@Override
			protected String doInBackground() {
				return manager.currentVersion(entry);
			}

			@Override
			protected void done() {
				String ver;
				try {
					ver = get();
				} catch (Exception e) {
					ver = "";
				}
				lblCurrent.setText(StrUtil.emptyToDefault(ver, "未知(程序不可执行)"));
				rollbackRecord = manager.findRollbackRecord(entry);
				btnRollback.setText(rollbackRecord != null
						? "回滚到 " + StrUtil.emptyToDefault(rollbackRecord.getFromVersion(), "旧版")
						: "回滚");
				if (rollbackRecord != null) {
					appendLog("检测到可回滚备份: " + rollbackRecord.getBackupExe());
				}
				updateControls();
			}
		}.execute();
	}

	/** 连通性检测: 服务器地址不可访问时, 超时为 10 秒 */
	private void checkUpgradeSource() {
		if (busy || checkingOnline || onlineTimeoutDisabled) {
			return;
		}
		checkingOnline = true;
		online = false;
		onlineError = null;
		refreshNetworkAlert();
		cbVersion.setModel(new DefaultComboBoxModel<String>());
		appendLog("正在检测 nginx.org 连通性: " + NginxPackageManager.DOWNLOAD_BASE);
		applyOnlineState("checking", "正在检测 nginx.org 连通性…（超时上限 10 秒）",
				"目标地址：" + NginxPackageManager.DOWNLOAD_BASE + "\n"
						+ "长时间无响应即判定为不可访问，请改用下方「离线升级」。");
		setStage("检测网络连通性");
		setGuide("正在检测网络连通性，请稍候…");
		updateControls();

		new SwingWorker<NginxPackageManager.Reachability, Void>() {
			@Override
			protected NginxPackageManager.Reachability doInBackground() {
				return NginxUpgradeManager.checkUpgradeSource();
			}

			@Override
			protected void done() {
				checkingOnline = false;
				NginxPackageManager.Reachability r;
				try {
					r = get();
				} catch (Exception e) {
					r = null;
				}
				if (r != null && r.online) {
					online = true;
					onlineError = null;
					refreshNetworkAlert();
					List<String> versions = r.versions;
					DefaultComboBoxModel<String> model = new DefaultComboBoxModel<String>();
					for (String v : versions) {
						model.addElement(v);
					}
					cbVersion.setModel(model);
					if (!versions.isEmpty()) {
						// 默认选中最新稳定版: 一键在线升级可直接开始
						cbVersion.setSelectedIndex(0);
					}
					String latest = versions.isEmpty() ? "—" : versions.get(0);
					applyOnlineState("online", "网络正常，支持在线升级",
							"连接正常（" + r.elapsedMs + " ms），已获取 "
									+ versions.size() + " 个稳定版，最新 " + latest + "。\n"
									+ "点击「一键在线升级」自动下载官方安装包；下载超时上限 180 秒，"
									+ "超时将判定失败并停用在线升级。");
					appendLog("nginx.org 连通正常（" + r.elapsedMs + " ms），可用稳定版 "
							+ versions.size() + " 个（最新 " + latest + "）");
				} else {
					online = false;
					String reason = r == null ? "检测异常中止"
							: StrUtil.removeSuffix(StrUtil.nullToEmpty(r.detail),
									NginxPackageManager.NET_FAIL_TIP).trim();
					if (StrUtil.isEmpty(reason)) {
						reason = "连接超时";
					}
					applyOnlineState("offline", "无法连接 nginx.org，在线升级不可用",
							"连通性检测失败：" + reason + "\n"
									+ "请使用下方「离线升级」：前往 " + OFFICIAL_PAGE
									+ " 下载完整安装包后上传。");
					appendLog("无法连接 nginx.org: " + reason);
					appendLog("在线升级不可用；请改用「离线升级」上传安装包");
					onlineError = "连通性检测失败：" + reason;
					refreshNetworkAlert();
				}
				if (online) {
					setStage("就绪");
				} else {
					setStage("在线升级不可用，请使用离线升级", true);
				}
				updateControls();
				refreshGuide();
			}
		}.execute();
	}

	// ==================== 本地安装包 ====================

	private void chooseZip() {
		if (busy) {
			return;
		}
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("上传 nginx Windows 完整安装包 (nginx-x.y.z.zip)");
		chooser.setFileFilter(new FileNameExtensionFilter("nginx 安装包 (*.zip)", "zip"));
		String current = StrUtil.nullToEmpty(tfZip.getText());
		if (StrUtil.isNotEmpty(current)) {
			chooser.setSelectedFile(new File(current));
		}
		if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
			return;
		}
		File zip = chooser.getSelectedFile();
		tfZip.setText(zip.getAbsolutePath());
		tfZip.setToolTipText(zip.getAbsolutePath());
		validateZipAsync(zip);
	}

	/** 选中包立即校验结构与版本, 避免升级中途才发现包不可用 */
	private void validateZipAsync(File zip) {
		final int seq = ++zipSeq;
		zipVersion = null;
		checkingZip = true;
		lblZipState.setText("正在校验安装包…");
		lblZipState.setForeground(Theme.TEXT_MUTED);
		appendLog("正在校验安装包: " + zip.getName());
		setStage("校验本地安装包");
		setGuide("正在校验安装包结构与 nginx 版本，请稍候…");
		updateControls();

		new SwingWorker<String, Void>() {
			@Override
			protected String doInBackground() {
				return NginxPackageManager.inspectZip(zip);
			}

			@Override
			protected void done() {
				if (seq != zipSeq) {
					return;
				}
				checkingZip = false;
				try {
					String ver = get();
					zipVersion = ver;
					long kb = Math.max(1, zip.length() / 1024);
					lblZipState.setText("已校验：nginx-" + ver + ".zip（" + kb + " KB）");
					lblZipState.setForeground(Theme.SUCCESS);
					appendLog("本地安装包校验通过: nginx-" + ver + ".zip（" + kb + " KB）");
					setStage("就绪");
					setGuide("下一步：点击「使用本地安装包升级」，升级到 " + ver + "。");
				} catch (Exception e) {
					Throwable cause = e.getCause() != null ? e.getCause() : e;
					String msg = StrUtil.emptyToDefault(com.nginxui.utils.ErrorReporter.userMessage(cause),
							"无法解析该安装包");
					tfZip.setText("");
					tfZip.setToolTipText(null);
					zipVersion = null;
					lblZipState.setText("校验失败：" + msg);
					lblZipState.setForeground(Theme.DANGER);
					appendLog("安装包校验失败: " + msg);
					setStage("安装包校验失败", true);
					setGuide("安装包校验失败，请重新「上传安装包…」：" + msg);
				}
				updateControls();
			}
		}.execute();
	}

	private void clearZip(String reason) {
		zipSeq++;
		checkingZip = false;
		zipVersion = null;
		tfZip.setText("");
		tfZip.setToolTipText(null);
		lblZipState.setText("未选择安装包");
		lblZipState.setForeground(Theme.TEXT_MUTED);
		appendLog(reason);
		setStage("就绪");
		updateControls();
		refreshGuide();
	}

	private void openOfficialPage() {
		try {
			if (!Desktop.isDesktopSupported()
					|| !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
				throw new IllegalStateException("当前系统不支持自动打开浏览器");
			}
			Desktop.getDesktop().browse(new URI(OFFICIAL_PAGE));
			appendLog("已打开官方下载页: " + OFFICIAL_PAGE);
			setGuide("请下载 nginx-<目标版本>.zip，下载完成后点击「上传安装包…」继续。");
		} catch (Exception e) {
			Toolkit.getDefaultToolkit().getSystemClipboard()
					.setContents(new StringSelection(OFFICIAL_PAGE), null);
			appendLog("无法自动打开浏览器，下载页地址已复制到剪贴板: " + OFFICIAL_PAGE);
			JOptionPane.showMessageDialog(this,
					"无法自动打开浏览器。\n下载页地址已复制到剪贴板：\n" + OFFICIAL_PAGE,
					"nginxUI - 官方下载页", JOptionPane.INFORMATION_MESSAGE);
		}
	}

	// ==================== 升级/回滚 ====================

	private void startOnlineUpgrade() {
		if (busy || checkingOnline || checkingZip) {
			return;
		}
		if (!online || onlineTimeoutDisabled) {
			JOptionPane.showMessageDialog(this,
					"在线升级当前不可用。\n请使用下方「离线升级」：上传从官方渠道下载的完整安装包。",
					"nginxUI - 在线升级", JOptionPane.WARNING_MESSAGE);
			return;
		}
		String version = cbVersion.getSelectedItem() == null ? null
				: String.valueOf(cbVersion.getSelectedItem());
		if (StrUtil.isEmpty(version)) {
			JOptionPane.showMessageDialog(this, "请先选择目标版本。",
					"nginxUI - 在线升级", JOptionPane.WARNING_MESSAGE);
			return;
		}
		String current = StrUtil.trim(lblCurrent.getText());
		if (version.equals(current)) {
			JOptionPane.showMessageDialog(this,
					"当前已是 " + version + "，无需升级。\n如需降级，请选择更低的版本。",
					"nginxUI - 在线升级", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		runUpgrade(version, null, "在线下载 nginx-" + version + ".zip");
	}

	private void startLocalUpgrade() {
		if (busy || checkingZip) {
			return;
		}
		if (zipVersion == null) {
			JOptionPane.showMessageDialog(this,
					"请先点击「上传安装包…」并等待校验通过。",
					"nginxUI - 离线升级", JOptionPane.WARNING_MESSAGE);
			return;
		}
		File zip = new File(StrUtil.trim(tfZip.getText()));
		if (!zip.isFile()) {
			clearZip("本地安装包已被移动或删除，请重新上传");
			JOptionPane.showMessageDialog(this, "安装包不存在: " + zip.getAbsolutePath(),
					"nginxUI - 离线升级", JOptionPane.WARNING_MESSAGE);
			return;
		}
		runUpgrade(null, zip, "本地安装包 " + zip.getName() + "（版本 " + zipVersion + "）");
	}

	private void runUpgrade(String targetVersion, File localZip, String modeText) {
		String object = "实例「" + MainFrame.InstanceRow.displayName(entry) + "」\n升级方式：" + modeText;
		String consequence = "将用新版本执行 nginx -t 预检，随后停止该实例（以及与它共用同一 nginx.exe 的其它实例），"
				+ "备份并替换 nginx.exe 后重新启动；升级期间站点不可访问。";
		String reversibility = "可撤销：升级成功后可通过「回滚」恢复上一版本；失败会自动还原备份，"
				+ "配置、站点与日志文件均不受影响。";
		if (!ConfirmDialog.confirm(this, "确认升级", object, consequence, reversibility, "升级")) {
			return;
		}

		if (!OperationLock.tryAcquire("升级实例")) {
			setGuide(OperationLock.reason());
			return;
		}

		final File zip = localZip;
		final String version = targetVersion;
		setBusy(true);
		setStage("准备升级");
		setGuide("正在准备升级，请勿离开本页…");
		appendLog("========== 开始升级（" + modeText + "） ==========");
		new SwingWorker<NginxUpgradeManager.UpgradeResult, Void>() {
			@Override
			protected NginxUpgradeManager.UpgradeResult doInBackground() {
				return manager.upgrade(entry, version, zip, progressSink());
			}

			@Override
			protected void done() {
				OperationLock.release();
				NginxUpgradeManager.UpgradeResult r = collect(this);
				setBusy(false);
				lblCurrent.setText(StrUtil.emptyToDefault(manager.currentVersion(entry), "未知"));
				owner.onUpgradeFinished();
				if (r != null && r.success) {
					setStage("升级完成");
					appendLog("========== 升级成功 ==========");
					setGuide("升级完成：当前版本 " + StrUtil.emptyToDefault(r.toVersion, "未知")
							+ "。如需撤销，可点击「回滚」。");
					JOptionPane.showMessageDialog(UpgradePage.this, r.message,
							"nginxUI - 升级成功", JOptionPane.INFORMATION_MESSAGE);
				} else if (r != null && r.downloadTimeout) {
					// 下载超过 180 秒: 判定失败, 停用在线升级, 仅保留离线入口
					markOnlineTimeout();
					appendLog("========== 升级失败: 安装包下载超时 ==========");
					JOptionPane.showMessageDialog(UpgradePage.this,
							r.message + "\n\n在线升级已停用，请使用「离线升级」上传安装包后重试。",
							"nginxUI - 在线升级失败", JOptionPane.ERROR_MESSAGE);
				} else {
					setStage("升级失败", true);
					appendLog("========== 升级失败 ==========");
					setGuide("升级失败，请查看上方日志排查原因后重试。");
					JOptionPane.showMessageDialog(UpgradePage.this,
							r == null ? "升级异常中止" : r.message,
							"nginxUI - 升级失败", JOptionPane.ERROR_MESSAGE);
				}
				updateControls();
				loadCurrentState();
			}
		}.execute();
	}

	/** 安装包下载超时: 停用在线升级全部入口, 仅保留离线升级 */
	private void markOnlineTimeout() {
		onlineTimeoutDisabled = true;
		online = false;
		cbVersion.setModel(new DefaultComboBoxModel<String>());
		applyOnlineState("offline", "在线升级已停用（下载超时）",
				"安装包下载超过 180 秒，已判定为下载失败。\n"
						+ "在线升级相关操作已全部禁用；请使用下方「离线升级」，"
						+ "上传从官方渠道下载的完整安装包后继续。");
		setStage("在线升级已停用（下载超时）", true);
		setGuide("在线升级已停用：下载超过 180 秒。请点击「上传安装包…」使用离线升级。");
		onlineError = "安装包下载超时（超过 180 秒），在线升级已停用";
		refreshNetworkAlert();
		updateControls();
	}

	private void startRollback() {
		if (busy || rollbackRecord == null) {
			return;
		}
		String object = "实例「" + MainFrame.InstanceRow.displayName(entry) + "」，回滚到 "
				+ rollbackRecord.getFromVersion();
		String consequence = "将停止实例、用备份程序替换当前 nginx.exe 并重新启动；回滚期间站点不可访问。";
		String reversibility = "可撤销：回滚失败会自动还原当前程序，配置、站点与日志文件均不受影响。";
		if (!ConfirmDialog.confirm(this, "确认回滚", object, consequence, reversibility, "回滚")) {
			return;
		}
		if (!OperationLock.tryAcquire("回滚实例")) {
			setGuide(OperationLock.reason());
			return;
		}
		setBusy(true);
		setStage("准备回滚");
		setGuide("正在回滚，请勿离开本页…");
		appendLog("========== 开始回滚 ==========");
		new SwingWorker<NginxUpgradeManager.UpgradeResult, Void>() {
			@Override
			protected NginxUpgradeManager.UpgradeResult doInBackground() {
				return manager.rollback(entry, progressSink());
			}

			@Override
			protected void done() {
				OperationLock.release();
				NginxUpgradeManager.UpgradeResult r = collect(this);
				setBusy(false);
				lblCurrent.setText(StrUtil.emptyToDefault(manager.currentVersion(entry), "未知"));
				owner.onUpgradeFinished();
				if (r != null && r.success) {
					setStage("回滚完成");
					appendLog("========== 回滚成功 ==========");
					setGuide("回滚完成：当前版本 " + StrUtil.emptyToDefault(r.toVersion, "未知") + "。");
					JOptionPane.showMessageDialog(UpgradePage.this, r.message,
							"nginxUI - 回滚成功", JOptionPane.INFORMATION_MESSAGE);
				} else {
					setStage("回滚失败", true);
					appendLog("========== 回滚失败 ==========");
					setGuide("回滚失败，请查看上方日志排查原因。");
					JOptionPane.showMessageDialog(UpgradePage.this,
							r == null ? "回滚异常中止" : r.message,
							"nginxUI - 回滚失败", JOptionPane.ERROR_MESSAGE);
				}
				updateControls();
				loadCurrentState();
			}
		}.execute();
	}

	private NginxUpgradeManager.UpgradeResult collect(
			SwingWorker<NginxUpgradeManager.UpgradeResult, Void> worker) {
		try {
			return worker.get();
		} catch (Exception e) {
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			appendLog("异常: " + com.nginxui.utils.ErrorReporter.userMessage(cause));
			return null;
		}
	}

	/** 后台线程 → EDT 的阶段/日志回调(不展示百分比进度条) */
	private NginxUpgradeManager.Progress progressSink() {
		return new NginxUpgradeManager.Progress() {
			@Override
			public void log(String message) {
				SwingUtilities.invokeLater(() -> appendLog(message));
			}

			@Override
			public void stage(int percent, String label) {
				SwingUtilities.invokeLater(() -> {
					setStage(label);
					if (busy) {
						setGuide("正在「" + label + "」，请勿离开本页…");
					}
				});
			}
		};
	}

	// ==================== 状态/控件 ====================

	private void setBusy(boolean value) {
		busy = value;
		updateControls();
	}

	/** 在线/离线两个模块各自独立: 任一模式不可用时, 另一模式保持可用 */
	private void updateControls() {
		boolean idle = !busy;
		boolean hasZip = StrUtil.isNotEmpty(tfZip.getText());
		boolean onlineEnabled = online && !onlineTimeoutDisabled;
		boolean canOnline = onlineEnabled && cbVersion.getSelectedItem() != null;
		String busyReason = busy ? "正在执行，请稍候" : OperationLock.reason();
		String timeoutReason = onlineTimeoutDisabled ? "在线检测已连续超时：切换本地安装包或稍后重试" : null;

		Theme.setEnabled(cbVersion, idle && onlineEnabled && !checkingOnline && !checkingZip,
				busyReason != null ? busyReason
						: (timeoutReason != null ? timeoutReason
								: (!online ? "当前为离线模式：切换到在线模式后才可选择版本"
										: (checkingOnline || checkingZip ? "正在获取版本列表，请稍候" : null))));
		Theme.setEnabled(btnRetry, idle && !checkingOnline && !onlineTimeoutDisabled,
				busyReason != null ? busyReason
						: (checkingOnline ? "正在检测在线版本，请稍候"
								: (timeoutReason != null ? "在线检测已连续超时：当前没有可重试的请求" : null)));
		Theme.setEnabled(btnOnlineUpgrade, idle && !OperationLock.isBusy() && canOnline && !checkingOnline && !checkingZip,
				busyReason != null ? busyReason
						: (timeoutReason != null ? timeoutReason
								: (!online ? "当前为离线模式：请使用本地安装包升级"
										: (checkingOnline || checkingZip ? "正在获取版本列表，请稍候"
												: (canOnline ? null : "先在版本列表中选择一个稳定版")))));

		Theme.setEnabled(btnUpload, idle && !checkingZip,
				busyReason != null ? busyReason : (checkingZip ? "正在解压安装包，请稍候" : null));
		Theme.setEnabled(btnClearZip, idle && hasZip && !checkingZip,
				busyReason != null ? busyReason : (checkingZip ? "正在解压安装包，请稍候" : "尚未选择安装包"));
		Theme.setEnabled(btnOfficial, idle, busyReason);
		Theme.setEnabled(btnLocalUpgrade, idle && !OperationLock.isBusy() && zipVersion != null && !checkingZip,
				busyReason != null ? busyReason
						: (checkingZip ? "正在解压安装包，请稍候"
								: (zipVersion != null ? null : "尚未选择安装包：先上传并校验本地安装包")));

		Theme.setEnabled(btnRollback, idle && !OperationLock.isBusy() && rollbackRecord != null,
				busyReason != null ? busyReason
						: (rollbackRecord != null ? null : "没有可回滚的升级记录"));
		Theme.setEnabled(btnClose, idle, busyReason);
	}

	/** 按当前网络/安装包状态给出明确的“下一步”引导 */
	private void refreshGuide() {
		if (busy) {
			return;
		}
		boolean hasZip = StrUtil.isNotEmpty(tfZip.getText());
		if (checkingOnline) {
			setGuide("正在检测网络连通性，请稍候…");
			return;
		}
		if (checkingZip) {
			setGuide("正在校验安装包结构与 nginx 版本，请稍候…");
			return;
		}
		if (onlineTimeoutDisabled) {
			setGuide("在线升级已停用（下载超过 180 秒）。下一步：点击「上传安装包…」使用离线升级。");
			return;
		}
		String v = cbVersion.getSelectedItem() == null ? null
				: String.valueOf(cbVersion.getSelectedItem());
		boolean zipReady = hasZip && zipVersion != null;
		if (online && v != null) {
			setGuide(zipReady
					? "两种方式均可用：在线升级到 " + v + "，或使用本地安装包升级到 " + zipVersion + "。"
					: "下一步：点击「一键在线升级」，自动下载 nginx-" + v + ".zip 并升级。");
			return;
		}
		if (zipReady) {
			setGuide("下一步：点击「使用本地安装包升级」，升级到 " + zipVersion + "。");
			return;
		}
		if (online) {
			setGuide("下一步：在「目标版本」选择版本，然后点击「一键在线升级」。");
			return;
		}
		setGuide("下一步：前往 " + OFFICIAL_PAGE + " 下载完整安装包，点击「上传安装包…」选择该 zip。");
	}

	/** 正常状态: 绿色阶段徽标 + 绿色状态卡竖条 */
	private void setStage(String label) {
		setStage(label, false);
	}

	/**
	 * 阶段状态: 正常绿色 / 异常红色.
	 * 同步更新阶段徽标、左侧状态条与“下一步”徽标配色, 让流程节点状态一眼可辨.
	 */
	private void setStage(String label, boolean error) {
		String text = "当前阶段：" + label;
		lblStage.setText(text);
		lblStage.setToolTipText(text);
		lblStage.setForeground(error ? Theme.DANGER : Theme.STATUS_RUNNING_TEXT);
		lblStage.setColors(error ? Theme.DANGER_BG : Theme.SUCCESS_BG,
				error ? Theme.DANGER_BORDER : Theme.SUCCESS_BORDER);
		stageIcon.update(error ? Theme.DANGER : Theme.SUCCESS, error);
		lblStage.repaint();

		statusCard.setAccent(error ? Theme.DANGER : Theme.SUCCESS);
		lblNext.setForeground(error ? Theme.DANGER : Theme.PRIMARY);
		lblNext.setColors(error ? Theme.DANGER_BG : Theme.INFO_BG,
				error ? Theme.DANGER_BORDER : Theme.BORDER);
		nextIcon.update(error ? Theme.DANGER : Theme.PRIMARY);
		lblNext.repaint();
	}

	/** 圆角状态徽标: 底色/描边随状态切换 */
	private static final class PillLabel extends JLabel {
		private Color pillBg;
		private Color pillBorder;

		PillLabel(String text) {
			super(text);
			setOpaque(false);
			setColors(Theme.INFO_BG, Theme.BORDER);
		}

		void setColors(Color bg, Color border) {
			this.pillBg = bg;
			this.pillBorder = border;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
						RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(pillBg);
				g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
				g2.setColor(pillBorder);
				g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
			} finally {
				g2.dispose();
			}
			super.paintComponent(g);
		}
	}

	/** 阶段图标: 正常=实心圆点, 异常=感叹号, 代码绘制避免字体缺字 */
	private static final class StageIcon implements Icon {
		private static final int SIZE = 14;
		private Color color;
		private boolean alert;

		StageIcon(Color color) {
			this.color = color;
		}

		void update(Color color, boolean alert) {
			this.color = color;
			this.alert = alert;
		}

		@Override
		public void paintIcon(java.awt.Component c, Graphics g, int x, int y) {
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
						RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(color);
				g2.fillOval(x, y, SIZE, SIZE);
				g2.setColor(Color.WHITE);
				if (alert) {
					g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND,
							BasicStroke.JOIN_ROUND));
					g2.drawLine(x + SIZE / 2, y + 4, x + SIZE / 2, y + 9);
					g2.fillOval(x + SIZE / 2 - 1, y + 10, 2, 2);
				} else {
					g2.fillOval(x + SIZE / 2 - 2, y + SIZE / 2 - 2, 4, 4);
				}
			} finally {
				g2.dispose();
			}
		}

		@Override
		public int getIconWidth() {
			return SIZE;
		}

		@Override
		public int getIconHeight() {
			return SIZE;
		}
	}

	/** “下一步”图标: 实心圆 + 右箭头, 强调可执行动作 */
	private static final class NextIcon implements Icon {
		private static final int SIZE = 14;
		private Color color;

		NextIcon(Color color) {
			this.color = color;
		}

		void update(Color color) {
			this.color = color;
		}

		@Override
		public void paintIcon(java.awt.Component c, Graphics g, int x, int y) {
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
						RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(color);
				g2.fillOval(x, y, SIZE, SIZE);
				g2.setColor(Color.WHITE);
				g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND,
						BasicStroke.JOIN_ROUND));
				int cy = y + SIZE / 2;
				g2.drawLine(x + 4, cy, x + 10, cy);
				g2.drawLine(x + 7, cy - 3, x + 10, cy);
				g2.drawLine(x + 7, cy + 3, x + 10, cy);
			} finally {
				g2.dispose();
			}
		}

		@Override
		public int getIconWidth() {
			return SIZE;
		}

		@Override
		public int getIconHeight() {
			return SIZE;
		}
	}

	/** 状态卡: 白底圆角 + 左侧状态色竖条(正常绿 / 异常红) */
	private static final class StatusCard extends JPanel {
		private Color accent = Theme.SUCCESS;

		StatusCard() {
			super(new BorderLayout());
			setOpaque(false);
		}

		void setAccent(Color color) {
			this.accent = color;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
						RenderingHints.VALUE_ANTIALIAS_ON);
				int w = getWidth();
				int h = getHeight();
				g2.setColor(Theme.SURFACE);
				g2.fillRoundRect(0, 0, w - 1, h - 1, 10, 10);
				g2.setColor(Theme.BORDER);
				g2.drawRoundRect(0, 0, w - 1, h - 1, 10, 10);
				g2.setColor(accent);
				g2.fillRoundRect(0, 8, 4, Math.max(1, h - 16), 4, 4);
			} finally {
				g2.dispose();
			}
			super.paintComponent(g);
		}
	}

	private void setGuide(String text) {
		taGuide.setText(text);
	}

	private void appendLog(String message) {
		if (StrUtil.isEmpty(message)) {
			return;
		}
		taLog.append("[" + LocalTime.now().format(TIME_FMT) + "] " + message + "\n");
		taLog.setCaretPosition(taLog.getDocument().getLength());
	}
}
