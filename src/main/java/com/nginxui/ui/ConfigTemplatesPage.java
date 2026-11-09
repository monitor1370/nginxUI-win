package com.nginxui.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

import com.nginxui.utils.NginxConfigTemplates;
import com.nginxui.utils.NginxConfigTemplates.Category;
import com.nginxui.utils.NginxConfigTemplates.Entry;

/**
 * 配置示例页面: 左侧按分类展示 Nginx 常用配置模板, 右侧预览源码,
 * 支持一键复制到剪贴板后手动粘贴到配置文件编辑器.
 */
public class ConfigTemplatesPage extends JPanel {

	private static final long serialVersionUID = 1L;

	private final JComboBox<Category> categoryCombo = new JComboBox<Category>();
	private final DefaultListModel<Entry> listModel = new DefaultListModel<Entry>();
	private final JList<Entry> entryList = new JList<Entry>(listModel);
	private final JTextArea taPreview = new JTextArea();
	private final JLabel lblTitle = Theme.label(" ", Theme.TITLE.deriveFont(java.awt.Font.BOLD, 15f), Theme.TEXT);
	private final JLabel lblDesc = Theme.label(" ", Theme.SMALL, Theme.TEXT_MUTED);
	private final JLabel lblPlaceholders = Theme.label(" ", Theme.SMALL, Theme.WARNING);
	private final JLabel lblStatus = Theme.label(" ", Theme.SMALL, Theme.TEXT_MUTED);

	private Entry selected;

	public ConfigTemplatesPage() {
		super(new BorderLayout());

		setOpaque(true);
		setBackground(Theme.BG);
		setBorder(BorderFactory.createEmptyBorder(12, Theme.GAP_L, Theme.GAP_M, Theme.GAP_L));

		add(buildHeader(), BorderLayout.NORTH);
		add(buildBody(), BorderLayout.CENTER);
		add(buildFooter(), BorderLayout.SOUTH);

		refreshListFromCurrentCategory();
	}

	/** MainFrame 每次切到本页时调一次 — 纯浏览页, 无需同步编辑态 */
	public void refreshState() {
		// 预留接口, 当前无动作
	}

	// ==================== 顶部标题 ====================

	private JPanel buildHeader() {
		PageHeader header = new PageHeader("配置示例",
				"按分类浏览 Nginx 常用配置模板，复制到剪贴板后手动粘贴到配置文件编辑器");
		header.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));

		JButton btnCopy = Theme.toolButton("复制到剪贴板");
		btnCopy.setToolTipText("把当前选中示例的内容复制到系统剪贴板");
		btnCopy.addActionListener(e -> copyToClipboard());

		header.addAction(btnCopy);
		return header;
	}

	// ==================== 中间主体 ====================

	private JPanel buildBody() {
		for (Category cat : NginxConfigTemplates.ALL_CATEGORIES) {
			categoryCombo.addItem(cat);
		}
		categoryCombo.setFont(Theme.BODY);
		categoryCombo.setRenderer(new DefaultListCellRenderer() {
			private static final long serialVersionUID = 1L;
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value,
					int index, boolean isSelected, boolean cellHasFocus) {
				JLabel l = (JLabel) super.getListCellRendererComponent(
						list, value, index, isSelected, cellHasFocus);
				if (value instanceof Category) {
					l.setText(((Category) value).name);
				}
				return l;
			}
		});
		categoryCombo.addActionListener(e -> refreshListFromCurrentCategory());

		JPanel categoryBar = new JPanel(new BorderLayout(8, 0));
		categoryBar.setOpaque(false);
		categoryBar.add(Theme.label("分类:", Theme.BODY, Theme.TEXT_MUTED), BorderLayout.WEST);
		categoryBar.add(categoryCombo, BorderLayout.CENTER);

		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
				buildListPanel(), buildPreviewPanel());
		split.setResizeWeight(0.32);
		split.setDividerLocation(280);
		split.setDividerSize(4);
		split.setBorder(BorderFactory.createEmptyBorder());

		JPanel body = new JPanel(new BorderLayout(0, 6));
		body.setOpaque(false);
		body.add(categoryBar, BorderLayout.NORTH);
		body.add(split, BorderLayout.CENTER);
		return body;
	}

	private void refreshListFromCurrentCategory() {
		Category cat = (Category) categoryCombo.getSelectedItem();
		if (cat == null) {
			return;
		}
		List<Entry> entries = NginxConfigTemplates.byCategory(cat);
		listModel.clear();
		for (Entry e : entries) {
			listModel.addElement(e);
		}
		lblStatus.setText("共 " + entries.size() + " 个示例");
		lblStatus.setForeground(Theme.TEXT_MUTED);
		if (!entries.isEmpty()) {
			entryList.setSelectedIndex(0);
		} else {
			onSelect(null);
		}
	}

	// ==================== 左侧列表 ====================

	private JScrollPane buildListPanel() {
		entryList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		entryList.setFont(Theme.BODY);
		entryList.setOpaque(false);
		entryList.setCellRenderer(new DefaultListCellRenderer() {
			private static final long serialVersionUID = 1L;
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value,
					int index, boolean isSelected, boolean cellHasFocus) {
				JLabel l = (JLabel) super.getListCellRendererComponent(
						list, value, index, isSelected, cellHasFocus);
				if (value instanceof Entry) {
					l.setText(((Entry) value).title);
				}
				// 紧凑 padding: 原来 8,12,8,12 → 现在 4,8,4,8, 一屏能多显示 1/3 条目
				l.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
				return l;
			}
		});
		entryList.addListSelectionListener(new ListSelectionListener() {
			@Override
			public void valueChanged(ListSelectionEvent e) {
				if (!e.getValueIsAdjusting()) {
					onSelect(entryList.getSelectedValue());
				}
			}
		});

		JScrollPane sp = new JScrollPane(entryList);
		sp.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
		sp.getVerticalScrollBar().setUnitIncrement(16);
		return sp;
	}

	// ==================== 右侧预览 ====================

	private JPanel buildPreviewPanel() {
		lblTitle.setBorder(BorderFactory.createEmptyBorder(0, 0, 1, 0));
		lblDesc.setBorder(BorderFactory.createEmptyBorder(0, 0, 2, 0));

		taPreview.setEditable(false);
		taPreview.setFont(Theme.MONO);
		taPreview.setTabSize(4);
		taPreview.setBackground(Theme.SURFACE);
		taPreview.setForeground(Theme.TEXT);
		taPreview.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
		JScrollPane sp = new JScrollPane(taPreview);
		sp.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
		sp.getVerticalScrollBar().setUnitIncrement(16);

		JPanel header = new JPanel(new BorderLayout(0, 1));
		header.setOpaque(false);
		header.add(lblTitle, BorderLayout.NORTH);
		header.add(lblDesc, BorderLayout.CENTER);
		header.add(lblPlaceholders, BorderLayout.SOUTH);

		JPanel body = new JPanel(new BorderLayout(0, 4));
		body.setOpaque(false);
		body.add(header, BorderLayout.NORTH);
		body.add(sp, BorderLayout.CENTER);
		return body;
	}

	private void onSelect(Entry e) {
		selected = e;
		if (e == null) {
			lblTitle.setText("—");
			lblDesc.setText("当前分类下没有示例");
			lblPlaceholders.setText(" ");
			taPreview.setText("");
			return;
		}
		lblTitle.setText(e.title);
		lblDesc.setText(e.description);
		lblPlaceholders.setText(e.placeholders == null || e.placeholders.isBlank()
				? " " : "提示: " + e.placeholders);
		taPreview.setText(e.content);
		taPreview.setCaretPosition(0);
	}

	// ==================== 底部状态栏 ====================

	private JPanel buildFooter() {
		JPanel bar = new JPanel(new BorderLayout());
		bar.setOpaque(false);
		bar.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		lblStatus.setAlignmentX(LEFT_ALIGNMENT);
		bar.add(lblStatus, BorderLayout.WEST);
		return bar;
	}

	// ==================== 操作 ====================

	private void copyToClipboard() {
		if (selected == null) {
			JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(this),
					"请先选择一个示例", "配置示例", JOptionPane.WARNING_MESSAGE);
			return;
		}
		Toolkit.getDefaultToolkit().getSystemClipboard()
				.setContents(new StringSelection(selected.content), null);
		lblStatus.setText("✅ 已复制到剪贴板: " + selected.title);
		lblStatus.setForeground(Theme.SUCCESS);
	}
}
