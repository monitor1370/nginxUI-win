package com.nginxui.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JList;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;

import com.formdev.flatlaf.FlatLaf;
import com.sun.jna.platform.win32.Winsvc;
import com.nginxui.ext.NginxInfo;
import com.nginxui.ext.NginxInstanceEntry;
import com.nginxui.ext.NginxInstanceIgnore;
import com.nginxui.utils.NginxDashboardUtils;
import com.nginxui.utils.NginxInstanceRegistry;
import com.nginxui.utils.NginxInstanceIgnoreStore;
import com.nginxui.utils.JsonFileStore;
import com.nginxui.utils.ErrorReporter;
import com.nginxui.utils.NginxUtils;
import com.nginxui.utils.WindowsNginxServiceUtils;

/**
 * 主窗口: 实例列表 + 启停控制 + 配置测试.
 * 无 Spring, 直接调 utils 层静态方法.
 */
public class MainFrame extends JFrame {

	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(MainFrame.class);
	private static final java.time.format.DateTimeFormatter TIME_FMT =
			java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");

	private final String home = resolveHome();
	private final String registryFile = home + File.separator + "instances.json";
	private final String ignoreFile = home + File.separator + "instances-ignore.json";

	private NginxInstanceRegistry registry;
	private NginxInstanceIgnoreStore ignoreStore = new NginxInstanceIgnoreStore();
	/** 最近一次采集结果: 改名/忽略后重渲染列表无需重新检测 */
	private RefreshData lastData;

	private JList<InstanceRow> instanceList;
	private DefaultListModel<InstanceRow> listModel;
	private JLabel lblStatus;
	private JLabel lblCount;
	private JLabel lblLastRefresh;
	/** 列表鼠标悬停行(仅用于 hover 高亮) */
	private int hoverIndex = -1;
	/** 列表筛选: 关键词 + 状态 */
	private JTextField txtSearch;
	private JComboBox<String> cbFilter;
	private CardLayout listCards;
	private JPanel listCardsPanel;
	private JLabel lblEmpty;
	/** 实例总览页(下拉选实例 + 配置详情卡片) */
	private InstanceOverviewPanel instancePage;
	/** 版本升级 / 安装实例 / 证书管理: 内容区页面(替代原弹窗) */
	private InstallPage installPage;
	private UpgradePage upgradePage;
	private CertManagerPage certPage;
	private SitePage sitePage;
	/** 升级页当前绑定的实例(切换实例时重建该页) */
	private NginxInstanceEntry upgradeEntry;
	/** Nginx 状态 / 日志查看: 内容区页面(替代原监控与日志弹窗) */
	private MonitorPage monitorPage;
	private LogPage logPage;
	/** 配置文件编辑页 */
	private ConfPage confPage;
	/** 配置示例页面 */
	private ConfigTemplatesPage templatesPage;

	/** 轻提示浮层: 右下角, 约 2.2s 后淡出 */
	private JPanel toastPanel;
	private JLabel toastLabel;
	private Timer toastTimer;
	private int toastTicks;

	// ===== 防重复触发: 高危动作执行期互斥 + 结束后冷却 =====
	/** 启停/重启的冷却时间: 结束后 1s 内拒绝再次触发 */
	private static final long ACTION_COOLDOWN_MS = 1000L;
	/** 手动检测的冷却时间: 结束后 2s 内拒绝再次触发 */
	private static final long REFRESH_COOLDOWN_MS = 2000L;
	/** 启停/重启执行中 */
	private boolean controlBusy;
	private long controlDoneAt;
	/** 配置检测执行中 */
	private boolean testBusy;
	private long testDoneAt;
	/** 全量采集单飞: 执行中再次触发只记一次待执行, 避免并发检测 */
	private boolean refreshing;
	private boolean refreshQueued;
	private long refreshDoneAt;
	/** 5 秒轻量探测得到的运行状态(按实例 id); 全量检测后重置, 外部停止可在 10 秒内纠正 */
	private final java.util.Map<String, Boolean> lightStatus = new java.util.concurrent.ConcurrentHashMap<>();
	private static final long LIGHT_PROBE_SECONDS = 5L;
	private static final long FOCUS_FULL_REFRESH_MS = 30000L;

	private JButton btnStart, btnStop, btnRestart;
	/** 配置与证书操作: 入口移到左侧导航「配置中心」「证书管理」 */
	private AbstractButton btnConf, btnCert, btnUpgrade;
	/** 实例属性/安装操作: 安装入口在左侧导航「Nginx 实例」分组, 添加已有实例在页头「⋯」菜单 */
	private AbstractButton btnInstall, btnIgnore, btnRename, btnOpenDir;

	// ===== 侧边栏 Shell =====
	private static final String PAGE_OVERVIEW = "overview";
	private static final String PAGE_INSTANCES = "instances";
	private static final String PAGE_UPGRADE = "upgrade";
	private static final String PAGE_INSTALL = "install";
	private static final String PAGE_CERT = "cert";
	private static final String PAGE_SITES = "sites";
	private static final String PAGE_STATUS = "status";
	private static final String PAGE_LOG = "log";
	private static final String PAGE_CONF = "conf";
	private static final String PAGE_TEMPLATES = "templates";

	/** 禁用原因: 未选择实例 / 实例已忽略 / 启停执行中(悬停与读屏可见) */
	private static final String REASON_NEED_SELECT = "未选择实例：先在上方列表中选择一个实例";
	private static final String REASON_IGNORED = "该实例已忽略：点击「恢复实例」后可操作";
	private static final String REASON_CONTROL_BUSY = "正在执行启停操作，请等待本次操作完成";
	private SideNav sideNav;
	/** 页面容器: 概览 / 实例列表 */
	private CardLayout pageLayout;
	private JPanel pageCards;
	private OverviewPanel overviewPanel;
	private String currentPage = PAGE_OVERVIEW;
	private final String uiStateFile = home + File.separator + "ui-state.json";
	/** 最近一次使用的界面缩放因子(跨屏 DPI 变化检测) */
	private double uiScale = -1;

	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "nginxui-win-refresh");
		t.setDaemon(true);
		return t;
	});

	public MainFrame() {
		super("nginxUI - Nginx 本地管理");
		// 升级缓存/历史跟工具目录走(绿色软件), NginxPackageManager 等按此属性定位
		System.setProperty("nginxui.home", home);
		// 关闭前需确认配置编辑页的未保存修改, 因此由 windowClosing 自行退出
		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		setSize(1180, 700);
		setMinimumSize(new Dimension(920, 560));
		setLocationRelativeTo(null);
		// 窗口/任务栏图标: 与 exe 图标同源(打包时由 jpackage --icon 写入 launcher)
		Theme.applyWindowIcon(this);
		// 品牌上移标题栏, 侧栏不再重复品牌区
		installTitleBarBrand();

		registry = loadRegistry();
		ignoreStore = loadIgnoreStore();

		initUi();
		installShortcuts();
		installFocusRefresh();
		installDpiListener();
		OperationLock.addListener(this::updateButtonState);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				// 配置编辑页有未保存修改时, 先确认再退出
				if (confPage != null && !confPage.confirmLeave()) {
					return;
				}
				saveRegistry();
				saveIgnoreStore();
				dispose();
				System.exit(0);
			}
		});

		// 先用已加载的注册表渲染一遍: 首屏立即可见实例, 状态随后台检测修正
		renderRegistry();
		scheduleRefresh();
	}

	private String resolveHome() {
		// 允许 JVM 显式指定: -Dnginxui.home=... (开发/测试用)
		String dir = System.getProperty("nginxui.home");
		if (dir != null && !dir.isEmpty()) {
			File d = new File(dir);
			if (!d.exists()) d.mkdirs();
			return d.getAbsolutePath();
		}
		// 1) 优先: app 所在目录 (绿色软件, 注册表跟 exe 走)
		//    jpackage: codeSource -> dist/nginxUI/app/nginxui-win.jar -> parent*2 = dist/nginxUI/
		//    fat jar:  codeSource -> target/nginxui-win.jar           -> parent   = target/
		//    IDE run:  codeSource -> target/classes/...              -> parent*2 = 项目根
		try {
			java.net.URL loc = MainFrame.class.getProtectionDomain()
					.getCodeSource().getLocation();
			if (loc != null) {
				File codeFile = new File(loc.toURI());
				File parent = codeFile.getParentFile();
				while (parent != null && parent.getName().equalsIgnoreCase("app")) {
					parent = parent.getParentFile();
				}
				if (parent == null) {
					parent = codeFile.getParentFile();
				}
				if (parent != null) {
					if (!parent.exists()) parent.mkdirs();
					return parent.getAbsolutePath();
				}
			}
		} catch (Exception ignored) {
		}
		// 2) 兜底: user.dir (当前工作目录)
		dir = System.getProperty("user.dir");
		if (dir == null || dir.isEmpty()) {
			dir = System.getProperty("java.io.tmpdir");
		}
		try {
			File d = new File(dir);
			if (!d.exists()) d.mkdirs();
		} catch (Exception ignored) {
		}
		return dir;
	}

	private NginxInstanceRegistry loadRegistry() {
		try {
			NginxInstanceRegistry loaded = JsonFileStore.read(new File(registryFile),
					NginxInstanceRegistry::parseStrict);
			if (loaded != null) {
				return loaded;
			}
		} catch (Exception e) {
			LOG.error("加载注册表失败, 使用空注册表: {}", registryFile, e);
		}
		return new NginxInstanceRegistry();
	}

	private void saveRegistry() {
		try {
			JsonFileStore.write(new File(registryFile), registry.toJson());
		} catch (Exception e) {
			LOG.error("保存注册表失败: {}", registryFile, e);
		}
	}

	private NginxInstanceIgnoreStore loadIgnoreStore() {
		try {
			NginxInstanceIgnoreStore loaded = JsonFileStore.read(new File(ignoreFile),
					NginxInstanceIgnoreStore::parseStrict);
			if (loaded != null) {
				return loaded;
			}
		} catch (Exception e) {
			LOG.error("加载忽略名单失败, 使用空名单: {}", ignoreFile, e);
		}
		return new NginxInstanceIgnoreStore();
	}

	private void saveIgnoreStore() {
		try {
			JsonFileStore.write(new File(ignoreFile), ignoreStore.toJson());
		} catch (Exception e) {
			LOG.error("保存忽略名单失败: {}", ignoreFile, e);
		}
	}

	/** 读取注册表原始 JSON(用于合并); 损坏时回退备份, 仍失败则按空表处理 */
	private String readRegistryJson() {
		try {
			String json = JsonFileStore.read(new File(registryFile), raw -> {
				NginxInstanceRegistry.parseStrict(raw);
				return raw;
			});
			return json == null ? "" : json;
		} catch (Exception e) {
			LOG.error("读取注册表失败, 以空表合并: {}", registryFile, e);
			return "";
		}
	}

	// ==================== UI ====================

	private void initUi() {
		// 实例级动作(启停/重启/改名/目录/忽略)统一收进实例列表卡片内
		btnStart = Theme.compactButton("启动");
		btnStop = Theme.compactButton("停止");
		btnRestart = Theme.compactButton("重启");
		// 配置/证书/实例管理入口移到左侧导航, 这里只保留动作按钮与启用状态
		btnConf = Theme.menuItem("编辑配置…");
		btnCert = Theme.menuItem("证书管理");
		btnUpgrade = Theme.menuItem("升级 / 回滚…");
		btnInstall = Theme.menuItem("安装实例…");
		btnOpenDir = Theme.compactButton("打开安装目录");
		btnRename = Theme.compactButton("编辑别名");
		btnIgnore = Theme.compactButton("忽略实例");

		btnInstall.setToolTipText("在线下载或上传官方安装包，全新安装一个 nginx 实例");
		btnStart.setToolTipText("启动所选实例（多选时批量执行）");
		btnStop.setToolTipText("停止所选实例（多选时批量执行）");
		btnRestart.setToolTipText("重启所选实例（多选时批量执行）");
		btnConf.setToolTipText("编辑所选实例的 nginx.conf");
		btnCert.setToolTipText("总览所有实例的 SSL 证书与到期状态，一键更换证书(自动备份、校验并支持回滚)");
		btnUpgrade.setToolTipText("升级/回滚该实例的 nginx 程序版本(保留配置与站点)");
		btnOpenDir.setToolTipText("打开该实例的安装目录");
		btnRename.setToolTipText("修改实例别名");
		btnIgnore.setToolTipText("忽略后不再纳管该实例(状态仍会显示)；已忽略时可恢复");

		Theme.setEnabled(btnStart, false, REASON_NEED_SELECT);
		Theme.setEnabled(btnStop, false, REASON_NEED_SELECT);
		Theme.setEnabled(btnRestart, false, REASON_NEED_SELECT);
		Theme.setEnabled(btnConf, false, REASON_NEED_SELECT);
		Theme.setEnabled(btnCert, true, null);
		Theme.setEnabled(btnUpgrade, false, REASON_NEED_SELECT);
		Theme.setEnabled(btnOpenDir, false, REASON_NEED_SELECT);
		Theme.setEnabled(btnIgnore, false, REASON_NEED_SELECT);
		Theme.setEnabled(btnRename, false, REASON_NEED_SELECT);

		btnInstall.addActionListener(e -> openInstallDialog());
		btnIgnore.addActionListener(e -> toggleIgnore());
		btnRename.addActionListener(e -> renameSelected());
		btnStart.addActionListener(e -> controlSelected("start"));
		btnStop.addActionListener(e -> controlSelected("stop"));
		btnRestart.addActionListener(e -> controlSelected("restart"));
		btnConf.addActionListener(e -> openConfPage());
		btnCert.addActionListener(e -> openCertManagerDialog());
		btnUpgrade.addActionListener(e -> openUpgradeDialog());
		btnOpenDir.addActionListener(e -> openInstanceDir());

		sideNav = new SideNav();
		buildSideNav();

		// ===== 实例列表: 双行单元格(状态点+名称 / 服务·PID) =====
		listModel = new DefaultListModel<>();
		instanceList = new JList<>(listModel);
		instanceList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
		instanceList.setFixedCellHeight(Theme.LIST_ROW_HEIGHT);
		instanceList.setBackground(Theme.SURFACE);
		instanceList.setCellRenderer(new InstanceRowRenderer());
		// 列表仅作为"选中态/动作可用性"的数据源(不再展示), 详情由实例总览页自行加载
		instanceList.addListSelectionListener(e -> updateButtonState());
		instanceList.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
			@Override
			public void mouseMoved(java.awt.event.MouseEvent e) {
				int idx = instanceList.locationToIndex(e.getPoint());
				java.awt.Rectangle cell = idx < 0 ? null : instanceList.getCellBounds(idx, idx);
				if (cell == null || !cell.contains(e.getPoint())) {
					idx = -1;
				}
				if (idx != hoverIndex) {
					hoverIndex = idx;
					instanceList.repaint();
				}
			}
		});
		instanceList.addMouseListener(new java.awt.event.MouseAdapter() {
			@Override
			public void mouseExited(java.awt.event.MouseEvent e) {
				if (hoverIndex != -1) {
					hoverIndex = -1;
					instanceList.repaint();
				}
			}
		});
		javax.swing.ToolTipManager.sharedInstance().registerComponent(instanceList);

		JScrollPane listScroll = new JScrollPane(instanceList);
		listScroll.setBackground(Theme.SURFACE);
		listScroll.setBorder(null);

		// 列表上方: 搜索 + 状态筛选, 空结果时显示空态提示
		txtSearch = new JTextField();
		txtSearch.setFont(Theme.BODY);
		txtSearch.putClientProperty("JTextField.placeholderText", "搜索名称 / 服务 / 路径");
		txtSearch.putClientProperty("JTextField.showClearButton", true);
		txtSearch.setPreferredSize(new Dimension(190, Theme.BUTTON_HEIGHT));
		txtSearch.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				reRender();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				reRender();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				reRender();
			}
		});

		cbFilter = new JComboBox<String>(
				new String[] { "全部状态", "运行中", "已停止", "已忽略" });
		cbFilter.setFont(Theme.BODY);
		cbFilter.setPreferredSize(new Dimension(104, Theme.BUTTON_HEIGHT));
		cbFilter.addActionListener(e -> reRender());

		JPanel filterBar = new JPanel(new BorderLayout(Theme.GAP_S, 0));
		filterBar.setOpaque(false);
		filterBar.add(txtSearch, BorderLayout.CENTER);
		filterBar.add(cbFilter, BorderLayout.EAST);

		lblEmpty = Theme.label("", Theme.BODY, Theme.TEXT_MUTED);
		lblEmpty.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
		JPanel emptyPanel = new JPanel(new BorderLayout());
		emptyPanel.setOpaque(false);
		emptyPanel.add(lblEmpty, BorderLayout.CENTER);

		listCards = new CardLayout();
		listCardsPanel = new JPanel(listCards);
		listCardsPanel.setBackground(Theme.SURFACE);
		listCardsPanel.add(listScroll, "list");
		listCardsPanel.add(emptyPanel, "empty");

		JPanel listPanel = new JPanel(new BorderLayout(0, Theme.GAP_S));
		listPanel.setOpaque(false);
		listPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 6, 8));
		listPanel.add(filterBar, BorderLayout.NORTH);
		listPanel.add(listCardsPanel, BorderLayout.CENTER);

		// 卡片标题栏: 左侧栏目名, 右侧启动/停止/重启(动作与列表同处一个区域)
		JPanel listCommands = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.GAP_XS, 0));
		listCommands.setOpaque(false);
		listCommands.add(btnStart);
		listCommands.add(btnStop);
		listCommands.add(btnRestart);
		JPanel listHeader = new JPanel(new BorderLayout(Theme.GAP_S, 0));
		listHeader.setOpaque(false);
		listHeader.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER),
				BorderFactory.createEmptyBorder(8, 10, 8, 10)));
		listHeader.add(Theme.label("本机 Nginx 实例", Theme.BODY_BOLD, Theme.TEXT), BorderLayout.WEST);
		listHeader.add(listCommands, BorderLayout.EAST);

		// 列表底部: 针对所选实例的行操作, 未选中/已忽略时由 updateButtonState 统一置灰
		JPanel rowActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.GAP_XS, 0));
		rowActions.setOpaque(false);
		rowActions.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER),
				BorderFactory.createEmptyBorder(6, 10, 6, 10)));
		rowActions.add(btnRename);
		rowActions.add(btnOpenDir);
		rowActions.add(btnIgnore);

		JPanel listBox = new JPanel(new BorderLayout());
		listBox.setBackground(Theme.SURFACE);
		listBox.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
		listBox.add(listHeader, BorderLayout.NORTH);
		listBox.add(listPanel, BorderLayout.CENTER);
		listBox.add(rowActions, BorderLayout.SOUTH);

		// ===== 状态栏: 状态 | 实例数 | 最后刷新时间 =====
		lblStatus = Theme.label("就绪", Theme.SMALL, Theme.TEXT_MUTED);
		// 右侧留 4px: JLabel 文字宽度与标签宽度完全相等时末字符会被抗锯齿裁掉
		lblStatus.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));
		lblCount = Theme.label("—", Theme.SMALL, Theme.TEXT_MUTED);
		lblLastRefresh = Theme.label("", Theme.SMALL, Theme.TEXT_MUTED);
		JPanel statusRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.GAP_M, 0));
		statusRight.setOpaque(false);
		statusRight.add(lblCount);
		statusRight.add(lblLastRefresh);

		JPanel statusBar = new JPanel(new BorderLayout(Theme.GAP_M, 0));
		statusBar.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER),
				BorderFactory.createEmptyBorder(4, Theme.GAP_M, 4, Theme.GAP_M)));
		statusBar.add(lblStatus, BorderLayout.WEST);
		statusBar.add(statusRight, BorderLayout.EAST);

		// ===== 页面容器: 概览 / 实例列表(原列表+详情整体作为一页) =====
		overviewPanel = new OverviewPanel(this::openInstallDialog,
				this::openUpgradeDialog, this::openCertManagerDialog, this::openInstancePage);
		pageLayout = new CardLayout();
		pageCards = new JPanel(pageLayout);
		pageCards.add(overviewPanel, PAGE_OVERVIEW);
		instancePage = new InstanceOverviewPanel(this);
		pageCards.add(instancePage, PAGE_INSTANCES);
		sitePage = new SitePage(this);
		pageCards.add(sitePage, PAGE_SITES);
		monitorPage = new MonitorPage(this);
		pageCards.add(monitorPage, PAGE_STATUS);
		logPage = new LogPage(this);
		pageCards.add(logPage, PAGE_LOG);
		confPage = new ConfPage(this);
		pageCards.add(confPage, PAGE_CONF);
		templatesPage = new ConfigTemplatesPage();
		pageCards.add(templatesPage, PAGE_TEMPLATES);

		JPanel center = new JPanel(new BorderLayout());
		center.add(pageCards, BorderLayout.CENTER);

		setLayout(new BorderLayout());
		// 侧栏放入滚动容器: 层级分组后内容变高, 矮窗口下滚动而不是裁掉末尾条目
		JScrollPane navScroll = new JScrollPane(sideNav,
				JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
				JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		navScroll.setBorder(null);
		navScroll.setBackground(Theme.BG);
		navScroll.getViewport().setBackground(Theme.BG);
		navScroll.getVerticalScrollBar().setUnitIncrement(18);
		// 品牌已在标题栏左侧展示, 侧栏直接从导航条目开始, 多出一屏内容高度
		navScroll.setPreferredSize(new Dimension(SideNav.EXPANDED_WIDTH, 10));
		add(navScroll, BorderLayout.WEST);
		add(center, BorderLayout.CENTER);
		add(statusBar, BorderLayout.SOUTH);
		restoreUiState();

		installToast();
	}

	/**
	 * 品牌上移标题栏: 用 FlatLaf 内嵌菜单栏承载标题栏左侧的品牌条,
	 * 同时隐藏原生标题文字与图标, 使品牌只在标题栏出现一次.
	 */
	private void installTitleBarBrand() {
		JMenuBar bar = new JMenuBar();
		bar.setOpaque(false);
		bar.setBorder(BorderFactory.createEmptyBorder());
		bar.setLayout(new BorderLayout());
		bar.add(SideNav.createBrandStrip(), BorderLayout.WEST);
		setJMenuBar(bar);

		javax.swing.JRootPane root = getRootPane();
		root.putClientProperty("JRootPane.menuBarEmbedded", true);
		root.putClientProperty("JRootPane.titleBarShowTitle", false);
		root.putClientProperty("JRootPane.titleBarShowIcon", false);
	}

	// ==================== 侧边栏与页面切换 ====================

	/** 左侧导航: 分组与条目对齐 nginxUI Web 版(总览 / Nginx 实例 / 证书 / 配置 / 监控 / 系统) */
	private void buildSideNav() {
		sideNav.addTopItem("nav-overview", "总览", SideNav.IC_OVERVIEW, () -> true,
				() -> showPage(PAGE_OVERVIEW));
		sideNav.addGroup("Nginx 实例");
		sideNav.addItem("nav-instances", "实例总览", SideNav.IC_INSTANCES, () -> true,
				() -> showPage(PAGE_INSTANCES));
		sideNav.addItem("nav-upgrade", "版本升级", SideNav.IC_UPGRADE, btnUpgrade::isEnabled,
				this::openUpgradeDialog);
		sideNav.addItem("nav-install", "安装实例", SideNav.IC_INSTALL, () -> true,
				this::openInstallDialog);

		sideNav.addGroup("站点与证书");
		sideNav.addItem("nav-sites", "站点管理", SideNav.IC_SITE, () -> true,
				() -> showPage(PAGE_SITES));
		sideNav.addItem("nav-cert", "证书管理", SideNav.IC_CERT, () -> true,
				this::openCertManagerDialog);

		sideNav.addGroup("配置中心");
		sideNav.addItem("nav-conf", "配置文件编辑", SideNav.IC_CONFIG, btnConf::isEnabled,
				this::openConfPage);
		sideNav.addItem("nav-templates", "配置示例", SideNav.IC_CONFIG, () -> true,
				() -> showPage(PAGE_TEMPLATES));

		sideNav.addGroup("监控与日志");
		sideNav.addItem("nav-status", "Nginx 状态", SideNav.IC_MONITOR, () -> true,
				() -> showPage(PAGE_STATUS));
		sideNav.addItem("nav-log", "日志查看", SideNav.IC_LOG, () -> true,
				() -> showPage(PAGE_LOG));

		sideNav.addGroup("系统");
		sideNav.addItem("nav-about", "关于", SideNav.IC_ABOUT, () -> true, this::openAboutDialog);
		sideNav.select("nav-overview");
	}

	/** 切换页面并同步标题/导航选中态/界面状态记忆; 返回是否真正完成切换 */
	private boolean showPage(String page) {
		if (pageCards == null || pageLayout == null) {
			return false;
		}
		// 离开配置编辑页前确认未保存的修改, 取消则停留在当前页
		if (PAGE_CONF.equals(currentPage) && !PAGE_CONF.equals(page)
				&& confPage != null && !confPage.confirmLeave()) {
			return false;
		}
		currentPage = page;
		pageLayout.show(pageCards, page);
		if (PAGE_OVERVIEW.equals(page)) {
			sideNav.select("nav-overview");
		} else if (PAGE_INSTANCES.equals(page)) {
			sideNav.select("nav-instances");
			if (instancePage != null) {
				instancePage.onShown();
			}
		} else if (PAGE_UPGRADE.equals(page)) {
			sideNav.select("nav-upgrade");
		} else if (PAGE_INSTALL.equals(page)) {
			sideNav.select("nav-install");
		} else if (PAGE_CERT.equals(page)) {
			sideNav.select("nav-cert");
			if (certPage != null) {
				certPage.onShown();
			}
		} else if (PAGE_SITES.equals(page)) {
			sideNav.select("nav-sites");
			if (sitePage != null) {
				sitePage.onShown();
			}
		} else if (PAGE_STATUS.equals(page)) {
			sideNav.select("nav-status");
			if (monitorPage != null) {
				monitorPage.onShown();
			}
		} else if (PAGE_LOG.equals(page)) {
			sideNav.select("nav-log");
			if (logPage != null) {
				logPage.onShown();
			}
		} else if (PAGE_CONF.equals(page)) {
			sideNav.select("nav-conf");
			if (confPage != null) {
				confPage.onShown();
			}
		} else if (PAGE_TEMPLATES.equals(page)) {
			sideNav.select("nav-templates");
			if (templatesPage != null) {
				templatesPage.refreshState();
			}
		}
		saveUiState();
		return true;
	}

	/** 页面内「关闭」回到实例列表(替代原弹窗关闭) */
	public void returnToInstances() {
		showPage(PAGE_INSTANCES);
	}

	/** 界面状态记忆: 当前页面(重启后回到上次位置) */
	private void restoreUiState() {
		String page = PAGE_OVERVIEW;
		try {
			String saved = JsonFileStore.read(new File(uiStateFile), json -> {
				cn.hutool.json.JSONObject o = cn.hutool.json.JSONUtil.parseObj(json);
				return o.getStr("page", PAGE_OVERVIEW);
			});
			if (saved != null) {
				page = saved;
			}
		} catch (Exception e) {
			LOG.warn("读取界面状态失败: {} ({})", uiStateFile, e.getMessage());
		}
		// 升级页绑定具体实例, 不做跨启动恢复
		if (!PAGE_INSTANCES.equals(page) && !PAGE_SITES.equals(page)) {
			page = PAGE_OVERVIEW;
		}
		if (PAGE_INSTANCES.equals(page) && instancePage == null) {
			page = PAGE_OVERVIEW;
		}
		if (PAGE_SITES.equals(page) && sitePage == null) {
			page = PAGE_OVERVIEW;
		}
		showPage(page);
	}

	private void saveUiState() {
		try {
			cn.hutool.json.JSONObject o = new cn.hutool.json.JSONObject();
			o.set("page", currentPage);
			JsonFileStore.write(new File(uiStateFile), o.toStringPretty());
		} catch (Exception e) {
			LOG.warn("保存界面状态失败: {} ({})", uiStateFile, e.getMessage());
		}
	}

	// ==================== 轻提示 ====================

	private static final int TOAST_ALPHA = 240;
	/** 保持时长(约 2.2s), 之后每 60ms 淡出一档 */
	private static final int TOAST_HOLD_TICKS = 36;

	private void installToast() {
		toastLabel = new JLabel(" ");
		toastLabel.setFont(Theme.BODY);
		toastLabel.setForeground(Color.WHITE);
		toastLabel.setBorder(BorderFactory.createEmptyBorder(7, 14, 7, 14));

		toastPanel = new JPanel(new BorderLayout()) {
			private static final long serialVersionUID = 1L;

			@Override
			protected void paintComponent(Graphics g) {
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(getBackground());
				g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
				g2.dispose();
			}
		};
		toastPanel.setOpaque(false);
		toastPanel.add(toastLabel, BorderLayout.CENTER);
		toastPanel.setVisible(false);
		getLayeredPane().add(toastPanel, JLayeredPane.POPUP_LAYER);

		toastTimer = new Timer(60, e -> {
			toastTicks++;
			int alpha = Math.max(0, Math.min(TOAST_ALPHA, TOAST_ALPHA - toastTicks * 20));
			if (alpha == 0) {
				toastTimer.stop();
				toastPanel.setVisible(false);
				return;
			}
			toastPanel.setBackground(new Color(0x2F, 0x36, 0x3D, alpha));
			toastPanel.repaint();
		});

		addComponentListener(new ComponentAdapter() {
			@Override
			public void componentResized(ComponentEvent e) {
				if (toastPanel.isVisible()) {
					positionToast();
				}
			}
		});
	}

	/** 非模态轻提示: 不打断操作, 也不占据状态栏 */
	private void toast(String message) {
		if (toastPanel == null || message == null) {
			return;
		}
		toastLabel.setText(message);
		toastPanel.setToolTipText(message);
		toastTicks = -TOAST_HOLD_TICKS;
		toastPanel.setBackground(new Color(0x2F, 0x36, 0x3D, TOAST_ALPHA));
		toastPanel.setVisible(true);
		positionToast();
		toastTimer.restart();
	}

	private void positionToast() {
		java.awt.Dimension pref = toastPanel.getPreferredSize();
		int maxW = Math.max(120, getContentPane().getWidth() - 40);
		int w = Math.min(pref.width, maxW);
		toastPanel.setSize(w, pref.height);
		int x = getContentPane().getWidth() - w - Theme.GAP_L;
		int y = getContentPane().getHeight() - pref.height - 40;
		toastPanel.setLocation(Math.max(Theme.GAP_S, x), Math.max(Theme.GAP_S, y));
	}

	private void updateButtonState() {
		InstanceRow lead = selectedRow();
		List<InstanceRow> rows = selectedRows();
		int actionable = 0;
		for (InstanceRow r : rows) {
			if (!r.ignored) actionable++;
		}
		boolean sel = lead != null;
		// 操作执行中禁用对应按钮: 避免用户在等待期间重复触发(选中变化也会重算, 不能只依赖点击入口)
		boolean controlIdle = !controlBusy && !OperationLock.isBusy();
		// 启停/重启支持多选批量执行
		String controlReason;
		if (!controlIdle) {
			controlReason = controlBusy ? REASON_CONTROL_BUSY : OperationLock.reason();
		} else if (!sel) {
			controlReason = REASON_NEED_SELECT;
		} else if (actionable == 0) {
			controlReason = "所选实例已忽略，不参与启停：点击「恢复实例」后可操作";
		} else {
			controlReason = null;
		}
		Theme.setEnabled(btnStart, controlReason == null, controlReason);
		Theme.setEnabled(btnStop, controlReason == null, controlReason);
		Theme.setEnabled(btnRestart, controlReason == null, controlReason);
		String singleReason = !sel ? REASON_NEED_SELECT : (lead.ignored ? REASON_IGNORED : null);
		Theme.setEnabled(btnConf, singleReason == null, singleReason);
		Theme.setEnabled(btnUpgrade, singleReason == null, singleReason);
		Theme.setEnabled(btnOpenDir, singleReason == null, singleReason);
		String selectReason = sel ? null : REASON_NEED_SELECT;
		Theme.setEnabled(btnRename, selectReason == null, selectReason);
		Theme.setEnabled(btnIgnore, selectReason == null, selectReason);
		btnIgnore.setText(sel && lead.ignored ? "恢复实例" : "忽略实例");
		// 多选时在按钮上标出将影响的实例数, 让批量操作范围可见
		String suffix = actionable > 1 ? " (" + actionable + ")" : "";
		btnStart.setText("启动" + suffix);
		btnStop.setText("停止" + suffix);
		btnRestart.setText("重启" + suffix);
		// 侧边栏条目跟随同一套启用状态(未选实例时配置/升级等置灰)
		if (sideNav != null) {
			sideNav.refreshEnabledState();
		}
	}

	// ==================== 刷新 ====================

	private void scheduleRefresh() {
		refreshAsync();
		// 定时刷新(30秒一次全量: 服务列表 + 进程检测 + 注册表归并)
		scheduler.scheduleAtFixedRate(this::refreshQuiet, 30, 30, TimeUnit.SECONDS);
		// 轻量状态探测(5秒): 只查 SCM 状态与已记录 master pid, 外部停止可在 10 秒内反映
		scheduler.scheduleAtFixedRate(this::probeLightStatus, LIGHT_PROBE_SECONDS,
				LIGHT_PROBE_SECONDS, TimeUnit.SECONDS);
	}

	private void triggerDetect() {
		// 防重复触发: 采集进行中或结束不足 2s 时忽略, 避免连点 F5 造成并发全量检测
		if (refreshing || System.currentTimeMillis() - refreshDoneAt < REFRESH_COOLDOWN_MS) {
			lblStatus.setText("正在检测中，请稍候…");
			return;
		}
		lblStatus.setText("正在检测本机 Nginx 实例…");
		refreshAsync();
	}

	/** 一轮刷新的后台数据: SCM服务列表 + 运行中实例 + 一次进程检测索引 */
	static class RefreshData {
		List<WindowsNginxServiceUtils.WinNginxService> services = new ArrayList<>();
		List<NginxInfo> live;
		/** 规范化exe路径 -> 本轮运行指纹集合(注册表归并时区分目录迁移与多实例并存) */
		java.util.Map<String, java.util.Set<String>> liveExeFids = new java.util.HashMap<>();
		NginxDashboardUtils.LiveIndex liveIndex;
		/** 实例id -> 主配置声明的监听端口(总览页展示用, 每轮按需解析一次) */
		java.util.Map<String, java.util.List<String>> listenPorts = new java.util.HashMap<>();
	}

	/**
	 * 采集本机实例: 只从两个有界数据源取数, 不再遍历磁盘.
	 * 1) SCM 服务注册表(含已停止的服务); 2) 正在运行的 nginx 进程.
	 */
	private RefreshData collectRuntimeState() {
		RefreshData data = new RefreshData();
		try {
			data.services = WindowsNginxServiceUtils.list();
		} catch (Exception ignored) {
		}
		try {
			data.live = NginxUtils.detectAll(data.services).getInstances();
		} catch (Exception ignored) {
		}
		if (data.live != null) {
			for (NginxInfo info : data.live) {
				if (StrUtil.isEmpty(info.getNginxPath()) || !new File(info.getNginxPath()).isFile()) {
					continue;
				}
				String fid = NginxInstanceRegistry.fingerprint(info.getNginxDir(), info.getNginxPath());
				if (StrUtil.isEmpty(fid)) {
					continue;
				}
				String exe = NginxInstanceRegistry.canonical(info.getNginxExe());
				if (StrUtil.isNotEmpty(exe)) {
					java.util.Set<String> set = data.liveExeFids.get(exe);
					if (set == null) {
						set = new java.util.HashSet<String>();
						data.liveExeFids.put(exe, set);
					}
					set.add(fid);
				}
			}
		}
		data.liveIndex = NginxDashboardUtils.buildLiveIndex(data.live);
		return data;
	}

	private void refreshAsync() {
		// 单飞: 采集进行中时只记一次待执行, 完成后补跑一轮, 避免多轮全量采集并发
		if (refreshing) {
			refreshQueued = true;
			return;
		}
		refreshing = true;
		final long start = System.currentTimeMillis();
		new SwingWorker<RefreshData, Void>() {
			@Override
			protected RefreshData doInBackground() {
				RefreshData data = collectRuntimeState();
				LOG.info("刷新: 采集={}ms, 运行实例={}, 服务={}", System.currentTimeMillis() - start,
						data.live == null ? 0 : data.live.size(), data.services == null ? 0 : data.services.size());
				return data;
			}

			@Override
			protected void done() {
				refreshing = false;
				refreshDoneAt = System.currentTimeMillis();
				try {
					applyResult(get());
				} catch (Exception ex) {
					lblStatus.setText("检测失败，请稍后重试（详情见日志）");
				}
				if (refreshQueued) {
					refreshQueued = false;
					refreshAsync();
				}
			}
		}.execute();
	}

	private void refreshQuiet() {
		if (!EventQueue.isDispatchThread()) {
			EventQueue.invokeLater(this::refreshQuiet);
			return;
		}
		refreshAsync();
	}

	/**
	 * 轻量状态探测: 服务实例查 SCM, 进程实例查上次记录的 master pid.
	 * 不触发进程枚举与注册表合并, 用于满足"外部启停 10 秒内可见"的验收要求;
	 * 运行状态变化时重绘列表与概览, 不更新"最后刷新"时间.
	 */
	private void probeLightStatus() {
		if (!EventQueue.isDispatchThread()) {
			EventQueue.invokeLater(this::probeLightStatus);
			return;
		}
		if (refreshing || lastData == null) {
			return;
		}
		java.util.Map<String, Boolean> status = new java.util.HashMap<>();
		for (NginxInstanceEntry entry : registry.entries()) {
			if (ignoreStore.containsEntry(entry)) {
				status.put(entry.getId(), false);
				continue;
			}
			status.put(entry.getId(), probeRunning(entry));
		}
		applyLightStatus(status);
	}

	/** 单个实例的运行判定: 服务走 SCM; 进程走 PID 存活; 无 pid 时沿用上次结论 */
	private boolean probeRunning(NginxInstanceEntry entry) {
		if (StrUtil.isNotEmpty(entry.getWinServiceName())) {
			return WindowsNginxServiceUtils.queryState(entry.getWinServiceName()) == Winsvc.SERVICE_RUNNING;
		}
		String pid = StrUtil.nullToEmpty(entry.getLastMasterPid()).trim();
		if (!pid.isEmpty()) {
			try {
				long value = Long.parseLong(pid);
				return ProcessHandle.of(value).map(ProcessHandle::isAlive).orElse(false);
			} catch (NumberFormatException ignored) {
				// pid 非数字: 回退到上次状态
			}
		}
		return lightStatus.getOrDefault(entry.getId(), false);
	}

	/** 轻量状态变化才重绘, 避免每 5 秒无谓重建列表 */
	private void applyLightStatus(java.util.Map<String, Boolean> status) {
		boolean changed = false;
		for (NginxInstanceEntry entry : registry.entries()) {
			if (ignoreStore.containsEntry(entry)) {
				continue;
			}
			if (Boolean.valueOf(status.get(entry.getId())).equals(lightStatus.get(entry.getId()))) {
				continue;
			}
			changed = true;
		}
		lightStatus.putAll(status);
		if (changed) {
			rebuildList(lastData, selectedId());
		}
	}

	/** 全量检测完成后以本轮快照重置轻量状态, 保证两侧口径一致 */
	private void syncLightStatus(RefreshData data) {
		lightStatus.clear();
		for (NginxInstanceEntry entry : registry.entries()) {
			lightStatus.put(entry.getId(), isRunningFromSnapshot(entry, data));
		}
	}

	private void installFocusRefresh() {
		addWindowFocusListener(new WindowAdapter() {
			@Override
			public void windowGainedFocus(WindowEvent e) {
				// 聚焦即做一次轻量探测; 距上次全量检测超过 30 秒再补一轮全量
				probeLightStatus();
				if (System.currentTimeMillis() - refreshDoneAt > FOCUS_FULL_REFRESH_MS) {
					refreshAsync();
				}
			}
		});
	}

	/** 首屏渲染: 直接展示注册表里的实例(状态由后台刷新修正) */
	private void renderRegistry() {
		listModel.clear();
		int total = registry.entries().size();
		for (NginxInstanceEntry entry : registry.entries()) {
			InstanceRow row = new InstanceRow(entry, false, true, ignoreStore.containsEntry(entry));
			if (matchesFilter(row)) {
				listModel.addElement(row);
			}
		}
		setCountLabel(listModel.size(), total);
		lblLastRefresh.setText("最后刷新 —");
		lblStatus.setText("正在检测本机 Nginx 实例…");
		updateOverviewStats(total, total, false);
		pushOverviewStatuses(null);
		updateButtonState();
		updateEmptyState();
		if (instancePage != null) {
			instancePage.onRowsChanged();
		}
	}

	/** 状态栏实例计数: 筛选生效时显示 "命中 / 总数" */
	private void setCountLabel(int shown, int total) {
		lblCount.setText(shown == total ? total + " 个实例" : shown + " / " + total + " 个实例");
	}

	/** 关键词 + 状态筛选 */
	private boolean matchesFilter(InstanceRow row) {
		int f = cbFilter == null ? 0 : cbFilter.getSelectedIndex();
		if (f == 3) {
			if (!row.ignored) {
				return false;
			}
		} else if (row.ignored) {
			return false;
		} else if (f == 1 && !row.running) {
			return false;
		} else if (f == 2 && (row.running || row.detecting)) {
			return false;
		}
		String q = txtSearch == null ? "" : txtSearch.getText().trim().toLowerCase();
		if (q.isEmpty()) {
			return true;
		}
		StringBuilder sb = new StringBuilder();
		sb.append(InstanceRow.displayName(row.entry)).append(' ');
		sb.append(StrUtil.nullToEmpty(row.entry.getName())).append(' ');
		sb.append(StrUtil.nullToEmpty(row.entry.getWinServiceName())).append(' ');
		sb.append(StrUtil.nullToEmpty(row.entry.getPrefixDir())).append(' ');
		sb.append(StrUtil.nullToEmpty(row.entry.getNginxDir())).append(' ');
		sb.append(StrUtil.nullToEmpty(row.entry.getConfPath())).append(' ');
		sb.append(StrUtil.nullToEmpty(row.entry.getId()));
		return sb.toString().toLowerCase().contains(q);
	}

	/** 列表为空时切换到空态提示(区分"无数据"与"筛选无结果") */
	private void updateEmptyState() {
		if (listCards == null || listCardsPanel == null || lblEmpty == null) {
			return;
		}
		if (!listModel.isEmpty()) {
			listCards.show(listCardsPanel, "list");
			return;
		}
		boolean filtered = (txtSearch != null && !txtSearch.getText().trim().isEmpty())
				|| (cbFilter != null && cbFilter.getSelectedIndex() > 0);
		lblEmpty.setText(filtered ? "没有匹配的实例，试试调整搜索或筛选条件"
				: "尚未发现 Nginx 实例：运行 nginx 后点击「刷新」，或在右上角「⋯」菜单中「添加已有实例」");
		listCards.show(listCardsPanel, "empty");
	}

	/**
	 * 双屏/DPI 切换: 字体度量变化后, 重新应用 LAF 默认值并重排,
	 * 避免按钮、页签、状态栏文字被截断.
	 */
	private void installDpiListener() {
		addComponentListener(new ComponentAdapter() {
			@Override
			public void componentMoved(ComponentEvent e) {
				applyUiScaleIfChanged();
			}
		});
		addPropertyChangeListener("graphicsConfiguration", e -> applyUiScaleIfChanged());
	}

	/** 缩放因子变化(跨不同 DPI 显示器)时, 重新应用 UI 并重排 */
	private void applyUiScaleIfChanged() {
		java.awt.GraphicsConfiguration gc = getGraphicsConfiguration();
		if (gc == null) {
			return;
		}
		double scale = gc.getDefaultTransform().getScaleX();
		if (uiScale < 0) {
			uiScale = scale;
			return;
		}
		if (Math.abs(scale - uiScale) < 0.01) {
			return;
		}
		uiScale = scale;
		FlatLaf.updateUILater();
		EventQueue.invokeLater(() -> {
			revalidate();
			repaint();
		});
	}

	/** 全局快捷键: F5/Ctrl+R 刷新, Ctrl+1 总览, Ctrl+2 实例列表, Ctrl+F 搜索 */
	private void installShortcuts() {
		javax.swing.JRootPane root = getRootPane();
		root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
				.put(KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK), "refreshInstances");
		root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
				.put(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "refreshInstances");
		root.getActionMap().put("refreshInstances", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				triggerDetect();
			}
		});
		root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
				.put(KeyStroke.getKeyStroke(KeyEvent.VK_1, InputEvent.CTRL_DOWN_MASK), "pageOverview");
		root.getActionMap().put("pageOverview", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				showPage(PAGE_OVERVIEW);
			}
		});
		root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
				.put(KeyStroke.getKeyStroke(KeyEvent.VK_2, InputEvent.CTRL_DOWN_MASK), "pageInstances");
		root.getActionMap().put("pageInstances", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				showPage(PAGE_INSTANCES);
			}
		});
		root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
				.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "focusSearch");
		root.getActionMap().put("focusSearch", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				txtSearch.requestFocusInWindow();
				txtSearch.selectAll();
			}
		});
		txtSearch.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clearSearch");
		txtSearch.getActionMap().put("clearSearch", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				txtSearch.setText("");
			}
		});
	}

	private String selectedId() {
		InstanceRow row = selectedRow();
		return row == null ? null : row.entry.getId();
	}

	private InstanceRow selectedRow() {
		Object sel = instanceList.getSelectedValue();
		return sel instanceof InstanceRow ? (InstanceRow) sel : null;
	}

	/** 当前多选的行(批量启停用) */
	private List<InstanceRow> selectedRows() {
		List<InstanceRow> rows = new ArrayList<InstanceRow>();
		for (Object o : instanceList.getSelectedValuesList()) {
			if (o instanceof InstanceRow) {
				rows.add((InstanceRow) o);
			}
		}
		return rows;
	}

	/**
	 * 用采集结果重建列表(维持选中项).
	 * data 为空时按"首屏检测中"渲染, 供刷新尚未完成时的改名/忽略重绘.
	 */
	private void rebuildList(RefreshData data, String prevSelectedId) {
		listModel.clear();
		List<NginxInstanceEntry> entries = registry.entries();
		int running = 0;
		int restoreIdx = -1;
		for (NginxInstanceEntry entry : entries) {
			boolean ignored = ignoreStore.containsEntry(entry);
			boolean isRun = !ignored && data != null && isRunning(entry, data);
			if (isRun) running++;
			InstanceRow row = new InstanceRow(entry, isRun, data == null, ignored);
			if (!matchesFilter(row)) {
				continue;
			}
			listModel.addElement(row);
			if (prevSelectedId != null && prevSelectedId.equals(entry.getId())) {
				restoreIdx = listModel.size() - 1;
			}
		}
		setCountLabel(listModel.size(), entries.size());
		if (data != null) {
			lblStatus.setText(String.format("检测完成：运行 %d / 共 %d", running, entries.size()));
		}
		updateOverviewStats(running, entries.size(), data != null);
		pushOverviewStatuses(data);
		updateButtonState();
		if (restoreIdx >= 0) {
			instanceList.setSelectedIndex(restoreIdx);
		} else if (listModel.size() > 0) {
			instanceList.clearSelection();
		}
		updateEmptyState();
		// 实例总览页跟随同一份数据: 重建下拉并保持当前选中项
		if (instancePage != null) {
			instancePage.onRowsChanged();
		}
		// 状态/日志页可见时同步实例下拉与其内容
		if (monitorPage != null && monitorPage.isShowing()) {
			monitorPage.onRowsChanged();
		}
		if (logPage != null && logPage.isShowing()) {
			logPage.onRowsChanged();
		}
		if (confPage != null && confPage.isShowing()) {
			confPage.onRowsChanged();
		}
	}

	/** 改名/忽略后立刻重绘(不重新检测) */
	private void reRender() {
		rebuildList(lastData, selectedId());
	}

	/** 概览页统计: 运行中/已停止/已忽略; 检测未完成时显示 — */
	private void updateOverviewStats(int running, int total, boolean detected) {
		if (overviewPanel == null) {
			return;
		}
		if (!detected) {
			overviewPanel.setStats(total, -1, -1, -1);
			return;
		}
		int ignored = 0;
		for (NginxInstanceEntry entry : registry.entries()) {
			if (ignoreStore.containsEntry(entry)) {
				ignored++;
			}
		}
		overviewPanel.setStats(total, running, Math.max(0, total - running - ignored), ignored);
	}

	/** 概览页实例状态表: 推送全部实例(不受列表筛选影响)的运行状态与健康指标 */
	private void pushOverviewStatuses(RefreshData data) {
		if (overviewPanel == null) {
			return;
		}
		List<OverviewPanel.InstanceStatus> statuses = new ArrayList<OverviewPanel.InstanceStatus>();
		for (NginxInstanceEntry entry : registry.entries()) {
			boolean ignored = ignoreStore.containsEntry(entry);
			boolean detecting = data == null;
			boolean running = !ignored && !detecting && isRunning(entry, data);
			NginxInfo live = detecting ? null : liveOf(entry, data);
			String pid = live != null && StrUtil.isNotEmpty(live.getMasterPid())
					? live.getMasterPid() : entry.getLastMasterPid();
			Integer workers = live == null ? null : workerCount(live.getPids());
			String version = live == null ? null : live.getVersion();
			String service = StrUtil.isNotEmpty(entry.getWinServiceName())
					? entry.getWinServiceName()
					: (live == null ? null : live.getWinServiceName());
			statuses.add(new OverviewPanel.InstanceStatus(entry, running, detecting, ignored,
					service, pid, workers, version,
					detecting ? null : listenPortsOf(entry, data)));
		}
		overviewPanel.setInstances(statuses);
	}

	/** 进程列表(含master)折算worker数: 无进程信息时返回null, 与详情页口径一致 */
	private static Integer workerCount(String pidsCsv) {
		if (StrUtil.isEmpty(pidsCsv)) {
			return null;
		}
		int count = 0;
		for (String pid : pidsCsv.split(",")) {
			if (StrUtil.isNotBlank(pid)) {
				count++;
			}
		}
		return Math.max(0, count - 1);
	}

	/** 读取并缓存本轮监听端口(配置解析为小文件读取, 每轮每实例只做一次) */
	private List<String> listenPortsOf(NginxInstanceEntry entry, RefreshData data) {
		List<String> ports = data.listenPorts.get(entry.getId());
		if (ports == null) {
			ports = NginxDashboardUtils.listenPortsOf(entry);
			data.listenPorts.put(entry.getId(), ports);
		}
		return ports;
	}

	/** 从概览实例状态表打开实例总览页并定位到该实例 */
	public void openInstancePage(NginxInstanceEntry entry) {
		if (instancePage != null && entry != null) {
			instancePage.selectEntry(entry);
		}
		showPage(PAGE_INSTANCES);
	}

	private boolean isRunning(NginxInstanceEntry entry, RefreshData data) {
		if (!ignoreStore.containsEntry(entry)) {
			Boolean light = lightStatus.get(entry.getId());
			if (light != null) {
				return light;
			}
		}
		return isRunningFromSnapshot(entry, data);
	}

	/** 按本轮全量快照判定运行状态(注册表归并与轻量状态重置使用) */
	private boolean isRunningFromSnapshot(NginxInstanceEntry entry, RefreshData data) {
		if (StrUtil.isNotEmpty(entry.getWinServiceName()) && data.services != null) {
			String svc = entry.getWinServiceName().toLowerCase();
			for (WindowsNginxServiceUtils.WinNginxService s : data.services) {
				if (svc.equals(StrUtil.nullToEmpty(s.serviceName).toLowerCase())) {
					return s.running();
				}
			}
		}
		return data.liveIndex != null && data.liveIndex.matches(entry);
	}

	/**
	 * 清理无来源支撑的历史条目.
	 * 旧版本靠"全盘遍历磁盘"写入的条目(非服务托管、当前也未运行)在新模型下已无任何来源支撑,
	 * 保留只会变成永远"已停止"的僵尸条目; 丢弃后由运行检测重新登记, 或阶段二手动添加.
	 */
	/**
	 * 清理无来源支撑的历史条目.
	 * @return true 表示本轮真删了条目(需要持久化)
	 */
	private boolean purgeUnbacked(RefreshData data) {
		if (data.live == null) {
			return false;
		}
		boolean purged = false;
		for (java.util.Iterator<NginxInstanceEntry> it = registry.entries().iterator(); it.hasNext();) {
			NginxInstanceEntry e = it.next();
			if (ignoreStore.containsEntry(e)) {
				continue;
			}
			if (NginxInstanceRegistry.SOURCE_DISCOVERED.equals(e.getSource())
					&& StrUtil.isEmpty(e.getWinServiceName())
					&& !data.liveIndex.matches(e)) {
				it.remove();
				purged = true;
			}
		}
		return purged;
	}

	private void applyResult(RefreshData data) {
		// 从磁盘读取持久化 JSON 作为 mergeIntoLatest 的基准(损坏时自动回退备份)
		String latestJson = readRegistryJson();

		// SCM 服务登记: 服务名即实例身份锚点, 停止态也保留.
		// 必须先于进程登记: 服务托管的实例以服务名为id/展示名, 若先由进程创建条目,
		// 会得到"默认实例"式的目录名与 discovered 来源, 身份不稳定.
		if (data.services != null) {
			for (WindowsNginxServiceUtils.WinNginxService svc : data.services) {
				if (ignoreStore.matches(NginxInstanceRegistry.serviceFid(svc.serviceName),
						svc.serviceName, svc.nginxExe, svc.confPath, svc.prefixDir)) {
					continue;
				}
				try {
					registry.acquireService(svc);
				} catch (Exception ignored) {
				}
			}
		}

		// 运行中实例登记(进程来源): 未注册成服务的 nginx 只有跑起来才会被发现,
		// 登记后即留在注册表, 停止后仍可见(不再依赖磁盘遍历)
		if (data.live != null) {
			for (NginxInfo info : data.live) {
				String conf = info.getNginxPath();
				if (StrUtil.isEmpty(conf) || !new File(conf).isFile()) {
					continue;
				}
				String fid = NginxInstanceRegistry.fingerprint(info.getNginxDir(), conf);
				if (StrUtil.isEmpty(fid)) {
					continue;
				}
				if (ignoreStore.matches(fid, info.getWinServiceName(), info.getNginxExe(),
						conf, info.getNginxDir())) {
					continue;
				}
				try {
					registry.acquire(fid, info, data.liveExeFids);
				} catch (Exception ignored) {
				}
			}
		}

		// 合并注册表 + 清理僵尸条目. 只在真有实体变化时才写盘,
		// 运行时刷新字段(lastSeen/lastMasterPid)不算脏 —— 每次启动/定时扫描重扫即得.
		boolean dirtyMerge = registry.mergeIntoLatest(latestJson, ignoreStore);
		boolean dirtyPurge = purgeUnbacked(data);
		if (dirtyMerge || dirtyPurge) {
			saveRegistry();
		}

		lastData = data;
		LOG.info("刷新完成: 实例={}, 服务={}", registry.entries().size(),
				data.services == null ? 0 : data.services.size());
		// 以本轮快照重置 5 秒轻量状态, 避免重建时使用上一轮的旧结论
		syncLightStatus(data);
		// 重建前记录选中项id, 重建后按id恢复(否则每次定时刷新都会丢失选中)
		rebuildList(data, selectedId());
		lblLastRefresh.setText("最后刷新 " + java.time.LocalTime.now().format(TIME_FMT));
		if (overviewPanel != null) {
			overviewPanel.setLastRefresh("最近检测: " + java.time.LocalTime.now().format(TIME_FMT));
		}
	}

	/** 当前是否运行(与列表状态同口径: 忽略的实例视为不纳管) */
	public boolean currentRunning(NginxInstanceEntry entry) {
		if (entry == null || lastData == null || ignoreStore.containsEntry(entry)) {
			return false;
		}
		return isRunning(entry, lastData);
	}

	/** 该实例对应的 SCM 服务(无则 null) */
	public WindowsNginxServiceUtils.WinNginxService currentService(NginxInstanceEntry entry) {
		return serviceOf(entry, lastData);
	}

	private WindowsNginxServiceUtils.WinNginxService serviceOf(NginxInstanceEntry entry, RefreshData data) {
		if (entry == null || data == null || data.services == null) {
			return null;
		}
		String name = entry.getWinServiceName();
		if (StrUtil.isEmpty(name)) {
			return null;
		}
		for (WindowsNginxServiceUtils.WinNginxService svc : data.services) {
			if (name.equalsIgnoreCase(StrUtil.nullToEmpty(svc.serviceName))) {
				return svc;
			}
		}
		return null;
	}

	/**
	 * 该实例在本轮刷新中对应的运行进程信息(无则 null).
	 * 依次按 组合指纹 → 服务名 → 包装器pid → 唯一exe 匹配, 避免详情页重复检测.
	 */
	public NginxInfo currentLive(NginxInstanceEntry entry) {
		return liveOf(entry, lastData);
	}

	private NginxInfo liveOf(NginxInstanceEntry entry, RefreshData data) {
		if (entry == null || data == null || data.live == null) {
			return null;
		}
		java.util.Set<String> ids = new java.util.HashSet<String>();
		ids.add(entry.getId());
		if (entry.getAltIds() != null) {
			ids.addAll(entry.getAltIds());
		}
		for (NginxInfo info : data.live) {
			String conf = info.getNginxPath();
			if (StrUtil.isEmpty(conf) || !new File(conf).isFile()) {
				continue;
			}
			String fid = NginxInstanceRegistry.fingerprint(info.getNginxDir(), conf);
			if (ids.contains(fid)) {
				return info;
			}
		}
		String svcName = entry.getWinServiceName();
		if (StrUtil.isNotEmpty(svcName)) {
			for (NginxInfo info : data.live) {
				if (svcName.equalsIgnoreCase(StrUtil.nullToEmpty(info.getWinServiceName()))) {
					return info;
				}
			}
			WindowsNginxServiceUtils.WinNginxService svc = serviceOf(entry, data);
			if (svc != null && svc.wrapperPid > 0) {
				String wrapperPid = String.valueOf(svc.wrapperPid);
				for (NginxInfo info : data.live) {
					if (wrapperPid.equals(StrUtil.nullToEmpty(info.getMasterPpid()))) {
						return info;
					}
				}
			}
		}
		String exe = NginxInstanceRegistry.canonical(entry.getNginxExe());
		if (StrUtil.isNotEmpty(exe)) {
			NginxInfo hit = null;
			for (NginxInfo info : data.live) {
				if (exe.equals(NginxInstanceRegistry.canonical(info.getNginxExe()))) {
					if (hit != null) {
						return null;
					}
					hit = info;
				}
			}
			return hit;
		}
		return null;
	}

	// ==================== 实例管理(添加/忽略/改名) ====================

	/** 手动添加实例: 选 nginx.exe 或安装目录, 推导 prefix 与 conf/nginx.conf */
	private void addInstance() {
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("选择 nginx.exe 或 nginx 安装目录");
		chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
		chooser.setAcceptAllFileFilterUsed(false);
		chooser.setFileFilter(new FileNameExtensionFilter("nginx 可执行文件 (nginx.exe / nginx_basic.exe)", "exe"));
		String seed = System.getProperty("user.home");
		if (StrUtil.isNotEmpty(seed)) {
			chooser.setCurrentDirectory(new File(seed));
		}
		if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
			return;
		}

		File picked = chooser.getSelectedFile();
		File exe;
		File prefix;
		if (picked.isDirectory()) {
			prefix = picked;
			exe = new File(prefix, "nginx.exe");
			if (!exe.isFile()) {
				exe = new File(prefix, "nginx_basic.exe");
			}
		} else {
			exe = picked;
			prefix = picked.getParentFile();
		}
		if (exe == null || !exe.isFile()) {
			JOptionPane.showMessageDialog(this,
					"未找到 nginx.exe。请选择 nginx 可执行文件, 或包含 nginx.exe 的安装目录。",
					"nginxUI - 添加实例", JOptionPane.WARNING_MESSAGE);
			return;
		}
		if (prefix == null) {
			prefix = exe.getParentFile();
		}

		// 该 nginx.exe 若已被 Windows 服务托管, 直接按服务身份纳管:
		// 服务实际使用的 prefix/conf 可能与 exe 所在目录不同(程序目录与站点目录分离),
		// 若按手动路径登记会得到"没有服务名"的重复实例——移除后重新添加时服务名丢失即由此产生.
		WindowsNginxServiceUtils.WinNginxService service = findServiceByExe(exe, prefix);
		if (service != null) {
			adoptService(service);
			return;
		}

		File conf = new File(prefix, "conf" + File.separator + "nginx.conf");
		if (!conf.isFile()) {
			JOptionPane.showMessageDialog(this,
					"未找到配置文件:\n" + conf.getAbsolutePath()
							+ "\n\n请选择标准布局的 nginx 安装目录(<prefix>/conf/nginx.conf)。",
					"nginxUI - 添加实例", JOptionPane.WARNING_MESSAGE);
			return;
		}

		try {
			NginxInstanceEntry entry = registry.manualRegister(exe.getAbsolutePath(),
					conf.getAbsolutePath(), prefix.getAbsolutePath(), null);
			saveRegistry();
			lblStatus.setText("已添加实例: " + InstanceRow.displayName(entry));
			toast("已添加实例: " + InstanceRow.displayName(entry));
			LOG.info("手动添加实例: id={}, exe={}, conf={}", entry.getId(), exe, conf);
			rebuildList(lastData, entry.getId());
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(this,
					ErrorReporter.uiText("添加实例", ex, "请检查 nginx.exe 与配置路径是否正确，然后重试。"),
					"nginxUI - 添加实例失败", JOptionPane.ERROR_MESSAGE);
		}
	}

	/**
	 * 按 nginx.exe 路径查找托管它的 SCM 服务.
	 * 多个服务共用同一 exe 时优先取 prefix/conf 与所选目录一致的, 仍无法区分则返回 null.
	 */
	private WindowsNginxServiceUtils.WinNginxService findServiceByExe(File exe, File prefix) {
		String target = NginxInstanceRegistry.canonical(exe.getAbsolutePath());
		if (StrUtil.isEmpty(target)) {
			return null;
		}
		String prefixCanonical = NginxInstanceRegistry.canonical(prefix.getAbsolutePath());
		WindowsNginxServiceUtils.WinNginxService hit = null;
		for (WindowsNginxServiceUtils.WinNginxService svc : WindowsNginxServiceUtils.list()) {
			if (!target.equalsIgnoreCase(NginxInstanceRegistry.canonical(svc.nginxExe))) {
				continue;
			}
			if (hit != null) {
				// 多个服务共用同一 exe: 只采用与所选目录一致的候选, 否则无法确定身份
				return StrUtil.isNotEmpty(prefixCanonical)
						&& prefixCanonical.equalsIgnoreCase(NginxInstanceRegistry.canonical(svc.prefixDir))
								? svc : null;
			}
			hit = svc;
		}
		return hit;
	}

	/**
	 * 按服务身份纳管(手动添加时识别出该 exe 由服务托管).
	 * 先清掉此前"移除"留下的忽略记录, 否则刷新时服务会被忽略名单过滤, 服务名永远不会回填.
	 */
	private void adoptService(WindowsNginxServiceUtils.WinNginxService svc) {
		ignoreStore.remove(NginxInstanceRegistry.serviceFid(svc.serviceName));
		ignoreStore.remove(svc.serviceName);
		if (StrUtil.isNotEmpty(svc.confPath)) {
			ignoreStore.remove(NginxInstanceRegistry.fingerprint(svc.prefixDir, svc.confPath));
		}
		saveIgnoreStore();

		NginxInstanceEntry entry = registry.acquireService(svc);
		saveRegistry();
		String msg = "已关联服务: " + svc.serviceName;
		lblStatus.setText(msg);
		toast(msg);
		LOG.info("手动添加实例: 关联服务 name={}, prefix={}", svc.serviceName, svc.prefixDir);
		rebuildList(lastData, entry.getId());
		refreshAsync();
	}

	/** 忽略/恢复: 未忽略 → 加入忽略名单; 已忽略 → 移出并重新纳管 */
	private void toggleIgnore() {
		InstanceRow row = selectedRow();
		if (row == null) {
			return;
		}
		NginxInstanceEntry entry = row.entry;
		String name = InstanceRow.displayName(entry);

		if (!row.ignored) {
			// 忽略即不再纳管, 属于破坏性操作: 二次确认(不删文件/服务, 可随时恢复)
			if (!ConfirmDialog.confirm(this, "忽略实例", "实例「" + name + "」",
					"忽略后不再纳管该实例，实例总览中不再显示它的状态与操作入口。",
					"可撤销：可在实例总览页的「实例管理」中选择「恢复实例」重新加入；"
							+ "不会删除任何磁盘文件或 Windows 服务。",
					"忽略实例")) {
				return;
			}
			ignoreStore.addFromEntry(entry);
			saveIgnoreStore();
			lblStatus.setText("已忽略: " + name);
			toast("已忽略: " + name);
			LOG.info("忽略实例: id={}, name={}", entry.getId(), name);
			reRender();
			return;
		}

		// 恢复: 移出忽略名单(条目可能以 idle/altId/服务名任一形式存在)
		List<NginxInstanceIgnore> removed = new ArrayList<NginxInstanceIgnore>(ignoreStore.remove(entry.getId()));
		if (removed.isEmpty() && entry.getAltIds() != null) {
			for (String alt : entry.getAltIds()) {
				removed.addAll(ignoreStore.remove(alt));
			}
		}
		if (removed.isEmpty() && StrUtil.isNotEmpty(entry.getWinServiceName())) {
			removed = ignoreStore.remove(entry.getWinServiceName());
		}
		saveIgnoreStore();

		// 逐个恢复登记: 目录/服务都没了的条目不再保留
		boolean anyRestored = false;
		for (NginxInstanceIgnore item : removed) {
			if (registry.restoreIgnored(item) != null) {
				anyRestored = true;
			}
		}
		if (!anyRestored) {
			registry.remove(entry.getId());
		}
		saveRegistry();
		lblStatus.setText(anyRestored ? "已恢复纳管: " + name
				: "已移出忽略名单, 但实例目录与服务均已不存在");
		toast(anyRestored ? "已恢复纳管: " + name : "已移出忽略名单, 但实例已不存在");
		LOG.info("恢复实例: id={}, name={}, restored={}", entry.getId(), name, anyRestored);
		reRender();
		refreshAsync();
	}

	/** 编辑别名: 留空则恢复默认展示名 */
	private void renameSelected() {
		InstanceRow row = selectedRow();
		if (row == null) {
			return;
		}
		String current = StrUtil.emptyToDefault(row.entry.getName(), InstanceRow.displayName(row.entry));
		String input = (String) JOptionPane.showInputDialog(this,
				"实例别名(留空恢复默认名):", "编辑别名",
				JOptionPane.PLAIN_MESSAGE, null, null, current);
		if (input == null) {
			return;
		}
		String name = input.trim();
		if (StrUtil.isNotEmpty(name)) {
			for (NginxInstanceEntry other : registry.entries()) {
				if (other != row.entry && name.equalsIgnoreCase(StrUtil.nullToEmpty(other.getName()))) {
					JOptionPane.showMessageDialog(this, "别名已被其它实例使用: " + name,
							"nginxUI - 编辑别名", JOptionPane.WARNING_MESSAGE);
					return;
				}
			}
		}
		row.entry.setName(StrUtil.isEmpty(name) ? null : name);
		saveRegistry();
		toast(StrUtil.isEmpty(name) ? "已恢复默认名称" : "别名已更新: " + name);
		LOG.info("实例改名: id={}, name={}", row.entry.getId(), name);
		reRender();
	}

	// ==================== 控制 ====================

	private void controlSelected(String action) {
		// 防重复触发: 上一次启停/重启未结束或收紧冷却期内, 直接忽略(后端虽串行, 排队执行仍会反复停机)
		if (controlBusy || System.currentTimeMillis() - controlDoneAt < ACTION_COOLDOWN_MS) {
			lblStatus.setText("上一次操作尚未结束，请稍候…");
			return;
		}
		final List<NginxInstanceEntry> targets = new ArrayList<NginxInstanceEntry>();
		for (InstanceRow r : selectedRows()) {
			if (!r.ignored) {
				targets.add(r.entry);
			}
		}
		if (targets.isEmpty()) {
			return;
		}

		String actionLabel;
		switch (action) {
			case "start": actionLabel = "启动"; break;
			case "stop": actionLabel = "停止"; break;
			case "restart": actionLabel = "重启"; break;
			default: actionLabel = action;
		}

		String what = targets.size() == 1
				? "实例 \"" + InstanceRow.displayName(targets.get(0)) + "\""
				: targets.size() + " 个实例";

		// 危险操作二次确认: 对象 + 后果 + 是否可撤销, 默认焦点在「取消」
		if (!action.equals("start")) {
			String consequence = action.equals("stop")
					? "实例停止后，其站点将立即无法访问（连接失败或返回 502）。"
					: "重启期间实例会短暂停止服务，正在处理的请求可能中断。";
			String reversibility = action.equals("stop")
					? "可撤销：配置文件与站点文件均不受影响，随时可再次启动恢复。"
					: "可撤销：文件与配置不受影响，重启完成后自动恢复服务。";
			if (!ConfirmDialog.confirm(this, actionLabel + "实例", what,
					consequence, reversibility, actionLabel)) {
				return;
			}
		}

		// 进入执行态: 三个控制按钮置灰, 直到后台操作结束(选中变化触发的 updateButtonState 同样尊重该状态)
		if (!OperationLock.tryAcquire(actionLabel + "实例")) {
			lblStatus.setText(OperationLock.reason());
			return;
		}
		controlBusy = true;
		Theme.setEnabled(btnStart, false, REASON_CONTROL_BUSY);
		Theme.setEnabled(btnStop, false, REASON_CONTROL_BUSY);
		Theme.setEnabled(btnRestart, false, REASON_CONTROL_BUSY);
		lblStatus.setText(actionLabel + "中… (" + targets.size() + ")");
		new SwingWorker<List<String>, Void>() {
			@Override
			protected List<String> doInBackground() {
				List<String> lines = new ArrayList<String>();
				for (NginxInstanceEntry entry : targets) {
					String name = InstanceRow.displayName(entry);
					try {
						NginxDashboardUtils.ControlResult r =
								NginxDashboardUtils.controlInstance(entry, action);
						if (r.success) {
							lines.add("成功: " + name
									+ (r.warning && r.message != null ? " (告警: " + r.message + ")" : ""));
						} else {
							lines.add("失败: " + name + " - " + r.message);
						}
					} catch (Exception ex) {
						lines.add("失败: " + name + " - " + ErrorReporter.friendlyReason(ex));
					}
				}
				return lines;
			}

			@Override
			protected void done() {
				// 先退出执行态再展示结果: 结果弹窗是模态的, 按钮需在弹窗期间保持可用性
				OperationLock.release();
				controlBusy = false;
				controlDoneAt = System.currentTimeMillis();
				updateButtonState();
				List<String> lines;
				try {
					lines = get();
				} catch (Exception ex) {
					lines = new ArrayList<String>();
				}
				int failed = 0;
				for (String line : lines) {
					if (line.startsWith("失败")) failed++;
				}
				String resultLine = actionLabel + "完成: 成功 " + (lines.size() - failed)
						+ " / 失败 " + failed;
				lblStatus.setText(resultLine);
				if (failed > 0) {
					javax.swing.JTextArea ta = new javax.swing.JTextArea(StrUtil.join("\n", lines));
					ta.setEditable(false);
					ta.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
					JOptionPane.showMessageDialog(MainFrame.this, new JScrollPane(ta),
							"nginxUI - " + actionLabel + "结果", JOptionPane.WARNING_MESSAGE);
				} else {
					toast(resultLine);
				}
				// 操作完刷新状态(轻量: 不需要重扫磁盘)
				refreshAsync();
			}
		}.execute();
	}

	// ==================== 配置检测 ====================

	/** 配置被改写(如启用 stub_status)后刷新状态 */
	public void onConfigChanged() {
		refreshAsync();
	}

	private void testSelected() {
		// 防重复触发: nginx -t 每次都要拉起进程解析全量配置, 连续点击只会排队放大开销
		if (testBusy || System.currentTimeMillis() - testDoneAt < ACTION_COOLDOWN_MS) {
			lblStatus.setText("上一次配置检测尚未结束，请稍候…");
			return;
		}
		int idx = instanceList.getSelectedIndex();
		if (idx < 0) return;
		NginxInstanceEntry entry = listModel.getElementAt(idx).entry;

		testBusy = true;
		lblStatus.setText("测试配置中…");
		new SwingWorker<NginxDashboardUtils.ConfigTestResult, Void>() {
			@Override
			protected NginxDashboardUtils.ConfigTestResult doInBackground() {
				return NginxDashboardUtils.testInstanceConfig(entry);
			}

			@Override
			protected void done() {
				try {
					NginxDashboardUtils.ConfigTestResult r = get();
					if (r.success) {
						lblStatus.setText("配置检测通过");
						JOptionPane.showMessageDialog(MainFrame.this,
								r.output,
								"nginxUI - 配置检测通过",
								JOptionPane.INFORMATION_MESSAGE);
					} else {
						lblStatus.setText("配置检测失败: " + r.message);
						JOptionPane.showMessageDialog(MainFrame.this,
								r.output,
								"nginxUI - 配置检测失败",
								JOptionPane.ERROR_MESSAGE);
					}
				} catch (Exception ex) {
					lblStatus.setText("配置检测异常，请稍后重试（详情见日志）");
				} finally {
					// 结果弹窗关闭后恢复: 置灰贯穿整个检测与结果查看过程
					testBusy = false;
					testDoneAt = System.currentTimeMillis();
					updateButtonState();
				}
			}
		}.execute();
	}

	// ==================== 安装 / 升级 ====================

	/** 全新安装实例: 内容区页面(在线下载或离线上传安装包, 安装到工具目录 instances 下) */
	private void openInstallDialog() {
		try {
			if (installPage == null) {
				installPage = new InstallPage(this);
				pageCards.add(installPage, PAGE_INSTALL);
			}
			showPage(PAGE_INSTALL);
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(this,
					ErrorReporter.uiText("打开安装页面", ex, "请关闭后重试；若持续失败，请查看日志目录。"),
					"nginxUI", JOptionPane.ERROR_MESSAGE);
		}
	}

	/** 全新实例安装/注册完成后刷新列表并选中新实例 */
	public void onInstanceInstalled(NginxInstanceEntry entry) {
		saveRegistry();
		rebuildList(lastData, entry == null ? null : entry.getId());
		refreshAsync();
	}

	/** 升级页按实例创建: 切换实例时重建页面, 避免沿用上一实例的版本/回滚状态 */
	private void openUpgradeDialog() {
		InstanceRow row = selectedRow();
		if (row == null || row.ignored) {
			toast("请先在实例总览页中选择一个实例");
			return;
		}
		try {
			if (upgradePage != null && upgradeEntry != row.entry) {
				upgradePage.dispose();
				pageCards.remove(upgradePage);
				upgradePage = null;
			}
			if (upgradePage == null) {
				upgradeEntry = row.entry;
				upgradePage = new UpgradePage(this, row.entry);
				pageCards.add(upgradePage, PAGE_UPGRADE);
			}
			showPage(PAGE_UPGRADE);
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(this,
					ErrorReporter.uiText("打开升级页面", ex, "请关闭后重试；若持续失败，请查看日志目录。"),
					"nginxUI - 升级", JOptionPane.ERROR_MESSAGE);
		}
	}

	/** 升级页内切换目标实例: 按实例重建页面(与从实例列表进入升级的路径一致) */
	public void openUpgradePageFor(NginxInstanceEntry entry) {
		if (entry == null) {
			return;
		}
		try {
			if (upgradePage != null) {
				upgradePage.dispose();
				pageCards.remove(upgradePage);
				upgradePage = null;
			}
			upgradeEntry = entry;
			upgradePage = new UpgradePage(this, entry);
			pageCards.add(upgradePage, PAGE_UPGRADE);
			showPage(PAGE_UPGRADE);
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(this,
					ErrorReporter.uiText("打开升级页面", ex, "请关闭后重试；若持续失败，请查看日志目录。"),
					"nginxUI - 升级", JOptionPane.ERROR_MESSAGE);
		}
	}

	/** 可升级实例(排除已忽略项), 供升级页「目标实例」下拉使用 */
	public List<NginxInstanceEntry> upgradableEntries() {
		List<NginxInstanceEntry> out = new ArrayList<NginxInstanceEntry>();
		for (NginxInstanceEntry e : registry.entries()) {
			if (!ignoreStore.containsEntry(e)) {
				out.add(e);
			}
		}
		return out;
	}

	/** 证书管理: 内容区页面, 未选中实例时也可打开(总览所有实例的证书) */
	private void openCertManagerDialog() {
		try {
			if (certPage == null) {
				certPage = new CertManagerPage(this);
				pageCards.add(certPage, PAGE_CERT);
			}
			showPage(PAGE_CERT);
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(this,
					ErrorReporter.uiText("打开证书管理页面", ex, "请关闭后重试；若持续失败，请查看日志目录。"),
					"nginxUI", JOptionPane.ERROR_MESSAGE);
		}
	}

	/** 关于: 版本、运行环境、组件与目录信息 */
	private void openAboutDialog() {
		try {
			new AboutDialog(this).setVisible(true);
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(this,
					ErrorReporter.uiText("打开关于窗口", ex, "请关闭后重试。"),
					"nginxUI", JOptionPane.ERROR_MESSAGE);
		}
	}

	/** 升级/回滚后刷新列表状态(程序就地替换, 注册表路径不变) */
	public void onUpgradeFinished() {
		saveRegistry();
		refreshAsync();
	}

	/** 工具目录: 升级缓存与历史所在位置 */
	public String appHome() {
		return home;
	}

	/** 当前注册表条目(升级时用于找出共用同一程序文件的其它实例) */
	public List<NginxInstanceEntry> registryEntries() {
		return registry.snapshot();
	}

	/** 实例注册表(安装向导用于登记全新实例) */
	public NginxInstanceRegistry instanceRegistry() {
		return registry;
	}

	// ==================== 实例总览页对外接口 ====================

	/** 实例总览页使用的实例行(与后台刷新同源, 顺序一致) */
	public List<InstanceRow> instanceRows() {
		List<InstanceRow> rows = new ArrayList<InstanceRow>();
		for (int i = 0; i < listModel.size(); i++) {
			rows.add(listModel.getElementAt(i));
		}
		return rows;
	}

	/** 下拉选中行同步到内部选中态, 驱动既有动作按钮与侧栏条目的可用性 */
	public void selectInstanceRow(InstanceRow row) {
		if (instanceList == null) {
			return;
		}
		if (row == null) {
			instanceList.clearSelection();
			return;
		}
		instanceList.setSelectedValue(row, true);
	}

	/** 当前选中实例(内容区页面的实例下拉据此保持上下文) */
	public NginxInstanceEntry selectedInstanceEntry() {
		InstanceRow row = selectedRow();
		return row == null ? null : row.entry;
	}

	/** 按实例条目定位列表行并选中(兼容刷新后行对象重建的情况) */
	private void selectRowOf(NginxInstanceEntry entry) {
		if (entry == null) {
			return;
		}
		for (int i = 0; i < listModel.size(); i++) {
			if (listModel.getElementAt(i).entry == entry) {
				selectInstanceRow(listModel.getElementAt(i));
				return;
			}
		}
	}

	public void controlInstanceEntry(NginxInstanceEntry entry, String action) {
		selectRowOf(entry);
		controlSelected(action);
	}

	public void testInstanceEntry(NginxInstanceEntry entry) {
		selectRowOf(entry);
		testSelected();
	}

	public void renameInstanceEntry(NginxInstanceEntry entry) {
		selectRowOf(entry);
		renameSelected();
	}

	public void toggleIgnoreEntry(NginxInstanceEntry entry) {
		selectRowOf(entry);
		toggleIgnore();
	}

	public void openInstanceDirOf(NginxInstanceEntry entry) {
		selectRowOf(entry);
		openInstanceDir();
	}

	public void upgradeInstanceEntry(NginxInstanceEntry entry) {
		selectRowOf(entry);
		openUpgradeDialog();
	}

	public void promptAddInstance() {
		addInstance();
	}

	public void promptInstallInstance() {
		openInstallDialog();
	}

	public void requestRefresh() {
		triggerDetect();
	}

	/** 监控开关: 仅影响展示/采集, 不改写 nginx.conf */
	public void setStubMonitor(NginxInstanceEntry entry, boolean on) {
		if (entry == null) {
			return;
		}
		entry.setStubMonitor(on);
		saveRegistry();
		LOG.info("实例监控开关: id={}, on={}", entry.getId(), on);
	}

	/** 供子面板使用的轻提示 */
	public void notifyToast(String message) {
		toast(message);
	}

	/** 打开 Nginx 状态页并定位到指定实例(实例总览的快捷入口) */
	public void openMonitorPage(NginxInstanceEntry entry) {
		showPage(PAGE_STATUS);
		if (monitorPage != null && entry != null) {
			monitorPage.onShown(entry);
		}
	}

	/** 打开日志查看页并定位到指定实例 */
	public void openLogPage(NginxInstanceEntry entry) {
		showPage(PAGE_LOG);
		if (logPage != null && entry != null) {
			logPage.onShown(entry);
		}
	}

	// ==================== 辅助 ====================

	/** 打开配置文件编辑页(定位到列表当前选中的实例) */
	public void openConfPage() {
		int idx = instanceList.getSelectedIndex();
		openConfPage(idx < 0 ? null : listModel.getElementAt(idx).entry);
	}

	/** 打开配置文件编辑页并定位到指定实例 */
	public void openConfPage(NginxInstanceEntry entry) {
		// 先按目标实例定位再切页: showPage 里的 onShown() 会沿用已选中的实例,
		// 避免先读一次默认实例、再读目标实例造成的重复读取
		if (confPage != null && entry != null) {
			confPage.onShown(entry);
		}
		showPage(PAGE_CONF);
	}

	private void openInstanceDir() {
		int idx = instanceList.getSelectedIndex();
		if (idx < 0) return;
		NginxInstanceEntry entry = listModel.getElementAt(idx).entry;
		String dir = entry.getNginxDir();
		if (dir == null || !FileUtil.exist(dir)) {
			dir = entry.getPrefixDir();
		}
		if (dir == null || !FileUtil.exist(dir)) {
			JOptionPane.showMessageDialog(this, "实例目录无效", "nginxUI", JOptionPane.WARNING_MESSAGE);
			return;
		}
		try {
			java.awt.Desktop.getDesktop().open(new File(dir));
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(this,
					ErrorReporter.uiText("打开目录", ex, "请确认目录存在且当前账户有访问权限。"),
					"nginxUI", JOptionPane.ERROR_MESSAGE);
		}
	}

	@Override
	public void dispose() {
		scheduler.shutdownNow();
		saveRegistry();
		super.dispose();
	}

	// ==================== 内部类型 ====================

	/** 列表行: 封装实例 + 运行状态, 用于渲染 */
	static class InstanceRow {
		final NginxInstanceEntry entry;
		final boolean running;
		/** 首屏尚未完成检测: 不谎报状态, 显示"检测中" */
		final boolean detecting;
		/** 已忽略: 不再纳管, 行内标记并禁用操作 */
		final boolean ignored;

		InstanceRow(NginxInstanceEntry entry, boolean running, boolean detecting, boolean ignored) {
			this.entry = entry;
			this.running = running;
			this.detecting = detecting;
			this.ignored = ignored;
		}

		@Override
		public String toString() {
			String state = ignored ? "⊘ 已忽略"
					: (detecting ? "… 检测中" : (running ? "● 运行中" : "○ 已停止"));
			String name = displayName(entry);
			String pid = (!ignored && !detecting && entry.getLastMasterPid() != null)
					? " pid=" + entry.getLastMasterPid() : "";
			return String.format("%s  %s%s", state, name, pid);
		}

		/**
		 * 实例展示名: 别名 > Windows服务注册名 > 安装目录名 > 注册id.
		 * 磁盘扫描发现的实例没有别名, 直接展示id(指纹)对用户无意义.
		 */
		static String displayName(NginxInstanceEntry entry) {
			if (StrUtil.isNotEmpty(entry.getName())) {
				return entry.getName();
			}
			if (StrUtil.isNotEmpty(entry.getWinServiceName())) {
				return entry.getWinServiceName();
			}
			String dir = StrUtil.emptyToDefault(entry.getPrefixDir(), entry.getNginxDir());
			if (StrUtil.isNotEmpty(dir)) {
				java.io.File f = new java.io.File(dir);
				String leaf = f.getName();
				if (StrUtil.isNotEmpty(leaf)) {
					// 目录名过于笼统(如 ...\nginx-windows\nginx)时带上父目录, 避免多条重名
					if (isGenericName(leaf) && f.getParentFile() != null) {
						String parent = f.getParentFile().getName();
						if (StrUtil.isNotEmpty(parent)) {
							return parent + "\\" + leaf;
						}
					}
					return leaf;
				}
			}
			return entry.getId();
		}

		/** 目录名是否过于笼统, 不足以区分实例 */
		static boolean isGenericName(String name) {
			String n = name.toLowerCase();
			return n.equals("nginx") || n.equals("nginx.exe") || n.equals("conf")
					|| n.equals("html") || n.equals("server");
		}
	}

	/** 列表渲染器: 双行单元格(状态点+名称 / 服务·PID), 选中与 hover 高亮 */
	private class InstanceRowRenderer extends JPanel
			implements javax.swing.ListCellRenderer<InstanceRow> {

		private final JLabel dot = new JLabel();
		private final JLabel name = new JLabel();
		private final JLabel meta = new JLabel();

		InstanceRowRenderer() {
			super(new BorderLayout(Theme.GAP_S, 0));
			dot.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
			dot.setFont(Theme.TITLE);
			dot.setPreferredSize(new Dimension(16, 18));
			name.setFont(Theme.BODY_BOLD);
			meta.setFont(Theme.SMALL);

			JPanel text = new JPanel(new java.awt.GridLayout(2, 1, 0, 1));
			text.setOpaque(false);
			text.add(name);
			text.add(meta);

			add(dot, BorderLayout.WEST);
			add(text, BorderLayout.CENTER);
		}

		@Override
		public java.awt.Component getListCellRendererComponent(
				JList<? extends InstanceRow> list, InstanceRow row, int index,
				boolean isSelected, boolean cellHasFocus) {
			setOpaque(true);
			// 运行中的实例整行淡绿底色, 便于一眼区分; 选中/悬停在此基础上升一档
			boolean runRow = row.running && !row.ignored && !row.detecting;
			java.awt.Color bg;
			if (runRow) {
				bg = isSelected ? Theme.RUNNING_BG_SELECTED
						: (index == hoverIndex ? Theme.RUNNING_BG_HOVER : Theme.RUNNING_BG);
			} else {
				bg = isSelected ? Theme.SELECTED_BG
						: (index == hoverIndex ? Theme.HOVER_BG : Theme.SURFACE);
			}
			setBackground(bg);
			setBorder(BorderFactory.createCompoundBorder(
					BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER),
					BorderFactory.createEmptyBorder(3, Theme.GAP_M, 3, Theme.GAP_M)));

			// 状态点: 运行中绿 / 已停止或检测中灰 / 已忽略 ⊘
			if (row.ignored) {
				dot.setText("⊘");
				dot.setForeground(Theme.TEXT_MUTED);
			} else if (row.detecting) {
				dot.setText("…");
				dot.setForeground(Theme.TEXT_MUTED);
			} else if (row.running) {
				dot.setText("●");
				dot.setForeground(Theme.statusColor(true));
			} else {
				dot.setText("○");
				dot.setForeground(Theme.statusColor(false));
			}

			name.setText(InstanceRow.displayName(row.entry));
			name.setForeground(row.ignored ? Theme.TEXT_MUTED : Theme.TEXT);

			StringBuilder sb = new StringBuilder();
			if (row.ignored) {
				sb.append("已忽略纳管");
			} else if (row.detecting) {
				sb.append("检测中…");
			} else {
				sb.append(row.running ? "运行中" : "已停止");
				if (StrUtil.isNotEmpty(row.entry.getWinServiceName())) {
					sb.append(" · 服务 ").append(row.entry.getWinServiceName());
				}
				if (row.running && row.entry.getLastMasterPid() != null) {
					sb.append(" · PID ").append(row.entry.getLastMasterPid());
				}
			}
			meta.setText(sb.toString());

			// 状态文字颜色与上面的状态文字同源(同一个 row.running), 本次渲染内一次性绑定
			meta.setForeground(row.ignored || row.detecting
					? Theme.TEXT_MUTED : Theme.statusColor(row.running));
			return this;
		}
	}
}
