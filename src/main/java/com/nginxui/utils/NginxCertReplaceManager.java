package com.nginxui.utils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.nginxui.ext.NginxInstanceEntry;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;

/**
 * 证书替换:
 *  - 扫描实例配置(递归 include)中声明的 ssl_certificate / ssl_certificate_key;
 *  - 校验上传的 PEM 证书(X.509)与私钥(未加密 PEM), 检查证书有效期;
 *  - 两种替换方式: overwrite 覆盖原文件 / repoint 写入新路径并改写配置指向;
 *  - 替换前自动备份, 替换后以 nginx -t 为门禁, 失败全量回滚;
 *  - 异常与关键动作写入 home/logs/cert-replace.log.
 *
 * 重启由界面在替换成功后二次确认, 本类只落盘并校验, 便于用户选择稍后手动生效.
 */
public class NginxCertReplaceManager {

	public static final String MODE_OVERWRITE = "overwrite";
	public static final String MODE_REPOINT = "repoint";

	public static final String ENC_AUTO = "自动识别";
	public static final String ENC_RSA = "RSA";
	public static final String ENC_ECC = "ECC";

	private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
	private static final DateTimeFormatter LOG_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private static final Pattern SERVER_NAME = Pattern.compile("^server_name\\s+([^;]+);",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern LISTEN = Pattern.compile("^listen\\s+([^;]+);",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern CERT_DIRECTIVE = Pattern.compile("^ssl_certificate\\s+([^;]+);",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern KEY_DIRECTIVE = Pattern.compile("^ssl_certificate_key\\s+([^;]+);",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern INCLUDE = Pattern.compile("^include\\s+([^;]+);",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern CERT_BLOCK = Pattern.compile(
			"-----BEGIN CERTIFICATE-----[\\s\\S]*?-----END CERTIFICATE-----");
	private static final Pattern KEY_HEADER = Pattern.compile(
			"-----BEGIN ([A-Z0-9 ]*?)PRIVATE KEY-----");
	private static final Pattern CERT_REWRITE = Pattern.compile(
			"(?im)^(\\s*ssl_certificate\\s+)([^;]+)(;)");
	private static final Pattern KEY_REWRITE = Pattern.compile(
			"(?im)^(\\s*ssl_certificate_key\\s+)([^;]+)(;)");

	private static final int MAX_INCLUDE_DEPTH = 8;

	private final File home;

	public NginxCertReplaceManager(File home) {
		this.home = home;
	}

	// ==================== 数据模型 ====================

	/** 证书信息(解析结果) */
	public static class CertInfo {
		public String subject;
		public String issuer;
		public Date notBefore;
		public Date notAfter;
		public long daysLeft;
		public boolean expired;
		public boolean notYetValid;
		public boolean expiringSoon;
		public List<String> domains = new ArrayList<String>();

		public String notAfterText() {
			return formatDay(notAfter);
		}

		public String notBeforeText() {
			return formatDay(notBefore);
		}

		public String cn() {
			return domains.isEmpty() ? "" : domains.get(0);
		}
	}

	/** 上传文件的校验结果 */
	public static class Inspect {
		public CertInfo cert;
		public String keyType;
		public String error;
		public List<String> warnings = new ArrayList<String>();

		public boolean ok() {
			return error == null;
		}
	}

	/** 实例配置中识别到的证书条目 */
	public static class CertItem {
		public File sourceConf;
		public String domain;
		/** 该 server 块声明的监听端口(已去重, 按出现顺序) */
		public List<String> ports = new ArrayList<String>();
		public String pemPath;
		public String keyPath;
		public CertInfo info;
		public boolean pemExists;
		public boolean keyExists;
		/** 证书文件内容 SHA-256, 用于识别多端口是否复用同一张证书 */
		public String pemSha256;

		public String portsText() {
			return ports.isEmpty() ? "-" : String.join(", ", ports);
		}

		/** 下拉框展示: 域名 | 证书文件 | 有效期 */
		public String displayText() {
			StringBuilder sb = new StringBuilder();
			sb.append(StrUtil.isEmpty(domain) ? "(未声明 server_name)" : domain);
			if (!ports.isEmpty()) {
				sb.append(":").append(String.join("/", ports));
			}
			sb.append("  |  ");
			sb.append(pemPath == null ? "(无证书路径)" : new File(pemPath).getName());
			if (!pemExists) {
				sb.append("  |  证书文件缺失");
			} else if (info != null) {
				sb.append("  |  至 ").append(info.notAfterText());
				if (info.expired) {
					sb.append("（已过期）");
				} else if (info.expiringSoon) {
					sb.append("（剩余 ").append(info.daysLeft).append(" 天）");
				}
			} else {
				sb.append("  |  无法解析证书");
			}
			return sb.toString();
		}
	}

	/** 跨实例证书总览条目: 实例 + 该实例配置中的一条证书声明 */
	public static class CertEntry {
		public NginxInstanceEntry instance;
		public CertItem item;
	}

	/** 证书状态码(数值越小越需要关注) */
	public static final int ST_MISSING = 0;
	public static final int ST_INVALID = 1;
	public static final int ST_EXPIRED = 2;
	public static final int ST_NOT_YET = 3;
	public static final int ST_EXPIRING = 4;
	public static final int ST_OK = 5;

	/** 状态判定: 证书文件缺失 > 无法解析 > 已过期 > 未生效 > 即将到期(≤30 天) > 正常 */
	public static int statusOf(CertItem item) {
		if (item == null) {
			return ST_INVALID;
		}
		if (StrUtil.isEmpty(item.pemPath) || !item.pemExists) {
			return ST_MISSING;
		}
		if (item.info == null) {
			return ST_INVALID;
		}
		if (item.info.expired) {
			return ST_EXPIRED;
		}
		if (item.info.notYetValid) {
			return ST_NOT_YET;
		}
		if (item.info.expiringSoon) {
			return ST_EXPIRING;
		}
		return ST_OK;
	}

	public static String statusText(int status) {
		switch (status) {
			case ST_MISSING:
				return "证书缺失";
			case ST_INVALID:
				return "无法解析";
			case ST_EXPIRED:
				return "已过期";
			case ST_NOT_YET:
				return "未生效";
			case ST_EXPIRING:
				return "即将到期";
			default:
				return "正常";
		}
	}

	/** 替换请求 */
	public static class ReplaceRequest {
		public NginxInstanceEntry entry;
		/** overwrite / repoint */
		public String mode = MODE_OVERWRITE;
		/** 原证书所在的配置文件(扫描得到) */
		public String sourceConf;
		/** 原证书路径(扫描得到) */
		public String pemPath;
		/** 原私钥路径(可能为空) */
		public String keyPath;
		/** overwrite 时忽略; repoint 时为目标路径, 空则默认 prefix/conf/ssl */
		public String targetPem;
		public String targetKey;
		public String newPemContent;
		public String newKeyContent;
		/** 自动识别 / RSA / ECC */
		public String encryption = ENC_AUTO;
	}

	/** 替换结果 */
	public static class ReplaceResult {
		public boolean success;
		public String message;
		public String output;
		public boolean rolledBack;
		public List<String> backups = new ArrayList<String>();
		public List<String> warnings = new ArrayList<String>();
		public String targetPem;
		public String targetKey;
		public boolean running;
		public CertInfo appliedCert;
		/** 成功替换后生成的可恢复清单(失败或未生成时为空) */
		public String backupSession = "";
	}

	/** 备份清单中的单个文件: 原路径 → 备份路径 */
	public static class BackupItem {
		/** cert / key / config */
		public String kind;
		public String originalPath;
		public String backupPath;

		public String kindText() {
			return "config".equals(kind) ? "配置文件" : ("key".equals(kind) ? "私钥" : "证书");
		}
	}

	/** 一次成功替换产生的可恢复备份集合 */
	public static class BackupSession {
		public File manifest;
		public long time;
		public String entryId = "";
		public String entryName = "";
		public String mode = "";
		public String confPath = "";
		public String prefixDir = "";
		public String nginxDir = "";
		public String nginxExe = "";
		public List<BackupItem> items = new ArrayList<BackupItem>();

		public String timeText() {
			return time <= 0 ? "-" : DateUtil.format(new Date(time), "yyyy-MM-dd HH:mm:ss");
		}

		public String modeText() {
			return MODE_REPOINT.equals(mode) ? "改写配置指向" : "覆盖原文件";
		}

		public String displayName() {
			return StrUtil.emptyToDefault(entryName, StrUtil.emptyToDefault(confPath, entryId));
		}
	}

	/** 备份恢复结果 */
	public static class RestoreResult {
		public boolean success;
		public boolean rolledBack;
		public boolean restarted;
		public String message = "";
		public String output = "";
		public int files;
		public List<String> backups = new ArrayList<String>();
	}

	// ==================== 证书扫描 ====================

	/** 扫描实例配置中声明的 SSL 证书(递归 include, 相对路径按 prefix/conf 解析) */
	public List<CertItem> scan(NginxInstanceEntry entry) {
		List<CertItem> items = new ArrayList<CertItem>();
		if (entry == null || StrUtil.isEmpty(entry.getConfPath())) {
			return items;
		}
		File main = new File(entry.getConfPath());
		if (!main.isFile()) {
			log("WARN", "实例配置文件不存在: " + entry.getConfPath(), null);
			return items;
		}
		File prefix = prefixDir(entry);
		File confBase = prefix == null ? main.getParentFile() : new File(prefix, "conf");
		if (confBase == null || !confBase.isDirectory()) {
			confBase = main.getParentFile();
		}
		Set<String> scanned = new LinkedHashSet<String>();
		for (File conf : collectConfFiles(main, confBase)) {
			if (scanned.add(canonical(conf))) {
				scanConfFile(conf, confBase, items);
			}
		}
		// 同一 pem+key 只保留一条
		Map<String, CertItem> unique = new LinkedHashMap<String, CertItem>();
		for (CertItem item : items) {
			String key = canonical(new File(item.pemPath)) + "|"
					+ (StrUtil.isEmpty(item.keyPath) ? "" : canonical(new File(item.keyPath)));
			if (!unique.containsKey(key)) {
				unique.put(key, item);
			}
		}
		return new ArrayList<CertItem>(unique.values());
	}

	/** 跨实例扫描: 汇总所有实例配置中的证书声明(实例顺序保持不变) */
	public List<CertEntry> scanAll(List<NginxInstanceEntry> entries) {
		List<CertEntry> out = new ArrayList<CertEntry>();
		if (entries == null) {
			return out;
		}
		for (NginxInstanceEntry entry : entries) {
			if (entry == null || StrUtil.isEmpty(entry.getConfPath())) {
				continue;
			}
			try {
				for (CertItem item : scan(entry)) {
					CertEntry ce = new CertEntry();
					ce.instance = entry;
					ce.item = item;
					out.add(ce);
				}
			} catch (Exception e) {
				// 单个实例读取失败不影响其余实例的扫描结果
				log("WARN", "扫描实例证书失败: " + entry.getName() + " - " + e.getMessage(), e);
			}
		}
		return out;
	}

	private void scanConfFile(File conf, File confBase, List<CertItem> out) {
		String text;
		try {
			text = FileUtil.readString(conf, StandardCharsets.UTF_8);
		} catch (Exception e) {
			log("WARN", "读取配置文件失败: " + conf.getAbsolutePath() + " - " + e.getMessage(), e);
			return;
		}
		String domain = "";
		List<String> ports = new ArrayList<String>();
		CertItem pending = null;
		File base = confBase != null ? confBase : conf.getParentFile();
		for (String raw : text.split("\r?\n")) {
			String line = raw.trim();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			if (line.matches("(?i)^server\\s*\\{.*")) {
				domain = "";
				ports = new ArrayList<String>();
				pending = null;
				continue;
			}
			Matcher lm = LISTEN.matcher(line);
			if (lm.find()) {
				for (String token : lm.group(1).trim().split("\\s+")) {
					String port = portOf(token);
					if (port != null && !ports.contains(port)) {
						ports.add(port);
					}
				}
				continue;
			}
			Matcher sm = SERVER_NAME.matcher(line);
			if (sm.find()) {
				String first = sm.group(1).trim().split("\\s+")[0];
				domain = unquote(first);
				continue;
			}
			Matcher cm = CERT_DIRECTIVE.matcher(line);
			if (cm.find()) {
				CertItem item = new CertItem();
				item.sourceConf = conf;
				item.domain = domain;
				item.ports.addAll(ports);
				item.pemPath = resolvePath(cm.group(1), base);
				File pem = new File(item.pemPath);
				item.pemExists = pem.isFile();
				item.info = parseCertFile(pem);
				item.pemSha256 = sha256OfFile(pem);
				out.add(item);
				pending = item;
				continue;
			}
			Matcher km = KEY_DIRECTIVE.matcher(line);
			if (km.find()) {
				String keyPath = resolvePath(km.group(1), base);
				if (pending != null && StrUtil.isEmpty(pending.keyPath)) {
					pending.keyPath = keyPath;
					pending.keyExists = new File(keyPath).isFile();
				}
				pending = null;
			}
		}
	}

	/** 递归跟踪 include: 相对路径按 prefix/conf 解析, 支持 * / ? 通配, 去重防环 */
	private List<File> collectConfFiles(File main, File confBase) {
		List<File> out = new ArrayList<File>();
		Set<String> seen = new HashSet<String>();
		Deque<File> queue = new ArrayDeque<File>();
		Deque<Integer> depths = new ArrayDeque<Integer>();
		queue.add(main);
		depths.add(0);
		while (!queue.isEmpty()) {
			File f = queue.poll();
			int depth = depths.poll();
			if (!seen.add(canonical(f))) {
				continue;
			}
			out.add(f);
			if (depth >= MAX_INCLUDE_DEPTH || !f.isFile()) {
				continue;
			}
			String text;
			try {
				text = FileUtil.readString(f, StandardCharsets.UTF_8);
			} catch (Exception e) {
				continue;
			}
			for (String raw : text.split("\r?\n")) {
				String line = raw.trim();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				Matcher m = INCLUDE.matcher(line);
				if (!m.find()) {
					continue;
				}
				for (String token : m.group(1).trim().split("\\s+")) {
					for (File inc : expandInclude(token, confBase != null ? confBase : f.getParentFile())) {
						if (inc.isFile()) {
							queue.add(inc);
							depths.add(depth + 1);
						}
					}
				}
			}
		}
		return out;
	}

	private List<File> expandInclude(String raw, File confBase) {
		List<File> out = new ArrayList<File>();
		String p = unquote(raw);
		if (StrUtil.isEmpty(p)) {
			return out;
		}
		File f = new File(p);
		if (!f.isAbsolute() && confBase != null) {
			f = new File(confBase, p);
		}
		if (!hasWildcard(p)) {
			if (f.isFile()) {
				out.add(f);
			}
			return out;
		}
		File dir = f.getParentFile();
		if (dir == null || !dir.isDirectory()) {
			return out;
		}
		Pattern glob = Pattern.compile(globToRegex(f.getName()));
		File[] children = dir.listFiles();
		if (children != null) {
			for (File child : children) {
				if (child.isFile() && glob.matcher(child.getName()).matches()) {
					out.add(child);
				}
			}
		}
		return out;
	}

	private static boolean hasWildcard(String p) {
		return p.indexOf('*') >= 0 || p.indexOf('?') >= 0 || p.indexOf('[') >= 0;
	}

	private static String globToRegex(String glob) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < glob.length(); i++) {
			char c = glob.charAt(i);
			if (c == '*') {
				sb.append(".*");
			} else if (c == '?') {
				sb.append('.');
			} else if ("\\[](){}.+^$|".indexOf(c) >= 0) {
				sb.append('\\').append(c);
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	/** 替换前预检结果: 阻断项 + 需用户确认的提示 */
	public static class Precheck {
		public boolean ok = true;
		public String message = "";
		public List<String> warnings = new ArrayList<String>();
	}

	// ==================== 校验 ====================

	/** 证书文件内容 SHA-256(统一换行与首尾空白, 同内容不同换行视为同一张证书) */
	public static String sha256OfFile(File file) {
		if (file == null || !file.isFile()) {
			return "";
		}
		try {
			return cn.hutool.crypto.SecureUtil.sha256(
					FileUtil.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n").trim());
		} catch (Exception e) {
			return "";
		}
	}

	/** 解析文件中的首张 X.509 证书, 失败返回 null */
	public static CertInfo parseCertFile(File file) {
		if (file == null || !file.isFile()) {
			return null;
		}
		try {
			return parsePemContent(FileUtil.readString(file, StandardCharsets.UTF_8));
		} catch (Exception e) {
			return null;
		}
	}

	/** 解析 PEM 文本中的首张 X.509 证书, 失败返回 null */
	public static CertInfo parsePemContent(String content) {
		String block = extractCertBlock(content);
		if (block == null) {
			return null;
		}
		try {
			CertificateFactory cf = CertificateFactory.getInstance("X.509");
			X509Certificate x = (X509Certificate) cf.generateCertificate(
					new ByteArrayInputStream(block.getBytes(StandardCharsets.UTF_8)));
			CertInfo info = new CertInfo();
			info.subject = x.getSubjectX500Principal().getName();
			info.issuer = x.getIssuerX500Principal().getName();
			info.notBefore = x.getNotBefore();
			info.notAfter = x.getNotAfter();
			long now = System.currentTimeMillis();
			info.daysLeft = (info.notAfter.getTime() - now) / (1000L * 60 * 60 * 24);
			info.expired = info.notAfter.getTime() <= now;
			info.notYetValid = info.notBefore.getTime() > now;
			info.expiringSoon = !info.expired && info.daysLeft <= 30;
			java.util.Collection<List<?>> sans = x.getSubjectAlternativeNames();
			if (sans != null) {
				for (List<?> san : sans) {
					if (san.size() >= 2 && Integer.valueOf(2).equals(san.get(0))) {
						String d = String.valueOf(san.get(1));
						if (!info.domains.contains(d)) {
							info.domains.add(d);
						}
					}
				}
			}
			String cn = firstCn(info.subject);
			if (StrUtil.isNotEmpty(cn) && !info.domains.contains(cn)) {
				info.domains.add(0, cn);
			}
			return info;
		} catch (Exception e) {
			return null;
		}
	}

	/** PEM 证书校验: 格式合法性 + 有效期 */
	public static Inspect inspectPem(String content) {
		Inspect r = new Inspect();
		if (StrUtil.isEmpty(content)) {
			r.error = "证书文件内容为空";
			return r;
		}
		CertInfo info = parsePemContent(content);
		if (info == null) {
			r.error = "无法解析为 X.509 PEM 证书（请上传 PEM 格式服务器证书，而非 DER/PFX/JKS）";
			return r;
		}
		r.cert = info;
		if (info.expired) {
			r.error = "证书已过期（有效期至 " + info.notAfterText() + "），不能用于替换";
			return r;
		}
		if (info.notYetValid) {
			r.warnings.add("证书尚未生效（生效时间 " + info.notBeforeText() + "），替换后将暂时不可用");
		}
		if (info.expiringSoon) {
			r.warnings.add("证书将在 " + info.daysLeft + " 天后过期（" + info.notAfterText() + "）");
		}
		return r;
	}

	/** PEM 私钥校验: 格式合法性 + 与所选证书类型是否匹配 */
	public static Inspect inspectKey(String content, String expectedType) {
		Inspect r = new Inspect();
		if (StrUtil.isEmpty(content)) {
			r.error = "私钥文件内容为空";
			return r;
		}
		if (content.contains("BEGIN ENCRYPTED PRIVATE KEY")) {
			r.error = "私钥已加密，nginx 无法直接读取，请提供未加密的 PEM 私钥";
			return r;
		}
		Matcher hm = KEY_HEADER.matcher(content);
		if (!hm.find()) {
			r.error = "不是有效的 PEM 私钥（缺少 BEGIN ... PRIVATE KEY 头）";
			return r;
		}
		String header = hm.group(1).trim();
		String type = null;
		if ("RSA".equalsIgnoreCase(header)) {
			type = ENC_RSA;
		} else if ("EC".equalsIgnoreCase(header)) {
			type = ENC_ECC;
		} else if (header.isEmpty()) {
			type = detectPkcs8(content);
		} else if ("OPENSSH".equalsIgnoreCase(header)) {
			r.error = "不支持 OpenSSH 格式私钥，请转换为 PEM（PKCS#1/PKCS#8）后上传";
			return r;
		} else {
			type = header.toUpperCase(Locale.ROOT);
		}
		if (!validBase64Body(content, hm.group(0))) {
			r.error = "私钥内容不是合法的 Base64/PEM 编码";
			return r;
		}
		if (type == null) {
			type = expectedType == null ? ENC_RSA : expectedType;
		}
		r.keyType = type;
		if (expectedType != null && !expectedType.equals(type)) {
			r.error = "私钥算法为 " + type + "，与所选证书类型 " + expectedType + " 不符";
			return r;
		}
		return r;
	}

	/** 识别 PKCS#8(通用 BEGIN PRIVATE KEY)私钥算法 */
	private static String detectPkcs8(String content) {
		try {
			int begin = content.indexOf("-----BEGIN PRIVATE KEY-----");
			int end = content.indexOf("-----END PRIVATE KEY-----");
			if (begin < 0 || end < 0 || end <= begin) {
				return null;
			}
			String body = content.substring(begin + "-----BEGIN PRIVATE KEY-----".length(), end)
					.replaceAll("\\s", "");
			byte[] der = Base64.getMimeDecoder().decode(body);
			PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
			for (String alg : new String[] { "RSA", "EC", "DSA", "Ed25519", "Ed448" }) {
				try {
					KeyFactory.getInstance(alg).generatePrivate(spec);
					return "EC".equals(alg) ? ENC_ECC : alg;
				} catch (Exception ignored) {
				}
			}
		} catch (Exception ignored) {
		}
		return null;
	}

	private static boolean validBase64Body(String content, String headerLine) {
		int begin = content.indexOf(headerLine);
		int endMarker = content.indexOf("-----END ", begin + headerLine.length());
		if (begin < 0 || endMarker < 0) {
			return false;
		}
		String body = content.substring(begin + headerLine.length(), endMarker).replaceAll("\\s", "");
		if (body.isEmpty()) {
			return false;
		}
		try {
			Base64.getMimeDecoder().decode(body);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private static String firstCn(String subjectDn) {
		Matcher m = Pattern.compile("CN=([^,]+)").matcher(StrUtil.nullToEmpty(subjectDn));
		return m.find() ? m.group(1).trim() : null;
	}

	/**
	 * 替换前置预检:
	 *  - 证书所在站点未启用 HTTPS → 阻断(应先走「站点管理 → 启用 HTTPS」);
	 *  - 证书声明中的端口被其它进程占用 → 阻断; 实例运行中且无法定位站点时降级为提示;
	 *  - 同实例 HTTPS 站点共享端口 → 提示按 SNI 共享.
	 */
	public Precheck precheck(CertItem item, NginxInstanceEntry entry) {
		Precheck check = new Precheck();
		if (item == null || entry == null) {
			check.ok = false;
			check.message = "未选择要替换的证书或实例。";
			return check;
		}
		List<NginxSite> sites = new NginxSiteScanner().scan(entry);
		NginxSite target = findTargetSite(sites, item);
		if (target != null && !target.hasSslListen()) {
			check.ok = false;
			check.message = "站点「" + target.displayDomain()
					+ "」已声明证书，但未启用 HTTPS。\n请先在「站点管理」中启用 HTTPS，再替换证书。";
			return check;
		}
		boolean running = NginxControlService.isInstanceRunning(entry);
		NginxHttpsManager portManager = new NginxHttpsManager(home);
		for (String portText : item.ports) {
			int port;
			try {
				port = Integer.parseInt(StrUtil.nullToEmpty(portText).trim());
			} catch (NumberFormatException e) {
				continue;
			}
			NginxHttpsManager.PortCheck pc = portManager.checkPort(entry, sites, target, port);
			if (!pc.ok) {
				if (running && target == null) {
					check.warnings.add("端口 " + port + " 当前被占用；实例正在运行，可能为自身占用，请确认后继续。");
					continue;
				}
				check.ok = false;
				check.message = "端口 " + port + "：" + pc.message + "\n请先释放该端口或调整站点监听后再替换。";
				return check;
			}
			if (pc.shared) {
				check.warnings.add("端口 " + port + "：" + pc.message);
			}
		}
		return check;
	}

	/** 按来源配置 + 域名定位证书所属站点, 定位失败返回 null */
	private NginxSite findTargetSite(List<NginxSite> sites, CertItem item) {
		if (sites == null || item == null || item.sourceConf == null) {
			return null;
		}
		String conf = canonical(item.sourceConf);
		for (NginxSite site : sites) {
			if (site.sourceConf == null || !conf.equals(canonical(site.sourceConf))) {
				continue;
			}
			if (StrUtil.isEmpty(item.domain) || site.serverNames.contains(item.domain)
					|| item.domain.equals(site.domain())) {
				return site;
			}
		}
		return null;
	}

	// ==================== 替换 ====================

	/**
	 * 执行替换: 备份 → 落盘/改写配置 → nginx -t 门禁 → 失败全量回滚.
	 * 成功后不自动重启, 由调用方二次确认是否立即重启.
	 */
	public ReplaceResult replace(ReplaceRequest req) {
		ReplaceResult result = new ReplaceResult();
		if (req == null || req.entry == null) {
			result.message = "未指定实例";
			return result;
		}
		String mode = StrUtil.emptyToDefault(req.mode, MODE_OVERWRITE);
		if (!MODE_OVERWRITE.equals(mode) && !MODE_REPOINT.equals(mode)) {
			result.message = "不支持的替换方式: " + mode;
			return result;
		}

		// ---- 1. 新内容校验(在改动任何文件之前完成)
		Inspect pemCheck = inspectPem(req.newPemContent);
		if (!pemCheck.ok()) {
			result.message = pemCheck.error;
			log("WARN", "证书替换被拒绝: " + pemCheck.error, null);
			return result;
		}
		result.warnings.addAll(pemCheck.warnings);

		boolean hasKey = StrUtil.isNotEmpty(req.newKeyContent);
		Inspect keyCheck = hasKey ? inspectKey(req.newKeyContent,
				ENC_AUTO.equals(req.encryption) || StrUtil.isEmpty(req.encryption) ? null : req.encryption)
				: null;
		if (keyCheck != null && !keyCheck.ok()) {
			result.message = keyCheck.error;
			log("WARN", "证书替换被拒绝: " + keyCheck.error, null);
			return result;
		}
		if (StrUtil.isEmpty(req.pemPath)) {
			result.message = "未识别到原证书路径，请重新扫描后选择要替换的证书";
			return result;
		}
		File pemTarget = new File(req.pemPath);
		File keyTarget = StrUtil.isEmpty(req.keyPath) ? null : new File(req.keyPath);

		if (MODE_OVERWRITE.equals(mode)) {
			if (!pemTarget.isFile()) {
				result.message = "原证书文件不存在，无法覆盖: " + req.pemPath;
				return result;
			}
			if (hasKey && keyTarget == null) {
				result.message = "未识别到原私钥路径，请改用「写入新路径并改写配置」";
				return result;
			}
			if (hasKey && keyTarget != null && !keyTarget.isFile()) {
				result.message = "原私钥文件不存在，无法覆盖: " + req.keyPath;
				return result;
			}
		} else {
			if (StrUtil.isEmpty(req.sourceConf) || !new File(req.sourceConf).isFile()) {
				result.message = "来源配置文件不存在，请重新扫描: "
						+ StrUtil.emptyToDefault(req.sourceConf, "(未指定)");
				return result;
			}
			if (!hasKey) {
				result.message = "改写配置指向时必须同时提供新私钥";
				return result;
			}
		}

		Map<String, String> originals = new LinkedHashMap<String, String>();
		List<File> created = new ArrayList<File>();
		Set<String> written = new LinkedHashSet<String>();
		List<BackupItem> backupItems = new ArrayList<BackupItem>();
		File sourceConf = StrUtil.isEmpty(req.sourceConf) ? null : new File(req.sourceConf);

		try {
			if (MODE_OVERWRITE.equals(mode)) {
				// 覆盖: 原文件内容入回滚表, 备份后写入
				backupAndRemember(pemTarget, originals, result.backups, backupItems, "cert");
				writeFile(pemTarget, req.newPemContent);
				written.add(pemTarget.getAbsolutePath());
				result.targetPem = pemTarget.getAbsolutePath();
				if (hasKey) {
					backupAndRemember(keyTarget, originals, result.backups, backupItems, "key");
					writeFile(keyTarget, req.newKeyContent);
					written.add(keyTarget.getAbsolutePath());
					result.targetKey = keyTarget.getAbsolutePath();
				} else if (keyTarget != null) {
					result.targetKey = keyTarget.getAbsolutePath();
				}
			} else {
				// repoint: 写入新路径(默认 prefix/conf/ssl)并改写配置指向
				File prefix = prefixDir(req.entry);
				File defaultDir = prefix != null ? new File(prefix, "conf/ssl")
						: new File(new File(req.entry.getConfPath()).getParentFile(), "ssl");
				String stamp = DateUtil.format(new Date(), "yyyyMMddHHmmss");
				File destPem = StrUtil.isEmpty(req.targetPem)
						? new File(defaultDir, "cert-" + stamp + ".pem")
						: new File(req.targetPem);
				File destKey = StrUtil.isEmpty(req.targetKey)
						? new File(defaultDir, "cert-" + stamp + ".key")
						: new File(req.targetKey);
				if (samePath(destPem, pemTarget)) {
					// 目标就是原文件: 等价覆盖, 仍需备份
					backupAndRemember(destPem, originals, result.backups, backupItems, "cert");
				} else if (destPem.isFile()) {
					backupAndRemember(destPem, originals, result.backups, backupItems, "cert");
				} else {
					created.add(destPem);
				}
				if (keyTarget != null && samePath(destKey, keyTarget)) {
					backupAndRemember(destKey, originals, result.backups, backupItems, "key");
				} else if (destKey.isFile()) {
					backupAndRemember(destKey, originals, result.backups, backupItems, "key");
				} else {
					created.add(destKey);
				}
				mkdirs(destPem.getParentFile());
				mkdirs(destKey.getParentFile());
				writeFile(destPem, req.newPemContent);
				written.add(destPem.getAbsolutePath());
				writeFile(destKey, req.newKeyContent);
				written.add(destKey.getAbsolutePath());
				result.targetPem = destPem.getAbsolutePath();
				result.targetKey = destKey.getAbsolutePath();

				String before = FileUtil.readString(sourceConf, StandardCharsets.UTF_8);
				File rewriteBase = prefix != null ? new File(prefix, "conf") : sourceConf.getParentFile();
				String after = rewriteConfPaths(before, rewriteBase,
						req.pemPath, req.keyPath, configPath(destPem), configPath(destKey));
				if (after == null) {
					rollback(originals, created, written);
					result.rolledBack = !written.isEmpty();
					result.message = "未能定位配置文件中的证书指令，已撤销写入的新文件";
					log("ERROR", result.message, null);
					return result;
				}
				backupAndRemember(sourceConf, originals, result.backups, backupItems, "config");
				writeFile(sourceConf, after);
				written.add(sourceConf.getAbsolutePath());
			}

			// ---- 2. nginx -t 门禁
			NginxDashboardUtils.ConfigTestResult test =
					NginxDashboardUtils.testInstanceConfig(req.entry);
			result.output = test.output;
			if (!test.success) {
				rollback(originals, created, written);
				result.rolledBack = !written.isEmpty();
				result.message = "证书替换后配置检测未通过，已回滚："
						+ StrUtil.emptyToDefault(test.message, test.output);
				log("ERROR", result.message, null);
				return result;
			}

			// ---- 3. 结果
			result.success = true;
			result.running = NginxDashboardUtils.isInstanceRunning(req.entry);
			result.appliedCert = parseCertFile(new File(result.targetPem));
			result.message = "证书已替换并通过配置检测";
			File session = saveBackupSession(req.entry, mode, backupItems);
			if (session != null) {
				result.backupSession = session.getAbsolutePath();
			}
			log("INFO", "证书替换成功: 实例=" + displayName(req.entry)
					+ ", 方式=" + mode
					+ ", 证书=" + result.targetPem
					+ ", 私钥=" + StrUtil.nullToEmpty(result.targetKey)
					+ ", 备份=" + result.backups
					+ (session == null ? "" : ", 可恢复清单=" + session.getAbsolutePath()), null);
			return result;
		} catch (Exception e) {
			rollback(originals, created, written);
			result.rolledBack = !written.isEmpty();
			result.message = "证书替换失败"
					+ (result.rolledBack ? "，已回滚" : "") + "：" + e.getMessage();
			log("ERROR", "证书替换失败: 实例=" + displayName(req.entry), e);
			return result;
		}
	}

	private void backupAndRemember(File src, Map<String, String> originals, List<String> backups,
			List<BackupItem> items, String kind) {
		if (src == null || !src.isFile()) {
			return;
		}
		String content = FileUtil.readString(src, StandardCharsets.UTF_8);
		originals.put(src.getAbsolutePath(), content);
		String backupPath = backup(src, "certbak");
		if (backupPath != null) {
			backups.add(backupPath);
			BackupItem item = new BackupItem();
			item.kind = kind;
			item.originalPath = src.getAbsolutePath();
			item.backupPath = backupPath;
			items.add(item);
		}
	}

	private void writeFile(File file, String content) {
		mkdirs(file.getParentFile());
		FileUtil.writeString(StrUtil.nullToEmpty(content), file, StandardCharsets.UTF_8);
	}

	private static void mkdirs(File dir) {
		if (dir != null && !dir.isDirectory()) {
			FileUtil.mkdir(dir);
		}
	}

	/** 回滚: 删除新建文件并恢复已写入文件的变更前内容 */
	private void rollback(Map<String, String> originals, List<File> created, Set<String> written) {
		for (File f : created) {
			if (!f.exists()) {
				continue;
			}
			try {
				FileUtil.del(f);
			} catch (Exception e) {
				log("ERROR", "回滚删除文件失败: " + f.getAbsolutePath(), e);
			}
		}
		for (Map.Entry<String, String> e : originals.entrySet()) {
			if (!written.contains(e.getKey())) {
				continue;
			}
			try {
				FileUtil.writeString(e.getValue(), new File(e.getKey()), StandardCharsets.UTF_8);
			} catch (Exception ex) {
				log("ERROR", "回滚恢复文件失败: " + e.getKey(), ex);
			}
		}
		if (!written.isEmpty() || !created.isEmpty()) {
			log("WARN", "已回滚本次证书替换的 " + (originals.size() + created.size()) + " 处文件变更", null);
		}
	}

	/**
	 * 改写配置中值解析为原 pem/key 路径的指令(保留引号风格).
	 * 返回改写后的文本; 未定位到指令时返回 null.
	 */
	private String rewriteConfPaths(String text, File base, String oldPem, String oldKey,
			String newPem, String newKey) {
		int certHits = 0;
		int keyHits = 0;
		Matcher cm = CERT_REWRITE.matcher(text);
		// Matcher.appendReplacement/appendTail 只接受 StringBuffer
		StringBuffer sb = new StringBuffer();
		while (cm.find()) {
			File resolved = resolveFile(unquote(cm.group(2)), base);
			if (resolved != null && samePath(resolved, new File(oldPem))) {
				certHits++;
				cm.appendReplacement(sb, Matcher.quoteReplacement(cm.group(1)
						+ quoteLike(cm.group(2), newPem) + cm.group(3)));
			} else {
				cm.appendReplacement(sb, Matcher.quoteReplacement(cm.group()));
			}
		}
		cm.appendTail(sb);
		String pass1 = sb.toString();

		Matcher km = KEY_REWRITE.matcher(pass1);
		sb = new StringBuffer();
		while (km.find()) {
			File resolved = resolveFile(unquote(km.group(2)), base);
			if (resolved != null && StrUtil.isNotEmpty(oldKey) && samePath(resolved, new File(oldKey))) {
				keyHits++;
				km.appendReplacement(sb, Matcher.quoteReplacement(km.group(1)
						+ quoteLike(km.group(2), newKey) + km.group(3)));
			} else {
				km.appendReplacement(sb, Matcher.quoteReplacement(km.group()));
			}
		}
		km.appendTail(sb);
		return certHits + keyHits > 0 ? sb.toString() : null;
	}

	private static String quoteLike(String original, String value) {
		String t = original.trim();
		if (t.startsWith("\"")) {
			return "\"" + value + "\"";
		}
		if (t.startsWith("'")) {
			return "'" + value + "'";
		}
		return value;
	}

	// ==================== 备份恢复 ====================

	/** 成功替换后写入可恢复清单: 记录每个备份文件的原路径, 供手动恢复 */
	private File saveBackupSession(NginxInstanceEntry entry, String mode, List<BackupItem> items) {
		if (entry == null || items.isEmpty()) {
			return null;
		}
		File dir = manifestDir();
		mkdirs(dir);
		String stamp = DateUtil.format(new Date(), "yyyyMMddHHmmssSSS");
		File file = new File(dir, stamp + ".session");
		int index = 2;
		while (file.exists()) {
			file = new File(dir, stamp + "-" + index + ".session");
			index++;
		}
		Properties props = new Properties();
		props.setProperty("version", "1");
		props.setProperty("time", String.valueOf(System.currentTimeMillis()));
		props.setProperty("entryId", StrUtil.nullToEmpty(entry.getId()));
		props.setProperty("entryName", StrUtil.nullToEmpty(entry.getName()));
		props.setProperty("mode", StrUtil.nullToEmpty(mode));
		props.setProperty("confPath", StrUtil.nullToEmpty(entry.getConfPath()));
		props.setProperty("prefixDir", StrUtil.nullToEmpty(entry.getPrefixDir()));
		props.setProperty("nginxDir", StrUtil.nullToEmpty(entry.getNginxDir()));
		props.setProperty("nginxExe", StrUtil.nullToEmpty(entry.getNginxExe()));
		props.setProperty("itemCount", String.valueOf(items.size()));
		for (int i = 0; i < items.size(); i++) {
			BackupItem item = items.get(i);
			props.setProperty("item" + i + ".kind", StrUtil.nullToEmpty(item.kind));
			props.setProperty("item" + i + ".original", StrUtil.nullToEmpty(item.originalPath));
			props.setProperty("item" + i + ".backup", StrUtil.nullToEmpty(item.backupPath));
		}
		try (OutputStreamWriter writer = new OutputStreamWriter(
				new FileOutputStream(file), StandardCharsets.UTF_8)) {
			props.store(writer, "nginxUI certificate backup session");
			return file;
		} catch (Exception e) {
			log("ERROR", "写入证书备份清单失败: " + file.getAbsolutePath(), e);
			return null;
		}
	}

	/** 列出所有可恢复的替换备份(按时间倒序) */
	public List<BackupSession> listBackupSessions() {
		List<BackupSession> sessions = new ArrayList<BackupSession>();
		File dir = manifestDir();
		File[] files = dir.isDirectory() ? dir.listFiles() : null;
		if (files != null) {
			for (File file : files) {
				if (!file.isFile() || !file.getName().endsWith(".session")) {
					continue;
				}
				BackupSession session = readBackupSession(file);
				if (session != null && !session.items.isEmpty()) {
					sessions.add(session);
				}
			}
		}
		sessions.sort((a, b) -> Long.compare(b.time, a.time));
		return sessions;
	}

	private BackupSession readBackupSession(File file) {
		Properties props = new Properties();
		try (InputStreamReader reader = new InputStreamReader(
				new FileInputStream(file), StandardCharsets.UTF_8)) {
			props.load(reader);
		} catch (Exception e) {
			log("WARN", "读取证书备份清单失败: " + file.getAbsolutePath(), e);
			return null;
		}
		BackupSession session = new BackupSession();
		session.manifest = file;
		try {
			session.time = Long.parseLong(StrUtil.emptyToDefault(props.getProperty("time"), "0"));
		} catch (Exception ignored) {
			session.time = 0L;
		}
		session.entryId = StrUtil.nullToEmpty(props.getProperty("entryId"));
		session.entryName = StrUtil.nullToEmpty(props.getProperty("entryName"));
		session.mode = StrUtil.nullToEmpty(props.getProperty("mode"));
		session.confPath = StrUtil.nullToEmpty(props.getProperty("confPath"));
		session.prefixDir = StrUtil.nullToEmpty(props.getProperty("prefixDir"));
		session.nginxDir = StrUtil.nullToEmpty(props.getProperty("nginxDir"));
		session.nginxExe = StrUtil.nullToEmpty(props.getProperty("nginxExe"));
		int count = 0;
		try {
			count = Integer.parseInt(StrUtil.emptyToDefault(props.getProperty("itemCount"), "0"));
		} catch (Exception ignored) {
			count = 0;
		}
		for (int i = 0; i < count; i++) {
			String original = props.getProperty("item" + i + ".original");
			String backup = props.getProperty("item" + i + ".backup");
			if (StrUtil.isEmpty(original) || StrUtil.isEmpty(backup)) {
				continue;
			}
			BackupItem item = new BackupItem();
			item.kind = StrUtil.emptyToDefault(props.getProperty("item" + i + ".kind"), "cert");
			item.originalPath = original;
			item.backupPath = backup;
			session.items.add(item);
		}
		return session;
	}

	/**
	 * 恢复一次替换备份: 覆盖当前证书/私钥/配置 → nginx -t 门禁 → 运行中则重启.
	 * 覆盖前把当前文件另存到 certbak, 失败时恢复为恢复前状态.
	 */
	public RestoreResult restore(BackupSession session, NginxInstanceEntry currentEntry) {
		RestoreResult result = new RestoreResult();
		if (session == null || session.items.isEmpty()) {
			result.message = "所选备份没有可恢复的文件";
			return result;
		}
		for (BackupItem item : session.items) {
			if (!new File(item.backupPath).isFile()) {
				result.message = "备份文件已不存在，无法恢复: " + item.backupPath;
				return result;
			}
		}
		NginxInstanceEntry entry = entryForSession(session, currentEntry);
		if (entry == null || StrUtil.isEmpty(entry.getNginxExe())) {
			result.message = "缺少实例或 nginx 可执行文件，无法校验恢复后的配置";
			return result;
		}

		Map<String, String> restorePoint = new LinkedHashMap<String, String>();
		List<String> written = new ArrayList<String>();
		boolean running = NginxDashboardUtils.isInstanceRunning(entry);
		try {
			for (BackupItem item : session.items) {
				File original = new File(item.originalPath);
				File backupFile = new File(item.backupPath);
				String current = original.isFile()
						? FileUtil.readString(original, StandardCharsets.UTF_8) : null;
				restorePoint.put(original.getAbsolutePath(), current);
				if (current != null) {
					String safety = backup(original, "certbak");
					if (safety != null) {
						result.backups.add(safety);
					}
				}
				mkdirs(original.getParentFile());
				writeFile(original, FileUtil.readString(backupFile, StandardCharsets.UTF_8));
				written.add(original.getAbsolutePath());
			}

			NginxDashboardUtils.ConfigTestResult test =
					NginxDashboardUtils.testInstanceConfig(entry);
			result.output = test.output;
			if (!test.success) {
				rollbackRestore(restorePoint, written);
				result.rolledBack = true;
				result.message = "恢复后的配置检测未通过，已回到恢复前状态："
						+ StrUtil.emptyToDefault(test.message, test.output);
				log("ERROR", result.message, null);
				return result;
			}

			if (running) {
				NginxDashboardUtils.ControlResult cr =
						NginxDashboardUtils.controlInstance(entry, "restart");
				if (!cr.success) {
					rollbackRestore(restorePoint, written);
					restartQuietly(entry);
					result.rolledBack = true;
					result.message = "配置检测通过，但实例重启失败，已回到恢复前状态："
							+ StrUtil.nullToEmpty(cr.message);
					log("ERROR", result.message, null);
					return result;
				}
				result.restarted = true;
			}

			result.files = written.size();
			result.success = true;
			result.message = "已恢复 " + result.files + " 个文件并通过配置检测"
					+ (running ? "，实例已重启生效" : "；实例当前未运行，启动后生效");
			log("INFO", "证书备份恢复成功: 实例=" + displayName(entry)
					+ ", 清单=" + (session.manifest == null ? "" : session.manifest.getAbsolutePath())
					+ ", 文件=" + written
					+ ", 恢复前备份=" + result.backups, null);
			return result;
		} catch (Exception e) {
			rollbackRestore(restorePoint, written);
			if (running) {
				restartQuietly(entry);
			}
			result.rolledBack = !written.isEmpty();
			result.message = "恢复证书备份失败"
					+ (result.rolledBack ? "，已回到恢复前状态" : "") + "：" + e.getMessage();
			log("ERROR", "恢复证书备份失败: " + result.message, e);
			return result;
		}
	}

	private void rollbackRestore(Map<String, String> restorePoint, List<String> written) {
		for (String path : written) {
			String content = restorePoint.get(path);
			File file = new File(path);
			try {
				if (content == null) {
					if (file.isFile()) {
						FileUtil.del(file);
					}
				} else {
					writeFile(file, content);
				}
			} catch (Exception e) {
				log("ERROR", "恢复前状态回写失败: " + path, e);
			}
		}
		if (!written.isEmpty()) {
			log("WARN", "已回到证书备份恢复前的 " + written.size() + " 处文件状态", null);
		}
	}

	private void restartQuietly(NginxInstanceEntry entry) {
		try {
			NginxDashboardUtils.controlInstance(entry, "restart");
		} catch (Exception e) {
			log("WARN", "恢复后重启实例失败: " + displayName(entry), e);
		}
	}

	private static NginxInstanceEntry entryForSession(BackupSession session, NginxInstanceEntry current) {
		if (current != null) {
			boolean sameId = StrUtil.isNotEmpty(session.entryId)
					&& StrUtil.equals(session.entryId, current.getId());
			boolean sameConf = StrUtil.isNotEmpty(session.confPath)
					&& StrUtil.equalsIgnoreCase(session.confPath, current.getConfPath());
			if (sameId || sameConf) {
				return current;
			}
		}
		NginxInstanceEntry entry = new NginxInstanceEntry();
		entry.setId(session.entryId);
		entry.setName(session.entryName);
		entry.setConfPath(session.confPath);
		entry.setPrefixDir(session.prefixDir);
		entry.setNginxDir(session.nginxDir);
		entry.setNginxExe(session.nginxExe);
		return entry;
	}

	private File manifestDir() {
		return new File(new File(home, "certbak"), "manifests");
	}

	// ==================== 备份 / 日志 ====================

	/** 备份到 home/&lt;subdir&gt;/&lt;name&gt;.&lt;yyyyMMddHHmmss&gt;-replace, 返回绝对路径 */
	public String backup(File src, String subdir) {
		if (src == null || !src.isFile()) {
			return null;
		}
		File dir = new File(home, subdir);
		mkdirs(dir);
		String ts = DateUtil.format(new Date(), "yyyyMMddHHmmss");
		File bak = new File(dir, src.getName() + "." + ts + "-replace");
		int i = 2;
		while (bak.exists()) {
			bak = new File(dir, src.getName() + "." + ts + "-replace-" + i);
			i++;
		}
		FileUtil.copy(src, bak, true);
		return bak.getAbsolutePath();
	}

	/** 证书替换日志(异常与关键动作) */
	public File logFile() {
		File dir = new File(home, "logs");
		return new File(dir, "cert-replace.log");
	}

	public synchronized void log(String level, String message, Throwable error) {
		try {
			File file = logFile();
			mkdirs(file.getParentFile());
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

	// ==================== 工具 ====================

	private static File prefixDir(NginxInstanceEntry entry) {
		String p = StrUtil.emptyToDefault(entry.getPrefixDir(), entry.getNginxDir());
		if (StrUtil.isNotEmpty(p)) {
			return new File(p);
		}
		File conf = new File(StrUtil.nullToEmpty(entry.getConfPath()));
		File parent = conf.getParentFile();
		return parent == null ? null : parent.getParentFile();
	}

	private static String displayName(NginxInstanceEntry entry) {
		return StrUtil.emptyToDefault(entry.getName(), entry.getConfPath());
	}

	private static String resolvePath(String raw, File base) {
		File f = resolveFile(unquote(raw), base);
		return f == null ? "" : f.getAbsolutePath();
	}

	private static File resolveFile(String raw, File base) {
		if (StrUtil.isEmpty(raw)) {
			return null;
		}
		File f = new File(raw);
		if (!f.isAbsolute() && base != null) {
			f = new File(base, raw);
		}
		return f;
	}

	private static String unquote(String raw) {
		String s = StrUtil.nullToEmpty(raw).trim();
		if (s.length() >= 2) {
			char first = s.charAt(0);
			char last = s.charAt(s.length() - 1);
			if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
				s = s.substring(1, s.length() - 1).trim();
			}
		}
		return s;
	}

	/** 从 listen 参数中提取端口: 8088 / 127.0.0.1:8088 / [::]:8088 / *:443, 非端口返回 null */
	private static String portOf(String raw) {
		String t = unquote(raw);
		if (StrUtil.isEmpty(t)) {
			return null;
		}
		if (t.startsWith("[")) {
			int idx = t.indexOf("]:");
			if (idx < 0) {
				return null;
			}
			t = t.substring(idx + 2);
		} else if (t.contains(":")) {
			t = t.substring(t.lastIndexOf(':') + 1);
		}
		if (!t.matches("\\d{1,5}")) {
			return null;
		}
		int port = Integer.parseInt(t);
		return port >= 1 && port <= 65535 ? String.valueOf(port) : null;
	}

	/** 配置中写入的路径统一使用 /, 避免 Windows 反斜杠转义问题 */
	private static String configPath(File file) {
		return file.getAbsolutePath().replace("\\", "/");
	}

	private static boolean samePath(File a, File b) {
		if (a == null || b == null) {
			return false;
		}
		try {
			return a.getCanonicalPath().equalsIgnoreCase(b.getCanonicalPath());
		} catch (Exception e) {
			return a.getAbsolutePath().equalsIgnoreCase(b.getAbsolutePath());
		}
	}

	private static String canonical(File f) {
		try {
			return f.getCanonicalPath().toLowerCase(Locale.ROOT);
		} catch (Exception e) {
			return f.getAbsolutePath().toLowerCase(Locale.ROOT);
		}
	}

	private static String extractCertBlock(String text) {
		if (StrUtil.isEmpty(text)) {
			return null;
		}
		Matcher m = CERT_BLOCK.matcher(text);
		return m.find() ? m.group() : null;
	}

	private static String formatDay(Date date) {
		if (date == null) {
			return "-";
		}
		return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault()).format(DAY_FMT);
	}
}
