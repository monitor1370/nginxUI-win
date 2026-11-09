package com.nginxui.utils;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.nginxui.ext.NginxInstanceEntry;
import com.nginxui.utils.NginxCertReplaceManager.Inspect;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;

/**
 * HTTPS 启用:
 *  以站点(server 块)为单位, 在保持原有监听不变的前提下追加 SSL 监听与证书指令,
 *  可选把 HTTP 请求 301 跳转到 HTTPS.
 *
 * 事务边界与证书替换一致: 先备份配置 → 写入 → nginx -t 门禁 → 实例运行中则重启 →
 *  任一步失败恢复原配置并尽力重启回原状态; 新建的证书文件在失败时删除.
 */
public class NginxHttpsManager {

	private static final DateTimeFormatter LOG_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final File home;

	public NginxHttpsManager(File home) {
		this.home = home;
	}

	// ==================== 端口预检 ====================

	public static class PortCheck {
		public boolean ok;
		/** 端口已被本实例的站点占用, 按 SNI 共享 */
		public boolean shared;
		public boolean free;
		public String message = "";
	}

	/**
	 * 端口预检:
	 *  - 本实例已有站点监听该端口 → 允许(SNI 共享), 给出提示;
	 *  - 端口被其它进程占用 → 阻断, 由用户更换端口或先释放.
	 */
	public PortCheck checkPort(NginxInstanceEntry entry, List<NginxSite> sites, NginxSite target, int port) {
		PortCheck check = new PortCheck();
		if (port < 1 || port > 65535) {
			check.message = "端口必须在 1-65535 之间";
			return check;
		}
		String portText = String.valueOf(port);
		if (sites != null) {
			for (NginxSite site : sites) {
				if (sameSite(site, target)) {
					continue;
				}
				for (NginxSite.Listen listen : site.listens) {
					if (!portText.equals(listen.port)) {
						continue;
					}
					if (listen.ssl) {
						check.ok = true;
						check.shared = true;
						check.message = "该端口已被本实例其它 HTTPS 站点使用，将按 SNI 共享（需各站点域名正确）";
					} else {
						check.message = "该端口已被本实例的非 HTTPS 站点占用，不能在同一端口同时提供 HTTP 与 HTTPS";
					}
					return check;
				}
			}
		}
		if (target != null) {
			for (NginxSite.Listen listen : target.listens) {
				if (portText.equals(listen.port)) {
					check.ok = true;
					check.message = listen.ssl ? "该端口已由当前站点以 SSL 监听，将补充证书配置"
							: "该端口已由当前站点监听，将就地补充 SSL 配置";
					return check;
				}
			}
		}
		if (portAvailable(port)) {
			check.ok = true;
			check.free = true;
			check.message = "端口可用";
		} else {
			check.message = "端口已被其它进程占用，请更换端口或先释放该端口";
		}
		return check;
	}

	private static boolean portAvailable(int port) {
		try (ServerSocket socket = new ServerSocket()) {
			socket.setReuseAddress(false);
			socket.bind(new InetSocketAddress("0.0.0.0", port), 1);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	// ==================== 启用请求 / 结果 ====================

	public static class EnableRequest {
		public NginxInstanceEntry entry;
		public NginxSite site;
		/** 对外提供 HTTPS 的端口, 默认 443 */
		public int httpsPort = 443;
		/** 是否新增 HTTP → HTTPS 301 跳转站点 */
		public boolean redirectHttp;
		/** 新证书与私钥(已通过界面校验的本地文件) */
		public File pemFile;
		public File keyFile;
		/** 证书来源描述, 仅用于日志 */
		public String sourceLabel = "";
	}

	public static class EnableResult {
		public boolean success;
		public boolean rolledBack;
		public boolean restarted;
		public boolean portVerified;
		public String message = "";
		public String output = "";
		public List<String> backups = new ArrayList<String>();
		public List<String> warnings = new ArrayList<String>();
		public String pemPath = "";
		public String keyPath = "";
		public String httpsUrl = "";
	}

	// ==================== 启用 ====================

	public EnableResult enable(EnableRequest req) {
		EnableResult result = new EnableResult();
		if (req == null || req.entry == null || req.site == null) {
			result.message = "未指定站点";
			return result;
		}
		NginxSite site = req.site;
		File conf = site.sourceConf;
		if (conf == null || !conf.isFile()) {
			result.message = "站点来源配置文件不存在，请重新扫描站点";
			return result;
		}
		if (req.httpsPort < 1 || req.httpsPort > 65535) {
			result.message = "HTTPS 端口无效: " + req.httpsPort;
			return result;
		}
		if (req.pemFile == null || !req.pemFile.isFile()) {
			result.message = "未选择有效的证书文件";
			return result;
		}
		if (req.keyFile == null || !req.keyFile.isFile()) {
			result.message = "未选择有效的私钥文件";
			return result;
		}

		String originalText;
		try {
			originalText = FileUtil.readString(conf, StandardCharsets.UTF_8);
		} catch (Exception e) {
			result.message = "读取配置文件失败: " + e.getMessage();
			return result;
		}
		if (site.blockOpen <= 0 || site.blockOpen >= originalText.length()
				|| originalText.charAt(site.blockOpen) != '{') {
			result.message = "站点在配置中的位置已变化，请重新扫描后重试";
			return result;
		}

		// ---- 1. 证书校验(改动任何文件之前)
		Inspect pemCheck = NginxCertReplaceManager.inspectPem(
				safeRead(req.pemFile));
		if (!pemCheck.ok()) {
			result.message = pemCheck.error;
			log("WARN", "启用 HTTPS 被拒绝: " + pemCheck.error, null);
			return result;
		}
		Inspect keyCheck = NginxCertReplaceManager.inspectKey(safeRead(req.keyFile), null);
		if (!keyCheck.ok()) {
			result.message = keyCheck.error;
			log("WARN", "启用 HTTPS 被拒绝: " + keyCheck.error, null);
			return result;
		}
		result.warnings.addAll(pemCheck.warnings);
		result.warnings.addAll(keyCheck.warnings);

		// ---- 2. 端口预检(以最近一次站点扫描结果为参照)
		PortCheck portCheck = checkPort(req.entry, new NginxSiteScanner().scan(req.entry), site,
				req.httpsPort);
		if (!portCheck.ok) {
			result.message = portCheck.message;
			return result;
		}
		if (portCheck.shared) {
			result.warnings.add(portCheck.message);
		}
		boolean addRedirect = req.redirectHttp && !site.hasSslListen();
		if (addRedirect) {
			for (NginxSite.Listen listen : site.listens) {
				if (!listen.ssl && String.valueOf(req.httpsPort).equals(listen.port)) {
					result.message = "跳转模式下 HTTPS 端口不能与站点 HTTP 端口相同（" + req.httpsPort + "）";
					return result;
				}
			}
		} else if (req.redirectHttp) {
			result.warnings.add("站点已声明 SSL 监听，已忽略新增的 HTTP 跳转站点");
		}

		// ---- 3. 证书落盘到实例 ssl 目录
		File sslDir = sslDir(req.entry, conf);
		FileUtil.mkdir(sslDir);
		String stamp = DateUtil.format(new Date(), "yyyyMMddHHmmss");
		String baseName = safeName(site.domain());
		File destPem = new File(sslDir, baseName + "-" + stamp + ".pem");
		File destKey = new File(sslDir, baseName + "-" + stamp + ".key");
		List<File> created = new ArrayList<File>();
		try {
			FileUtil.copy(req.pemFile, destPem, true);
			created.add(destPem);
			FileUtil.copy(req.keyFile, destKey, true);
			created.add(destKey);
		} catch (Exception e) {
			cleanup(created);
			result.message = "证书写入实例目录失败: " + e.getMessage();
			log("ERROR", result.message, e);
			return result;
		}
		result.pemPath = destPem.getAbsolutePath();
		result.keyPath = destKey.getAbsolutePath();

		String configPem = configPath(destPem);
		String configKey = configPath(destKey);

		// ---- 4. 生成新配置
		String block = originalText.substring(site.blockStart, site.blockClose + 1);
		String separator = originalText.contains("\r\n") ? "\r\n" : "\n";
		String baseIndent = leadingWhitespace(atLineStart(originalText, site.blockStart));
		StringBuilder newText = new StringBuilder(originalText.length() + 1024);
		newText.append(originalText, 0, site.blockStart);

		// 边界: 原块已经有 SSL 监听且端口 == httpsPort → 就地只换证书, 不新增重复 HTTPS server 块
		if (site.hasSslListen() && hasSslListenOnPort(block, req.httpsPort)) {
			String patched = replaceCertInBlock(block, configPem, configKey);
			newText.append(patched);
			newText.append(originalText, site.blockClose + 1, originalText.length());
			result.warnings.add("站点已存在 HTTPS 监听（端口 " + req.httpsPort
					+ " SSL），本次仅替换证书，不新增 server 块");
		} else {
			// 正常路径: 独立新增 HTTPS server 块
			String newHttpsBlock;
			try {
				newHttpsBlock = buildHttpsBlock(block, req.httpsPort, configPem, configKey);
			} catch (Exception e) {
				cleanup(created);
				result.message = "生成 HTTPS 配置失败: " + e.getMessage();
				log("ERROR", result.message, e);
				return result;
			}
			if (addRedirect) {
				newText.append(stripNonSslListens(block));
				newText.append(separator).append(separator);
				newText.append(buildRedirectBlock(site, req.httpsPort, baseIndent));
			} else {
				newText.append(block);
			}
			newText.append(separator).append(separator);
			newText.append(newHttpsBlock);
			newText.append(originalText, site.blockClose + 1, originalText.length());
		}

		// ---- 5. 备份 + 写入 + nginx -t 门禁 + 重启
		File backup = backup(conf);
		if (backup == null || !backup.isFile()) {
			cleanup(created);
			result.message = "配置备份失败，已取消本次操作";
			log("ERROR", result.message + ": " + conf.getAbsolutePath(), null);
			return result;
		}
		result.backups.add(backup.getAbsolutePath());
		boolean running = NginxDashboardUtils.isInstanceRunning(req.entry);
		try {
			FileUtil.writeString(newText.toString(), conf, StandardCharsets.UTF_8);
			NginxDashboardUtils.ConfigTestResult test =
					NginxDashboardUtils.testInstanceConfig(req.entry);
			result.output = test.output;
			if (!test.success) {
				restore(conf, originalText);
				cleanup(created);
				result.rolledBack = true;
				result.message = "启用 HTTPS 后配置检测未通过，已回滚：" + StrUtil.emptyToDefault(test.message,
						test.output);
				log("ERROR", result.message, null);
				return result;
			}

			if (running) {
				NginxDashboardUtils.ControlResult cr =
						NginxDashboardUtils.controlInstance(req.entry, "restart");
				if (!cr.success) {
					restore(conf, originalText);
					cleanup(created);
					result.rolledBack = true;
					restartQuietly(req.entry);
					result.message = "配置检测通过，但实例重启失败，已回滚配置："
							+ StrUtil.nullToEmpty(cr.message);
					log("ERROR", result.message, null);
					return result;
				}
				result.restarted = true;
				result.portVerified = waitForPort(req.httpsPort, 4000);
				if (!result.portVerified) {
					result.warnings.add("实例已重启，但 HTTPS 端口 " + req.httpsPort
							+ " 暂未可连接，请检查端口占用或查看错误日志");
				}
			}

			result.success = true;
			result.httpsUrl = "https://" + site.domain()
					+ (req.httpsPort == 443 ? "" : ":" + req.httpsPort);
			result.message = running
					? "HTTPS 已启用并通过配置检测，实例已重启生效"
					: "HTTPS 配置已写入并通过检测；实例当前未运行，启动后生效";
			log("INFO", "启用 HTTPS 成功: 实例=" + displayName(req.entry)
					+ ", 站点=" + site.domain()
					+ ", 端口=" + req.httpsPort
					+ ", 跳转=" + req.redirectHttp
					+ ", 来源=" + req.sourceLabel
					+ ", 证书=" + result.pemPath
					+ ", 备份=" + result.backups, null);
			return result;
		} catch (Exception e) {
			restore(conf, originalText);
			cleanup(created);
			result.rolledBack = true;
			if (running) {
				restartQuietly(req.entry);
			}
			result.message = "启用 HTTPS 失败" + (result.rolledBack ? "，已回滚" : "") + "：" + e.getMessage();
			log("ERROR", "启用 HTTPS 失败: 实例=" + displayName(req.entry), e);
			return result;
		}
	}

	// ==================== server 块改写 ====================

	/**
	 * 根据原 server 块内容生成一个独立的 HTTPS server 块:
	 *  - 复制原块的 server_name、location、proxy_*、root 等全部指令;
	 *  - 所有原 listen 行去掉, 替换为一行 listen &lt;port&gt; ssl;
	 *  - 追加/替换 ssl_certificate、ssl_certificate_key;
	 *  - 追加 ssl_protocols TLSv1.2 TLSv1.3 (若原块里没有 ssl_protocols).
	 *
	 * 原 HTTP server 块由调用方完整保留, 不在此处修改.
	 */
	private String buildHttpsBlock(String block, int port, String configPem, String configKey) {
		String separator = block.contains("\r\n") ? "\r\n" : "\n";
		String[] lines = block.split("\r?\n", -1);
		String indent = "    ";
		boolean indentSet = false;

		List<String> copied = new ArrayList<String>();
		// 去掉结尾大括号, 我们自己拼一个
		int braceEnd = -1;
		for (int i = lines.length - 1; i >= 0; i--) {
			if (lines[i].trim().equals("}")) {
				braceEnd = i;
				break;
			}
		}
		int copyUntil = braceEnd < 0 ? lines.length - 1 : braceEnd - 1;

		boolean certSet = false;
		boolean keySet = false;
		boolean protoSet = false;
		int lastListenIdx = -1;

		for (int i = 0; i <= copyUntil; i++) {
			String line = lines[i];
			String trimmed = line.trim();
			if (trimmed.toLowerCase().startsWith("listen") && trimmed.endsWith(";")) {
				lastListenIdx = copied.size();
				String lead = leadingWhitespace(line);
				if (!indentSet && !lead.isEmpty()) {
					indent = lead;
					indentSet = true;
				}
				// 原 listen 行丢弃, 最后统一换成 listen <port> ssl
				continue;
			}
			if (trimmed.toLowerCase().startsWith("ssl_certificate_key") && trimmed.endsWith(";")) {
				copied.add(leadingWhitespace(line) + "ssl_certificate_key " + configKey + ";");
				keySet = true;
				continue;
			}
			if (trimmed.toLowerCase().startsWith("ssl_certificate") && trimmed.endsWith(";")) {
				copied.add(leadingWhitespace(line) + "ssl_certificate " + configPem + ";");
				certSet = true;
				continue;
			}
			if (trimmed.toLowerCase().startsWith("ssl_protocols") && trimmed.endsWith(";")) {
				protoSet = true;
				// 保留原 ssl_protocols 指令不动
				copied.add(line);
				continue;
			}
			if (trimmed.toLowerCase().startsWith("ssl_") && trimmed.endsWith(";")) {
				// 其它 ssl_ 指令 (ssl_ciphers / ssl_prefer_server_ciphers 等) 原封不动
				copied.add(line);
				continue;
			}
			copied.add(line);
		}

		// 确定插入点: 在原 listen 之后 / server_name 之后
		int insertAt = lastListenIdx >= 0 ? lastListenIdx : Math.min(1, copied.size());
		if (lastListenIdx < 0) {
			for (int i = 0; i < copied.size(); i++) {
				if (copied.get(i).trim().toLowerCase().startsWith("server_name")) {
					insertAt = i + 1;
					break;
				}
			}
		}

		// 统一生成新的 SSL listen 行
		copied.add(insertAt++, indent + "listen " + port + " ssl;");
		if (!certSet) {
			copied.add(insertAt++, indent + "ssl_certificate " + configPem + ";");
		}
		if (!keySet) {
			copied.add(insertAt++, indent + "ssl_certificate_key " + configKey + ";");
		}
		if (!protoSet) {
			copied.add(insertAt, indent + "ssl_protocols TLSv1.2 TLSv1.3;");
		}

		String head = leadingWhitespace(lines[0]);
		StringBuilder sb = new StringBuilder();
		sb.append(head).append("server {").append(separator);
		for (String l : copied) {
			sb.append(l).append(separator);
		}
		sb.append(head).append("}").append(separator);
		return sb.toString();
	}

	private static String atLineStart(String text, int index) {
		int start = text.lastIndexOf('\n', Math.max(0, index - 1)) + 1;
		return text.substring(start, index);
	}

	private static String leadingWhitespace(String line) {
		int i = 0;
		while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
			i++;
		}
		return line.substring(0, i);
	}

	/** 生成 HTTP → HTTPS 跳转站点, 插在原站点之前; 原站点非 SSL 监听已同步移除 */
	private String buildRedirectBlock(NginxSite site, int httpsPort, String baseIndent) {
		String separator = "\r\n";
		StringBuilder sb = new StringBuilder();
		sb.append("server {").append(separator);
		List<String> listenArgs = new ArrayList<String>();
		for (NginxSite.Listen listen : site.listens) {
			if (!listen.ssl) {
				String args = listen.plainArgs();
				if (!listenArgs.contains(args)) {
					listenArgs.add(args);
				}
			}
		}
		if (listenArgs.isEmpty()) {
			listenArgs.add("80");
		}
		for (String args : listenArgs) {
			sb.append(baseIndent).append("    listen ").append(args).append(";").append(separator);
		}
		List<String> names = site.serverNameTextList();
		sb.append(baseIndent).append("    server_name ")
				.append(names.isEmpty() ? "_" : String.join(" ", names)).append(";").append(separator);
		sb.append(baseIndent).append("    return 301 https://$host")
				.append(httpsPort == 443 ? "" : ":" + httpsPort)
				.append("$request_uri;").append(separator);
		sb.append(baseIndent).append("}").append(separator).append(separator);
		return sb.toString();
	}

	// ==================== 边界辅助 ====================

	/** 去掉 server 块内所有非 SSL 的 listen 行 (redirect 模式下避免同端口重复) */
	private static String stripNonSslListens(String block) {
		String sep = block.contains("\r\n") ? "\r\n" : "\n";
		String[] lines = block.split("\r?\n", -1);
		List<String> out = new ArrayList<String>();
		for (String line : lines) {
			String trimmed = line.trim();
			if (trimmed.toLowerCase().startsWith("listen") && trimmed.endsWith(";")) {
				boolean ssl = false;
				for (String token : trimmed.substring("listen".length(), trimmed.length() - 1).trim().split("\\s+")) {
					if ("ssl".equalsIgnoreCase(token)) {
						ssl = true;
						break;
					}
				}
				if (!ssl) {
					continue; // 跳过非 SSL 的 listen 行
				}
			}
			out.add(line);
		}
		return String.join(sep, out);
	}

	/** 原 server 块是否已存在 listen {port} ssl 监听 */
	private static boolean hasSslListenOnPort(String block, int port) {
		String[] lines = block.split("\r?\n", -1);
		for (String line : lines) {
			String trimmed = line.trim();
			if (!trimmed.toLowerCase().startsWith("listen") || !trimmed.endsWith(";")) {
				continue;
			}
			String value = trimmed.substring("listen".length(), trimmed.length() - 1).trim();
			String[] tokens = value.split("\\s+");
			if (tokens.length == 0) {
				continue;
			}
			boolean ssl = false;
			for (String t : tokens) {
				if ("ssl".equalsIgnoreCase(t)) {
					ssl = true;
					break;
				}
			}
			if (!ssl) {
				continue;
			}
			String lp = portOf(tokens[0]);
			if (String.valueOf(port).equals(lp)) {
				return true;
			}
		}
		return false;
	}

	/** 仅替换证书路径, 原块其它内容不动 (同端口 SSL 已存在时的快速路径) */
	private static String replaceCertInBlock(String block, String configPem, String configKey) {
		String sep = block.contains("\r\n") ? "\r\n" : "\n";
		String[] lines = block.split("\r?\n", -1);
		List<String> out = new ArrayList<String>();
		for (String line : lines) {
			String trimmed = line.trim();
			if (trimmed.toLowerCase().startsWith("ssl_certificate_key") && trimmed.endsWith(";")) {
				out.add(leadingWhitespace(line) + "ssl_certificate_key " + configKey + ";");
			} else if (trimmed.toLowerCase().startsWith("ssl_certificate") && trimmed.endsWith(";")) {
				out.add(leadingWhitespace(line) + "ssl_certificate " + configPem + ";");
			} else {
				out.add(line);
			}
		}
		return String.join(sep, out);
	}

	// ==================== 配置事务 ====================

	private void restore(File conf, String originalText) {
		try {
			FileUtil.writeString(originalText, conf, StandardCharsets.UTF_8);
			log("WARN", "已恢复配置: " + conf.getAbsolutePath(), null);
		} catch (Exception e) {
			log("ERROR", "恢复配置失败: " + conf.getAbsolutePath(), e);
		}
	}

	private void restartQuietly(NginxInstanceEntry entry) {
		try {
			NginxDashboardUtils.controlInstance(entry, "restart");
		} catch (Exception e) {
			log("WARN", "回滚后重启实例失败: " + displayName(entry), e);
		}
	}

	private void cleanup(List<File> created) {
		for (File file : created) {
			try {
				if (file.isFile()) {
					FileUtil.del(file);
				}
			} catch (Exception e) {
				log("ERROR", "清理新建证书文件失败: " + file.getAbsolutePath(), e);
			}
		}
	}

	private boolean waitForPort(int port, long timeoutMs) {
		long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
		while (true) {
			try (Socket socket = new Socket()) {
				socket.connect(new InetSocketAddress("127.0.0.1", port), 600);
				return true;
			} catch (Exception ignored) {
				if (System.currentTimeMillis() >= deadline) {
					return false;
				}
				try {
					Thread.sleep(200);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return false;
				}
			}
		}
	}

	// ==================== 工具 ====================

	private File sslDir(NginxInstanceEntry entry, File conf) {
		File prefix = prefixDir(entry);
		return prefix != null ? new File(prefix, "conf/ssl") : new File(conf.getParentFile(), "ssl");
	}

	private static File prefixDir(NginxInstanceEntry entry) {
		String p = StrUtil.emptyToDefault(entry.getPrefixDir(), entry.getNginxDir());
		if (StrUtil.isNotEmpty(p)) {
			return new File(p);
		}
		File conf = new File(StrUtil.nullToEmpty(entry.getConfPath()));
		File parent = conf.getParentFile();
		return parent == null ? null : parent.getParentFile();
	}

	private static String safeName(String domain) {
		String name = StrUtil.nullToEmpty(domain).replaceAll("[^A-Za-z0-9.-]", "_");
		name = name.replaceAll("^_+|_+$", "");
		return StrUtil.isEmpty(name) ? "site" : name;
	}

	private static String portOf(String spec) {
		String value = StrUtil.nullToEmpty(spec).trim();
		if (value.startsWith("[")) {
			int idx = value.indexOf("]:");
			value = idx < 0 ? "" : value.substring(idx + 2);
		} else if (value.contains(":")) {
			value = value.substring(value.lastIndexOf(':') + 1);
		}
		return value.matches("\\d{1,5}") ? value : "";
	}

	private static String configPath(File file) {
		return file.getAbsolutePath().replace("\\", "/");
	}

	private static boolean sameSite(NginxSite a, NginxSite b) {
		if (a == null || b == null) {
			return false;
		}
		try {
			return a.blockStart == b.blockStart
					&& a.sourceConf != null && b.sourceConf != null
					&& a.sourceConf.getCanonicalPath().equalsIgnoreCase(b.sourceConf.getCanonicalPath());
		} catch (Exception e) {
			return a.blockStart == b.blockStart
					&& a.sourceConf != null && b.sourceConf != null
					&& a.sourceConf.getAbsolutePath().equalsIgnoreCase(b.sourceConf.getAbsolutePath());
		}
	}

	private static String safeRead(File file) {
		try {
			return FileUtil.readString(file, StandardCharsets.UTF_8);
		} catch (Exception e) {
			return "";
		}
	}

	private static String displayName(NginxInstanceEntry entry) {
		return StrUtil.emptyToDefault(entry.getName(), entry.getConfPath());
	}

	/** 配置备份到 home/sitebak, 返回备份文件路径 */
	public File backup(File src) {
		if (src == null || !src.isFile()) {
			return null;
		}
		File dir = new File(home, "sitebak");
		FileUtil.mkdir(dir);
		String stamp = DateUtil.format(new Date(), "yyyyMMddHHmmss");
		File target = new File(dir, src.getName() + "." + stamp + "-https.bak");
		int index = 2;
		while (target.exists()) {
			target = new File(dir, src.getName() + "." + stamp + "-https-" + index + ".bak");
			index++;
		}
		try {
			FileUtil.copy(src, target, true);
			return target;
		} catch (Exception e) {
			log("ERROR", "配置备份失败: " + src.getAbsolutePath(), e);
			return null;
		}
	}

	public File logFile() {
		return new File(new File(home, "logs"), "site-https.log");
	}

	public synchronized void log(String level, String message, Throwable error) {
		try {
			File file = logFile();
			FileUtil.mkdir(file.getParentFile());
			StringBuilder sb = new StringBuilder();
			sb.append("[").append(LocalDateTime.now().format(LOG_FMT)).append("] [")
					.append(level).append("] ").append(StrUtil.nullToEmpty(message))
					.append(System.lineSeparator());
			if (error != null) {
				java.io.StringWriter sw = new java.io.StringWriter();
				error.printStackTrace(new java.io.PrintWriter(sw));
				sb.append(sw);
				sb.append(System.lineSeparator());
			}
			Files.write(file.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8),
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (Exception ignored) {
		}
	}
}
