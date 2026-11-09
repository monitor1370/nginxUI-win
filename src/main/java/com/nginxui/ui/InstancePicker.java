package com.nginxui.ui;

import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.List;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JPanel;

import com.nginxui.ext.NginxInstanceEntry;

import cn.hutool.core.util.StrUtil;

/**
 * 内容区页面的实例下拉:
 *  - 数据与实例列表同源(顺序、检测中/已忽略标记一致);
 *  - 切换后同步主窗口选中态, 驱动既有按钮与侧栏可用性;
 *  - 刷新后按实例 id 保持当前选择, 避免列表重建时丢失上下文.
 */
class InstancePicker extends JPanel {

	private final MainFrame mf;
	private final JComboBox<MainFrame.InstanceRow> combo = new JComboBox<MainFrame.InstanceRow>();
	private boolean syncing;
	private Runnable onChanged;

	InstancePicker(MainFrame mf, int width) {
		super(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		setOpaque(false);
		this.mf = mf;
		combo.setFont(Theme.BODY);
		combo.setPreferredSize(new Dimension(width, Theme.BUTTON_HEIGHT));
		combo.addActionListener(e -> {
			if (syncing) {
				return;
			}
			MainFrame.InstanceRow row = selected();
			if (row == null) {
				return;
			}
			mf.selectInstanceRow(row);
			if (onChanged != null) {
				onChanged.run();
			}
		});
		add(combo);
	}

	void setOnChanged(Runnable onChanged) {
		this.onChanged = onChanged;
	}

	/** 下拉只显示实例名(状态已在页面标题行展示), 窄宽度下也能看清实例名 */
	void setNameOnly() {
		combo.setRenderer(new DefaultListCellRenderer() {
			private static final long serialVersionUID = 1L;

			@Override
			public java.awt.Component getListCellRendererComponent(JList<?> list, Object value,
					int index, boolean isSelected, boolean cellHasFocus) {
				super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				setText(value instanceof MainFrame.InstanceRow
						? MainFrame.InstanceRow.displayName(((MainFrame.InstanceRow) value).entry) : "");
				return this;
			}
		});
	}

	MainFrame.InstanceRow selected() {
		Object o = combo.getSelectedItem();
		return o instanceof MainFrame.InstanceRow ? (MainFrame.InstanceRow) o : null;
	}

	/** 重建下拉并返回最终选中行: 优先 keepId, 其次当前选中, 最后取第一项 */
	MainFrame.InstanceRow refresh(String keepId) {
		MainFrame.InstanceRow prev = selected();
		String targetId = StrUtil.emptyToDefault(keepId, prev == null ? null : prev.entry.getId());
		NginxInstanceEntry listSelected = mf.selectedInstanceEntry();
		if (targetId == null && listSelected != null) {
			targetId = listSelected.getId();
		}
		List<MainFrame.InstanceRow> rows = mf.instanceRows();
		MainFrame.InstanceRow target = null;
		syncing = true;
		try {
			combo.removeAllItems();
			for (MainFrame.InstanceRow r : rows) {
				combo.addItem(r);
				if (target != null || targetId == null || !targetId.equals(r.entry.getId())) {
					continue;
				}
				target = r;
			}
			if (target == null && combo.getItemCount() > 0) {
				target = combo.getItemAt(0);
			}
			if (target != null) {
				combo.setSelectedItem(target);
			}
		} finally {
			syncing = false;
		}
		if (target != null) {
			mf.selectInstanceRow(target);
		}
		return target;
	}
}
