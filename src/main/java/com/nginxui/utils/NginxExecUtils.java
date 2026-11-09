package com.nginxui.utils;

import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;

/**
 * nginx 相关外部命令执行与通用小工具(被发现/控制/配置/探针四个模块共用).
 */
final class NginxExecUtils {

	private static final Logger LOG = LoggerFactory.getLogger(NginxExecUtils.class);

	private NginxExecUtils() {
	}

	static String exec(long timeoutSeconds, String... command) {
		return exec(null, timeoutSeconds, command);
	}

	/**
	 * 执行系统命令并合并标准错误输出, 超时后强制销毁进程.
	 * 输出在独立线程消费, 保证 waitFor 超时对挂起进程真正生效.
	 * 失败时记录错误码与命令, 不再静默返回空串.
	 */
	static String exec(File workdir, long timeoutSeconds, String... command) {
		Process process = null;
		try {
			ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
			if (workdir != null) {
				builder.directory(workdir);
			}
			process = builder.start();
			StringBuilder result = new StringBuilder();

			Process current = process;
			Thread reader = new Thread(() -> {
				try (InputStream inputStream = current.getInputStream()) {
					byte[] buffer = new byte[4096];
					int length;
					while ((length = inputStream.read(buffer)) != -1) {
						result.append(new String(buffer, 0, length, StandardCharsets.UTF_8));
					}
				} catch (Exception e) {
					// 进程被销毁时读取中断, 属正常收尾
					LOG.debug("[{}] 进程输出读取中断", NginxErrorCode.READ_LOG_FAILED);
				}
			}, "nginxui-exec-read");
			reader.setDaemon(true);
			reader.start();

			boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
			if (!finished) {
				LOG.warn("[{}] 命令执行超时({}s): {}", NginxErrorCode.EXEC_FAILED, timeoutSeconds,
						String.join(" ", command));
				process.destroyForcibly();
				process.waitFor(2, TimeUnit.SECONDS);
			}
			reader.join(1000);
			return result.toString();
		} catch (Exception e) {
			LOG.warn("[{}] 命令执行失败: {} ({})", NginxErrorCode.EXEC_FAILED,
					String.join(" ", command), e.getMessage());
			return "";
		} finally {
			if (process != null) {
				process.destroyForcibly();
			}
		}
	}

	static void sleepQuietly(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** 读取文本文件最后N行并单行化 */
	static String tailText(File file, int maxLines, int maxChars) {
		try {
			if (file == null || !file.isFile()) {
				return "";
			}
			List<String> lines = FileUtil.readLines(file, StandardCharsets.UTF_8);
			if (lines == null || lines.isEmpty()) {
				return "";
			}
			int from = Math.max(0, lines.size() - maxLines);
			String joined = String.join(" | ", lines.subList(from, lines.size())).trim();
			if (joined.length() > maxChars) {
				joined = joined.substring(0, maxChars) + "…";
			}
			return joined;
		} catch (Exception e) {
			LOG.debug("[{}] 读取日志尾部失败: {}", NginxErrorCode.READ_LOG_FAILED,
					file == null ? "" : file.getAbsolutePath());
			return "";
		}
	}

	static double round(double val, int scale) {
		return new BigDecimal(val).setScale(scale, BigDecimal.ROUND_HALF_UP).doubleValue();
	}

	static String firstToken(String text) {
		if (StrUtil.isEmpty(text)) {
			return "";
		}
		String t = text.trim();
		int sp = t.indexOf(' ');
		return sp > 0 ? t.substring(0, sp) : t;
	}
}
