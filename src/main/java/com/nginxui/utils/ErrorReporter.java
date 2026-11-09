package com.nginxui.utils;

import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.JOptionPane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.hutool.core.util.StrUtil;

/**
 * 统一异常上报: 完整堆栈写日志, 需要时给用户可读提示.
 *
 * 弹窗做了去重与节流(同一消息 60s 内只弹一次), 避免异常风暴或定时任务
 * 反复失败时刷屏. 无桌面环境只记日志.
 */
public final class ErrorReporter {

	private static final Logger LOG = LoggerFactory.getLogger(ErrorReporter.class);

	private static final long DIALOG_THROTTLE_MS = 60_000L;
	private static final AtomicLong LAST_DIALOG_AT = new AtomicLong(0);
	private static volatile String lastDialogMessage = "";

	private ErrorReporter() {
	}

	/** 仅记录异常(完整堆栈), 不打扰用户; context 说明正在做什么 */
	public static void report(String context, Throwable error) {
		LOG.error(context, error);
	}

	/** 记录异常并提示用户: 用于用户主动操作失败且需要立即告知的场景 */
	public static void reportUser(String context, Throwable error) {
		LOG.error(context, error);
		showError(I18n.t("errorReporter.userDetailWithHint", context, userMessage(error)));
	}

	/** 安装全局未捕获异常兜底(Main 启动时调用一次) */
	public static void installGlobalHandler() {
		Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
			LOG.error("未捕获异常: 线程={}", thread.getName(), error);
			showError(I18n.t("errorReporter.global", userMessage(error)));
		});
	}

	/** 取异常链最内层的可读信息(面向用户, 不暴露类名/堆栈细节) */
	public static String userMessage(Throwable error) {
		Throwable t = rootCause(error);
		if (t == null) {
			return I18n.t("errorReporter.unknown");
		}
		String msg = safeMessage(t);
		return StrUtil.isEmpty(msg) ? friendlyReason(t) : msg;
	}

	/**
	 * 统一错误提示模板: 发生了什么 / 为什么 / 下一步.
	 * 用于用户主动操作失败时的对话框与状态栏, 不直接拼接异常原文.
	 */
	public static String uiText(String action, Throwable error, String nextStep) {
		StringBuilder sb = new StringBuilder();
		sb.append(action).append("未完成。\n原因：").append(friendlyReason(error));
		if (StrUtil.isNotEmpty(nextStep)) {
			sb.append("\n建议：").append(nextStep);
		}
		return sb.toString();
	}

	/** 异常归类为可执行原因, 避免把 Java 类名或原始终端输出甩给用户 */
	public static String friendlyReason(Throwable error) {
		Throwable t = rootCause(error);
		if (t == null) {
			return I18n.t("errorReporter.unknown");
		}
		if (t instanceof java.nio.file.AccessDeniedException || t instanceof SecurityException) {
			return "没有访问文件或目录的权限";
		}
		if (t instanceof java.io.FileNotFoundException) {
			return "目标文件不存在或已被移动";
		}
		if (t instanceof java.net.UnknownHostException) {
			return "无法解析目标地址，请检查网络连接";
		}
		if (t instanceof java.net.SocketTimeoutException) {
			return "连接超时，请检查网络后重试";
		}
		if (t instanceof java.net.ConnectException) {
			return "无法连接到目标地址，请检查网络或端口";
		}
		if (t instanceof java.io.IOException) {
			return "读写文件失败，文件可能被占用或路径不可用";
		}
		if (t instanceof NumberFormatException) {
			return "数据格式不正确";
		}
		return "发生未预期的错误（详情已写入日志）";
	}

	private static Throwable rootCause(Throwable error) {
		Throwable t = error;
		while (t != null && t.getCause() != null && t.getCause() != t) {
			t = t.getCause();
		}
		return t;
	}

	/** 业务层主动给出的中文提示可保留, 但截断并去掉换行, 避免刷屏 */
	private static String safeMessage(Throwable t) {
		String msg = t.getMessage();
		if (StrUtil.isEmpty(msg)) {
			return "";
		}
		if (containsTechnicalToken(msg)) {
			return "";
		}
		msg = msg.replace('\r', ' ').replace('\n', ' ').trim();
		return msg.length() > 60 ? msg.substring(0, 60) + "…" : msg;
	}

	private static boolean containsTechnicalToken(String msg) {
		String lower = msg.toLowerCase();
		return lower.contains("exception") || lower.contains(".java:") || lower.contains("java.")
				|| lower.contains(" at ") || lower.contains("caused by");
	}

	private static void showError(String message) {
		if (GraphicsEnvironment.isHeadless()) {
			return;
		}
		long now = System.currentTimeMillis();
		synchronized (ErrorReporter.class) {
			if (message.equals(lastDialogMessage)
					&& now - LAST_DIALOG_AT.get() < DIALOG_THROTTLE_MS) {
				LOG.debug("重复异常提示已抑制: {}", message);
				return;
			}
			lastDialogMessage = message;
			LAST_DIALOG_AT.set(now);
		}
		Runnable show = () -> JOptionPane.showMessageDialog(null, message,
				I18n.t("errorReporter.dialogTitle"), JOptionPane.ERROR_MESSAGE);
		if (EventQueue.isDispatchThread()) {
			show.run();
		} else {
			EventQueue.invokeLater(show);
		}
	}
}
