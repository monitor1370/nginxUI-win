package com.nginxui.ui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
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
import java.io.File;
import java.net.URI;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
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
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;

import com.nginxui.utils.NginxInstallManager;
import com.nginxui.utils.NginxPackageManager;
import com.nginxui.utils.NginxWinServiceInstaller;

import cn.hutool.core.util.StrUtil;

/**
 * 全新安装 nginx 实例页面(内容区, 替代原弹窗).
 *
 * 布局与升级页一致:
 *  - 在线安装: 检测 nginx.org 连通性(超时 10 秒) → 选择稳定版 → 一键下载并安装;
 *    下载超过 180 秒判定失败, 在线入口全部置灰, 仅保留离线安装.
 *  - 离线安装: 前往官方下载页获取完整 nginx-x.y.z.zip → 上传校验 → 使用本地包安装;
 *  - 安装位置: 选择安装根目录, 自动创建独立目录(自动避让重名, 不影响现有实例).
 * 安装阶段与下一步操作以文字呈现, 不使用估算进度条.
 */
public class InstallPage extends JPanel {

	private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
	/** 官方下载页(离线安装的指定渠道) */
	private static final String OFFICIAL_PAGE = "https://nginx.org/en/download.html";

	private final MainFrame owner;
	private final NginxInstallManager manager;

	// 在线安装模块
	private JPanel onlinePanel;
	private JPanel onlineStatusBox;
	private JLabel lblOnlineStatus;
	private JTextArea taOnlineDesc;
	private JComboBox<String> cbVersion;
	/** 版本必填提示: 未选择有效版本时常驻显示在版本行下方 */
	private JLabel lblVersionHint;
	private JButton btnRetry;

	// 离线安装模块
	private JTextField tfZip;
	private JButton btnUpload, btnClearZip, btnOfficial;
	private JLabel lblZipState;
	private String zipVersion;
	private File zipFile;
	private boolean checkingZip;
	private int zipSeq;

	// 安装位置
	private JTextField tfRoot;
	private JButton btnChooseRoot;
	private JTextArea taRootHint;

	// 实例名称
	private JTextField tfName;
	private JButton btnCustomName;
	private JTextArea taNameHint;
	/** true=自定义名称模式(输入框可编辑), false=自动命名 */
	private boolean customNameMode;
	/** 自动生成的实例名称(自定义模式下为 null) */
	private String autoName;
	/** 当前名称校验错误(null=可用; 检查中时为提示文案) */
	private String nameError;
	/** 自定义名称的防抖校验定时器 / 序号 */
	private Timer nameCheckTimer;
	private int nameCheckSeq;
	/** 自动命名的异步计算序号(丢弃过期结果) */
	private int autoNameSeq;
	/** 安装根目录变更后的自动命名重算防抖 */
	private Timer rootDebounceTimer;

	// 安装方式切换(在线/离线互斥) + 网络异常提示
	private ModeSwitch modeSwitch;
	private JPanel modeHost;
	private JPanel onlineModule;
	private JPanel offlineModule;
	private NetworkAlert networkAlert;
	/** 当前方式: 0=在线安装, 1=离线安装 */
	private int mode;
	/** 在线预检失败原因(null=正常或检测中), 用于顶部提示条 */
	private String onlineError;

	// 三步向导: 版本 → 安装位置 → 实例名称
	private static final int STEP_COUNT = 3;
	/** 版本下拉占位项: 默认选中但不视为有效版本, 用于必填校验 */
	private static final String VERSION_PLACEHOLDER = "请选择版本";
	private static final String[] STEP_TITLES = { "选择版本", "安装位置", "实例名称" };
	private static final String[] STEP_SUBTITLES = {
			"先确定安装方式与目标版本，安装位置和名称在后续步骤设置。",
			"新实例会在此根目录下创建独立目录，不影响已有实例。",
			"名称将作为实例目录名与 Windows 服务名，确认后即可开始安装。" };
	private int stepIndex;
	private PageHeader header;
	private JPanel stepCard;
	private JPanel stepHost;
	private JPanel[] stepPanels;
	private PillLabel stepPill;
	private JButton btnPrev, btnNext, btnStart;
	private JPanel logPanel;

	// 状态 / 日志
	private StatusCard statusCard;
	private PillLabel lblStage;
	private PillLabel lblNext;
	private StageIcon stageIcon;
	private NextIcon nextIcon;
	private JTextArea taGuide, taLog;
	private JButton btnClose;

	private volatile boolean busy;
	private boolean online;
	private boolean checkingOnline;
	/** 下载超时后本次对话框内永久停用在线安装 */
	private boolean onlineTimeoutDisabled;

	public InstallPage(MainFrame owner) {
		this.owner = owner;
		this.manager = new NginxInstallManager(new File(owner.appHome()),
				owner.instanceRegistry());
		setOpaque(true);
		setBackground(Theme.BG);
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(14, Theme.GAP_L, Theme.GAP_M, Theme.GAP_L));
		buildUi();
		refreshAutoName();
		checkSource();
		OperationLock.addListener(this::updateControls);
	}

	// ==================== 界面 ====================

	private void buildUi() {
		JPanel body = new JPanel(new BorderLayout());
		body.setOpaque(false);

		modeSwitch = new ModeSwitch(new String[] { "在线安装", "离线安装" }, this::onModeChanged);
		networkAlert = new NetworkAlert("切换到离线安装", () -> modeSwitch.setSelected(1));

		// 三步向导: 同一位置只展示当前步骤, 切换时整体替换
		stepPanels = new JPanel[STEP_COUNT];
		stepPanels[0] = buildVersionStep();
		stepPanels[1] = buildInstallRootModule();
		stepPanels[2] = buildNameModule();
		// 每个步骤都是一张独立卡片: 用 CardLayout 保证同一位置只显示一张,
		// 且父容器首选高度取所有卡片最大值(多个组件共用 BorderLayout.CENTER 会塌成 0)
		stepHost = new JPanel(new CardLayout());
		stepHost.setOpaque(false);
		for (int i = 0; i < STEP_COUNT; i++) {
			stepHost.add(stepPanels[i], "step" + i);
		}

		// 白色卡片承载当前步骤内容: 1px 描边 + 16 内边距
		stepCard = new JPanel(new BorderLayout());
		stepCard.setBackground(Theme.SURFACE);
		stepCard.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(Theme.BORDER),
				BorderFactory.createEmptyBorder(16, 16, 16, 16)));
		stepCard.add(stepHost, BorderLayout.CENTER);

		taLog = new JTextArea();
		taLog.setEditable(false);
		taLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		taLog.setBackground(new Color(252, 252, 252));
		JScrollPane scroll = new JScrollPane(taLog);
		scroll.setBorder(BorderFactory.createTitledBorder(
				BorderFactory.createLineBorder(Theme.BORDER), "安装日志"));
		logPanel = new JPanel(new BorderLayout());
		logPanel.setOpaque(false);
		logPanel.setPreferredSize(new Dimension(10, 180));
		logPanel.add(scroll, BorderLayout.CENTER);

		JPanel content = new JPanel(new BorderLayout(0, 12));
		content.setOpaque(false);
		content.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
		content.add(stepCard, BorderLayout.NORTH);
		content.add(logPanel, BorderLayout.CENTER);
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

		JPanel stageRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		stageRow.setOpaque(false);
		stageRow.add(lblStage);
		// “下一步”徽标与首行文字顶部对齐
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

		statusCard = new StatusCard();
		statusCard.setBorder(BorderFactory.createEmptyBorder(9, 12, 9, 12));
		statusCard.add(statusText, BorderLayout.CENTER);
		setStage("准备中");

		btnClose = Theme.toolButton("返回实例总览");
		btnClose.addActionListener(e -> {
			if (!busy) {
				owner.returnToInstances();
			}
		});
		btnPrev = Theme.secondaryButton("上一步");
		btnPrev.addActionListener(e -> showStep(stepIndex - 1));
		btnNext = Theme.primaryButton("下一步");
		btnNext.addActionListener(e -> showStep(stepIndex + 1));
		btnStart = Theme.primaryButton("开始安装");
		btnStart.addActionListener(e -> startInstall(mode == 0));

		JPanel navLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		navLeft.setOpaque(false);
		navLeft.add(btnClose);
		JPanel navRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.GAP_S, 0));
		navRight.setOpaque(false);
		navRight.add(btnPrev);
		navRight.add(btnNext);
		navRight.add(btnStart);
		JPanel nav = new JPanel(new BorderLayout());
		nav.setOpaque(false);
		nav.add(navLeft, BorderLayout.WEST);
		nav.add(navRight, BorderLayout.EAST);

		JPanel bottom = new JPanel(new BorderLayout(0, 8));
		bottom.setBorder(BorderFactory.createEmptyBorder(6, 12, 10, 12));
		bottom.add(statusCard, BorderLayout.NORTH);
		bottom.add(nav, BorderLayout.SOUTH);
		body.add(bottom, BorderLayout.SOUTH);

		add(buildHeader(), BorderLayout.NORTH);
		add(body, BorderLayout.CENTER);
		showStep(0);
	}

	/** 页面标题行: 当前步骤名称 + 「1 / 3」进度指示, 副标题说明本步目标 */
	private JPanel buildHeader() {
		header = new PageHeader(STEP_TITLES[0], STEP_SUBTITLES[0]);
		header.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));
		stepPill = new PillLabel((stepIndex + 1) + " / " + STEP_COUNT);
		stepPill.setFont(Theme.BODY_BOLD);
		stepPill.setForeground(Theme.PRIMARY);
		stepPill.setColors(Theme.INFO_BG, Theme.BORDER);
		stepPill.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
		header.addAction(stepPill);
		return header;
	}

	/** 切换向导步骤: 只展示当前步骤的输入, 同步标题/进度与状态区可见性 */
	private void showStep(int index) {
		if (busy || stepPanels == null) {
			return;
		}
		stepIndex = Math.max(0, Math.min(STEP_COUNT - 1, index));
		((CardLayout) stepHost.getLayout()).show(stepHost, "step" + stepIndex);
		header.setTitle(STEP_TITLES[stepIndex]);
		header.setSubtitle(STEP_SUBTITLES[stepIndex]);
		stepPill.setText((stepIndex + 1) + " / " + STEP_COUNT);
		if (stepIndex > 0) {
			refreshRootHint();
		}
		if (stepIndex == STEP_COUNT - 1) {
			refreshGuide();
		}
		stepHost.revalidate();
		stepHost.repaint();
		updateControls();
	}

	/** 第 1 步: 安装方式与目标版本 */
	private JPanel buildVersionStep() {
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setOpaque(false);
		GridBagConstraints gc = new GridBagConstraints();
		gc.gridx = 0;
		gc.weightx = 1;
		gc.fill = GridBagConstraints.HORIZONTAL;
		gc.insets = new Insets(0, 0, 10, 0);
		gc.gridy = 0;
		panel.add(buildModeRow(), gc);
		gc.gridy = 1;
		panel.add(networkAlert, gc);
		gc.gridy = 2;
		gc.insets = new Insets(0, 0, 0, 0);
		panel.add(buildModeHost(), gc);
		return panel;
	}

	/** 安装方式切换(在线/离线): 位于内容区左侧, 与升级页布局一致 */
	private JPanel buildModeRow() {
		JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		row.setOpaque(false);
		row.add(Theme.label("安装方式", Theme.BODY_BOLD, Theme.TEXT));
		row.add(javax.swing.Box.createHorizontalStrut(8));
		row.add(modeSwitch);
		return row;
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

	/** 切换安装方式: 仅展示对应内容, 不触发网络请求(切换即时响应) */
	private void onModeChanged(int index) {
		mode = index;
		if (modeHost != null) {
			modeHost.removeAll();
			modeHost.add(index == 0 ? onlineModule : offlineModule, BorderLayout.CENTER);
			modeHost.revalidate();
			modeHost.repaint();
		}
		refreshNetworkAlert();
		// 两个模块的输入与选择都保留在原有组件上, 切换只改可见性;
		// 这里刷新按钮状态, 避免沿用切换前的“下一步”可用态
		if (btnClose != null) {
			updateControls();
			refreshGuide();
		}
	}

	/** 在线预检失败且处于在线方式时, 顶部展示网络异常与切换建议 */
	private void refreshNetworkAlert() {
		if (networkAlert == null) {
			return;
		}
		if (mode == 0 && !checkingOnline && onlineError != null) {
			networkAlert.showAlert("在线安装当前不可用",
					onlineError + "。可切换到离线安装，上传本地安装包后继续。");
		} else {
			networkAlert.hideAlert();
		}
	}

	/** 在线安装模块: 连通性状态 + 目标版本选择 */
	private JPanel buildOnlineModule() {
		onlinePanel = new JPanel(new BorderLayout(0, 12));
		onlinePanel.setOpaque(false);

		lblOnlineStatus = Theme.label("正在检测 nginx.org 连通性…", Theme.BODY_BOLD, Theme.TEXT);
		taOnlineDesc = hintArea(3, 30);
		onlineStatusBox = new JPanel(new BorderLayout(0, 4));
		onlineStatusBox.setOpaque(true);
		onlineStatusBox.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
		onlineStatusBox.add(lblOnlineStatus, BorderLayout.NORTH);
		onlineStatusBox.add(taOnlineDesc, BorderLayout.CENTER);

		cbVersion = new JComboBox<String>();
		cbVersion.setPreferredSize(new Dimension(160, 26));
		cbVersion.addActionListener(e -> {
			if (!busy) {
				updateControls();
				refreshGuide();
			}
		});
		btnRetry = Theme.toolButton("重新检测");
		btnRetry.setToolTipText("重新检测 nginx.org 连通性(超时 10 秒)并刷新稳定版列表");
		btnRetry.addActionListener(e -> checkSource());

		JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
		row.setOpaque(false);
		row.add(Theme.label("目标版本", Theme.BODY, Theme.TEXT));
		row.add(cbVersion);
		row.add(btnRetry);

		lblVersionHint = Theme.label(" ", Theme.SMALL, Theme.DANGER);
		JPanel versionBlock = new JPanel(new BorderLayout(0, 4));
		versionBlock.setOpaque(false);
		versionBlock.add(row, BorderLayout.NORTH);
		versionBlock.add(lblVersionHint, BorderLayout.SOUTH);

		onlinePanel.add(onlineStatusBox, BorderLayout.CENTER);
		onlinePanel.add(versionBlock, BorderLayout.SOUTH);
		applyOnlineState("checking", "正在检测 nginx.org 连通性…",
				"正在确认官方安装源是否可达（最长 10 秒）；不可达时可改用离线安装。");
		return onlinePanel;
	}

	/** 离线安装模块: 官方渠道下载 + 上传校验, 与在线模块完全独立 */
	private JPanel buildOfflineModule() {
		JPanel panel = new JPanel(new BorderLayout(0, 10));
		panel.setOpaque(false);

		JTextArea desc = hintArea(2, 30);
		desc.setText("已从官方渠道下载完整安装包？上传 nginx-x.y.z.zip，校验通过后即可离线完成安装。");

		tfZip = new JTextField();
		tfZip.setEditable(false);
		tfZip.setPreferredSize(new Dimension(220, 26));
		tfZip.setMinimumSize(new Dimension(120, 26));
		btnUpload = Theme.toolButton("上传安装包…");
		btnUpload.setToolTipText("选择从 " + OFFICIAL_PAGE + " 下载的完整 nginx-x.y.z.zip 安装包");
		btnUpload.addActionListener(e -> chooseZip());
		btnClearZip = Theme.toolButton("清除");
		Theme.setEnabled(btnClearZip, false, "尚未选择安装包");
		btnClearZip.addActionListener(e -> clearZip());
		btnOfficial = Theme.toolButton("打开官方下载页");
		btnOfficial.addActionListener(e -> openOfficialPage());

		JPanel row = new JPanel(new GridBagLayout());
		row.setOpaque(false);
		GridBagConstraints rc = new GridBagConstraints();
		rc.gridy = 0;
		rc.anchor = GridBagConstraints.WEST;
		rc.insets = new Insets(0, 0, 0, 6);
		rc.gridx = 0;
		row.add(Theme.label("安装包", Theme.BODY, Theme.TEXT), rc);
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
		JPanel stateRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		stateRow.setOpaque(false);
		stateRow.add(lblZipState);

		panel.add(desc, BorderLayout.NORTH);
		panel.add(row, BorderLayout.CENTER);
		panel.add(stateRow, BorderLayout.SOUTH);
		return panel;
	}

	/** 安装位置: 根目录 + 自动创建独立实例目录说明 */
	private JPanel buildInstallRootModule() {
		JPanel panel = new JPanel(new BorderLayout(0, 10));
		panel.setOpaque(false);

		tfRoot = new JTextField(manager.instancesRoot().getAbsolutePath());
		tfRoot.setPreferredSize(new Dimension(280, 26));
		tfRoot.setMinimumSize(new Dimension(140, 26));
		tfRoot.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				onRootChanged();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				onRootChanged();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				onRootChanged();
			}
		});
		btnChooseRoot = Theme.toolButton("选择目录…");
		btnChooseRoot.addActionListener(e -> chooseRoot());

		JPanel row = new JPanel(new GridBagLayout());
		row.setOpaque(false);
		GridBagConstraints rc = new GridBagConstraints();
		rc.gridy = 0;
		rc.anchor = GridBagConstraints.WEST;
		rc.insets = new Insets(0, 0, 0, 6);
		rc.gridx = 0;
		row.add(Theme.label("安装根目录", Theme.BODY, Theme.TEXT), rc);
		rc.gridx = 1;
		rc.weightx = 1;
		rc.fill = GridBagConstraints.HORIZONTAL;
		row.add(tfRoot, rc);
		rc.weightx = 0;
		rc.fill = GridBagConstraints.NONE;
		rc.gridx = 2;
		rc.insets = new Insets(0, 0, 0, 0);
		row.add(btnChooseRoot, rc);

		taRootHint = hintArea(2, 30);
		panel.add(row, BorderLayout.NORTH);
		panel.add(taRootHint, BorderLayout.CENTER);
		refreshRootHint();
		return panel;
	}

	/** 实例名称模块: 默认展示自动生成的标准名, 点击按钮后才允许自定义 */
	private JPanel buildNameModule() {
		JPanel panel = new JPanel(new BorderLayout(0, 10));
		panel.setOpaque(false);

		tfName = new JTextField();
		tfName.setEditable(false);
		tfName.setBackground(new Color(245, 246, 248));
		tfName.setForeground(Theme.TEXT_MUTED);
		tfName.setHorizontalAlignment(JTextField.LEFT);
		tfName.setPreferredSize(new Dimension(80, 26));
		tfName.setMinimumSize(new Dimension(60, 26));
		tfName.setToolTipText("变量 N：默认自动生成，点击右侧按钮后可修改");
		tfName.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				onNameEdited();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				onNameEdited();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				onNameEdited();
			}
		});

		btnCustomName = Theme.toolButton("自定义 N…");
		btnCustomName.setToolTipText("点击后可修改变量 N（1-" + NginxWinServiceInstaller.SUFFIX_MAX_LENGTH
				+ " 位数字）");
		btnCustomName.addActionListener(e -> toggleCustomName());

		JPanel row = new JPanel(new GridBagLayout());
		row.setOpaque(false);
		GridBagConstraints rc = new GridBagConstraints();
		rc.gridy = 0;
		rc.anchor = GridBagConstraints.WEST;
		rc.insets = new Insets(0, 0, 0, 6);
		rc.gridx = 0;
		row.add(new JLabel("实例名称:"), rc);
		// 固定前缀: 不可编辑、不可替换
		rc.gridx = 1;
		rc.insets = new Insets(0, 0, 0, 0);
		JLabel lblPrefix = new JLabel(NginxWinServiceInstaller.NAME_PREFIX);
		lblPrefix.setFont(Theme.BODY);
		row.add(lblPrefix, rc);
		rc.gridx = 2;
		rc.weightx = 1;
		rc.fill = GridBagConstraints.HORIZONTAL;
		rc.insets = new Insets(0, 0, 0, 6);
		row.add(tfName, rc);
		rc.weightx = 0;
		rc.fill = GridBagConstraints.NONE;
		rc.gridx = 3;
		rc.insets = new Insets(0, 0, 0, 0);
		row.add(btnCustomName, rc);

		taNameHint = hintArea(2, 30);
		panel.add(row, BorderLayout.NORTH);
		panel.add(taNameHint, BorderLayout.CENTER);
		return panel;
	}

	// ==================== 实例名称 ====================

	/** 安装根目录变更: 刷新目录预览, 自动模式下防抖重算名称 */
	private void onRootChanged() {
		refreshRootHint();
		if (customNameMode || btnCustomName == null) {
			return;
		}
		if (rootDebounceTimer == null) {
			rootDebounceTimer = new Timer(450, e -> refreshAutoName());
			rootDebounceTimer.setRepeats(false);
		}
		rootDebounceTimer.restart();
	}

	/** 自动命名: 检测 SCM 已注册服务与安装根目录已有实例, 生成 nginx-win-ngui-N */
	private void refreshAutoName() {
		if (customNameMode || tfName == null) {
			return;
		}
		final int seq = ++autoNameSeq;
		autoName = null;
		nameError = "正在生成实例名称";
		// 重算期间保留上一次展示的名称, 避免输入框闪烁; 名称仍以本次计算结果为准
		setNameHint("正在检测本机已存在的实例与服务，自动生成唯一名称…", Theme.TEXT_MUTED);
		updateControls();
		final File root = rootDirOrNull();
		new SwingWorker<String, Void>() {
			@Override
			protected String doInBackground() {
				return NginxWinServiceInstaller.nextServiceName(root);
			}

			@Override
			protected void done() {
				if (seq != autoNameSeq || customNameMode) {
					return;
				}
				String name;
				try {
					name = get();
				} catch (Exception e) {
					name = null;
				}
				autoName = name;
				if (name == null) {
					nameError = "自动生成实例名称失败";
					tfName.setText("");
					setNameHint("自动生成实例名称失败，可点击「自定义 N…」手动指定。", Theme.DANGER);
				} else {
					nameError = null;
					tfName.setText(name.substring(NginxWinServiceInstaller.NAME_PREFIX.length()));
					setNameHint("自动生成：" + name + "（前缀固定，仅变量 N 可调整；已避开本机已有实例与服务）；"
							+ "如需调整 N 请点击「自定义 N…」。", Theme.TEXT_MUTED);
				}
				updateControls();
				refreshRootHint();
			}
		}.execute();
	}

	/** 切换自动命名 / 自定义 N: 自定义模式下变量 N 输入框才可编辑 */
	private void toggleCustomName() {
		if (busy || tfName == null) {
			return;
		}
		customNameMode = !customNameMode;
		Theme.setEnabled(btnCustomName, !busy, busy ? "正在执行，请稍候" : null);
		if (customNameMode) {
			autoNameSeq++;
			autoName = null;
			tfName.setEditable(true);
			tfName.setBackground(Color.WHITE);
			tfName.setForeground(Theme.TEXT);
			tfName.setText("");
			tfName.setToolTipText("变量 N：1-" + NginxWinServiceInstaller.SUFFIX_MAX_LENGTH
					+ " 位数字，大于 0，无前导零");
			btnCustomName.setText("恢复自动命名");
			btnCustomName.setToolTipText("放弃自定义，恢复自动生成的 nginx-win-ngui-N");
			nameError = "请输入变量 N";
			setNameHint("请输入变量 N（1-" + NginxWinServiceInstaller.SUFFIX_MAX_LENGTH
					+ " 位数字，且大于 0）；前缀 " + NginxWinServiceInstaller.NAME_PREFIX
					+ " 固定不变，最终名称为 " + NginxWinServiceInstaller.NAME_PREFIX + "N。",
					Theme.TEXT_MUTED);
			tfName.requestFocusInWindow();
		} else {
			tfName.setEditable(false);
			tfName.setBackground(new Color(245, 246, 248));
			tfName.setForeground(Theme.TEXT_MUTED);
			tfName.setToolTipText("变量 N：默认自动生成，点击右侧按钮后可修改");
			btnCustomName.setText("自定义 N…");
			btnCustomName.setToolTipText("点击后可修改变量 N（1-"
					+ NginxWinServiceInstaller.SUFFIX_MAX_LENGTH
					+ " 位数字）");
			nameError = null;
			refreshAutoName();
		}
		updateControls();
		refreshRootHint();
	}

	/** 变量 N 输入变更: 先做格式校验, 再防抖做唯一性校验 */
	private void onNameEdited() {
		if (!customNameMode || tfName == null) {
			return;
		}
		String value = StrUtil.trim(tfName.getText());
		String format = formatNameError(value);
		if (format != null) {
			nameError = format;
			setNameHint(format, Theme.DANGER);
			updateControls();
			refreshRootHint();
			return;
		}
		nameError = "正在检查名称是否可用";
		setNameHint("正在检查「" + NginxWinServiceInstaller.NAME_PREFIX + value + "」是否已被占用…",
				Theme.TEXT_MUTED);
		if (nameCheckTimer == null) {
			nameCheckTimer = new Timer(400, e -> checkNameAsync());
			nameCheckTimer.setRepeats(false);
		}
		nameCheckTimer.restart();
		updateControls();
		refreshRootHint();
	}

	/** 唯一性校验(异步): SCM 服务名 + 安装根目录同名目录 */
	private void checkNameAsync() {
		if (!customNameMode || tfName == null) {
			return;
		}
		final String value = StrUtil.trim(tfName.getText());
		String format = formatNameError(value);
		if (format != null) {
			nameError = format;
			setNameHint(format, Theme.DANGER);
			updateControls();
			refreshRootHint();
			return;
		}
		final String fullName = NginxWinServiceInstaller.NAME_PREFIX + value;
		final int seq = ++nameCheckSeq;
		final File root = rootDirOrNull();
		new SwingWorker<String, Void>() {
			@Override
			protected String doInBackground() {
				return NginxWinServiceInstaller.checkInstanceName(fullName, root);
			}

			@Override
			protected void done() {
				if (seq != nameCheckSeq || !customNameMode
						|| !value.equals(StrUtil.trim(tfName.getText()))) {
					return;
				}
				String err;
				try {
					err = get();
				} catch (Exception e) {
					err = "名称检查失败：" + com.nginxui.utils.ErrorReporter.userMessage(e);
				}
				nameError = err;
				if (err == null) {
					setNameHint("「" + fullName + "」可用：将作为实例目录名与 Windows 服务名。",
							Theme.SUCCESS);
				} else {
					setNameHint(err, Theme.DANGER);
				}
				updateControls();
				refreshRootHint();
			}
		}.execute();
	}

	/** 变量 N 格式校验: 1-6 位数字, 大于 0, 无前导零 */
	private static String formatNameError(String value) {
		if (StrUtil.isEmpty(value)) {
			return "请输入变量 N";
		}
		if (!value.matches("[0-9]+")) {
			return "变量 N 仅允许数字（前缀 nginx-win-ngui- 固定，无需输入）";
		}
		if (value.length() > NginxWinServiceInstaller.SUFFIX_MAX_LENGTH) {
			return "变量 N 最长 " + NginxWinServiceInstaller.SUFFIX_MAX_LENGTH
					+ " 位数字（当前 " + value.length() + "）";
		}
		if (value.startsWith("0")) {
			return "变量 N 必须为大于 0 的整数（不能为 0 或带前导零）";
		}
		return null;
	}

	/** 当前生效的实例名称: 固定前缀 + 变量 N(自动或自定义) */
	private String currentName() {
		if (tfName == null) {
			return "";
		}
		if (customNameMode) {
			String n = StrUtil.trim(tfName.getText());
			return StrUtil.isEmpty(n) ? "" : NginxWinServiceInstaller.NAME_PREFIX + n;
		}
		return StrUtil.nullToEmpty(autoName);
	}

	private File rootDirOrNull() {
		String raw = StrUtil.trim(tfRoot == null ? "" : tfRoot.getText());
		return StrUtil.isEmpty(raw) ? null : new File(raw);
	}

	private void setNameHint(String text, Color color) {
		if (taNameHint == null) {
			return;
		}
		taNameHint.setText(text);
		taNameHint.setForeground(color);
	}

	/** 名称是否已通过格式与唯一性校验, 可用于提交安装 */
	private boolean nameReady() {
		return nameError == null && StrUtil.isNotEmpty(currentName());
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

	/** 在线状态区: 底色/标题色随检测结果切换, 不使用嵌套描边 */
	private void applyOnlineState(String style, String title, String desc) {
		Color bg;
		Color titleColor;
		if ("online".equals(style)) {
			bg = Theme.SUCCESS_BG;
			titleColor = Theme.SUCCESS;
		} else if ("checking".equals(style)) {
			bg = Theme.INFO_BG;
			titleColor = Theme.TEXT_MUTED;
		} else {
			bg = Theme.WARNING_BG;
			titleColor = Theme.WARNING;
		}
		onlineStatusBox.setBackground(bg);
		lblOnlineStatus.setText(title);
		lblOnlineStatus.setForeground(titleColor);
		taOnlineDesc.setText(desc);
		taOnlineDesc.setForeground(Theme.TEXT_MUTED);
		onlineStatusBox.revalidate();
		onlineStatusBox.repaint();
	}

	// ==================== 连通性检测 ====================

	/** 版本下拉模型: 首项为必填占位, 其后为有效版本 */
	private static DefaultComboBoxModel<String> versionModel(List<String> versions) {
		DefaultComboBoxModel<String> model = new DefaultComboBoxModel<String>();
		model.addElement(VERSION_PLACEHOLDER);
		for (String v : versions) {
			if (StrUtil.isNotBlank(v)) {
				model.addElement(v);
			}
		}
		model.setSelectedItem(VERSION_PLACEHOLDER);
		return model;
	}

	/** 仅当选中真实版本(非 null / 非空 / 非占位)时返回 true */
	private boolean versionSelected() {
		Object v = cbVersion.getSelectedItem();
		return v instanceof String && StrUtil.isNotBlank((String) v) && !VERSION_PLACEHOLDER.equals(v);
	}

	/** 版本必填提示文案: 覆盖检测中 / 不可用 / 无版本 / 未选择四种场景 */
	private String versionHintText() {
		if (mode != 0) {
			return "";
		}
		if (checkingOnline) {
			return "正在获取可用版本…";
		}
		if (!online) {
			return onlineTimeoutDisabled ? "在线安装已停用，请改用离线安装"
					: "在线安装不可用，请重新检测或改用离线安装";
		}
		if (cbVersion.getItemCount() <= 1) {
			return "暂无可用版本，请重新检测";
		}
		if (!versionSelected()) {
			return "请先选择版本";
		}
		return "";
	}

	/** 安装源连通性检测: 10 秒硬超时, 不可达时引导离线安装 */
	private void checkSource() {
		if (busy || checkingOnline || onlineTimeoutDisabled) {
			return;
		}
		checkingOnline = true;
		online = false;
		onlineError = null;
		refreshNetworkAlert();
		cbVersion.setModel(versionModel(java.util.List.of()));
		appendLog("正在检测 nginx.org 连通性: " + NginxPackageManager.DOWNLOAD_BASE + "（超时上限 10 秒）");
		applyOnlineState("checking", "正在检测 nginx.org 连通性…",
				"检测目标：" + NginxPackageManager.DOWNLOAD_BASE
						+ "\n最长等待 10 秒；不可访问时可切换到离线安装。");
		setStage("检测网络连通性");
		setGuide("正在确认在线安装源，通常几秒内完成。");
		updateControls();

		new SwingWorker<NginxPackageManager.Reachability, Void>() {
			@Override
			protected NginxPackageManager.Reachability doInBackground() {
				return NginxPackageManager.probeUpgradeSource();
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
					// 首项固定为占位提示, 用户必须主动选择具体版本才能进入下一步
					cbVersion.setModel(versionModel(versions));
					String latest = versions.isEmpty() ? "—" : versions.get(0);
					applyOnlineState("online", "官方安装源连接正常",
							"已获取 " + versions.size() + " 个稳定版本，最新为 " + latest + "。\n"
									+ "选择目标版本后继续下一步，安装时自动下载官方安装包。");
					appendLog("nginx.org 连通正常（" + r.elapsedMs + " ms），可用稳定版 "
							+ versions.size() + " 个（最新 " + latest + "）");
					setStage("版本可选");
					setGuide("选择目标版本后点击「下一步」继续设置安装位置。");
				} else {
					online = false;
					String reason = r == null ? "检测异常中止"
							: StrUtil.removeSuffix(StrUtil.nullToEmpty(r.detail),
									NginxPackageManager.NET_FAIL_TIP).trim();
					if (StrUtil.isEmpty(reason)) {
						reason = "超过 10 秒无响应";
					}
					applyOnlineState("offline", "无法连接 nginx.org，在线安装暂不可用",
							"原因：" + reason + "\n可改用离线安装：前往官方下载页获取完整安装包后上传。");
					appendLog("无法连接 nginx.org: " + reason);
					appendLog("在线安装不可用；请改用「离线安装」上传安装包");
					onlineError = "连通性检测失败：" + reason;
					refreshNetworkAlert();
					setStage("在线安装暂不可用", true);
					setGuide("切换到离线安装，前往 " + OFFICIAL_PAGE
							+ " 下载 nginx-x.y.z.zip 后点击「上传安装包…」。");
				}
				updateControls();
			}
		}.execute();
	}

	// ==================== 本地安装包 ====================

	private void chooseZip() {
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("选择 nginx 安装包 (nginx-x.y.z.zip)");
		chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
		chooser.setAcceptAllFileFilterUsed(false);
		chooser.setFileFilter(new FileNameExtensionFilter("nginx 安装包 (*.zip)", "zip"));
		String seed = NginxPackageManager.workDir().getAbsolutePath();
		chooser.setCurrentDirectory(new File(seed));
		if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
			return;
		}
		File picked = chooser.getSelectedFile();
		tfZip.setText(picked.getAbsolutePath());
		tfZip.setToolTipText(picked.getAbsolutePath());
		zipFile = null;
		zipVersion = null;
		lblZipState.setText("正在校验安装包…");
		lblZipState.setForeground(Theme.TEXT_MUTED);
		validateZipAsync(picked);
	}

	/** 上传即校验: 结构与版本一次确认, 失败清空路径避免误装 */
	private void validateZipAsync(File zip) {
		checkingZip = true;
		int seq = ++zipSeq;
		setStage("校验安装包");
		setGuide("正在确认安装包结构与版本，请稍候…");
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
				String ver;
				try {
					ver = get();
				} catch (Exception e) {
					ver = null;
					String msg = com.nginxui.utils.ErrorReporter.userMessage(e);
					tfZip.setText("");
					tfZip.setToolTipText(null);
					zipFile = null;
					zipVersion = null;
					lblZipState.setText("校验失败：" + msg);
					lblZipState.setForeground(Theme.DANGER);
					appendLog("安装包校验失败: " + msg);
					setStage("安装包校验失败", true);
					setGuide("安装包校验失败，请重新「上传安装包…」：" + msg);
				}
				if (ver != null) {
					zipFile = zip;
					zipVersion = ver;
					long kb = Math.max(1, zip.length() / 1024);
					lblZipState.setText("已校验：nginx-" + ver + ".zip（" + kb + " KB）");
					lblZipState.setForeground(Theme.SUCCESS);
					appendLog("本地安装包校验通过: nginx-" + ver + ".zip（" + kb + " KB）");
					setStage("安装包就绪");
					setGuide("安装包校验通过，点击「下一步」继续设置安装位置。");
				}
				updateControls();
			}
		}.execute();
	}

	private void clearZip() {
		zipFile = null;
		zipVersion = null;
		zipSeq++;
		checkingZip = false;
		tfZip.setText("");
		tfZip.setToolTipText(null);
		lblZipState.setText("未选择安装包");
		lblZipState.setForeground(Theme.TEXT_MUTED);
		appendLog("已清除本地安装包");
		setStage("就绪");
		refreshGuide();
		updateControls();
	}

	private void chooseRoot() {
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("选择安装根目录");
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		chooser.setAcceptAllFileFilterUsed(false);
		File current = new File(StrUtil.trim(tfRoot.getText()));
		if (current.isDirectory()) {
			chooser.setCurrentDirectory(current);
		}
		if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
			tfRoot.setText(chooser.getSelectedFile().getAbsolutePath());
		}
	}

	private void refreshRootHint() {
		String raw = StrUtil.trim(tfRoot.getText());
		if (StrUtil.isEmpty(raw)) {
			taRootHint.setText("请选择用于存放实例的可写目录。");
			if (!busy) {
				updateControls();
			}
			return;
		}
		File base = new File(raw);
		String name = currentName();
		String dir = StrUtil.isEmpty(name)
				? (customNameMode ? "（请填写变量 N）" : "（实例名称生成中…）")
				: new File(base, name).getAbsolutePath();
		taRootHint.setText("新实例目录：" + dir
				+ "\n实例名称与服务名同名；前缀 nginx-win-ngui- 固定，自动命名会避开重名。");
		if (!busy) {
			updateControls();
		}
	}

	// ==================== 安装 ====================

	private void startInstall(boolean onlineMode) {
		if (busy) {
			return;
		}
		File root = new File(StrUtil.trim(tfRoot.getText()));
		if (StrUtil.isEmpty(tfRoot.getText())) {
			JOptionPane.showMessageDialog(this, "请先选择安装位置。",
					"nginxUI - 安装实例", JOptionPane.WARNING_MESSAGE);
			return;
		}
		if (!onlineMode && (zipFile == null || zipVersion == null)) {
			JOptionPane.showMessageDialog(this, "请先上传并校验本地安装包。",
					"nginxUI - 安装实例", JOptionPane.WARNING_MESSAGE);
			return;
		}
		String target = null;
		if (onlineMode) {
			if (!versionSelected()) {
				JOptionPane.showMessageDialog(this, "请先选择版本。",
						"nginxUI - 安装实例", JOptionPane.WARNING_MESSAGE);
				return;
			}
			target = (String) cbVersion.getSelectedItem();
		}

		// 提交前双重校验: 固定前缀 + 变量 N 的格式, 再复核唯一性
		// (自动名称也复核一次, 防止展示后被其它实例占用)
		String suffix = StrUtil.trim(tfName.getText());
		String instanceName = currentName();
		String nameProblem;
		if (StrUtil.isEmpty(suffix)) {
			nameProblem = "实例名称尚未生成完成，请稍候重试";
		} else {
			nameProblem = formatNameError(suffix);
			if (nameProblem == null) {
				nameProblem = NginxWinServiceInstaller.checkInstanceName(instanceName, root);
			}
		}
		if (nameProblem != null) {
			nameError = nameProblem;
			setNameHint(nameProblem, Theme.DANGER);
			updateControls();
			JOptionPane.showMessageDialog(this, "实例名称不可用：" + nameProblem,
					"nginxUI - 安装实例", JOptionPane.WARNING_MESSAGE);
			return;
		}

		if (!OperationLock.tryAcquire("安装实例")) {
			setGuide(OperationLock.reason());
			return;
		}
		busy = true;
		updateControls();
		setStage(onlineMode ? "下载并安装 nginx-" + target : "使用本地安装包安装");
		setGuide("正在准备安装包并创建实例目录，请勿离开本页…");
		appendLog("========== 开始安装 ==========");
		appendLog("安装位置: " + root.getAbsolutePath());
		appendLog("实例名称: " + instanceName
				+ (customNameMode ? "（自定义 N）" : "（自动生成）"));
		final boolean onlineInstall = onlineMode;
		final String version = target;
		final File rootDir = root;
		final String finalName = instanceName;

		new SwingWorker<NginxInstallManager.InstallResult, Void>() {
			@Override
			protected NginxInstallManager.InstallResult doInBackground() {
				return manager.install(version, onlineInstall ? null : zipFile, rootDir, finalName,
						new NginxInstallManager.Progress() {
							@Override
							public void log(String message) {
								SwingUtilities.invokeLater(() -> appendLog(message));
							}

							@Override
							public void stage(String label) {
								SwingUtilities.invokeLater(() -> {
									setStage(label);
									setGuide("正在「" + label + "」，请勿离开本页…");
								});
							}
						});
			}

			@Override
			protected void done() {
				OperationLock.release();
				busy = false;
				NginxInstallManager.InstallResult r;
				try {
					r = get();
				} catch (Exception e) {
					String msg = com.nginxui.utils.ErrorReporter.userMessage(e);
					if (onlineInstall && StrUtil.nullToEmpty(msg).contains("下载超时")) {
						markOnlineTimeout();
					} else {
						setStage("安装失败", true);
						setGuide("安装失败：" + msg);
						appendLog("========== 安装失败 ==========");
						appendLog(msg);
					}
					updateControls();
					return;
				}
				if (r == null) {
					setStage("安装失败", true);
					setGuide("安装失败：未返回结果，请查看日志。");
					updateControls();
					return;
				}
				if (r.success) {
					setStage("安装完成");
					setGuide("实例「" + r.entry.getName() + "」已安装并注册；返回实例总览后可直接选中它并启动。");
					appendLog("========== 安装完成 ==========");
					appendLog(r.message);
					appendLog("程序目录: " + r.entry.getPrefixDir());
					owner.onInstanceInstalled(r.entry);
				} else if (r.downloadTimeout) {
					markOnlineTimeout();
				} else {
					setStage("安装失败", true);
					setGuide("安装失败：" + r.message);
					appendLog("========== 安装失败 ==========");
					appendLog(r.message);
				}
				updateControls();
			}
		}.execute();
	}

	/** 下载超时: 在线安装入口全部停用, 只保留离线安装 */
	private void markOnlineTimeout() {
		onlineTimeoutDisabled = true;
		online = false;
		cbVersion.setModel(versionModel(java.util.List.of()));
		applyOnlineState("offline", "在线安装已停用（下载超时）",
				"安装包下载超过 180 秒，已判定为失败。\n可切换到离线安装，上传官方完整安装包后继续。");
		setStage("在线安装已停用（下载超时）", true);
		setGuide("切换为离线安装，上传官方完整安装包后继续。");
		onlineError = "安装包下载超时（超过 180 秒），在线安装已停用";
		refreshNetworkAlert();
		updateControls();
	}

	// ==================== 状态 / 控件 ====================

	private void refreshGuide() {
		if (busy) {
			return;
		}
		if (mode == 1) {
			if (zipVersion != null) {
				setGuide("将使用本地安装包（nginx-" + zipVersion + "）安装" + nameSuffix()
						+ "；确认无误后点击「开始安装」。");
			} else {
				setGuide("请返回第一步上传并校验官方安装包，再开始安装。");
			}
			return;
		}
		if (onlineTimeoutDisabled) {
			setGuide("在线安装已停用（下载超时）。可切换到离线安装并上传官方安装包后继续。");
			return;
		}
		if (checkingOnline) {
			setGuide("正在确认在线安装源，请稍候…");
			return;
		}
		if (online) {
			if (versionSelected()) {
				setGuide("将安装 nginx " + cbVersion.getSelectedItem() + nameSuffix()
						+ "；确认无误后点击「开始安装」。");
			} else {
				setGuide("请先返回第一步选择目标版本，再继续。");
			}
		} else {
			setGuide("请返回第一步完成版本选择，再开始安装。");
		}
	}

	/** 引导文案中的实例名称提示 */
	private String nameSuffix() {
		String name = currentName();
		return StrUtil.isEmpty(name) ? "" : "（实例名称 " + name + "）";
	}

	private void updateControls() {
		if (btnClose == null) {
			// 构建安装位置模块时 refreshRootHint 会提前触发, 控件尚未创建
			return;
		}
		boolean idle = !busy;
		boolean hasZip = zipVersion != null;
		boolean hasRoot = StrUtil.isNotEmpty(StrUtil.trim(tfRoot.getText()));
		boolean nameOk = nameReady();
		boolean onlineEnabled = online && !onlineTimeoutDisabled;
		boolean canOnline = onlineEnabled && versionSelected();
		String busyReason = busy ? "正在执行，请稍候" : OperationLock.reason();
		String versionHint = idle ? versionHintText() : "";
		String versionSelectReason = busyReason != null ? busyReason
				: (onlineTimeoutDisabled ? "在线检测已连续超时：切换离线安装或稍后重试"
						: (!online ? "当前为离线模式：切换到在线模式后才可选择版本"
								: (checkingOnline || checkingZip ? "正在获取版本列表，请稍候" : null)));
		Theme.setEnabled(cbVersion, idle && onlineEnabled && !checkingOnline && !checkingZip,
				versionSelectReason);
		Theme.setEnabled(btnRetry, idle && !checkingOnline && !onlineTimeoutDisabled,
				busyReason != null ? busyReason
						: (checkingOnline ? "正在检测在线版本，请稍候"
								: (onlineTimeoutDisabled ? "在线检测已连续超时：当前没有可重试的请求" : null)));
		Theme.setEnabled(btnUpload, idle && !checkingZip,
				busyReason != null ? busyReason : (checkingZip ? "正在解压安装包，请稍候" : null));
		Theme.setEnabled(btnClearZip, idle && hasZip && !checkingZip,
				busyReason != null ? busyReason : (checkingZip ? "正在解压安装包，请稍候" : "尚未选择安装包"));
		Theme.setEnabled(btnOfficial, idle, busyReason);
		Theme.setEnabled(btnChooseRoot, idle, busyReason);
		Theme.setEnabled(btnCustomName, idle, busyReason);
		// 只读输入框也需要说明原因: 用户无法输入时必须知道如何恢复可编辑
		tfName.setEditable(idle && customNameMode);
		tfName.setToolTipText(!idle ? "正在执行，请稍候"
				: (customNameMode ? "变量 N：1-" + NginxWinServiceInstaller.SUFFIX_MAX_LENGTH + " 位数字"
						: "默认自动生成的名称：点击右侧「自定义 N…」后可修改"));
		tfRoot.setEditable(idle);
		tfRoot.setToolTipText(idle ? "Nginx 安装根目录：可直接输入或点击「选择目录…」"
				: "正在执行，请稍候");
		Theme.setEnabled(btnClose, idle, busyReason);

		// 每一步的前置条件: 版本就绪 / 安装目录已填写 / 名称可用
		boolean versionReady = mode == 0
				? canOnline && !checkingOnline && !checkingZip
				: hasZip && !checkingZip;
		btnPrev.setVisible(stepIndex > 0);
		Theme.setEnabled(btnPrev, idle, busyReason);
		btnNext.setVisible(stepIndex < STEP_COUNT - 1);
		String nextReason = busyReason != null ? busyReason
				: (stepIndex == 0 ? (versionSelectReason != null ? versionSelectReason : versionHint)
						: (hasRoot ? null : "先填写 Nginx 安装目录"));
		Theme.setEnabled(btnNext, idle && (stepIndex == 0 ? versionReady : hasRoot),
				nextReason == null || nextReason.isEmpty() ? "先完成当前步骤的必填项" : nextReason);
		btnStart.setVisible(stepIndex == STEP_COUNT - 1);
		String startReason = busyReason != null ? busyReason
				: (!versionReady ? (versionHint.isEmpty() ? "先返回第一步完成版本选择" : versionHint)
						: (!hasRoot ? "先返回第二步填写安装目录"
								: (nameError != null ? "实例名称不可用：" + nameError
										: (StrUtil.isEmpty(currentName()) ? "先填写实例名称" : null))));
		Theme.setEnabled(btnStart, idle && !OperationLock.isBusy() && versionReady && hasRoot && nameOk, startReason);
		// 版本必填提示: 只出现在第 1 步的在线方式下, 明确说明为何不能继续
		lblVersionHint.setText(versionHint.isEmpty() ? " " : versionHint);
		lblVersionHint.setForeground(checkingOnline ? Theme.TEXT_MUTED : Theme.DANGER);
		lblVersionHint.setVisible(stepIndex == 0 && mode == 0 && !versionHint.isEmpty());

		// 状态卡与日志只在本步或安装过程中出现, 前两步保持界面清爽
		boolean advanced = stepIndex >= STEP_COUNT - 1 || busy;
		statusCard.setVisible(advanced);
		logPanel.setVisible(advanced);
	}

	private void setStage(String label) {
		setStage(label, false);
	}

	/** 阶段状态: 正常绿色 / 异常红色, 同步状态卡竖条与“下一步”徽标 */
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

	private void setGuide(String text) {
		taGuide.setText(text);
	}

	private void appendLog(String message) {
		String line = LocalTime.now().format(TIME_FMT) + "  " + message;
		taLog.append(line + "\n");
		taLog.setCaretPosition(taLog.getDocument().getLength());
	}

	private void openOfficialPage() {
		try {
			Desktop.getDesktop().browse(new URI(OFFICIAL_PAGE));
		} catch (Exception e) {
			JOptionPane.showMessageDialog(this,
					"无法打开浏览器，请手动访问：" + OFFICIAL_PAGE,
					"nginxUI - 安装实例", JOptionPane.INFORMATION_MESSAGE);
		}
	}

	// ==================== 状态徽标控件 ====================

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

	/** 阶段图标: 正常=实心圆点, 异常=感叹号 */
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

	/** “下一步”图标: 实心圆 + 右箭头 */
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
}
