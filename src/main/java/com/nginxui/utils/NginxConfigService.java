package com.nginxui.utils;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nginxui.ext.NginxDashboard;
import com.nginxui.ext.NginxInstanceEntry;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;

/**
 * 配置模块: nginx.conf 解析(容量/监听/stub_status 声明)、stub 监控块写入与移除、
 * 参数调优、nginx -t 配置检测与写入门禁.
 */
public final class NginxConfigService {

	private static final Logger LOG = LoggerFactory.getLogger(NginxConfigService.class);

	private static final Pattern WORKER_PROCESSES_PATTERN = Pattern
			.compile("(?m)^\\s*worker_processes\\s+([^\\s;]+)\\s*;");
	private static final Pattern WORKER_CONNECTIONS_PATTERN = Pattern
			.compile("(?m)^\\s*worker_connections\\s+(\\d+)\\s*;");
	private static final Pattern LISTEN_PATTERN = Pattern.compile("(?m)^\\s*listen\\s+([^;]+);");
	private static final Pattern STUB_DIRECTIVE_PATTERN = Pattern.compile("stub_status\\s*;");
	private static final Pattern LISTEN_VALUE_PATTERN = Pattern.compile("(?m)^\\s*listen\\s+([^;]+);");
	private static final Pattern LOCATION_PATH_PATTERN = Pattern
			.compile("location\\s*(?:=[\\s]*|\\^~[\\s]*|~\\*?[\\s]*)?([^\\s{]+)");

	private NginxConfigService() {
	}

	/** 配置中是否含本系统写入的 stub_status 标记块(兼容新旧品牌标记) */
	public static boolean isStubManaged(String confText) {
		return confText != null
				&& (confText.contains(NginxDashboardUtils.MARK_BEGIN)
						|| confText.contains(NginxDashboardUtils.LEGACY_MARK_BEGIN));
	}

	/** 安全读取配置文本; 读取失败记录错误码并返回 null, 由调用方给出用户提示 */
	static String readConfig(File file) {
		try {
			return FileUtil.readString(file, StandardCharsets.UTF_8);
		} catch (Exception e) {
			LOG.warn("[{}] 读取配置失败: {}", NginxErrorCode.READ_LOG_FAILED,
					file == null ? "" : file.getAbsolutePath(), e);
			return null;
		}
	}

	/**
	 * 写入stub_status监控配置(显式动作, 非开关副作用):
	 * 配置中已有 stub_status 声明时直接纳管(以声明为准, 不改写配置);
	 * 否则从9527起自动分配空闲端口, 向http块插入受标记保护的监控server段, 测试通过后生效.
	 */
	public static synchronized NginxDashboardUtils.StubConfigResult writeStubConfig(NginxInstanceEntry entry) {
		NginxDashboardUtils.StubConfigResult r = new NginxDashboardUtils.StubConfigResult();
		String confPath = StrUtil.nullToEmpty(entry.getConfPath());
		if (StrUtil.isEmpty(confPath) || !new File(confPath).isFile()) {
			r.code = NginxErrorCode.CONF_NOT_FOUND;
			r.error = NginxErrorCode.message(r.code, StrUtil.emptyToDefault(confPath, I18n.t("dash.stub.unidentified")));
			return r;
		}
		String conf = readConfig(new File(confPath));
		if (conf == null) {
			r.code = NginxErrorCode.CONFIG_WRITE_FAILED;
			r.error = NginxErrorCode.message(r.code, confPath);
			return r;
		}

		// 已有声明(系统块或手工块): 直接纳管, 端口/路径以配置实际声明为准
		StubDecl decl = detectStubDecl(conf);
		if (decl != null) {
			r.port = decl.port;
			r.uri = StrUtil.isNotEmpty(decl.uri) ? decl.uri : NginxInstanceRegistry.stubUriOf(decl.port);
			r.adopted = true;
			return r;
		}

		String dir = StrUtil.emptyToDefault(entry.getNginxDir(), entry.getPrefixDir());
		if (!supportsStubStatus(entry.getNginxExe(), dir)) {
			r.code = NginxErrorCode.STUB_MODULE_MISSING;
			r.error = NginxErrorCode.message(r.code);
			return r;
		}

		// 端口从9527(或本实例既有分配值)起, 被占用则+1直到空闲
		int port = entry.getStubPort() == null ? NginxInstanceRegistry.BASE_STUB_PORT : entry.getStubPort();
		if (!isStubPortFree(port)) {
			port = nextFreePort(port + 1);
			if (port < 0) {
				r.code = NginxErrorCode.STUB_PORT_EXHAUSTED;
				r.error = NginxErrorCode.message(r.code);
				return r;
			}
		}
		String uri = NginxInstanceRegistry.stubUriOf(port);

		int[] httpBlock = findBlock(conf, "http");
		if (httpBlock == null) {
			r.code = NginxErrorCode.HTTP_BLOCK_MISSING;
			r.error = NginxErrorCode.message(r.code);
			return r;
		}
		String newConf = conf.substring(0, httpBlock[1]) + buildStubBlock(port, uri) + conf.substring(httpBlock[1]);
		ApplyResult ap = writeTestApply(confPath, newConf, entry);
		if (ap.error != null) {
			r.code = ap.code;
			r.error = ap.error;
			return r;
		}
		r.port = port;
		r.uri = uri;
		r.applied = ap.applied;
		r.appliedBy = ap.by;
		return r;
	}

	/**
	 * 移除本系统写入的受标记stub块(绝不触碰手工声明的块), 测试通过后生效
	 */
	public static synchronized NginxDashboardUtils.StubConfigResult removeStubConfig(NginxInstanceEntry entry) {
		NginxDashboardUtils.StubConfigResult r = new NginxDashboardUtils.StubConfigResult();
		String confPath = StrUtil.nullToEmpty(entry.getConfPath());
		if (StrUtil.isEmpty(confPath) || !new File(confPath).isFile()) {
			r.code = NginxErrorCode.CONF_NOT_FOUND;
			r.error = NginxErrorCode.message(r.code, StrUtil.emptyToDefault(confPath, I18n.t("dash.stub.unidentified")));
			return r;
		}
		String conf = readConfig(new File(confPath));
		if (conf == null) {
			r.code = NginxErrorCode.CONFIG_WRITE_FAILED;
			r.error = NginxErrorCode.message(r.code, confPath);
			return r;
		}
		if (!isStubManaged(conf)) {
			r.code = NginxErrorCode.STUB_NOT_MANAGED;
			r.error = NginxErrorCode.message(r.code);
			return r;
		}
		String removed = Pattern
				.compile("[ \\t]*(?:" + Pattern.quote(NginxDashboardUtils.MARK_BEGIN) + "|"
						+ Pattern.quote(NginxDashboardUtils.LEGACY_MARK_BEGIN) + ").*?(?:"
						+ Pattern.quote(NginxDashboardUtils.MARK_END) + "|"
						+ Pattern.quote(NginxDashboardUtils.LEGACY_MARK_END) + ")" + "[ \\t]*\\r?\\n?",
						Pattern.DOTALL)
				.matcher(conf).replaceAll("");
		ApplyResult ap = writeTestApply(confPath, removed, entry);
		if (ap.error != null) {
			r.code = ap.code;
			r.error = ap.error;
			return r;
		}
		r.port = entry.getStubPort();
		r.uri = entry.getStubUri();
		r.applied = ap.applied;
		r.appliedBy = ap.by;
		return r;
	}

	/**
	 * 参数优化: 调整worker_processes/worker_connections, 测试并生效
	 */
	public static String tune(NginxInstanceEntry entry, String workerProcesses, Integer workerConnections) {
		String confPath = StrUtil.nullToEmpty(entry.getConfPath());
		if (StrUtil.isEmpty(confPath) || !new File(confPath).isFile()) {
			return NginxErrorCode.message(NginxErrorCode.CONF_NOT_FOUND, confPath);
		}
		String conf = readConfig(new File(confPath));
		if (conf == null) {
			return NginxErrorCode.message(NginxErrorCode.CONFIG_WRITE_FAILED, confPath);
		}

		try {
			if (StrUtil.isNotEmpty(workerProcesses)) {
				if (!"auto".equalsIgnoreCase(workerProcesses) && !workerProcesses.matches("\\d+")) {
					return NginxErrorCode.message(NginxErrorCode.TUNE_INVALID_WORKER);
				}
				Matcher wpm = WORKER_PROCESSES_PATTERN.matcher(conf);
				if (wpm.find()) {
					conf = conf.replaceFirst("(?m)^\\s*worker_processes\\s+\\S+\\s*;",
							Matcher.quoteReplacement("worker_processes  " + workerProcesses + ";"));
				} else {
					conf = "worker_processes  " + workerProcesses + ";\r\n" + conf;
				}
			}

			if (workerConnections != null && workerConnections > 0) {
				Matcher wcm = WORKER_CONNECTIONS_PATTERN.matcher(conf);
				if (wcm.find()) {
					conf = conf.replaceFirst("(?m)^\\s*worker_connections\\s+\\d+\\s*;",
							Matcher.quoteReplacement("    worker_connections  " + workerConnections + ";"));
				} else {
					int[] events = findBlock(conf, "events");
					String directive = "    worker_connections  " + workerConnections + ";\r\n";
					if (events != null) {
						conf = conf.substring(0, events[0] + 1) + "\r\n" + directive
								+ conf.substring(events[0] + 1);
					} else {
						int[] http = findBlock(conf, "http");
						String eventsBlock = "events {\r\n" + directive + "}\r\n\r\n";
						int insertAt = http != null ? http[0] : 0;
						conf = conf.substring(0, insertAt) + eventsBlock + conf.substring(insertAt);
					}
				}
			}

			ApplyResult ap = writeTestApply(confPath, conf, entry);
			return ap.error;
		} catch (Exception e) {
			LOG.warn("[{}] 参数优化失败: {}", NginxErrorCode.CONFIG_WRITE_FAILED, e.getMessage(), e);
			return NginxErrorCode.message(NginxErrorCode.CONFIG_WRITE_FAILED, e.getMessage());
		}
	}

	private static String buildStubBlock(int port, String uri) {
		if (StrUtil.isEmpty(uri)) {
			uri = NginxDashboardUtils.DEFAULT_STUB_URI;
		}
		if (!uri.startsWith("/")) {
			uri = "/" + uri;
		}
		StringBuilder sb = new StringBuilder();
		sb.append("    ").append(NginxDashboardUtils.MARK_BEGIN).append("\r\n");
		sb.append("    server {\r\n");
		sb.append("        listen 127.0.0.1:").append(port).append(";\r\n");
		sb.append("        server_name _;\r\n");
		sb.append("        location = ").append(uri).append(" {\r\n");
		sb.append("            stub_status;\r\n");
		sb.append("            access_log off;\r\n");
		sb.append("            allow 127.0.0.1;\r\n");
		sb.append("            deny all;\r\n");
		sb.append("        }\r\n");
		sb.append("    }\r\n");
		sb.append("    ").append(NginxDashboardUtils.MARK_END).append("\r\n");
		return sb.toString();
	}

	/** 备份写入 -> nginx -t门禁(失败回滚) -> 生效(仅本实例运行中) 的结果 */
	private static class ApplyResult {
		String code;
		String error;
		boolean applied;
		String by;
	}

	/**
	 * 备份写入 -> nginx -t校验(失败回滚) -> 实例运行中时使其生效:
	 * 统一走重启(Windows 下服务托管与进程实例的平滑重载均不可靠, 不再使用 reload);
	 * 实例未运行时只写配置, 下次启动生效.
	 */
	private static ApplyResult writeTestApply(String confPath, String newConf, NginxInstanceEntry entry) {
		ApplyResult r = new ApplyResult();
		File file = new File(confPath);
		File backup = new File(confPath + ".webuibak");
		String origin = readConfig(file);
		if (origin == null) {
			r.code = NginxErrorCode.CONFIG_WRITE_FAILED;
			r.error = NginxErrorCode.message(r.code, confPath);
			return r;
		}
		String exe = StrUtil.nullToEmpty(entry.getNginxExe());
		String dir = StrUtil.emptyToDefault(entry.getNginxDir(), entry.getPrefixDir());
		File workdir = StrUtil.isNotEmpty(dir) && new File(dir).isDirectory() ? new File(dir) : null;
		try {
			FileUtil.writeString(newConf, file, StandardCharsets.UTF_8);

			if (StrUtil.isEmpty(exe) || !new File(exe).isFile()) {
				FileUtil.writeString(origin, backup, StandardCharsets.UTF_8);
				return r;
			}

			List<String> testCmd = new ArrayList<String>();
			testCmd.add(resolveTestExe(workdir, exe));
			testCmd.add("-t");
			testCmd.add("-c");
			testCmd.add(confPath);
			if (StrUtil.isNotEmpty(dir)) {
				testCmd.add("-p");
				testCmd.add(dir);
			}
			String rs = NginxExecUtils.exec(30, testCmd.toArray(new String[0]));
			if (!rs.contains("successful")) {
				FileUtil.writeString(origin, file, StandardCharsets.UTF_8);
				r.code = StrUtil.isNotEmpty(rs.trim()) ? NginxErrorCode.CONFIG_TEST_FAILED : NginxErrorCode.TEST_ROLLBACK;
				r.error = NginxErrorCode.message(r.code, StrUtil.isNotEmpty(rs.trim()) ? rs.trim() : null);
				return r;
			}

			// 只有本实例在运行才需要生效(不能拿"同机任意nginx在跑"当依据)
			if (NginxControlService.findLiveInstance(entry) != null) {
				NginxDashboardUtils.ControlResult cr = NginxControlService.controlInstance(entry, "restart");
				if (!cr.success) {
					r.code = NginxErrorCode.RESTART_AFTER_WRITE_FAILED;
					r.error = NginxErrorCode.message(r.code, StrUtil.nullToEmpty(cr.message));
					return r;
				}
				r.applied = true;
				r.by = "restart";
			}

			FileUtil.writeString(origin, backup, StandardCharsets.UTF_8);
			return r;
		} catch (Exception e) {
			LOG.warn("[{}] 配置写入/检测失败: {}", NginxErrorCode.CONFIG_WRITE_FAILED, confPath, e);
			try {
				FileUtil.writeString(origin, file, StandardCharsets.UTF_8);
			} catch (Exception rollbackError) {
				LOG.error("[{}] 配置回滚失败: {}", NginxErrorCode.CONFIG_WRITE_FAILED, confPath, rollbackError);
			}
			r.code = NginxErrorCode.CONFIG_WRITE_FAILED;
			r.error = NginxErrorCode.message(r.code, e.getMessage());
			return r;
		}
	}

	/**
	 * nginx是否编译了 http_stub_status_module(按 nginx -V 编译参数判定, 结果按exe缓存).
	 * 取不到编译参数时不阻断(交由 nginx -t 门禁说话).
	 */
	public static boolean supportsStubStatus(String nginxExe, String prefixDir) {
		String exe = StrUtil.nullToEmpty(nginxExe);
		if (StrUtil.isEmpty(exe) || !new File(exe).isFile()) {
			File basic = StrUtil.isNotEmpty(prefixDir) ? new File(prefixDir, "nginx_basic.exe") : null;
			if (basic == null || !basic.isFile()) {
				return true;
			}
			exe = basic.getAbsolutePath();
		}

		String args = NginxProbeService.configureArgs(exe);
		if (StrUtil.isEmpty(args)) {
			return true;
		}
		if (args.contains("--without-http_stub_status_module")) {
			return false;
		}
		return args.contains("--with-http_stub_status_module");
	}

	private static boolean portAvailable(int port) {
		try (java.net.ServerSocket socket = new java.net.ServerSocket()) {
			socket.setReuseAddress(false);
			socket.bind(new InetSocketAddress("127.0.0.1", port), 1);
			return true;
		} catch (Exception e) {
			// 端口占用属正常探测结果
			return false;
		}
	}

	/** stub端口是否空闲(对外暴露) */
	public static boolean isStubPortFree(int port) {
		return portAvailable(port);
	}

	/**
	 * 从from起向后查找第一个空闲TCP端口(最多尝试20个), 用于多实例stub端口自动顺延.
	 * 全部被占用时返回-1.
	 */
	public static int nextFreePort(int from) {
		for (int port = from; port < from + 20; port++) {
			if (portAvailable(port)) {
				return port;
			}
		}
		return -1;
	}

	/** 执行 nginx -t 检测实例配置语法与文件可达性(不影响运行中的实例) */
	public static synchronized NginxDashboardUtils.ConfigTestResult testInstanceConfig(NginxInstanceEntry entry) {
		NginxDashboardUtils.ConfigTestResult result = new NginxDashboardUtils.ConfigTestResult();

		String exe = StrUtil.nullToEmpty(entry.getNginxExe());
		String conf = StrUtil.nullToEmpty(entry.getConfPath());
		String dir = StrUtil.emptyToDefault(entry.getNginxDir(), entry.getPrefixDir());
		if (!new File(exe).isFile()) {
			result.code = NginxErrorCode.EXE_NOT_FOUND;
			result.message = NginxErrorCode.message(result.code, exe);
			result.output = result.message;
			return result;
		}
		if (StrUtil.isEmpty(conf) || !new File(conf).isFile()) {
			result.code = NginxErrorCode.CONF_NOT_FOUND;
			result.message = NginxErrorCode.message(result.code, StrUtil.emptyToDefault(conf, I18n.t("dash.stub.unidentified")));
			result.output = result.message;
			return result;
		}
		File workdir = StrUtil.isNotEmpty(dir) && new File(dir).isDirectory() ? new File(dir) : null;
		return runNginxTest(workdir, resolveTestExe(workdir, exe), conf, dir);
	}

	/** 用指定可执行文件对实例配置执行 nginx -t(升级前用新版/备份版程序预检, 不改动实例) */
	public static synchronized NginxDashboardUtils.ConfigTestResult testInstanceConfigWithExe(NginxInstanceEntry entry,
			String exePath) {
		NginxDashboardUtils.ConfigTestResult result = new NginxDashboardUtils.ConfigTestResult();
		String conf = StrUtil.nullToEmpty(entry.getConfPath());
		String dir = StrUtil.emptyToDefault(entry.getNginxDir(), entry.getPrefixDir());
		if (StrUtil.isEmpty(exePath) || !new File(exePath).isFile()) {
			result.code = NginxErrorCode.EXE_NOT_FOUND;
			result.message = NginxErrorCode.message(result.code, exePath);
			result.output = result.message;
			return result;
		}
		if (StrUtil.isEmpty(conf) || !new File(conf).isFile()) {
			result.code = NginxErrorCode.CONF_NOT_FOUND;
			result.message = NginxErrorCode.message(result.code, StrUtil.emptyToDefault(conf, I18n.t("dash.stub.unidentified")));
			result.output = result.message;
			return result;
		}
		File workdir = StrUtil.isNotEmpty(dir) && new File(dir).isDirectory() ? new File(dir) : null;
		return runNginxTest(workdir, exePath, conf, dir);
	}

	/**
	 * 用给定文本临时落盘到实例配置同目录并执行 nginx -t(不改动真实配置).
	 * 临时文件与真实 conf 同目录且以同一 prefix 为工作目录, 使相对 include
	 * (如 conf.d/xx.conf) 的解析与真实配置一致, 检测结果可直接作为写入门禁.
	 */
	public static synchronized NginxDashboardUtils.ConfigTestResult testConfigContent(NginxInstanceEntry entry,
			String content) {
		NginxDashboardUtils.ConfigTestResult result = new NginxDashboardUtils.ConfigTestResult();

		String exe = StrUtil.nullToEmpty(entry.getNginxExe());
		String conf = StrUtil.nullToEmpty(entry.getConfPath());
		String dir = StrUtil.emptyToDefault(entry.getNginxDir(), entry.getPrefixDir());
		if (!new File(exe).isFile()) {
			result.code = NginxErrorCode.EXE_NOT_FOUND;
			result.message = NginxErrorCode.message(result.code, exe);
			result.output = result.message;
			return result;
		}
		if (StrUtil.isEmpty(conf) || !new File(conf).isFile()) {
			result.code = NginxErrorCode.CONF_NOT_FOUND;
			result.message = NginxErrorCode.message(result.code, StrUtil.emptyToDefault(conf, I18n.t("dash.stub.unidentified")));
			result.output = result.message;
			return result;
		}

		File tmp = new File(new File(conf).getParentFile(), "nginx.conf.ngui-test");
		File workdir = StrUtil.isNotEmpty(dir) && new File(dir).isDirectory() ? new File(dir) : null;
		try {
			FileUtil.writeString(StrUtil.nullToEmpty(content), tmp, StandardCharsets.UTF_8);
			return runNginxTest(workdir, resolveTestExe(workdir, exe), tmp.getAbsolutePath(), dir);
		} catch (Exception e) {
			LOG.warn("[{}] 临时配置写入失败: {}", NginxErrorCode.TEST_TMP_WRITE_FAILED, tmp.getAbsolutePath(), e);
			result.code = NginxErrorCode.TEST_TMP_WRITE_FAILED;
			result.message = NginxErrorCode.message(result.code, e.getMessage());
			result.output = result.message;
			return result;
		} finally {
			FileUtil.del(tmp);
		}
	}

	/**
	 * 执行 nginx -t(核心实现), 不做前置校验, 由调用方保证exe/conf存在.
	 */
	static NginxDashboardUtils.ConfigTestResult runNginxTest(File workdir, String exe, String conf, String dir) {
		NginxDashboardUtils.ConfigTestResult result = new NginxDashboardUtils.ConfigTestResult();
		List<String> cmd = new ArrayList<String>();
		cmd.add(exe);
		cmd.add("-t");
		cmd.add("-c");
		cmd.add(conf);
		if (StrUtil.isNotEmpty(dir)) {
			cmd.add("-p");
			cmd.add(dir);
		}
		try {
			String out = NginxExecUtils.exec(workdir, 30, cmd.toArray(new String[0]));
			String text = StrUtil.nullToEmpty(out).trim();
			result.output = StrUtil.isEmpty(text) ? I18n.t("dash.config.noOutput") : text;
			boolean ok = text.contains("successful") && !hasNginxError(text);
			result.success = ok;
			if (!ok) {
				result.code = NginxErrorCode.CONFIG_TEST_FAILED;
				result.message = NginxErrorCode.message(result.code, extractConfigError(text));
			}
		} catch (Exception e) {
			LOG.warn("[{}] nginx -t 执行异常: {}", NginxErrorCode.TEST_EXEC_ERROR, exe, e);
			result.code = NginxErrorCode.TEST_EXEC_ERROR;
			result.message = NginxErrorCode.message(result.code, e.getMessage());
			result.output = result.message;
		}
		return result;
	}

	private static boolean hasNginxError(String output) {
		return StrUtil.isNotEmpty(output)
				&& (output.contains("[error]") || output.contains("[emerg]") || output.contains("[alert]"));
	}

	/** 从 nginx -t 输出提取首条 emerg/error 行作为简要原因 */
	private static String extractConfigError(String output) {
		String text = StrUtil.nullToEmpty(output);
		for (String line : text.split("\\r?\\n")) {
			String l = line.trim();
			if (l.contains("[emerg]") || l.contains("[alert]") || l.contains("[error]")) {
				int pos = l.indexOf("]");
				return pos >= 0 ? l.substring(pos + 1).trim() : l;
			}
		}
		if (text.contains("failed")) {
			int idx = text.indexOf("failed");
			return text.substring(Math.max(0, idx - 80), Math.min(text.length(), idx + 20)).trim();
		}
		return I18n.t("dash.config.failedReason");
	}

	/**
	 * 启动前选择配置检测所用exe: Windows下prefix目录中的 nginx_basic.exe 优先(泛联/winsw风格改版nginx),
	 * 其次使用实例登记的exe; 配置本身已是basic或非Windows时直接用登记exe.
	 */
	static String resolveTestExe(File workdir, String configuredExe) {
		String lower = StrUtil.nullToEmpty(configuredExe).toLowerCase();
		if (lower.endsWith("nginx_basic.exe") || !SystemTool.isWindows()) {
			return configuredExe;
		}
		File basic = workdir != null ? new File(workdir, "nginx_basic.exe") : null;
		if (basic != null && basic.isFile()) {
			return basic.getAbsolutePath();
		}
		return configuredExe;
	}

	/** 配置中声明的stub_status监控: 监听端口 + 监控路径 */
	static class StubDecl {
		final int port;
		final String uri;

		StubDecl(int port, String uri) {
			this.port = port;
			this.uri = uri;
		}
	}

	/**
	 * 从实例配置解析 stub_status 监控server块声明的端口与路径.
	 * 兼容本系统生成的受标记块与手工/外部写入的块; 先剥离注释避免注释块误判.
	 * 未声明或解析失败时返回null(调用方回退到注册表分配值).
	 */
	static StubDecl detectStubDecl(String conf) {
		if (StrUtil.isEmpty(conf)) {
			return null;
		}
		String clean = conf.replaceAll("#[^\\r\\n]*", "");
		int from = 0;
		while (true) {
			int[] server = findBlock(clean.substring(from), "server");
			if (server == null) {
				return null;
			}
			int open = from + server[0];
			int close = from + server[1];
			StubDecl decl = stubDeclOf(clean.substring(open + 1, close));
			if (decl != null) {
				return decl;
			}
			from = close + 1;
		}
	}

	private static StubDecl stubDeclOf(String serverBody) {
		if (!STUB_DIRECTIVE_PATTERN.matcher(serverBody).find()) {
			return null;
		}
		Matcher lm = LISTEN_VALUE_PATTERN.matcher(serverBody);
		if (!lm.find()) {
			return null;
		}
		Integer port = portOfListen(lm.group(1));
		if (port == null || port <= 0 || port > 65535) {
			return null;
		}
		return new StubDecl(port, stubUriOfBlock(serverBody));
	}

	/** listen值取端口: 支持 80 / 127.0.0.1:80 / [::]:80 / 80 ssl 等写法 */
	private static Integer portOfListen(String value) {
		String token = value.trim().split("\\s+")[0];
		int colon = token.lastIndexOf(':');
		String digits = (colon >= 0 ? token.substring(colon + 1) : token).replaceAll("[^0-9]", "");
		if (StrUtil.isEmpty(digits)) {
			Matcher m = Pattern.compile("(\\d+)").matcher(value);
			if (m.find()) {
				digits = m.group(1);
			}
		}
		if (StrUtil.isEmpty(digits)) {
			return null;
		}
		try {
			return Integer.valueOf(digits);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** 含stub_status的location块声明的监控路径 */
	private static String stubUriOfBlock(String serverBody) {
		int from = 0;
		while (true) {
			int at = serverBody.indexOf("location", from);
			if (at < 0) {
				return null;
			}
			int brace = serverBody.indexOf('{', at);
			if (brace < 0) {
				return null;
			}
			int close = matchBrace(serverBody, brace);
			if (close < 0) {
				return null;
			}
			if (STUB_DIRECTIVE_PATTERN.matcher(serverBody.substring(brace + 1, close)).find()) {
				Matcher pm = LOCATION_PATH_PATTERN.matcher(serverBody.substring(at, brace));
				return pm.find() ? pm.group(1) : null;
			}
			from = close + 1;
		}
	}

	/**
	 * 查找nginx配置中指定块的大括号位置(跳过注释与引号)
	 *
	 * @return [开括号位置, 闭括号位置]
	 */
	static int[] findBlock(String conf, String blockName) {
		if (conf == null) {
			return null;
		}
		Pattern start = Pattern.compile("(^|[\\s;{}])" + Pattern.quote(blockName) + "\\s*\\{");
		Matcher m = start.matcher(conf);
		while (m.find()) {
			int open = conf.indexOf('{', m.start());
			int close = matchBrace(conf, open);
			if (close > 0) {
				return new int[] { open, close };
			}
		}
		return null;
	}

	/**
	 * 从开括号位置匹配对应闭括号
	 */
	static int matchBrace(String text, int open) {
		int depth = 0;
		boolean inSingle = false;
		boolean inDouble = false;
		for (int i = open; i < text.length(); i++) {
			char c = text.charAt(i);

			if (inSingle) {
				if (c == '\'') {
					inSingle = false;
				}
				continue;
			}
			if (inDouble) {
				if (c == '"') {
					inDouble = false;
				}
				continue;
			}

			if (c == '#') {
				while (i < text.length() && text.charAt(i) != '\n') {
					i++;
				}
				continue;
			}
			if (c == '\'') {
				inSingle = true;
			} else if (c == '"') {
				inDouble = true;
			} else if (c == '{') {
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}
		return -1;
	}

	static void fillConfConfig(NginxDashboard d, String conf, int cores) {
		if (StrUtil.isEmpty(conf)) {
			return;
		}
		Matcher wpm = WORKER_PROCESSES_PATTERN.matcher(conf);
		if (wpm.find()) {
			String val = wpm.group(1).trim();
			d.setWorkerProcesses(val);
			if ("auto".equalsIgnoreCase(val)) {
				d.setWorkerProcessesEff(cores);
			} else {
				try {
					d.setWorkerProcessesEff(Integer.parseInt(val));
				} catch (NumberFormatException e) {
					d.setWorkerProcessesEff(cores);
				}
			}
		} else {
			d.setWorkerProcesses("1");
			d.setWorkerProcessesEff(1);
		}

		Matcher wcm = WORKER_CONNECTIONS_PATTERN.matcher(conf);
		if (wcm.find()) {
			d.setWorkerConnections(Integer.parseInt(wcm.group(1)));
		}

		if (d.getWorkerProcessesEff() != null && d.getWorkerConnections() != null) {
			long max = (long) d.getWorkerProcessesEff() * d.getWorkerConnections();
			d.setMaxConnections(max);
		}
	}

	/**
	 * 提取配置中全部listen地址(每个声明原样首段, 供展示与冲突检测).
	 */
	static List<String> extractListenPorts(String conf) {
		List<String> listens = new ArrayList<String>();
		if (StrUtil.isEmpty(conf)) {
			return listens;
		}
		Matcher m = LISTEN_PATTERN.matcher(conf);
		while (m.find()) {
			String chunk = m.group(1);
			int hash = chunk.indexOf('#');
			if (hash >= 0) {
				chunk = chunk.substring(0, hash);
			}
			String token = chunk.trim().split("\\s+")[0];
			if (!listens.contains(token)) {
				listens.add(token);
			}
		}
		return listens;
	}

	/**
	 * 解析listen首个地址段, 返回 [address, port](通配地址归一化为*); unix套接字返回null.
	 */
	static String[] parseListen(String raw) {
		if (StrUtil.isEmpty(raw)) {
			return null;
		}
		// 去行内注释, 取第一个token
		String line = raw;
		int hash = line.indexOf('#');
		if (hash >= 0) {
			line = line.substring(0, hash);
		}
		line = line.trim();
		if (line.isEmpty()) {
			return null;
		}
		String spec = line.split("\\s+")[0].trim();
		if (spec.toLowerCase().startsWith("unix:")) {
			return null;
		}

		String address;
		String port;
		if (spec.startsWith("[")) {
			// IPv6: [::1]:80 或 [::]:80
			int rb = spec.indexOf(']');
			String host = rb > 0 ? spec.substring(1, rb) : "";
			String rest = rb >= 0 && rb + 1 < spec.length() && spec.charAt(rb + 1) == ':' ? spec.substring(rb + 2) : "80";
			address = (host.isEmpty() || "::".equals(host)) ? "*" : host;
			port = rest;
		} else if (spec.contains(":")) {
			int colon = spec.lastIndexOf(':');
			String host = spec.substring(0, colon);
			port = spec.substring(colon + 1);
			address = (host.isEmpty() || "*".equals(host) || "0.0.0.0".equals(host)) ? "*" : host;
		} else {
			address = "*";
			port = spec;
		}
		if (!port.matches("\\d+")) {
			return null;
		}
		return new String[] { address, port };
	}

	/** 判断配置解析出的listen声明中是否包含目标端口(任意地址) */
	static boolean listenDeclares(List<String> listenPorts, int port) {
		if (listenPorts == null) {
			return false;
		}
		for (String raw : listenPorts) {
			String[] socket = parseListen(raw);
			if (socket != null && String.valueOf(port).equals(socket[1])) {
				return true;
			}
		}
		return false;
	}
}
