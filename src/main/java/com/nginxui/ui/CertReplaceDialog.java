package com.nginxui.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.AbstractAction;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;

import com.nginxui.ext.NginxInstanceEntry;
import com.nginxui.utils.ErrorReporter;
import com.nginxui.utils.NginxCertReplaceManager;
import com.nginxui.utils.NginxCertReplaceManager.CertInfo;
import com.nginxui.utils.NginxCertReplaceManager.CertItem;
import com.nginxui.utils.NginxCertReplaceManager.Inspect;
import com.nginxui.utils.NginxCertReplaceManager.ReplaceRequest;
import com.nginxui.utils.NginxCertReplaceManager.ReplaceResult;
import com.nginxui.utils.NginxDashboardUtils;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;

/**
 * 证书替换对话框:
 *  选择实例 → 扫描配置中的 SSL 证书 → 上传/校验 PEM 证书与私钥 → 备份并替换 →
 *  nginx -t 门禁(失败自动回滚) → 二次确认是否立即重启生效.
 */
public class CertReplaceDialog extends JDialog {

	private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
	private static final String[] MODE_LABELS = { "覆盖原证书文件（推荐）", "写入新路径并改写配置指向" };
	private static final String[] ENC_LABELS = { NginxCertReplaceManager.ENC_AUTO,
			NginxCertReplaceManager.ENC_RSA, NginxCertReplaceManager.ENC_ECC };
	/** 单文件上限 20MB, 超出直接拦截 */
	private static final long MAX_UPLOAD_BYTES = 20L * 1024 * 1024;
	/** Nginx 可用证书格式: PEM 优先, 兼容 DER / PFX */
	private static final String[] CERT_EXTENSIONS = { "pem", "cer", "crt", "der", "pfx" };
	/** 私钥仅支持未加密 PEM */
	private static final String[] KEY_EXTENSIONS = { "pem", "key" };
	private static final String PEM_HINT = "未选择证书。推荐 PEM（.pem/.cer/.crt），"
			+ "兼容 DER（.der/.cer/.crt）与 PFX（.pfx），单文件 ≤ 20MB。";
	private static final String KEY_HINT = "未选择私钥。支持未加密 PEM 私钥（.pem/.key，RSA/ECC），"
			+ "单文件 ≤ 20MB。";
	/** 三步向导页面标识: 选择证书 / 替换方式 / 上传与确认 */
	private static final String[] STEP_KEYS = { "step-select", "step-mode", "step-upload" };
	private static final String[] STEP_TITLES = { "选择证书", "替换方式", "上传与确认" };

	private final MainFrame owner;
	private final NginxCertReplaceManager manager;

	private JComboBox<NginxInstanceEntry> cbInstance;
	private JComboBox<CertItem> cbCert;
	private JComboBox<String> cbMode;
	private JComboBox<String> cbEncryption;
	private JTextField tfPemTarget;
	private JTextField tfKeyTarget;
	private JButton btnChooseDir;

	private JTextField tfPemFile;
	private JTextField tfKeyFile;
	private JButton btnChoosePem;
	private JButton btnChooseKey;
	private JTextArea taPemState;
	private JTextArea taKeyState;

	private JTextArea taInfo;
	private JTextArea taModeHint;
	private JTextArea taReview;
	private JTextArea taLog;
	private JTextArea taGuide;
	private JButton btnScan;
	private JButton btnReplace;
	private JButton btnPrev;
	private JButton btnNext;
	private JButton btnClose;

	/** 分步引导: 进度指示器 + 页面容器 + 当前步骤校验提示 */
	private StepIndicator stepIndicator;
	private WizardCards cards;
	private JLabel lblStepHint;
	private int currentStep;

	private StatusCard statusCard;
	private PillLabel lblStage;
	private StageIcon stageIcon;

	private String pemContent;
	private String keyContent;
	private String keyType;
	private boolean busy;
	private boolean updating;
	private boolean scanned;
	/** 扫描失败: 与"正在扫描"区分开, 便于步骤提示给出准确文案 */
	private boolean scanFailed;
	/** 从证书总览进入时预选的证书路径(扫描完成后自动选中) */
	private final String preselectPemPath;

	public CertReplaceDialog(MainFrame owner, NginxInstanceEntry initial) {
		this(owner, initial, null);
	}

	public CertReplaceDialog(MainFrame owner, NginxInstanceEntry initial, String initialPemPath) {
		super(owner, "nginxUI - 证书替换", true);
		this.owner = owner;
		this.preselectPemPath = initialPemPath;
		this.manager = new NginxCertReplaceManager(new File(owner.appHome()));
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		setSize(900, 660);
		setMinimumSize(new Dimension(760, 600));
		buildUi();
		bindKeyboard();
		setLocationRelativeTo(owner);
		loadInstances(initial);
		appendLog("证书替换会先备份原文件，再执行 nginx -t 校验；校验失败自动回滚。");
		appendLog("操作日志: " + manager.logFile());
		javax.swing.SwingUtilities.invokeLater(() -> {
			if (cbInstance != null) {
				cbInstance.requestFocusInWindow();
			}
		});
	}

	// ==================== 界面 ====================

	private void buildUi() {
		setLayout(new BorderLayout());

		// 顶部步骤进度: 固定位置与样式, 三个页面共享
		stepIndicator = new StepIndicator(STEP_TITLES);
		JPanel header = new JPanel(new BorderLayout());
		header.setBorder(BorderFactory.createEmptyBorder(8, 12, 2, 12));
		header.add(stepIndicator, BorderLayout.CENTER);
		add(header, BorderLayout.NORTH);

		// 三个步骤各自独立一页, 同一时间只展示当前步骤
		cards = new WizardCards();
		cards.addPage(STEP_KEYS[0], buildSelectPanel());
		cards.addPage(STEP_KEYS[1], buildModePanel());
		cards.addPage(STEP_KEYS[2], buildUploadPanel());

		lblStepHint = new JLabel(" ");
		lblStepHint.setFont(Theme.SMALL);
		lblStepHint.setForeground(Theme.TEXT_MUTED);
		lblStepHint.setBorder(BorderFactory.createEmptyBorder(2, 3, 4, 3));

		JPanel pages = new JPanel(new BorderLayout());
		pages.setBorder(BorderFactory.createEmptyBorder(2, 12, 0, 12));
		pages.add(cards, BorderLayout.CENTER);
		pages.add(lblStepHint, BorderLayout.SOUTH);

		JPanel content = new JPanel(new BorderLayout());
		content.add(pages, BorderLayout.CENTER);
		content.add(buildLogPanel(), BorderLayout.SOUTH);
		add(content, BorderLayout.CENTER);

		stageIcon = new StageIcon(Theme.SUCCESS);
		lblStage = new PillLabel("");
		lblStage.setFont(Theme.BODY_BOLD);
		lblStage.setIcon(stageIcon);
		lblStage.setIconTextGap(6);
		lblStage.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 12));

		taGuide = hintArea(2, 30);
		taGuide.setForeground(Theme.TEXT);
		taGuide.setFont(Theme.BODY);
		setGuide("按步骤完成选择、配置与上传，最后确认替换。");

		JPanel stageRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		stageRow.setOpaque(false);
		stageRow.add(lblStage);

		JPanel statusText = new JPanel(new BorderLayout(0, 5));
		statusText.setOpaque(false);
		statusText.add(stageRow, BorderLayout.NORTH);
		statusText.add(taGuide, BorderLayout.CENTER);

		statusCard = new StatusCard();
		statusCard.setBorder(BorderFactory.createEmptyBorder(9, 12, 9, 12));
		statusCard.add(statusText, BorderLayout.CENTER);
		setStage("准备中");

		// 步骤导航按钮强化视觉权重: 上一步=描边次要按钮, 下一步=品牌色主按钮
		btnPrev = Theme.secondaryButton("上一步");
		btnPrev.setToolTipText("返回上一个步骤，已填写的内容会保留");
		btnPrev.addActionListener(e -> goPrev());
		btnPrev.setVisible(false);

		btnNext = Theme.primaryButton("下一步");
		btnNext.setToolTipText("完成当前步骤的必填项后进入下一步");
		Theme.setEnabled(btnNext, false, "先选择实例与证书");
		btnNext.addActionListener(e -> goNext());

		btnReplace = Theme.primaryButton("确认替换");
		btnReplace.setToolTipText("备份原证书并替换，随后执行 nginx -t 校验；失败自动回滚");
		Theme.setEnabled(btnReplace, false, "还需完成前面的步骤后才能替换");
		btnReplace.setVisible(false);
		btnReplace.addActionListener(e -> startReplace());

		btnClose = new JButton("关闭");
		btnClose.addActionListener(e -> {
			if (!busy) {
				dispose();
			}
		});

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		buttons.setOpaque(false);
		buttons.add(btnPrev);
		buttons.add(btnNext);
		buttons.add(btnReplace);
		buttons.add(btnClose);

		JPanel bottom = new JPanel(new BorderLayout(12, 0));
		bottom.setBorder(BorderFactory.createEmptyBorder(6, 12, 10, 12));
		bottom.add(statusCard, BorderLayout.CENTER);
		bottom.add(buttons, BorderLayout.EAST);
		add(bottom, BorderLayout.SOUTH);

		setStep(0);
	}

	/**
	 * 操作日志区: 始终保留在窗口底部, 窗口缩小时优先压缩页面区域,
	 * 日志框固定可见并自带垂直滚动条(内容超出时自动出现).
	 */
	private JPanel buildLogPanel() {
		taLog = new JTextArea();
		taLog.setEditable(false);
		taLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		taLog.setBackground(new Color(252, 252, 252));
		JScrollPane scroll = new JScrollPane(taLog,
				ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
				ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		scroll.setBorder(BorderFactory.createTitledBorder(
				BorderFactory.createLineBorder(Theme.BORDER), "操作日志"));
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setBorder(BorderFactory.createEmptyBorder(4, 12, 8, 12));
		wrap.setPreferredSize(new Dimension(0, 170));
		wrap.add(scroll, BorderLayout.CENTER);
		return wrap;
	}

	// ==================== 分步引导 ====================

	private void goNext() {
		if (busy || currentStep >= STEP_KEYS.length - 1) {
			return;
		}
		if (!stepValid(currentStep)) {
			refreshWizardState();
			return;
		}
		setStep(currentStep + 1);
	}

	private void goPrev() {
		if (busy || currentStep <= 0) {
			return;
		}
		setStep(currentStep - 1);
	}

	/** F7 键盘闭环: Esc 关闭(执行中不中断), 回车触发当前步骤主操作, 切换步骤后焦点落到首个输入控件 */
	private void bindKeyboard() {
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
	}

	private void focusStepInput() {
		if (!isShowing()) {
			return;
		}
		Component target = currentStep == 0 ? cbInstance
				: (currentStep == 1 ? cbMode : btnChoosePem);
		if (target != null && target.isShowing()) {
			target.requestFocusInWindow();
		}
	}

	private void setStep(int step) {
		currentStep = Math.max(0, Math.min(STEP_KEYS.length - 1, step));
		cards.showPage(STEP_KEYS[currentStep]);
		stepIndicator.setCurrent(currentStep);
		btnPrev.setVisible(currentStep > 0);
		btnNext.setVisible(currentStep < STEP_KEYS.length - 1);
		btnReplace.setVisible(currentStep == STEP_KEYS.length - 1);
		refreshWizardState();
		refreshStepReview();
		javax.swing.SwingUtilities.invokeLater(this::focusStepInput);
	}

	/** 当前步骤是否满足必填项要求(与原有提交门槛保持一致) */
	private boolean stepValid(int step) {
		if (step == 0) {
			return selectedInstance() != null && scanned && selectedCert() != null;
		}
		if (step == 1) {
			boolean repoint = cbMode.getSelectedIndex() == 1;
			return StrUtil.isNotEmpty(tfPemTarget.getText().trim())
					&& (!repoint || StrUtil.isNotEmpty(tfKeyTarget.getText().trim()));
		}
		return replaceReady();
	}

	/** 原有「开始替换」按钮的启用条件, 逐字保留 */
	private boolean replaceReady() {
		return selectedInstance() != null
				&& selectedCert() != null
				&& StrUtil.isNotEmpty(pemContent)
				&& StrUtil.isNotEmpty(tfPemTarget.getText())
				&& (cbMode.getSelectedIndex() == 0
						|| (StrUtil.isNotEmpty(keyContent) && StrUtil.isNotEmpty(tfKeyTarget.getText())));
	}

	/** 按钮启用状态 + 当前步骤实时校验提示, 在每次字段变化后刷新 */
	private void refreshWizardState() {
		if (btnPrev == null || btnNext == null || btnReplace == null || lblStepHint == null) {
			return;
		}
		String hint;
		Color color = Theme.TEXT_MUTED;
		if (currentStep == 0) {
			if (selectedInstance() == null) {
				hint = "请选择要操作的 Nginx 实例。";
				color = Theme.DANGER;
			} else if (!scanned && scanFailed) {
				hint = "扫描配置失败，请点击「扫描证书」重试。";
				color = Theme.DANGER;
			} else if (!scanned) {
				hint = "正在扫描实例配置中的 SSL 证书…";
			} else if (selectedCert() == null) {
				hint = "未找到 ssl_certificate 声明，请确认实例后重新扫描。";
				color = Theme.DANGER;
			} else {
				hint = "已识别证书，点击「下一步」继续。";
			}
		} else if (currentStep == 1) {
			boolean repoint = cbMode.getSelectedIndex() == 1;
			if (StrUtil.isEmpty(tfPemTarget.getText().trim())) {
				hint = "目标证书路径不能为空。";
				color = Theme.DANGER;
			} else if (repoint && StrUtil.isEmpty(tfKeyTarget.getText().trim())) {
				hint = "写入新路径时必须指定目标私钥路径。";
				color = Theme.DANGER;
			} else {
				hint = "确认目标路径后点击「下一步」上传新证书。";
			}
		} else if (StrUtil.isEmpty(pemContent)) {
			hint = "请选择新的 PEM 证书文件。";
			color = Theme.DANGER;
		} else if (cbMode.getSelectedIndex() == 1 && StrUtil.isEmpty(keyContent)) {
			hint = "「写入新路径并改写配置」必须同时提供新私钥。";
			color = Theme.DANGER;
		} else {
			hint = "核对信息后点击「确认替换」：替换前自动备份，nginx -t 失败自动回滚。";
		}
		boolean nextOk = !busy && currentStep < STEP_KEYS.length - 1 && stepValid(currentStep);
		Theme.setEnabled(btnNext, nextOk, busy ? "正在执行，请稍候"
				: (currentStep >= STEP_KEYS.length - 1 ? "已是最后一步，请使用「确认替换」" : hint));
		boolean replaceOk = !busy && scanned && replaceReady();
		String replaceReason;
		if (busy) {
			replaceReason = "正在执行，请稍候";
		} else if (!scanned) {
			replaceReason = scanFailed ? "扫描配置失败：先点击「扫描证书」重试" : "正在扫描证书，请稍候";
		} else if (currentStep < STEP_KEYS.length - 1) {
			replaceReason = "还需完成前面的步骤后才能替换";
		} else {
			replaceReason = replaceOk ? null : hint;
		}
		Theme.setEnabled(btnReplace, replaceOk, replaceReason);
		// 回车触发当前步骤主操作; 最后一步的替换仍有独立的二次确认
		getRootPane().setDefaultButton(currentStep < STEP_KEYS.length - 1 ? btnNext : btnReplace);
		lblStepHint.setText(hint);
		lblStepHint.setForeground(color);
		if (currentStep == STEP_KEYS.length - 1) {
			refreshStepReview();
		}
	}

	/** 最后一步的只读核对信息, 汇总前两步的选择 */
	private void refreshStepReview() {
		if (taReview == null) {
			return;
		}
		CertItem item = selectedCert();
		if (item == null) {
			taReview.setText("");
			return;
		}
		boolean repoint = cbMode.getSelectedIndex() == 1;
		String pem = StrUtil.emptyToDefault(tfPemTarget.getText().trim(), "(未设置)");
		String key = repoint
				? StrUtil.emptyToDefault(tfKeyTarget.getText().trim(), "(未设置)")
				: StrUtil.emptyToDefault(item.keyPath, "(保持原私钥)");
		taReview.setText("将替换：域名 " + (StrUtil.isEmpty(item.domain) ? "(未声明)" : item.domain)
				+ " · 方式 " + MODE_LABELS[cbMode.getSelectedIndex()]
				+ "\n目标证书：" + pem
				+ "\n目标私钥：" + key);
	}

	/** 页面底部留白, 让表单内容顶部对齐 */
	private void addPageSpacer(JPanel panel, int gridy) {
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = gridy;
		c.gridwidth = 4;
		c.weighty = 1;
		c.fill = GridBagConstraints.BOTH;
		panel.add(Box.createGlue(), c);
	}

	/** 实例 + 配置中识别到的证书 */
	private JPanel buildSelectPanel() {
		JPanel panel = titled("选择实例与要替换的证书");
		panel.setLayout(new GridBagLayout());

		cbInstance = new JComboBox<NginxInstanceEntry>();
		cbInstance.setRenderer(new DefaultListCellRenderer() {
			private static final long serialVersionUID = 1L;

			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index,
					boolean selected, boolean focus) {
				super.getListCellRendererComponent(list, value, index, selected, focus);
				if (value instanceof NginxInstanceEntry) {
					NginxInstanceEntry e = (NginxInstanceEntry) value;
					setText(StrUtil.emptyToDefault(e.getName(), e.getConfPath()));
					setToolTipText(e.getConfPath());
				}
				return this;
			}
		});
		cbInstance.addActionListener(e -> {
			if (!updating) {
				onInstanceChanged();
			}
		});

		btnScan = new JButton("扫描证书");
		btnScan.setToolTipText("解析实例配置(含 include)中声明的 ssl_certificate / ssl_certificate_key");
		btnScan.addActionListener(e -> scanCertificates());

		cbCert = new JComboBox<CertItem>();
		cbCert.setRenderer(new DefaultListCellRenderer() {
			private static final long serialVersionUID = 1L;

			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index,
					boolean selected, boolean focus) {
				super.getListCellRendererComponent(list, value, index, selected, focus);
				if (value instanceof CertItem) {
					CertItem item = (CertItem) value;
					setText(item.displayText());
					setToolTipText("<html>配置: " + escape(item.sourceConf.getAbsolutePath())
							+ "<br>证书: " + escape(item.pemPath)
							+ "<br>私钥: " + escape(StrUtil.nullToEmpty(item.keyPath)) + "</html>");
				}
				return this;
			}
		});
		Theme.setEnabled(cbCert, false, "正在扫描证书，请稍候");
		cbCert.addActionListener(e -> {
			if (!updating) {
				onCertChanged();
			}
		});

		taInfo = hintArea(2, 30);
		taInfo.setText("尚未扫描。请选择实例后点击「扫描证书」。");

		GridBagConstraints c = new GridBagConstraints();
		c.gridy = 0;
		c.anchor = GridBagConstraints.WEST;
		c.insets = new Insets(0, 0, 4, 6);
		c.gridx = 0;
		panel.add(new JLabel("实例:"), c);
		c.gridx = 1;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		panel.add(cbInstance, c);
		c.weightx = 0;
		c.fill = GridBagConstraints.NONE;
		c.gridx = 2;
		c.insets = new Insets(0, 0, 4, 0);
		panel.add(btnScan, c);

		c.gridy = 1;
		c.gridx = 0;
		c.insets = new Insets(0, 0, 4, 6);
		panel.add(new JLabel("证书:"), c);
		c.gridx = 1;
		c.gridwidth = 2;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.insets = new Insets(0, 0, 4, 0);
		panel.add(cbCert, c);

		c.gridy = 2;
		c.gridx = 0;
		c.gridwidth = 3;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.insets = new Insets(0, 0, 4, 0);
		panel.add(taInfo, c);

		c.gridy = 3;
		c.insets = new Insets(0, 0, 0, 0);
		panel.add(pageDoc("操作说明：选择实例后点击「扫描证书」，再从配置声明的证书中选择要替换的一项。"), c);

		addPageSpacer(panel, 4);
		return panel;
	}

	/** 替换方式 + 证书类型 + 目标路径 */
	private JPanel buildModePanel() {
		JPanel panel = titled("替换方式与目标路径");
		panel.setLayout(new GridBagLayout());

		cbMode = new JComboBox<String>(MODE_LABELS);
		cbMode.addActionListener(e -> {
			if (!updating) {
				applyModeDefaults(true);
				updateReplaceEnabled();
			}
		});

		cbEncryption = new JComboBox<String>(ENC_LABELS);
		cbEncryption.setToolTipText("证书类型用于校验私钥算法；不确定时选择「自动识别」");
		cbEncryption.addActionListener(e -> {
			if (!updating) {
				revalidateKey();
			}
		});

		tfPemTarget = new JTextField();
		tfKeyTarget = new JTextField();
		DocumentListener targetListener = new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				onTargetEdited();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				onTargetEdited();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				onTargetEdited();
			}
		};
		tfPemTarget.getDocument().addDocumentListener(targetListener);
		tfKeyTarget.getDocument().addDocumentListener(targetListener);

		btnChooseDir = new JButton("选择目录…");
		Theme.setEnabled(btnChooseDir, false, "仅「写入新路径并改写配置」时可选择目标目录");
		btnChooseDir.addActionListener(e -> chooseTargetDir());

		taModeHint = hintArea(2, 30);
		taModeHint.setForeground(Theme.TEXT_MUTED);

		GridBagConstraints c = new GridBagConstraints();
		c.gridy = 0;
		c.anchor = GridBagConstraints.WEST;
		c.insets = new Insets(0, 0, 4, 6);
		c.gridx = 0;
		panel.add(new JLabel("替换方式:"), c);
		c.gridx = 1;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		panel.add(cbMode, c);
		c.gridx = 2;
		c.weightx = 0;
		c.fill = GridBagConstraints.NONE;
		c.insets = new Insets(0, 12, 4, 6);
		panel.add(new JLabel("证书类型:"), c);
		c.gridx = 3;
		c.insets = new Insets(0, 0, 4, 0);
		panel.add(cbEncryption, c);

		c.gridy = 1;
		c.gridx = 0;
		c.insets = new Insets(0, 0, 4, 6);
		panel.add(new JLabel("目标证书:"), c);
		c.gridx = 1;
		c.gridwidth = 2;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		panel.add(tfPemTarget, c);
		c.gridx = 3;
		c.gridwidth = 1;
		c.weightx = 0;
		c.fill = GridBagConstraints.NONE;
		c.insets = new Insets(0, 6, 4, 0);
		panel.add(btnChooseDir, c);

		c.gridy = 2;
		c.gridx = 0;
		c.gridwidth = 1;
		c.weightx = 0;
		c.insets = new Insets(0, 0, 4, 6);
		panel.add(new JLabel("目标私钥:"), c);
		c.gridx = 1;
		c.gridwidth = 3;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.insets = new Insets(0, 0, 4, 0);
		panel.add(tfKeyTarget, c);

		c.gridy = 3;
		c.gridx = 0;
		c.gridwidth = 4;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.insets = new Insets(0, 0, 4, 0);
		panel.add(taModeHint, c);

		c.gridy = 4;
		c.insets = new Insets(0, 0, 0, 0);
		panel.add(pageDoc("操作说明：覆盖模式写入配置当前引用的路径；写入新路径模式会同时改写配置中的证书指令。"), c);

		addPageSpacer(panel, 5);
		return panel;
	}

	/** 目标路径实时编辑: 同步「下一步」/「确认替换」启用状态与校验提示 */
	private void onTargetEdited() {
		if (!updating) {
			updateReplaceEnabled();
		}
	}

	/** 步骤页底部操作说明(只读, 不参与业务校验) */
	private JTextArea pageDoc(String text) {
		JTextArea area = hintArea(3, 30);
		area.setForeground(Theme.TEXT_MUTED);
		area.setText(text);
		return area;
	}

	/** 上传新证书 / 私钥 */
	private JPanel buildUploadPanel() {
		JPanel panel = titled("上传新证书与私钥（单文件 ≤ 20MB）");
		panel.setLayout(new GridBagLayout());

		taReview = hintArea(3, 30);
		taReview.setForeground(Theme.TEXT);

		tfPemFile = new JTextField();
		tfPemFile.setEditable(false);
		tfPemFile.putClientProperty("JTextField.placeholderText", "未选择证书文件，点击「选择证书…」");
		tfPemFile.setToolTipText("推荐 PEM：.pem/.cer/.crt，兼容 DER：.der/.cer/.crt、PFX：.pfx");
		btnChoosePem = new JButton("选择证书…");
		btnChoosePem.addActionListener(e -> choosePem());
		tfKeyFile = new JTextField();
		tfKeyFile.setEditable(false);
		tfKeyFile.putClientProperty("JTextField.placeholderText", "未选择私钥文件，点击「选择私钥…」");
		tfKeyFile.setToolTipText("仅支持未加密 PEM 私钥（.pem/.key）");
		btnChooseKey = new JButton("选择私钥…");
		btnChooseKey.addActionListener(e -> chooseKey());

		taPemState = hintArea(2, 30);
		taPemState.setText(PEM_HINT);
		taKeyState = hintArea(2, 30);
		taKeyState.setText(KEY_HINT);

		GridBagConstraints c = new GridBagConstraints();
		c.gridy = 0;
		c.gridx = 0;
		c.gridwidth = 3;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.insets = new Insets(0, 0, 6, 0);
		panel.add(taReview, c);

		c.gridy = 1;
		c.gridx = 0;
		c.gridwidth = 1;
		c.anchor = GridBagConstraints.WEST;
		c.weightx = 0;
		c.insets = new Insets(0, 0, 2, 6);
		panel.add(new JLabel("证书文件:"), c);
		c.gridx = 1;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		panel.add(tfPemFile, c);
		c.gridx = 2;
		c.weightx = 0;
		c.fill = GridBagConstraints.NONE;
		c.insets = new Insets(0, 0, 2, 0);
		panel.add(btnChoosePem, c);

		c.gridy = 2;
		c.gridx = 1;
		c.gridwidth = 2;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.insets = new Insets(0, 0, 6, 0);
		panel.add(taPemState, c);

		c.gridy = 3;
		c.gridx = 0;
		c.gridwidth = 1;
		c.insets = new Insets(0, 0, 2, 6);
		panel.add(new JLabel("私钥文件:"), c);
		c.gridx = 1;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		panel.add(tfKeyFile, c);
		c.gridx = 2;
		c.weightx = 0;
		c.fill = GridBagConstraints.NONE;
		c.insets = new Insets(0, 0, 2, 0);
		panel.add(btnChooseKey, c);

		c.gridy = 4;
		c.gridx = 1;
		c.gridwidth = 2;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.insets = new Insets(0, 0, 4, 0);
		panel.add(taKeyState, c);

		c.gridy = 5;
		c.gridx = 0;
		c.gridwidth = 3;
		c.insets = new Insets(0, 0, 0, 0);
		panel.add(pageDoc("操作说明：推荐 PEM（.pem/.cer/.crt）；兼容 DER（.der/.cer/.crt）与 PFX（.pfx），"
				+ "Nginx 部署前建议转换为 PEM。点击按钮选择文件，提交后自动备份，nginx -t 失败自动回滚。"), c);

		addPageSpacer(panel, 6);
		return panel;
	}

	private JPanel titled(String title) {
		JPanel panel = new JPanel();
		TitledBorder tb = BorderFactory.createTitledBorder(
				BorderFactory.createLineBorder(Theme.BORDER), title);
		tb.setTitleFont(Theme.BODY_BOLD);
		panel.setBorder(BorderFactory.createCompoundBorder(tb,
				BorderFactory.createEmptyBorder(2, 6, 4, 6)));
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

	// ==================== 数据 ====================

	private void loadInstances(NginxInstanceEntry initial) {
		List<NginxInstanceEntry> entries = owner.registryEntries();
		updating = true;
		cbInstance.removeAllItems();
		int select = -1;
		for (int i = 0; i < entries.size(); i++) {
			NginxInstanceEntry e = entries.get(i);
			cbInstance.addItem(e);
			if (initial != null && StrUtil.equals(initial.getId(), e.getId())) {
				select = i;
			}
		}
		updating = false;
		if (entries.isEmpty()) {
			Theme.setEnabled(btnScan, false, "未发现可操作的实例：先在主界面刷新或添加实例");
			setStage("无可操作实例", true);
			setGuide("未发现任何 Nginx 实例，请先在主界面刷新或添加实例。");
			updateReplaceEnabled();
			return;
		}
		if (select < 0) {
			select = 0;
		}
		boolean changed = cbInstance.getSelectedIndex() != select;
		cbInstance.setSelectedIndex(select);
		if (!changed) {
			onInstanceChanged();
		}
	}

	private NginxInstanceEntry selectedInstance() {
		Object o = cbInstance.getSelectedItem();
		return o instanceof NginxInstanceEntry ? (NginxInstanceEntry) o : null;
	}

	private CertItem selectedCert() {
		Object o = cbCert.getSelectedItem();
		return o instanceof CertItem ? (CertItem) o : null;
	}

	private void onInstanceChanged() {
		pemContent = null;
		keyContent = null;
		keyType = null;
		tfPemFile.setText("");
		tfKeyFile.setText("");
		taPemState.setText(PEM_HINT);
		taPemState.setForeground(Theme.TEXT_MUTED);
		taKeyState.setText(KEY_HINT);
		taKeyState.setForeground(Theme.TEXT_MUTED);
		updating = true;
		cbCert.removeAllItems();
		updating = false;
		scanned = false;
		scanFailed = false;
		updateReplaceEnabled();
		scanCertificates();
	}

	private void scanCertificates() {
		final NginxInstanceEntry entry = selectedInstance();
		if (entry == null || busy) {
			return;
		}
		scanned = false;
		scanFailed = false;
		setBusy(true);
		setStage("扫描证书");
		setGuide("正在解析实例配置中的 ssl_certificate / ssl_certificate_key…");
		new SwingWorker<List<CertItem>, Void>() {
			@Override
			protected List<CertItem> doInBackground() {
				return manager.scan(entry);
			}

			@Override
			protected void done() {
				try {
					List<CertItem> items = get();
					updating = true;
					cbCert.removeAllItems();
					for (CertItem item : items) {
						cbCert.addItem(item);
					}
					updating = false;
					scanned = true;
					appendLog("扫描完成：在配置中找到 " + items.size() + " 个 SSL 证书声明");
					if (items.isEmpty()) {
						setStage("未找到证书", true);
						setGuide("该实例配置中没有 ssl_certificate 指令（或配置文件不可读），"
								+ "请确认实例选择是否正确，或先为站点配置 HTTPS。");
						taInfo.setText("未找到 SSL 证书声明。");
						updateReplaceEnabled();
					} else {
						setStage("证书已识别");
						setGuide("已识别证书，请上传新的 PEM 证书与私钥（可选覆盖或写入新路径）。");
						int preselect = -1;
						for (int i = 0; i < items.size(); i++) {
							if (samePath(preselectPemPath, items.get(i).pemPath)) {
								preselect = i;
								break;
							}
						}
						cbCert.setSelectedIndex(preselect >= 0 ? preselect : 0);
					}
				} catch (Exception ex) {
					scanFailed = true;
					appendLog("扫描失败: " + ErrorReporter.friendlyReason(ex));
					setStage("扫描失败", true);
					setGuide("扫描配置失败：" + ErrorReporter.friendlyReason(ex));
				} finally {
					setBusy(false);
					updateReplaceEnabled();
				}
			}
		}.execute();
	}

	private void onCertChanged() {
		CertItem item = selectedCert();
		if (item == null) {
			tfPemTarget.setText("");
			tfKeyTarget.setText("");
			taInfo.setText("");
			return;
		}
		StringBuilder sb = new StringBuilder();
		sb.append("域名 ").append(StrUtil.isEmpty(item.domain) ? "(未声明)" : item.domain);
		sb.append(" · 配置 ").append(item.sourceConf.getName());
		sb.append(" · 当前证书 ").append(new File(item.pemPath).getName());
		if (item.info != null) {
			sb.append("（至 ").append(item.info.notAfterText()).append("）");
		}
		sb.append(" · 私钥 ").append(StrUtil.isEmpty(item.keyPath) ? "(未声明)"
				: new File(item.keyPath).getName());
		taInfo.setText(sb.toString());
		applyModeDefaults(true);
		updateReplaceEnabled();
	}

	private void applyModeDefaults(boolean resetRepoint) {
		CertItem item = selectedCert();
		boolean repoint = cbMode.getSelectedIndex() == 1;

		if (!repoint) {
			if (item != null) {
				tfPemTarget.setText(item.pemPath);
				tfKeyTarget.setText(StrUtil.nullToEmpty(item.keyPath));
			}
			tfPemTarget.setEditable(false);
			tfKeyTarget.setEditable(false);
			Theme.setEnabled(btnChooseDir, false, "当前为「覆盖原证书文件」，目标路径由配置决定");
			taModeHint.setText("覆盖原证书：写入配置当前引用的路径，原文件自动备份。");
		} else {
			if (resetRepoint || StrUtil.isEmpty(tfPemTarget.getText())) {
				fillRepointDefaults(item);
			}
			tfPemTarget.setEditable(!busy);
			tfKeyTarget.setEditable(!busy);
			Theme.setEnabled(btnChooseDir, !busy, busy ? "正在执行，请稍候" : null);
			taModeHint.setText("写入新路径：新证书落到下方路径，并改写配置中的证书指令。");
		}
	}

	private void fillRepointDefaults(CertItem item) {
		NginxInstanceEntry entry = selectedInstance();
		File dir = null;
		if (entry != null) {
			String prefix = StrUtil.emptyToDefault(entry.getPrefixDir(), entry.getNginxDir());
			if (StrUtil.isNotEmpty(prefix)) {
				dir = new File(prefix, "conf/ssl");
			} else if (StrUtil.isNotEmpty(entry.getConfPath())) {
				File parent = new File(entry.getConfPath()).getParentFile();
				dir = parent == null ? null : new File(parent, "ssl");
			}
		}
		if (dir == null) {
			return;
		}
		String base = safeName(item == null ? "" : item.domain);
		tfPemTarget.setText(new File(dir, base + ".pem").getAbsolutePath());
		tfKeyTarget.setText(new File(dir, base + ".key").getAbsolutePath());
	}

	private static String safeName(String domain) {
		String base = StrUtil.isEmpty(domain) ? "cert" : domain.trim().replaceAll("[\\\\/:*?\"<>|,\\s]+", "_");
		return StrUtil.isEmpty(base) ? "cert" : base;
	}

	private void chooseTargetDir() {
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("选择新证书存放目录");
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		File current = new File(tfPemTarget.getText().trim()).getParentFile();
		if (current != null && current.isDirectory()) {
			chooser.setCurrentDirectory(current);
		}
		if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
			return;
		}
		File dir = chooser.getSelectedFile();
		String pemName = new File(tfPemTarget.getText().trim()).getName();
		String keyName = new File(tfKeyTarget.getText().trim()).getName();
		if (StrUtil.isEmpty(pemName)) {
			pemName = "cert.pem";
		}
		if (StrUtil.isEmpty(keyName)) {
			keyName = "cert.key";
		}
		tfPemTarget.setText(new File(dir, pemName).getAbsolutePath());
		tfKeyTarget.setText(new File(dir, keyName).getAbsolutePath());
	}

	// ==================== 上传 ====================

	private void choosePem() {
		File f = chooseFile("选择证书文件", new FileNameExtensionFilter(
				"证书文件 (PEM / DER / PFX)", "pem", "cer", "crt", "der", "pfx"));
		if (f != null) {
			applyPemFile(f);
		}
	}

	private void chooseKey() {
		File f = chooseFile("选择私钥文件", new FileNameExtensionFilter(
				"PEM 私钥 (*.key, *.pem)", "key", "pem"));
		if (f != null) {
			applyKeyFile(f);
		}
	}

	/**
	 * 证书文件统一入口:
	 * 20MB 硬拦截 → 扩展名二次确认(不硬拦截) → 读取并沿用原 PEM 格式/有效期校验.
	 */
	private void applyPemFile(File f) {
		if (f == null || !f.isFile()) {
			return;
		}
		if (!checkFileSize(f, "证书文件", taPemState)) {
			return;
		}
		if (!confirmUnsupportedExtension(f, "证书",
				"PEM：.pem/.cer/.crt；DER：.der/.cer/.crt；PFX：.pfx")) {
			return;
		}
		try {
			pemContent = FileUtil.readString(f, java.nio.charset.StandardCharsets.UTF_8);
		} catch (Exception e) {
			pemContent = null;
			showUploadError(taPemState, "读取证书文件失败：" + ErrorReporter.friendlyReason(e));
			appendLog("读取证书文件失败: " + ErrorReporter.friendlyReason(e));
			return;
		}
		tfPemFile.setText(f.getAbsolutePath());
		appendLog("已选择证书文件: " + f.getName());
		Inspect check = NginxCertReplaceManager.inspectPem(pemContent);
		if (!check.ok()) {
			String ext = extOf(f);
			String tail = ("der".equals(ext) || "pfx".equals(ext))
					? "（Nginx 需 PEM 格式，建议先转换为 PEM 再替换）" : "";
			taPemState.setForeground(Theme.DANGER);
			taPemState.setText("格式错误：" + check.error + tail);
			appendLog("证书校验失败: " + check.error);
		} else {
			CertInfo info = check.cert;
			StringBuilder sb = new StringBuilder();
			sb.append("格式合法 · 域名 ").append(StrUtil.emptyToDefault(info.cn(), "(无 CN)"));
			sb.append(" · 有效期 ").append(info.notBeforeText()).append(" 至 ").append(info.notAfterText());
			sb.append(" · 剩余 ").append(info.daysLeft).append(" 天");
			for (String w : check.warnings) {
				sb.append("；").append(w);
			}
			taPemState.setForeground(check.warnings.isEmpty() ? Theme.statusColor(true) : Theme.WARNING);
			taPemState.setText(sb.toString());
			appendLog("证书校验通过: " + info.subject + "，有效期至 " + info.notAfterText());
			for (String w : check.warnings) {
				appendLog("提示: " + w);
			}
		}
		updateReplaceEnabled();
	}

	/** 私钥文件统一入口 */
	private void applyKeyFile(File f) {
		if (f == null || !f.isFile()) {
			return;
		}
		if (!checkFileSize(f, "私钥文件", taKeyState)) {
			return;
		}
		if (!confirmUnsupportedExtension(f, "私钥", "PEM 私钥：.pem/.key")) {
			return;
		}
		try {
			keyContent = FileUtil.readString(f, java.nio.charset.StandardCharsets.UTF_8);
		} catch (Exception e) {
			keyContent = null;
			showUploadError(taKeyState, "读取私钥文件失败：" + ErrorReporter.friendlyReason(e));
			appendLog("读取私钥文件失败: " + ErrorReporter.friendlyReason(e));
			return;
		}
		tfKeyFile.setText(f.getAbsolutePath());
		appendLog("已选择私钥文件: " + f.getName());
		revalidateKey();
	}

	/** 20MB 上限硬拦截: 超出直接拒绝并给出明确错误, 不进入后续流程 */
	private boolean checkFileSize(File f, String label, JTextArea state) {
		long size = f.length();
		if (size <= MAX_UPLOAD_BYTES) {
			return true;
		}
		String text = label + "超过 20MB 上限（当前 "
				+ String.format(Locale.ROOT, "%.1f MB", size / 1048576.0) + "），已拦截";
		showUploadError(state, text);
		return false;
	}

	/**
	 * 扩展名不在 Nginx 支持清单内时不直接拦截, 弹出二次确认;
	 * 用户确认强制上传后仍走原有的内容格式校验.
	 */
	private boolean confirmUnsupportedExtension(File f, String label, String allowedText) {
		String ext = extOf(f);
		for (String allowed : "证书".equals(label) ? CERT_EXTENSIONS : KEY_EXTENSIONS) {
			if (allowed.equalsIgnoreCase(ext)) {
				return true;
			}
		}
		String shown = ext.isEmpty() ? "（无扩展名）" : "." + ext;
		String message = "当前" + label + "文件扩展名 " + shown
				+ " 不在 Nginx 支持的格式范围内。\n支持：" + allowedText + "\n\n"
				+ "强制上传可能导致格式校验或 nginx -t 检查失败，是否仍要继续？";
		int r = JOptionPane.showConfirmDialog(this, message, "nginxUI - 格式确认",
				JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
		if (r == JOptionPane.YES_OPTION) {
			appendLog("用户确认强制上传不受支持的" + label + "文件: " + f.getName() + "（" + shown + "）");
			return true;
		}
		appendLog("已取消上传不受支持的" + label + "文件: " + f.getName() + "（" + shown + "）");
		return false;
	}

	private static String extOf(File f) {
		String name = f.getName();
		int dot = name.lastIndexOf('.');
		if (dot < 0 || dot == name.length() - 1) {
			return "";
		}
		return name.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	/** 重新按当前证书类型校验私钥 */
	private void revalidateKey() {
		if (StrUtil.isEmpty(keyContent)) {
			updateReplaceEnabled();
			return;
		}
		String expected = cbEncryption.getSelectedIndex() <= 0 ? null : ENC_LABELS[cbEncryption.getSelectedIndex()];
		Inspect check = NginxCertReplaceManager.inspectKey(keyContent, expected);
		if (!check.ok()) {
			keyType = null;
			taKeyState.setForeground(Theme.DANGER);
			taKeyState.setText("格式错误：" + check.error);
			appendLog("私钥校验失败: " + check.error);
		} else {
			keyType = check.keyType;
			taKeyState.setForeground(Theme.statusColor(true));
			taKeyState.setText("格式合法 · " + keyType + " 私钥");
		}
		updateReplaceEnabled();
	}

	private void showUploadError(JTextArea area, String text) {
		area.setForeground(Theme.DANGER);
		area.setText(text);
		appendLog(text);
		updateReplaceEnabled();
	}

	private File chooseFile(String title, FileNameExtensionFilter filter) {
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle(title);
		chooser.setFileFilter(filter);
		// 允许选择清单外的扩展名: 后续走二次确认, 而不是让文件在对话框里不可见
		chooser.addChoosableFileFilter(chooser.getAcceptAllFileFilter());
		String dir = StrUtil.isEmpty(tfPemFile.getText()) ? tfKeyFile.getText() : tfPemFile.getText();
		if (StrUtil.isNotEmpty(dir)) {
			File parent = new File(dir).getParentFile();
			if (parent != null && parent.isDirectory()) {
				chooser.setCurrentDirectory(parent);
			}
		}
		return chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION
				? chooser.getSelectedFile() : null;
	}

	// ==================== 替换 ====================

	/** 提交门槛沿用原逻辑, 同时刷新分步按钮与校验提示 */
	private void updateReplaceEnabled() {
		refreshWizardState();
	}

	private void startReplace() {
		if (busy) {
			return;
		}
		final NginxInstanceEntry entry = selectedInstance();
		final CertItem item = selectedCert();
		if (entry == null || item == null) {
			JOptionPane.showMessageDialog(this, "请先扫描并选择要替换的证书。",
					"nginxUI - 证书替换", JOptionPane.WARNING_MESSAGE);
			return;
		}
		if (StrUtil.isEmpty(pemContent)) {
			JOptionPane.showMessageDialog(this, "请先选择并校验新的证书文件。",
					"nginxUI - 证书替换", JOptionPane.WARNING_MESSAGE);
			return;
		}
		boolean repoint = cbMode.getSelectedIndex() == 1;
		if (repoint && StrUtil.isEmpty(keyContent)) {
			JOptionPane.showMessageDialog(this, "「写入新路径并改写配置」必须同时提供新私钥。",
					"nginxUI - 证书替换", JOptionPane.WARNING_MESSAGE);
			return;
		}
		final String targetPem = tfPemTarget.getText().trim();
		final String targetKey = tfKeyTarget.getText().trim();
		if (StrUtil.isEmpty(targetPem)) {
			JOptionPane.showMessageDialog(this, "目标证书路径不能为空。",
					"nginxUI - 证书替换", JOptionPane.WARNING_MESSAGE);
			return;
		}
		if (repoint && StrUtil.isEmpty(targetKey)) {
			JOptionPane.showMessageDialog(this, "目标私钥路径不能为空。",
					"nginxUI - 证书替换", JOptionPane.WARNING_MESSAGE);
			return;
		}

		NginxCertReplaceManager.Precheck precheck = manager.precheck(item, entry);
		for (String warning : precheck.warnings) {
			appendLog("预检提示: " + warning);
		}
		if (!precheck.ok) {
			JOptionPane.showMessageDialog(this, precheck.message,
					"nginxUI - 无法替换证书", JOptionPane.WARNING_MESSAGE);
			return;
		}

		StringBuilder object = new StringBuilder();
		object.append("实例「").append(StrUtil.emptyToDefault(entry.getName(), entry.getConfPath()))
				.append("」\n方式：").append(MODE_LABELS[cbMode.getSelectedIndex()])
				.append("\n证书类型：").append(ENC_LABELS[cbEncryption.getSelectedIndex()])
				.append("\n目标证书：").append(targetPem)
				.append("\n目标私钥：").append(StrUtil.emptyToDefault(targetKey, "(保持原私钥)"));
		String consequence = "将用新证书覆盖目标文件（或写入新路径并改写配置），随后执行 nginx -t 校验；"
				+ "校验失败时替换不会生效。";
		String reversibility = "可撤销：替换前会自动备份原文件，失败将自动回滚；"
				+ "也可通过证书备份恢复还原到替换前状态。";
		if (!ConfirmDialog.confirm(this, "确认证书替换", object.toString(),
				consequence, reversibility, "替换证书")) {
			return;
		}

		if (!OperationLock.tryAcquire("替换证书")) {
			showError("nginxUI - 证书替换", OperationLock.reason());
			return;
		}
		setBusy(true);
		setStage(repoint ? "写入并改写配置" : "覆盖原证书");
		setGuide("正在备份原文件并写入新证书…");

		final ReplaceRequest req = new ReplaceRequest();
		req.entry = entry;
		req.mode = repoint ? NginxCertReplaceManager.MODE_REPOINT
				: NginxCertReplaceManager.MODE_OVERWRITE;
		req.sourceConf = item.sourceConf.getAbsolutePath();
		req.pemPath = item.pemPath;
		req.keyPath = item.keyPath;
		req.targetPem = targetPem;
		req.targetKey = targetKey;
		req.newPemContent = pemContent;
		req.newKeyContent = keyContent;
		req.encryption = ENC_LABELS[cbEncryption.getSelectedIndex()];

		new SwingWorker<ReplaceResult, Void>() {
			@Override
			protected ReplaceResult doInBackground() {
				return manager.replace(req);
			}

			@Override
			protected void done() {
				OperationLock.release();
				setBusy(false);
				ReplaceResult r;
				try {
					r = get();
				} catch (Exception ex) {
					appendLog("替换执行失败: " + ErrorReporter.friendlyReason(ex));
					setStage("替换失败", true);
					setGuide("替换执行失败：" + ErrorReporter.friendlyReason(ex));
					showError("nginxUI - 证书替换失败",
							ErrorReporter.uiText("替换证书", ex, "未写入任何变更；请检查文件权限后重试。"));
					return;
				}
				if (!r.success) {
					setStage("替换失败", true);
					setGuide(StrUtil.emptyToDefault(r.message, "证书替换失败"));
					appendLog("替换失败: " + r.message);
					if (StrUtil.isNotEmpty(r.output)) {
						appendLog("nginx -t 输出: " + r.output.trim());
					}
					showError("nginxUI - 证书替换失败", r.message
							+ (r.rolledBack ? "\n\n已自动回滚到替换前状态。" : "")
							+ (StrUtil.isEmpty(r.output) ? "" : "\n\nnginx -t 输出:\n" + r.output));
					return;
				}

				setStage("替换成功");
				StringBuilder guide = new StringBuilder("证书已替换并通过 nginx -t 校验。");
				if (!r.backups.isEmpty()) {
					guide.append("备份 ").append(r.backups.size()).append(" 个文件。");
				}
				setGuide(guide.toString());
				appendLog(r.message);
				for (String bak : r.backups) {
					appendLog("已备份: " + bak);
				}
				for (String w : r.warnings) {
					appendLog("提示: " + w);
				}
				if (r.appliedCert != null) {
					appendLog("生效证书: " + r.appliedCert.subject
							+ "，有效期至 " + r.appliedCert.notAfterText());
				}
				owner.onUpgradeFinished();
				JOptionPane.showMessageDialog(CertReplaceDialog.this,
						"证书已替换并通过配置检测。"
								+ (r.warnings.isEmpty() ? "" : "\n\n注意：" + String.join("\n", r.warnings)),
						"nginxUI - 证书替换成功", JOptionPane.INFORMATION_MESSAGE);
				confirmRestart(entry, r);
			}
		}.execute();
	}

	/**
	 * 替换成功后的二次确认: 立即重启生效, 或稍后手动操作.
	 * 服务托管与进程实例统一走重启(Windows 下平滑重载不可靠, 已移除 reload).
	 */
	private void confirmRestart(final NginxInstanceEntry entry, ReplaceResult result) {
		if (!result.running) {
			setGuide("证书已替换；实例当前未运行，新证书将在下次启动时自动生效。");
			appendLog("实例当前未运行，无需重启；新证书将在下次启动时生效。");
			scanCertificates();
			return;
		}
		final String action = "restart";
		final String verb = "重启";
		String object = "实例「" + StrUtil.emptyToDefault(entry.getName(), entry.getConfPath()) + "」";
		String consequence = "证书已替换并通过配置检测；重启期间该实例的站点会短暂无法访问。";
		String reversibility = "可取消：取消后新证书保持不变，可稍后手动重启使其生效。";
		if (!ConfirmDialog.confirm(this, "是否立即重启", object, consequence,
				reversibility, "立即" + verb)) {
			setGuide("证书已替换；已跳过立即" + verb + "，请稍后手动" + verb + "使新证书生效。");
			appendLog("已跳过立即" + verb + "：请在合适时间手动" + verb + "该实例使新证书生效。");
			scanCertificates();
			return;
		}

		setBusy(true);
		setStage(verb + "中");
		setGuide("正在" + verb + "实例…");
		new SwingWorker<NginxDashboardUtils.ControlResult, Void>() {
			@Override
			protected NginxDashboardUtils.ControlResult doInBackground() {
				return NginxDashboardUtils.controlInstance(entry, action);
			}

			@Override
			protected void done() {
				setBusy(false);
				try {
					NginxDashboardUtils.ControlResult cr = get();
					if (cr.success) {
						setStage("替换完成");
						setGuide("证书替换完成，已" + verb + "生效。");
						appendLog(verb + "成功: " + StrUtil.emptyToDefault(cr.message, ""));
					} else {
						setStage(verb + "失败", true);
						setGuide("证书已替换，但" + verb + "失败：" + cr.message);
						appendLog(verb + "失败: " + cr.message);
						showError("nginxUI - " + verb + "失败",
								"证书已替换，但" + verb + "失败：" + cr.message
										+ "\n\n请稍后手动" + verb + "实例使新证书生效。");
					}
				} catch (Exception ex) {
					setStage(verb + "失败", true);
					setGuide("证书已替换，但" + verb + "失败：" + ErrorReporter.friendlyReason(ex));
					appendLog(verb + "失败: " + ErrorReporter.friendlyReason(ex));
					showError("nginxUI - " + verb + "失败",
							"证书已替换，但" + verb + "未完成。\n原因："
									+ ErrorReporter.friendlyReason(ex)
									+ "\n\n请稍后手动" + verb + "实例使新证书生效。");
				} finally {
					owner.onUpgradeFinished();
					scanCertificates();
				}
			}
		}.execute();
	}

	// ==================== 状态 / 日志 ====================

	private void setBusy(boolean value) {
		this.busy = value;
		String reason = value ? "正在执行，请稍候" : null;
		boolean repoint = cbMode.getSelectedIndex() == 1;
		Theme.setEnabled(cbInstance, !value, reason);
		Theme.setEnabled(btnScan, !value, reason);
		Theme.setEnabled(cbCert, !value && scanned,
				value ? reason : "尚未扫描到可替换的证书，先点击「扫描证书」");
		Theme.setEnabled(cbMode, !value, reason);
		Theme.setEnabled(cbEncryption, !value, reason);
		Theme.setEnabled(btnChoosePem, !value, reason);
		Theme.setEnabled(btnChooseKey, !value, reason);
		tfPemTarget.setEditable(!value && repoint);
		tfKeyTarget.setEditable(!value && repoint);
		Theme.setEnabled(btnChooseDir, !value && repoint,
				value ? reason : (repoint ? null : "仅「写入新路径并改写配置」时可选择目标目录"));
		Theme.setEnabled(btnClose, !value, reason);
		updateReplaceEnabled();
	}

	private void setStage(String label) {
		setStage(label, false);
	}

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

	private static String escape(String text) {
		return StrUtil.nullToEmpty(text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static boolean samePath(String a, String b) {
		if (StrUtil.isEmpty(a) || StrUtil.isEmpty(b)) {
			return false;
		}
		return new File(a).getAbsolutePath().equalsIgnoreCase(new File(b).getAbsolutePath());
	}

	/**
	 * 步骤进度指示器: 三个页面共用同一实例, 位置/样式固定.
	 * 已完成=实心+对勾, 当前=实心+序号, 未开始=白底+边框.
	 */
	private static final class StepIndicator extends JPanel {
		private static final long serialVersionUID = 1L;
		private static final int CIRCLE = 26;
		private final String[] titles;
		private int current;

		StepIndicator(String[] titles) {
			this.titles = titles;
			setOpaque(false);
			setPreferredSize(new Dimension(0, 56));
		}

		void setCurrent(int index) {
			this.current = index;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
						RenderingHints.VALUE_ANTIALIAS_ON);
				int n = titles.length;
				int w = getWidth();
				// 等分单元格: 圆点与标题在一个单元内居中, 最后一步的标题不会被裁切
				int margin = 8;
				int cell = Math.max(1, (w - margin * 2) / n);
				int circleY = 6;
				int[] cx = new int[n];
				for (int i = 0; i < n; i++) {
					cx[i] = margin + cell * i + cell / 2;
				}
				if (n > 1) {
					for (int i = 0; i < n - 1; i++) {
						int x1 = cx[i] + CIRCLE / 2 + 4;
						int x2 = cx[i + 1] - CIRCLE / 2 - 4;
						g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND,
								BasicStroke.JOIN_ROUND));
						g2.setColor(i < current ? Theme.PRIMARY : Theme.BORDER);
						g2.drawLine(x1, circleY + CIRCLE / 2, x2, circleY + CIRCLE / 2);
					}
				}
				for (int i = 0; i < n; i++) {
					boolean done = i < current;
					boolean active = i == current;
					int x = cx[i] - CIRCLE / 2;
					if (done || active) {
						g2.setColor(Theme.PRIMARY);
						g2.fillOval(x, circleY, CIRCLE, CIRCLE);
					} else {
						g2.setColor(Theme.SURFACE);
						g2.fillOval(x, circleY, CIRCLE, CIRCLE);
						g2.setColor(Theme.BORDER);
						g2.drawOval(x, circleY, CIRCLE, CIRCLE);
					}
					if (done) {
						g2.setColor(Color.WHITE);
						g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND,
								BasicStroke.JOIN_ROUND));
						g2.drawLine(x + 7, circleY + 13, x + 11, circleY + 17);
						g2.drawLine(x + 11, circleY + 17, x + 19, circleY + 9);
					} else {
						String num = String.valueOf(i + 1);
						Font f = Theme.SMALL;
						g2.setFont(f);
						FontMetrics fm = g2.getFontMetrics();
						g2.setColor(active ? Color.WHITE : Theme.TEXT_MUTED);
						g2.drawString(num, x + (CIRCLE - fm.stringWidth(num)) / 2,
								circleY + (CIRCLE - fm.getHeight()) / 2 + fm.getAscent());
					}

					String label = titles[i];
					Font f = active ? Theme.BODY_BOLD : Theme.SMALL;
					g2.setFont(f);
					FontMetrics fm = g2.getFontMetrics();
					g2.setColor(active || done ? Theme.TEXT : Theme.TEXT_MUTED);
					g2.drawString(label, cx[i] - fm.stringWidth(label) / 2, circleY + CIRCLE + 16);
				}
			} finally {
				g2.dispose();
			}
		}
	}

	/**
	 * 步骤页面容器: CardLayout 切换 + 轻量滑动淡入动画.
	 * 动画期间直接绘制切换前后的两张快照, 结束后恢复常规绘制.
	 */
	private static final class WizardCards extends JPanel {
		private static final long serialVersionUID = 1L;
		private final CardLayout cardLayout = new CardLayout();
		private String currentKey;
		private BufferedImage shotFrom;
		private BufferedImage shotTo;
		private Timer timer;
		private float progress;

		WizardCards() {
			setLayout(cardLayout);
			setOpaque(true);
			setBackground(Theme.BG);
		}

		void addPage(String key, Component page) {
			add(page, key);
		}

		void showPage(String key) {
			if (key.equals(currentKey)) {
				return;
			}
			stopAnimation();
			if (!isShowing() || getWidth() <= 0 || getHeight() <= 0) {
				cardLayout.show(this, key);
				currentKey = key;
				shotFrom = null;
				shotTo = null;
				repaint();
				return;
			}
			shotFrom = snapshot();
			cardLayout.show(this, key);
			currentKey = key;
			revalidate();
			doLayout();
			shotTo = snapshot();
			progress = 0f;
			timer = new Timer(15, e -> {
				progress = Math.min(1f, progress + 0.13f);
				if (progress >= 1f) {
					stopAnimation();
				}
				repaint();
			});
			timer.start();
		}

		private void stopAnimation() {
			if (timer != null) {
				timer.stop();
				timer = null;
			}
			shotFrom = null;
			shotTo = null;
			progress = 0f;
		}

		private BufferedImage snapshot() {
			int w = getWidth();
			int h = getHeight();
			if (w <= 0 || h <= 0) {
				return null;
			}
			BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g2 = img.createGraphics();
			try {
				printAll(g2);
			} finally {
				g2.dispose();
			}
			return img;
		}

		@Override
		protected void paintChildren(Graphics g) {
			boolean animating = shotFrom != null && shotTo != null
					&& shotFrom.getWidth() == getWidth()
					&& shotFrom.getHeight() == getHeight();
			if (!animating) {
				super.paintChildren(g);
				return;
			}
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
						RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(getBackground());
				g2.fillRect(0, 0, getWidth(), getHeight());
				float eased = 1f - (float) Math.pow(1f - progress, 3);
				int offset = Math.max(10, getHeight() / 30);
				g2.drawImage(shotTo, 0, (int) ((1f - eased) * offset), null);
				Composite old = g2.getComposite();
				g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f - eased));
				g2.drawImage(shotFrom, 0, -(int) (eased * offset), null);
				g2.setComposite(old);
			} finally {
				g2.dispose();
			}
		}
	}

	/** 圆角状态徽标 */
	private static final class PillLabel extends JLabel {
		private static final long serialVersionUID = 1L;
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
		public void paintIcon(Component c, Graphics g, int x, int y) {
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

	/** 状态卡: 白底圆角 + 左侧状态色竖条 */
	private static final class StatusCard extends JPanel {
		private static final long serialVersionUID = 1L;
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
