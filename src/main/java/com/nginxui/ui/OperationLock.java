package com.nginxui.ui;

import java.util.concurrent.CopyOnWriteArrayList;

import javax.swing.SwingUtilities;

/**
 * 全局操作互斥锁：安装、升级、回滚、证书写入、监控配置写入等会改动实例/配置的长操作，
 * 同一时刻只允许一个执行。其它入口通过 {@link #reason()} 置灰并展示原因，避免并发改写配置、
 * 替换正在被其它实例占用的 nginx.exe。
 */
public final class OperationLock {

	private static final Object MONITOR = new Object();
	private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<Runnable>();

	/** 当前执行中的操作名；空闲时为 null */
	private static String current;

	private OperationLock() {
	}

	/** 尝试获取操作权；已有操作在进行时返回 false */
	public static boolean tryAcquire(String operation) {
		synchronized (MONITOR) {
			if (current != null) {
				return false;
			}
			current = operation;
		}
		fireChanged();
		return true;
	}

	/** 释放操作权；未持有时忽略 */
	public static void release() {
		synchronized (MONITOR) {
			if (current == null) {
				return;
			}
			current = null;
		}
		fireChanged();
	}

	public static boolean isBusy() {
		synchronized (MONITOR) {
			return current != null;
		}
	}

	/** 当前操作名；空闲时返回 null */
	public static String currentOperation() {
		synchronized (MONITOR) {
			return current;
		}
	}

	/** 其它入口的禁用原因；空闲时返回 null */
	public static String reason() {
		String name = currentOperation();
		return name == null ? null : "正在执行「" + name + "」，请等待本次操作完成";
	}

	public static void addListener(Runnable listener) {
		if (listener != null) {
			LISTENERS.addIfAbsent(listener);
		}
	}

	public static void removeListener(Runnable listener) {
		LISTENERS.remove(listener);
	}

	private static void fireChanged() {
		for (Runnable listener : LISTENERS) {
			SwingUtilities.invokeLater(listener);
		}
	}
}
