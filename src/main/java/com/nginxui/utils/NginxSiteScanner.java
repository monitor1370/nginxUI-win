package com.nginxui.utils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.nginxui.ext.NginxInstanceEntry;
import com.nginxui.utils.NginxCertReplaceManager.CertItem;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;

/**
 * 站点扫描: 解析实例配置(递归 include)中的 server 块, 得到域名、监听端口、
 * 是否 SSL、证书/私钥路径与配置来源, 并记录 server 块在文件中的位置, 供 HTTPS 启用时定点改写.
 *
 * 只做静态解析, 不触发 nginx 进程或网络探测.
 */
public class NginxSiteScanner {

	private static final Pattern SERVER_START = Pattern.compile("(^|[\\s;{}])server\\s*\\{",
			Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
	private static final Pattern LISTEN_DIRECTIVE = Pattern.compile("(?m)^[\\t ]*listen[\\t ]+([^;]+);",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern SERVER_NAME_DIRECTIVE = Pattern.compile(
			"(?m)^[\\t ]*server_name[\\t ]+([^;]+);", Pattern.CASE_INSENSITIVE);
	private static final Pattern CERT_DIRECTIVE = Pattern.compile(
			"(?m)^[\\t ]*ssl_certificate[\\t ]+([^;]+);", Pattern.CASE_INSENSITIVE);
	private static final Pattern KEY_DIRECTIVE = Pattern.compile(
			"(?m)^[\\t ]*ssl_certificate_key[\\t ]+([^;]+);", Pattern.CASE_INSENSITIVE);
	private static final Pattern INCLUDE = Pattern.compile("^include\\s+([^;]+);",
			Pattern.CASE_INSENSITIVE);

	private static final int MAX_INCLUDE_DEPTH = 8;

	/** 扫描实例的全部站点; 配置不存在或读取失败时返回空列表 */
	public List<NginxSite> scan(NginxInstanceEntry entry) {
		List<NginxSite> sites = new ArrayList<NginxSite>();
		if (entry == null || StrUtil.isEmpty(entry.getConfPath())) {
			return sites;
		}
		File main = new File(entry.getConfPath());
		if (!main.isFile()) {
			return sites;
		}
		File prefix = prefixDir(entry);
		File confBase = prefix == null ? main.getParentFile() : new File(prefix, "conf");
		if (confBase == null || !confBase.isDirectory()) {
			confBase = main.getParentFile();
		}
		Set<String> scanned = new LinkedHashSet<String>();
		for (File conf : collectConfFiles(main, confBase)) {
			if (scanned.add(canonical(conf))) {
				scanFile(entry, conf, confBase, sites);
			}
		}
		return sites;
	}

	private void scanFile(NginxInstanceEntry entry, File conf, File confBase, List<NginxSite> out) {
		String text;
		try {
			text = FileUtil.readString(conf, StandardCharsets.UTF_8);
		} catch (Exception e) {
			return;
		}
		String masked = maskComments(text);
		Matcher m = SERVER_START.matcher(masked);
		int cursor = 0;
		while (m.find(cursor)) {
			int keyword = m.start() + (m.group(1) == null ? 0 : m.group(1).length());
			int open = masked.indexOf('{', keyword);
			if (open < 0) {
				break;
			}
			int close = NginxConfigService.matchBrace(text, open);
			if (close < 0) {
				break;
			}
			NginxSite site = parseSite(entry, conf, confBase, text, keyword, open, close);
			if (site != null) {
				out.add(site);
			}
			cursor = close + 1;
		}
	}

	private NginxSite parseSite(NginxInstanceEntry entry, File conf, File confBase, String text,
			int keyword, int open, int close) {
		String body = text.substring(open + 1, close);
		NginxSite site = new NginxSite();
		site.instance = entry;
		site.sourceConf = conf;
		site.blockStart = keyword;
		site.blockOpen = open;
		site.blockClose = close;

		Matcher sm = SERVER_NAME_DIRECTIVE.matcher(body);
		while (sm.find()) {
			for (String token : sm.group(1).trim().split("\\s+")) {
				String name = unquote(token);
				if (StrUtil.isNotEmpty(name) && !site.serverNames.contains(name)) {
					site.serverNames.add(name);
				}
			}
		}

		Matcher lm = LISTEN_DIRECTIVE.matcher(body);
		while (lm.find()) {
			NginxSite.Listen listen = parseListen(lm.group(1));
			if (listen != null && !containsListen(site.listens, listen)) {
				site.listens.add(listen);
			}
		}

		String pemPath = firstDirective(body, CERT_DIRECTIVE, confBase);
		String keyPath = firstDirective(body, KEY_DIRECTIVE, confBase);
		if (StrUtil.isNotEmpty(pemPath)) {
			CertItem item = new CertItem();
			item.sourceConf = conf;
			item.domain = site.domain();
			item.ports.addAll(site.ports());
			item.pemPath = pemPath;
			item.keyPath = keyPath;
			File pem = new File(pemPath);
			item.pemExists = pem.isFile();
			item.keyExists = StrUtil.isNotEmpty(keyPath) && new File(keyPath).isFile();
			item.info = NginxCertReplaceManager.parseCertFile(pem);
			item.pemSha256 = NginxCertReplaceManager.sha256OfFile(pem);
			site.cert = item;
		}
		return site;
	}

	private static boolean containsListen(List<NginxSite.Listen> listens, NginxSite.Listen candidate) {
		for (NginxSite.Listen listen : listens) {
			if (listen.address.equals(candidate.address) && listen.port.equals(candidate.port)
					&& listen.ssl == candidate.ssl) {
				return true;
			}
		}
		return false;
	}

	private static String firstDirective(String body, Pattern pattern, File confBase) {
		Matcher m = pattern.matcher(body);
		if (!m.find()) {
			return "";
		}
		return resolvePath(m.group(1), confBase);
	}

	/** 解析 listen 参数: 80 / 127.0.0.1:8088 / [::]:443 ssl; 非端口返回 null */
	private static NginxSite.Listen parseListen(String raw) {
		String args = StrUtil.nullToEmpty(raw).replaceAll("\\s+", " ").trim();
		if (StrUtil.isEmpty(args)) {
			return null;
		}
		String[] tokens = args.split(" ");
		String spec = unquote(tokens[0]);
		if (StrUtil.isEmpty(spec) || spec.toLowerCase(Locale.ROOT).startsWith("unix:")) {
			return null;
		}
		String address;
		String port;
		if (spec.startsWith("[")) {
			int rb = spec.indexOf(']');
			String host = rb > 0 ? spec.substring(1, rb) : "";
			String rest = rb >= 0 && rb + 1 < spec.length() && spec.charAt(rb + 1) == ':'
					? spec.substring(rb + 2)
					: "80";
			address = (host.isEmpty() || "::".equals(host)) ? "*" : "[" + host + "]";
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
		if (!port.matches("\\d{1,5}")) {
			return null;
		}
		int value = Integer.parseInt(port);
		if (value < 1 || value > 65535) {
			return null;
		}
		NginxSite.Listen listen = new NginxSite.Listen();
		listen.args = args;
		listen.address = address;
		listen.port = String.valueOf(value);
		for (String token : tokens) {
			if ("ssl".equalsIgnoreCase(token)) {
				listen.ssl = true;
				break;
			}
		}
		return listen;
	}

	/** 注释替换为等长空格, 保持下标可用于原文定位 */
	private static String maskComments(String text) {
		StringBuilder sb = new StringBuilder(text.length());
		boolean inComment = false;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '\n' || c == '\r') {
				inComment = false;
				sb.append(c);
				continue;
			}
			if (inComment) {
				sb.append(' ');
				continue;
			}
			if (c == '#') {
				inComment = true;
				sb.append(' ');
				continue;
			}
			sb.append(c);
		}
		return sb.toString();
	}

	/** 递归跟踪 include: 相对路径按 prefix/conf 解析, 支持通配, 去重防环 */
	private List<File> collectConfFiles(File main, File confBase) {
		List<File> out = new ArrayList<File>();
		Set<String> seen = new HashSet<String>();
		Deque<File> queue = new ArrayDeque<File>();
		Deque<Integer> depths = new ArrayDeque<Integer>();
		queue.add(main);
		depths.add(0);
		while (!queue.isEmpty()) {
			File file = queue.poll();
			int depth = depths.poll();
			if (!seen.add(canonical(file))) {
				continue;
			}
			out.add(file);
			if (depth >= MAX_INCLUDE_DEPTH || !file.isFile()) {
				continue;
			}
			String text;
			try {
				text = FileUtil.readString(file, StandardCharsets.UTF_8);
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
					for (File inc : expandInclude(token, confBase)) {
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
		String path = unquote(raw);
		if (StrUtil.isEmpty(path)) {
			return out;
		}
		File file = new File(path);
		if (!file.isAbsolute() && confBase != null) {
			file = new File(confBase, path);
		}
		if (path.indexOf('*') < 0 && path.indexOf('?') < 0) {
			if (file.isFile()) {
				out.add(file);
			}
			return out;
		}
		File dir = file.getParentFile();
		if (dir == null || !dir.isDirectory()) {
			return out;
		}
		Pattern glob = Pattern.compile(globToRegex(file.getName()));
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

	private static String resolvePath(String raw, File confBase) {
		File file = resolveFile(unquote(raw), confBase);
		return file == null ? "" : file.getAbsolutePath();
	}

	private static File resolveFile(String raw, File confBase) {
		if (StrUtil.isEmpty(raw)) {
			return null;
		}
		File file = new File(raw);
		if (!file.isAbsolute() && confBase != null) {
			file = new File(confBase, raw);
		}
		return file;
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

	private static File prefixDir(NginxInstanceEntry entry) {
		String p = StrUtil.emptyToDefault(entry.getPrefixDir(), entry.getNginxDir());
		if (StrUtil.isNotEmpty(p)) {
			return new File(p);
		}
		File conf = new File(StrUtil.nullToEmpty(entry.getConfPath()));
		File parent = conf.getParentFile();
		return parent == null ? null : parent.getParentFile();
	}

	private static String canonical(File file) {
		try {
			return file.getCanonicalPath().toLowerCase(Locale.ROOT);
		} catch (Exception e) {
			return file.getAbsolutePath().toLowerCase(Locale.ROOT);
		}
	}
}
