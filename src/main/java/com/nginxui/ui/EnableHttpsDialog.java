package com.nginxui.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import com.nginxui.ext.NginxInstanceEntry;
import com.nginxui.utils.CertificateStoreManager;
import com.nginxui.utils.CertificateStoreManager.ManagedCert;
import com.nginxui.utils.NginxCertReplaceManager;
import com.nginxui.utils.NginxCertReplaceManager.CertInfo;
import com.nginxui.utils.NginxCertReplaceManager.Inspect;
import com.nginxui.utils.NginxHttpsManager;
import com.nginxui.utils.NginxHttpsManager.EnableRequest;
import com.nginxui.utils.NginxHttpsManager.EnableResult;
import com.nginxui.utils.NginxHttpsManager.PortCheck;
import com.nginxui.utils.NginxSite;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;

/**
 * 站点级 HTTPS 启用向导(3 步):
 *  1. 站点与端口: 展示站点现状, 选择 HTTPS 端口并做占用预检;
 *  2. 证书来源: 从证书库选择或上传 PEM 证书/私钥, 复用统一校验;
 *  3. 确认执行: 明确列出后果与回滚策略, 只有点击「启用 HTTPS」才真正写入配置.
 *
 * 危险操作不设默认按钮, Esc / 关闭窗口即取消; 执行期间禁止关闭.
 */
public class EnableHttpsDialog extends JDialog {

	private static final int STEP_SITE = 0;
	private static final int STEP_CERT = 1;
	private static final int STEP_CONFIRM = 2;
	private static final int MAX_CERT_BYTES = 20 * 1024 * 1024;

	private final NginxInstanceEntry entry;
	private final List<NginxSite> allSites;
	private final NginxSite site;
	private final NginxHttpsManager manager;
	private final CertificateStoreManager certStore;
	private final File home;

	private final CardLayout stepLayout = new CardLayout();
	private final JPanel stepCards = new JPanel(stepLayout);
	private JLabel lblStepTitle;
	private JLabel lblStepPill;

	private JTextField tfPort;
	private JLabel lblPortState;
	private JButton btnCheckPort;

	private JRadioButton rbLibrary;
	private JRadioButton rbUpload;
	private JComboBox<ManagedCert> cbLibrary;
	private JTextField tfPem;
	private JTextField tfKey;
	private JTextArea taCertInfo;

	private JTextArea taSummary;
	private JTextArea taLog;

	private JButton btnClose;
	private JButton btnBack;
	private JButton btnNext;
	private JButton btnApply;

	private int step = STEP_SITE;
	private boolean busy;
	private PortCheck portCheck;
	private File pemFile;
	private File keyFile;
	private CertInfo certInfo;
	private String certError;

	public EnableHttpsDialog(MainFrame owner, NginxInstanceEntry entry, List<NginxSite> allSites,
			NginxSite site) {
		super(owner, "启用 HTTPS - " + site.domain(), true);
		this.entry = entry;
		this.allSites = allSites;
		this.site = site;
		this.home = new File(owner.appHome());
		this.manager = new NginxHttpsManager(home);
		this.certStore = new CertificateStoreManager(home);

		setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
		setMinimumSize(new Dimension(760, 620));
		setSize(860, 720);
		setLocationRelativeTo(owner);
		setLayout(new BorderLayout());
		getContentPane().setBackground(Theme.BG);

		add(buildHeader(), BorderLayout.NORTH);
		add(buildBody(), BorderLayout.CENTER);
		add(buildFooter(), BorderLayout.SOUTH);

		bindEscape();
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				if (!busy) {
					dispose();
				}
			}
		});
		setStep(STEP_SITE);
		loadLibrary();
		javax.swing.SwingUtilities.invokeLater(() -> {
			checkPortNow();
			// 初始焦点放在第一步的端口输入, Tab 从首个可交互控件开始
			if (tfPort != null) {
				tfPort.requestFocusInWindow();
			}
		});
	}

	// ==================== 头部 ====================

	private JPanel buildHeader() {
		JPanel header = new JPanel(new BorderLayout(8, 4));
		header.setBackground(Theme.SURFACE);
		header.setBorder(BorderFactory.createEmptyBorder(14, 20, 12, 20));

		lblStepTitle = new JLabel();
		lblStepTitle.setFont(Theme.TITLE);
		lblStepTitle.setForeground(Theme.TEXT);

		JLabel sub = new JLabel("站点 = nginx server 块（域名 + 监听端口）；执行前自动备份，失败自动回滚");
		sub.setFont(Theme.SMALL);
		sub.setForeground(Theme.TEXT_MUTED);

		JPanel left = new JPanel(new BorderLayout(0, 2));
		left.setOpaque(false);
		left.add(lblStepTitle, BorderLayout.NORTH);
		left.add(sub, BorderLayout.SOUTH);

		lblStepPill = new JLabel();
		lblStepPill.setFont(Theme.BODY_BOLD);
		lblStepPill.setForeground(Theme.PRIMARY);
		lblStepPill.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(Theme.PRIMARY),
				BorderFactory.createEmptyBorder(3, 10, 3, 10)));

		header.add(left, BorderLayout.WEST);
		header.add(lblStepPill, BorderLayout.EAST);
		return header;
	}

	private JPanel buildBody() {
		JPanel body = new JPanel(new BorderLayout(0, 10));
		body.setOpaque(false);
		body.setBorder(BorderFactory.createEmptyBorder(12, 20, 8, 20));

		stepCards.setOpaque(false);
		stepCards.add(buildSiteStep(), "site");
		stepCards.add(buildCertStep(), "cert");
		stepCards.add(buildConfirmStep(), "confirm");
		body.add(stepCards, BorderLayout.CENTER);

		JPanel logPanel = new JPanel(new BorderLayout());
		logPanel.setOpaque(false);
		JLabel logTitle = new JLabel("执行日志");
		logTitle.setFont(Theme.SMALL);
		logTitle.setForeground(Theme.TEXT_MUTED);
		taLog = new JTextArea(5, 20);
		taLog.setEditable(false);
		taLog.setFont(Theme.MONO);
		taLog.setBackground(new java.awt.Color(250, 250, 250));
		JScrollPane logScroll = new JScrollPane(taLog);
		logScroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
		logPanel.add(logTitle, BorderLayout.NORTH);
		logPanel.add(logScroll, BorderLayout.CENTER);
		logPanel.setPreferredSize(new Dimension(0, 118));
		body.add(logPanel, BorderLayout.SOUTH);
		return body;
	}

	private JPanel buildFooter() {
		JPanel footer = new JPanel(new BorderLayout());
		footer.setBackground(Theme.SURFACE);
		footer.setBorder(BorderFactory.createEmptyBorder(10, 20, 12, 20));

		JLabel hint = new JLabel("提示：启用过程会备份当前配置；任一检查失败将恢复原配置并重启回原状态。");
		hint.setFont(Theme.SMALL);
		hint.setForeground(Theme.TEXT_MUTED);

		btnClose = Theme.secondaryButton("取消");
		btnClose.addActionListener(e -> {
			if (!busy) {
				dispose();
			}
		});
		btnBack = Theme.toolButton("上一步");
		btnBack.addActionListener(e -> setStep(step - 1));
		btnNext = Theme.primaryButton("下一步");
		btnNext.addActionListener(e -> goNext());
		btnApply = Theme.primaryButton("启用 HTTPS");
		btnApply.addActionListener(e -> apply());

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		buttons.setOpaque(false);
		buttons.add(btnClose);
		buttons.add(btnBack);
		buttons.add(btnNext);
		buttons.add(btnApply);

		footer.add(hint, BorderLayout.WEST);
		footer.add(buttons, BorderLayout.EAST);
		return footer;
	}

	// ==================== 第 1 步: 站点与端口 ====================

	private JPanel buildSiteStep() {
		JPanel card = new JPanel(new GridBagLayout());
		card.setOpaque(false);
		card.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(Theme.BORDER),
				BorderFactory.createEmptyBorder(16, 18, 16, 18)));
		card.setBackground(Theme.SURFACE);
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.anchor = GridBagConstraints.NORTHWEST;

		c.gridy = 0;
		card.add(sectionTitle("当前站点"), c);
		c.gridy = 1;
		card.add(infoLine("站点域名", site.domain()), c);
		c.gridy = 2;
		card.add(infoLine("server_name", site.serverNamesText()), c);
		c.gridy = 3;
		card.add(infoLine("当前监听", site.portsWithSslText()), c);
		c.gridy = 4;
		card.add(infoLine("来源配置", site.sourceConf == null ? "(未知)"
				: site.sourceConf.getAbsolutePath()), c);
		c.gridy = 5;
		c.insets = new Insets(14, 0, 4, 0);
		card.add(sectionTitle("HTTPS 端口"), c);

		JPanel portRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
		portRow.setOpaque(false);
		tfPort = new JTextField("443", 8);
		tfPort.setFont(Theme.BODY);
		tfPort.setPreferredSize(new Dimension(90, Theme.BUTTON_HEIGHT));
		tfPort.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				invalidatePort();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				invalidatePort();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				invalidatePort();
			}
		});
		btnCheckPort = Theme.toolButton("检测端口");
		btnCheckPort.addActionListener(e -> checkPortNow());
		lblPortState = new JLabel(" ");
		lblPortState.setFont(Theme.SMALL);
		lblPortState.setForeground(Theme.TEXT_MUTED);
		portRow.add(new JLabel("端口:"));
		portRow.add(tfPort);
		portRow.add(btnCheckPort);
		portRow.add(lblPortState);

		c.gridy = 6;
		c.insets = new Insets(0, 0, 0, 0);
		card.add(portRow, c);

		JLabel note = new JLabel("<html>端口预检优先识别本实例内的占用（同端口多站点按 SNI 共享）；"
				+ "若端口被外部进程占用将被阻断。</html>");
		note.setFont(Theme.SMALL);
		note.setForeground(Theme.TEXT_MUTED);
		c.gridy = 7;
		c.insets = new Insets(12, 0, 0, 0);
		card.add(note, c);

		c.gridy = 8;
		c.weighty = 1;
		c.fill = GridBagConstraints.BOTH;
		card.add(new JPanel(), c);
		return card;
	}

	private void checkPortNow() {
		int port = parsePort();
		if (port < 0) {
			portCheck = null;
			lblPortState.setText("端口格式无效（1-65535）");
			lblPortState.setForeground(Theme.DANGER);
			return;
		}
		portCheck = manager.checkPort(entry, allSites, site, port);
		lblPortState.setText(portCheck.message);
		lblPortState.setForeground(!portCheck.ok ? Theme.DANGER
				: (portCheck.shared ? Theme.WARNING : Theme.SUCCESS));
		updateNav();
	}

	private void invalidatePort() {
		portCheck = null;
		if (lblPortState != null) {
			lblPortState.setText("端口已修改，请重新检测");
			lblPortState.setForeground(Theme.TEXT_MUTED);
		}
		updateNav();
	}

	private int parsePort() {
		String text = tfPort.getText().trim();
		if (!text.matches("\\d{1,5}")) {
			return -1;
		}
		int port = Integer.parseInt(text);
		return port >= 1 && port <= 65535 ? port : -1;
	}

	// ==================== 第 2 步: 证书 ====================

	private JPanel buildCertStep() {
		JPanel card = new JPanel(new BorderLayout(0, 10));
		card.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(Theme.BORDER),
				BorderFactory.createEmptyBorder(16, 18, 16, 18)));
		card.setBackground(Theme.SURFACE);

		rbLibrary = new JRadioButton("从证书库选择（推荐）");
		rbUpload = new JRadioButton("上传证书文件");
		rbLibrary.setFont(Theme.BODY_BOLD);
		rbUpload.setFont(Theme.BODY);
		rbLibrary.setOpaque(false);
		rbUpload.setOpaque(false);
		ButtonGroup group = new ButtonGroup();
		group.add(rbLibrary);
		group.add(rbUpload);
		rbLibrary.setSelected(true);
		rbLibrary.addActionListener(e -> {
			updateCertSource();
			validateCert();
		});
		rbUpload.addActionListener(e -> {
			updateCertSource();
			validateCert();
		});

		JPanel sourcePanel = new JPanel(new CardLayout());
		sourcePanel.setOpaque(false);
		sourcePanel.add(buildLibraryPanel(), "library");
		sourcePanel.add(buildUploadPanel(), "upload");
		sourcePanel.setName("certSource");

		cbLibrary = (JComboBox<ManagedCert>) findByName(sourcePanel, "certLibrary");
		tfPem = (JTextField) findByName(sourcePanel, "certPem");
		tfKey = (JTextField) findByName(sourcePanel, "certKey");

		JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 0));
		options.setOpaque(false);
		options.add(rbLibrary);
		options.add(rbUpload);

		taCertInfo = new JTextArea(5, 20);
		taCertInfo.setEditable(false);
		taCertInfo.setFont(Theme.SMALL);
		taCertInfo.setBackground(new java.awt.Color(250, 250, 250));
		JScrollPane infoScroll = new JScrollPane(taCertInfo);
		infoScroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));

		card.add(sectionTitle("证书来源"), BorderLayout.NORTH);
		JPanel center = new JPanel(new BorderLayout(0, 10));
		center.setOpaque(false);
		center.add(options, BorderLayout.NORTH);
		center.add(sourcePanel, BorderLayout.CENTER);
		center.add(infoScroll, BorderLayout.SOUTH);
		card.add(center, BorderLayout.CENTER);

		updateCertSource();
		return card;
	}

	private JPanel buildLibraryPanel() {
		JPanel panel = new JPanel(new BorderLayout(8, 4));
		panel.setOpaque(false);
		panel.setName("libraryPanel");
		JLabel label = new JLabel("证书库:");
		label.setFont(Theme.BODY);
		cbLibrary = new JComboBox<ManagedCert>();
		cbLibrary.setName("certLibrary");
		cbLibrary.setFont(Theme.BODY);
		cbLibrary.addActionListener(e -> validateCert());
		panel.add(label, BorderLayout.WEST);
		panel.add(cbLibrary, BorderLayout.CENTER);
		JLabel note = new JLabel("证书库证书统一校验，启用时复制到实例 conf/ssl 目录并使用副本路径。");
		note.setFont(Theme.SMALL);
		note.setForeground(Theme.TEXT_MUTED);
		panel.add(note, BorderLayout.SOUTH);
		return panel;
	}

	private JPanel buildUploadPanel() {
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setOpaque(false);
		panel.setName("uploadPanel");
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(4, 0, 4, 8);
		c.anchor = GridBagConstraints.WEST;

		c.gridx = 0;
		c.gridy = 0;
		panel.add(new JLabel("证书 PEM:"), c);
		c.gridx = 1;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		JTextField pem = new JTextField();
		pem.setName("certPem");
		pem.setFont(Theme.BODY);
		pem.getDocument().addDocumentListener(simpleListener(this::validateCert));
		panel.add(pem, c);
		c.gridx = 2;
		JButton browsePem = Theme.toolButton("浏览…");
		browsePem.addActionListener(e -> browseInto(pem, false));
		panel.add(browsePem, c);

		c.gridx = 0;
		c.gridy = 1;
		c.weightx = 0;
		c.fill = GridBagConstraints.NONE;
		panel.add(new JLabel("私钥 KEY:"), c);
		c.gridx = 1;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		JTextField key = new JTextField();
		key.setName("certKey");
		key.setFont(Theme.BODY);
		key.getDocument().addDocumentListener(simpleListener(this::validateCert));
		panel.add(key, c);
		c.gridx = 2;
		JButton browseKey = Theme.toolButton("浏览…");
		browseKey.addActionListener(e -> browseInto(key, true));
		panel.add(browseKey, c);

		JLabel fmt = new JLabel("<html>支持证书格式：推荐 PEM 证书格式，扩展名：.pem、.cer、.crt</html>");
		fmt.setFont(Theme.SMALL);
		fmt.setForeground(Theme.TEXT_MUTED);
		c.gridx = 0;
		c.gridy = 2;
		c.gridwidth = 3;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.insets = new Insets(12, 0, 4, 8);
		panel.add(fmt, c);
		c.gridwidth = 1;
		c.weightx = 0;
		c.fill = GridBagConstraints.NONE;

		return panel;
	}

	private void browseInto(JTextField target, boolean key) {
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle(key ? "选择 PEM 私钥" : "选择 PEM 证书");
		if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
			target.setText(chooser.getSelectedFile().getAbsolutePath());
		}
	}

	private void updateCertSource() {
		JPanel sourcePanel = (JPanel) findByName(stepCards, "certSource");
		if (sourcePanel == null) {
			return;
		}
		CardLayout layout = (CardLayout) sourcePanel.getLayout();
		layout.show(sourcePanel, rbLibrary.isSelected() ? "library" : "upload");
	}

	private void loadLibrary() {
		List<ManagedCert> certs = certStore.list();
		DefaultComboBoxModel<ManagedCert> model = new DefaultComboBoxModel<ManagedCert>();
		for (ManagedCert cert : certs) {
			model.addElement(cert);
		}
		cbLibrary.setModel(model);
		cbLibrary.setRenderer(new javax.swing.DefaultListCellRenderer() {
			private static final long serialVersionUID = 1L;

			@Override
			public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
					int index, boolean selected, boolean focus) {
				super.getListCellRendererComponent(list, value, index, selected, focus);
				if (value instanceof ManagedCert) {
					ManagedCert cert = (ManagedCert) value;
					String expiry = cert.info == null ? "无法解析" : cert.info.notAfterText();
					setText(cert.displayName() + "  |  " + cert.statusText() + "  |  至 " + expiry);
				}
				return this;
			}
		});
		if (certs.isEmpty()) {
			rbUpload.setSelected(true);
			updateCertSource();
		}
		validateCert();
	}

	private void validateCert() {
		certError = null;
		certInfo = null;
		pemFile = null;
		keyFile = null;
		if (rbLibrary == null || taCertInfo == null) {
			return;
		}
		if (rbLibrary.isSelected()) {
			ManagedCert cert = (ManagedCert) cbLibrary.getSelectedItem();
			if (cert == null) {
				certError = "证书库为空，请切换为上传证书文件，或先在证书管理中导入证书";
			} else {
				pemFile = certStore.certFile(cert.id);
				keyFile = certStore.keyFile(cert.id);
			}
		} else {
			pemFile = StrUtil.isEmpty(tfPem.getText().trim()) ? null : new File(tfPem.getText().trim());
			keyFile = StrUtil.isEmpty(tfKey.getText().trim()) ? null : new File(tfKey.getText().trim());
		}
		if (certError == null) {
			certError = validatePair(pemFile, keyFile);
		}
		renderCertInfo();
		updateNav();
	}

	private String validatePair(File pem, File key) {
		if (pem == null || !pem.isFile()) {
			return "请选择存在的证书文件";
		}
		if (key == null || !key.isFile()) {
			return "请选择存在的私钥文件";
		}
		if (pem.length() > MAX_CERT_BYTES || key.length() > MAX_CERT_BYTES) {
			return "证书/私钥文件过大（上限 20MB）";
		}
		String pemContent = safeRead(pem);
		String keyContent = safeRead(key);
		Inspect pemCheck = NginxCertReplaceManager.inspectPem(pemContent);
		if (!pemCheck.ok()) {
			return pemCheck.error;
		}
		Inspect keyCheck = NginxCertReplaceManager.inspectKey(keyContent, null);
		if (!keyCheck.ok()) {
			return keyCheck.error;
		}
		certInfo = pemCheck.cert;
		return null;
	}

	private void renderCertInfo() {
		if (certError != null) {
			taCertInfo.setForeground(Theme.DANGER);
			taCertInfo.setText(certError);
			return;
		}
		if (certInfo == null) {
			taCertInfo.setForeground(Theme.TEXT_MUTED);
			taCertInfo.setText("选择证书后将在这里显示有效期与域名。");
			return;
		}
		StringBuilder sb = new StringBuilder();
		sb.append("有效期: ").append(certInfo.notBeforeText()).append(" ~ ")
				.append(certInfo.notAfterText()).append('\n');
		sb.append("状态: ").append(certInfo.expired ? "已过期"
				: certInfo.notYetValid ? "尚未生效"
						: certInfo.expiringSoon ? "即将到期（剩余 " + certInfo.daysLeft + " 天）"
								: "正常（剩余 " + certInfo.daysLeft + " 天）").append('\n');
		sb.append("证书域名: ")
				.append(certInfo.domains.isEmpty() ? "(未识别)" : String.join(", ", certInfo.domains));
		taCertInfo.setForeground(certInfo.expired ? Theme.DANGER : Theme.TEXT);
		taCertInfo.setText(sb.toString());
	}

	// ==================== 第 3 步: 确认 ====================

	private JPanel buildConfirmStep() {
		JPanel card = new JPanel(new BorderLayout(0, 10));
		card.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(Theme.BORDER),
				BorderFactory.createEmptyBorder(16, 18, 16, 18)));
		card.setBackground(Theme.SURFACE);

		taSummary = new JTextArea(9, 20);
		taSummary.setEditable(false);
		taSummary.setFont(Theme.BODY);
		taSummary.setBackground(new java.awt.Color(250, 250, 250));
		JScrollPane scroll = new JScrollPane(taSummary);
		scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));

		JLabel danger = new JLabel("<html><b>执行后果：</b>写入站点 SSL 监听与证书指令，"
				+ "并可能重启实例；配置检测或重启失败会自动恢复备份。</html>");
		danger.setFont(Theme.SMALL);
		danger.setForeground(Theme.WARNING);

		JLabel safe = new JLabel("默认不执行：需要你主动点击「启用 HTTPS」；关闭窗口或按 Esc 即取消。");
		safe.setFont(Theme.SMALL);
		safe.setForeground(Theme.TEXT_MUTED);

		card.add(sectionTitle("确认信息"), BorderLayout.NORTH);
		card.add(scroll, BorderLayout.CENTER);
		JPanel south = new JPanel(new BorderLayout(0, 4));
		south.setOpaque(false);
		south.add(danger, BorderLayout.NORTH);
		south.add(safe, BorderLayout.SOUTH);
		card.add(south, BorderLayout.SOUTH);
		return card;
	}

	// ==================== 步骤流转 ====================

	private void setStep(int target) {
		step = target;
		if (target == STEP_CONFIRM) {
			refreshSummary();
		}
		stepLayout.show(stepCards, target == STEP_SITE ? "site"
				: target == STEP_CERT ? "cert" : "confirm");
		lblStepTitle.setText(target == STEP_SITE ? "第 1 步 · 站点与端口"
				: target == STEP_CERT ? "第 2 步 · 选择证书"
						: "第 3 步 · 确认并启用");
		lblStepPill.setText((target + 1) + " / 3");
		updateNav();
	}

	private void goNext() {
		if (busy) {
			return;
		}
		if (step == STEP_SITE) {
			if (parsePort() < 0) {
				checkPortNow();
				return;
			}
			if (portCheck == null) {
				checkPortNow();
			}
			if (portCheck == null || !portCheck.ok) {
				return;
			}
			setStep(STEP_CERT);
		} else if (step == STEP_CERT) {
			validateCert();
			if (certError != null) {
				return;
			}
			setStep(STEP_CONFIRM);
		}
	}

	private void updateNav() {
		if (btnBack == null) {
			return;
		}
		boolean siteReady = parsePort() >= 0 && portCheck != null && portCheck.ok;
		boolean certReady = certError == null && certInfo != null && pemFile != null && keyFile != null;
		String busyReason = busy ? "正在执行，请稍候" : null;
		Theme.setEnabled(btnBack, !busy && step > STEP_SITE,
				busyReason != null ? busyReason : "已是第一步，无法返回");
		btnNext.setVisible(step < STEP_CONFIRM);
		String nextReason;
		if (busy) {
			nextReason = busyReason;
		} else if (step == STEP_SITE) {
			nextReason = parsePort() < 0 ? "先填写有效的 HTTPS 端口（1-65535）"
					: (portCheck == null ? "先点击「检测端口」完成端口占用检查"
							: (portCheck.ok ? null : "端口检查未通过：更换端口或先释放占用"));
		} else {
			nextReason = certReady ? null : "先选择证书与私钥，并确保校验通过";
		}
		Theme.setEnabled(btnNext, nextReason == null, nextReason);
		btnApply.setVisible(step == STEP_CONFIRM);
		String applyReason;
		if (busy) {
			applyReason = busyReason;
		} else if (!siteReady) {
			applyReason = "先完成端口占用检查";
		} else {
			applyReason = certReady ? null : "先完成证书与私钥校验";
		}
		Theme.setEnabled(btnApply, applyReason == null, applyReason);
		Theme.setEnabled(btnClose, !busy, busyReason);
		// 回车触发当前步骤的主操作(执行前仍会经过确认页/校验), Esc 取消
		getRootPane().setDefaultButton(step < STEP_CONFIRM ? btnNext : btnApply);
	}

	private void refreshSummary() {
		int port = parsePort();
		StringBuilder sb = new StringBuilder();
		sb.append("实例: ").append(StrUtil.emptyToDefault(entry.getName(),
				StrUtil.emptyToDefault(entry.getPrefixDir(), entry.getConfPath()))).append('\n');
		sb.append("站点: ").append(site.domain()).append("　server_name: ")
				.append(site.serverNamesText()).append('\n');
		sb.append("当前监听: ").append(site.portsWithSslText()).append('\n');
		sb.append("HTTPS 端口: ").append(port == -1 ? "(无效)" : port).append('\n');
		sb.append("证书来源: ").append(rbLibrary.isSelected() ? "证书库" : "上传文件").append('\n');
		sb.append("证书: ").append(pemFile == null ? "(未选择)" : pemFile.getAbsolutePath()).append('\n');
		sb.append("私钥: ").append(keyFile == null ? "(未选择)" : keyFile.getAbsolutePath()).append('\n');
		sb.append("有效期: ").append(certInfo == null ? "-"
				: certInfo.notBeforeText() + " ~ " + certInfo.notAfterText()).append('\n');
		sb.append("配置文件: ").append(site.sourceConf == null ? "-" : site.sourceConf.getAbsolutePath())
				.append('\n');
		sb.append("备份位置: ").append(new File(home, "sitebak").getAbsolutePath())
				.append('\n');
		if (certInfo != null && !certInfo.domains.isEmpty()
				&& !certInfo.domains.contains(site.domain())) {
			sb.append("\n提示: 证书域名 ").append(String.join(", ", certInfo.domains))
					.append(" 与站点域名 ").append(site.domain()).append(" 不一致");
		}
		taSummary.setText(sb.toString());
		taSummary.setCaretPosition(0);
	}

	// ==================== 执行 ====================

	private void apply() {
		if (busy) {
			return;
		}
		validateCert();
		if (certError != null) {
			setStep(STEP_CERT);
			return;
		}
		int port = parsePort();
		if (port < 0) {
			setStep(STEP_SITE);
			return;
		}
		portCheck = manager.checkPort(entry, allSites, site, port);
		if (!portCheck.ok) {
			setStep(STEP_SITE);
			checkPortNow();
			return;
		}

		EnableRequest req = new EnableRequest();
		req.entry = entry;
		req.site = site;
		req.httpsPort = port;
		req.redirectHttp = false;
		req.pemFile = pemFile;
		req.keyFile = keyFile;
		req.sourceLabel = rbLibrary.isSelected() ? "证书库" : "上传文件";

		setBusy(true);
		toggleLog("开始启用 HTTPS: 站点=" + site.domain() + ", 端口=" + port);
		if (portCheck.shared) {
			toggleLog("提示: " + portCheck.message);
		}
		new SwingWorker<EnableResult, Void>() {
			@Override
			protected EnableResult doInBackground() {
				return manager.enable(req);
			}

			@Override
			protected void done() {
				EnableResult result;
				try {
					result = get();
				} catch (Exception e) {
					result = new EnableResult();
					result.message = "执行失败：" + com.nginxui.utils.ErrorReporter.friendlyReason(e);
				}
				handleResult(result);
			}
		}.execute();
	}

	private void handleResult(EnableResult result) {
		setBusy(false);
		if (result.output != null && !result.output.isEmpty()) {
			toggleLog(result.output.trim());
		}
		for (String warning : result.warnings) {
			toggleLog("[提示] " + warning);
		}
		if (result.success) {
			toggleLog("[成功] " + result.message);
			StringBuilder sb = new StringBuilder(result.message);
			if (!result.httpsUrl.isEmpty()) {
				sb.append("\n\n访问地址: ").append(result.httpsUrl);
			}
			if (!result.backups.isEmpty()) {
				sb.append("\n配置备份: ").append(result.backups.get(0));
			}
			sb.append("\n证书位置: ").append(result.pemPath);
			JOptionPane.showMessageDialog(this, sb.toString(), "启用 HTTPS 成功",
					JOptionPane.INFORMATION_MESSAGE);
			dispose();
			return;
		}
		toggleLog("[失败] " + result.message);
		if (result.rolledBack) {
			toggleLog("已回滚到执行前配置");
		}
		JOptionPane.showMessageDialog(this,
				result.message + (result.rolledBack ? "\n\n配置已自动回滚，站点保持原状态。" : ""),
				"启用 HTTPS 失败", JOptionPane.ERROR_MESSAGE);
	}

	private void setBusy(boolean value) {
		busy = value;
		String reason = value ? "正在执行，请稍候" : null;
		Theme.setEnabled(tfPort, !value, reason);
		Theme.setEnabled(btnCheckPort, !value, reason);
		Theme.setEnabled(rbLibrary, !value, reason);
		Theme.setEnabled(rbUpload, !value, reason);
		Theme.setEnabled(cbLibrary, !value, reason);
		Theme.setEnabled(tfPem, !value, reason);
		Theme.setEnabled(tfKey, !value, reason);
		updateNav();
		setCursor(Cursor.getPredefinedCursor(value ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));
	}

	private void toggleLog(String line) {
		if (line == null || line.isEmpty()) {
			return;
		}
		if (!taLog.getText().isEmpty()) {
			taLog.append(System.lineSeparator());
		}
		taLog.append(line);
		taLog.setCaretPosition(taLog.getDocument().getLength());
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
		// 默认按钮由 updateNav 按步骤绑定(回车触发当前步骤主操作), 这里只处理 Esc
		getRootPane().setDefaultButton(null);
	}

	// ==================== 小工具 ====================

	private static DocumentListener simpleListener(Runnable action) {
		return new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				action.run();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				action.run();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				action.run();
			}
		};
	}

	private static Component findByName(java.awt.Container root, String name) {
		if (root == null) {
			return null;
		}
		for (Component comp : root.getComponents()) {
			if (name.equals(comp.getName())) {
				return comp;
			}
			if (comp instanceof java.awt.Container) {
				Component found = findByName((java.awt.Container) comp, name);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	private static JLabel sectionTitle(String text) {
		JLabel label = new JLabel(text);
		label.setFont(Theme.BODY_BOLD);
		label.setForeground(Theme.TEXT);
		return label;
	}

	private static JPanel infoLine(String key, String value) {
		JPanel row = new JPanel(new BorderLayout(8, 0));
		row.setOpaque(false);
		row.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
		JLabel label = new JLabel(key + ":");
		label.setFont(Theme.SMALL);
		label.setForeground(Theme.TEXT_MUTED);
		label.setPreferredSize(new Dimension(82, 20));
		JLabel content = new JLabel(StrUtil.emptyToDefault(value, "-"));
		content.setFont(Theme.BODY);
		content.setForeground(Theme.TEXT);
		row.add(label, BorderLayout.WEST);
		row.add(content, BorderLayout.CENTER);
		return row;
	}

	private static String safeRead(File file) {
		try {
			return FileUtil.readString(file, StandardCharsets.UTF_8);
		} catch (Exception e) {
			return "";
		}
	}
}
