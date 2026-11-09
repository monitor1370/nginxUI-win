package com.nginxui.utils;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;

/**
 * 全新安装实例的 Windows 服务注册, 对齐系统标准布局:
 *  - 实例名固定为 {@code nginx-win-ngui-} + 变量 N, 仅 N 可自定义(1-6 位数字),
 *    默认自动分配最小可用序号;
 *  - 实例目录内放置同名 WinSW 包装器({@code <name>.exe})、{@code <name>.xml}、{@code stop.bat};
 *  - 由包装器执行 {@code install} 完成 SCM 注册: 自动启动、失败自动重启、日志滚动。
 *
 * 注册成功后由 NginxInstanceRegistry 记录 winServiceName, 之后启动/停止/重启走服务控制。
 */
public class NginxWinServiceInstaller {

	/** 标准服务名/目录名前缀 */
	public static final String NAME_PREFIX = "nginx-win-ngui-";

	/** 随包内置的 WinSW 包装器(使用时复制为 <服务名>.exe) */
	private static final String WRAPPER_RESOURCE = "/winsw/winsw.exe";

	private static final Pattern SEQ_PATTERN = Pattern.compile("^" + NAME_PREFIX + "(\\d+)$");

	/** 变量 N 的格式: 1-6 位、大于 0、无前导零 */
	private static final Pattern SUFFIX_PATTERN = Pattern.compile("^[1-9][0-9]{0,5}$");

	/** 变量 N 的最大长度 */
	public static final int SUFFIX_MAX_LENGTH = 6;

	private static final long INSTALL_TIMEOUT_MS = 60_000L;

	public static class Result {
		public boolean ok;
		public String serviceName;
		public String message;
	}

	/**
	 * 分配下一个标准服务名: 同时避开 SCM 中已注册的服务名与安装根目录下已存在的目录,
	 * 取已用序号最大值 +1.
	 */
	public static String nextServiceName(File installRoot) {
		Set<String> used = new HashSet<String>();
		for (WindowsNginxServiceUtils.WinNginxService svc : WindowsNginxServiceUtils.list()) {
			if (StrUtil.isNotEmpty(svc.serviceName)) {
				used.add(svc.serviceName.trim().toLowerCase());
			}
		}
		if (installRoot != null && installRoot.isDirectory()) {
			File[] children = installRoot.listFiles();
			if (children != null) {
				for (File child : children) {
					used.add(child.getName().toLowerCase());
				}
			}
		}
		int max = 0;
		for (String name : used) {
			Matcher m = SEQ_PATTERN.matcher(name);
			if (m.matches()) {
				try {
					max = Math.max(max, Integer.parseInt(m.group(1)));
				} catch (NumberFormatException ignore) {
					// 序号溢出视为未占用
				}
			}
		}
		// list() 只返回可解析的 nginx 服务, 可能漏掉注册残留/目录已删的服务,
		// 这里逐个向 SCM 复核, 确保不返回已占用的服务名
		int seq = max + 1;
		String candidate = NAME_PREFIX + seq;
		while (WindowsNginxServiceUtils.queryState(candidate) != -1
				|| (installRoot != null && new File(installRoot, candidate).exists())) {
			seq++;
			candidate = NAME_PREFIX + seq;
		}
		return candidate;
	}

	/** 变量 N 格式校验: 1-6 位数字, 大于 0, 无前导零 */
	public static boolean isValidSuffix(String suffix) {
		return StrUtil.isNotEmpty(suffix) && SUFFIX_PATTERN.matcher(suffix.trim()).matches();
	}

	/** 实例名格式校验: 固定前缀 nginx-win-ngui- + 合法变量 N */
	public static boolean isValidName(String name) {
		if (StrUtil.isEmpty(name)) {
			return false;
		}
		Matcher m = SEQ_PATTERN.matcher(name.trim());
		return m.matches() && isValidSuffix(m.group(1));
	}

	/**
	 * 安装前的实例名双重校验: 格式(固定前缀 + 变量 N) + 唯一性(SCM 服务名、安装根目录同名目录).
	 *
	 * @return null 表示可用; 否则为面向用户的原因说明
	 */
	public static String checkInstanceName(String name, File installRoot) {
		String trimmed = StrUtil.trimToEmpty(name);
		if (trimmed.isEmpty()) {
			return "实例名称不能为空";
		}
		Matcher m = SEQ_PATTERN.matcher(trimmed);
		if (!m.matches()) {
			return "实例名称必须为 " + NAME_PREFIX + "N 格式：前缀固定不可修改，N 为数字";
		}
		if (!isValidSuffix(m.group(1))) {
			return "变量 N 必须为 1-" + SUFFIX_MAX_LENGTH + " 位整数且大于 0（不能为 0 或带前导零）";
		}
		if (WindowsNginxServiceUtils.queryState(trimmed) != -1) {
			return "名称冲突：Windows 服务「" + trimmed + "」已存在，请调整 N";
		}
		if (installRoot != null && new File(installRoot, trimmed).exists()) {
			return "名称冲突：目录「" + new File(installRoot, trimmed).getAbsolutePath() + "」已存在，请调整 N";
		}
		return null;
	}

	/**
	 * 在实例目录内生成标准服务要素并注册到 SCM.
	 *
	 * @param instanceDir 实例目录(即 nginx prefix, 同时作为服务包装器所在目录)
	 * @param nginxExe    实例内的 nginx.exe
	 * @param serviceName 标准服务名
	 * @param log         进度输出(可空)
	 */
	public static Result install(File instanceDir, File nginxExe, String serviceName, Consumer<String> log) {
		Result r = new Result();
		r.serviceName = serviceName;
		if (instanceDir == null || !instanceDir.isDirectory()) {
			return fail(r, "实例目录不存在: " + instanceDir, log);
		}
		if (nginxExe == null || !nginxExe.isFile()) {
			return fail(r, "实例内缺少 nginx.exe: " + nginxExe, log);
		}
		if (StrUtil.isEmpty(serviceName) || !isValidName(serviceName)) {
			return fail(r, "服务名不符合命名规则: " + serviceName, log);
		}
		if (WindowsNginxServiceUtils.queryState(serviceName) != -1) {
			return fail(r, "服务名已被占用: " + serviceName, log);
		}

		File wrapper = new File(instanceDir, serviceName + ".exe");
		try {
			log(log, "生成服务配置: " + serviceName + ".xml");
			FileUtil.writeString(buildXml(instanceDir, nginxExe, serviceName),
					new File(instanceDir, serviceName + ".xml"), StandardCharsets.UTF_8);
			log(log, "生成停止脚本: stop.bat");
			FileUtil.writeString(buildStopBat(instanceDir, nginxExe),
					new File(instanceDir, "stop.bat"), StandardCharsets.UTF_8);
			log(log, "释放服务包装器: " + wrapper.getName());
			copyWrapper(wrapper);
		} catch (Exception e) {
			return fail(r, "生成服务文件失败: " + e.getMessage(), log);
		}

		try {
			log(log, "注册 Windows 服务: " + serviceName);
			String out = runWrapper(wrapper, INSTALL_TIMEOUT_MS, "install");
			if (WindowsNginxServiceUtils.queryState(serviceName) == -1) {
				return fail(r, "服务注册未生效(SCM 中不存在): " + StrUtil.trimToEmpty(out), log);
			}
			r.ok = true;
			r.message = "Windows 服务已注册: " + serviceName;
			log(log, r.message);
			return r;
		} catch (Exception e) {
			// 注册失败但可能留下半注册项: 尽力清理, 避免脏服务名占用
			try {
				runWrapper(wrapper, 30_000L, "uninstall");
			} catch (Exception ignore) {
				// 清理失败不影响主流程错误信息
			}
			return fail(r, "Windows 服务注册失败: " + e.getMessage(), log);
		}
	}

	/** 标准 WinSW 配置(与系统既有实例保持一致) */
	private static String buildXml(File instanceDir, File nginxExe, String serviceName) {
		String prefix = instanceDir.getAbsolutePath();
		StringBuilder sb = new StringBuilder();
		sb.append("<service>\r\n");
		sb.append("  <id>").append(serviceName).append("</id>\r\n");
		sb.append("  <name>").append(serviceName).append("</name>\r\n");
		sb.append("  <description>nginx instance managed by nginxUI (")
				.append(serviceName).append(")</description>\r\n");
		sb.append("  <executable>").append(escapeXml(nginxExe.getAbsolutePath()))
				.append("</executable>\r\n");
		sb.append("  <arguments>-p \"").append(escapeXml(prefix)).append("\" -c \"")
				.append(escapeXml(new File(instanceDir, "conf" + File.separator + "nginx.conf")
						.getAbsolutePath()))
				.append("\"</arguments>\r\n");
		sb.append("  <workingdirectory>").append(escapeXml(prefix))
				.append("</workingdirectory>\r\n");
		sb.append("  <stopexecutable>").append(escapeXml(new File(instanceDir, "stop.bat")
				.getAbsolutePath())).append("</stopexecutable>\r\n");
		sb.append("  <stoptimeout>15sec</stoptimeout>\r\n");
		sb.append("  <startmode>Automatic</startmode>\r\n");
		sb.append("  <delayedAutoStart>true</delayedAutoStart>\r\n");
		sb.append("  <logpath>").append(escapeXml(new File(new File(instanceDir, "logs"), "winsw")
				.getAbsolutePath())).append("</logpath>\r\n");
		sb.append("  <logmode>rotate</logmode>\r\n");
		sb.append("  <onfailure action=\"restart\" delay=\"10 sec\"/>\r\n");
		sb.append("  <resetfailure>1 hour</resetfailure>\r\n");
		sb.append("</service>\r\n");
		return sb.toString();
	}

	/** 停止脚本: 使用绝对路径, 不受包装器工作目录影响 */
	private static String buildStopBat(File instanceDir, File nginxExe) {
		String prefix = instanceDir.getAbsolutePath();
		String conf = new File(instanceDir, "conf" + File.separator + "nginx.conf").getAbsolutePath();
		return "@echo off\r\n\""
				+ nginxExe.getAbsolutePath() + "\" -p \"" + prefix + "\" -c \"" + conf + "\" -s quit\r\n";
	}

	private static void copyWrapper(File target) throws Exception {
		InputStream in = NginxWinServiceInstaller.class.getResourceAsStream(WRAPPER_RESOURCE);
		if (in == null) {
			throw new IllegalStateException("内置服务包装器资源缺失: " + WRAPPER_RESOURCE);
		}
		try (InputStream input = in; OutputStream out = Files.newOutputStream(target.toPath())) {
			IoUtil.copy(input, out);
		}
	}

	/** 执行包装器子命令(install/uninstall), 失败抛异常并携带输出 */
	private static String runWrapper(File wrapper, long timeoutMs, String arg) throws Exception {
		ProcessBuilder pb = new ProcessBuilder(wrapper.getAbsolutePath(), arg);
		pb.directory(wrapper.getParentFile());
		pb.redirectErrorStream(true);
		Process process = pb.start();
		String out = drain(process);
		if (!process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
			process.destroyForcibly();
			throw new IllegalStateException(wrapper.getName() + " " + arg + " 执行超时");
		}
		if (process.exitValue() != 0) {
			throw new IllegalStateException(wrapper.getName() + " " + arg + " 退出码 "
					+ process.exitValue() + ": " + StrUtil.trimToEmpty(out));
		}
		return out;
	}

	private static String drain(Process process) {
		try {
			InputStream in = process.getInputStream();
			String text = IoUtil.read(in, StandardCharsets.UTF_8);
			return text.length() > 4096 ? text.substring(0, 4096) : text;
		} catch (Exception e) {
			return "";
		}
	}

	private static String escapeXml(String value) {
		return StrUtil.nullToEmpty(value).replace("&", "&amp;").replace("<", "&lt;")
				.replace(">", "&gt;").replace("\"", "&quot;");
	}

	private static void log(Consumer<String> sink, String message) {
		if (sink != null) {
			sink.accept(message);
		}
	}

	private static Result fail(Result r, String msg, Consumer<String> log) {
		r.ok = false;
		r.message = msg;
		if (log != null) {
			log.accept(msg);
		}
		return r;
	}
}
