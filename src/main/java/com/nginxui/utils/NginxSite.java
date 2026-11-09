package com.nginxui.utils;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.nginxui.ext.NginxInstanceEntry;
import com.nginxui.utils.NginxCertReplaceManager.CertItem;

import cn.hutool.core.util.StrUtil;

/**
 * 站点实体: 一个 nginx server 块即一个站点.
 *
 * 站点是 HTTP/HTTPS 配置的主维度: 端口只是站点的属性与冲突检测点,
 * 同一个 server_name 在 80 与 443 上仍属于同一站点.
 * 证书仍是全局资产(证书库), 站点只记录当前引用的证书文件.
 */
public class NginxSite {

	/** 单个 listen 声明(如 80 / 127.0.0.1:8088 / 443 ssl) */
	public static class Listen {
		/** 原始参数文本, 不含 listen 关键字与分号, 如 "443 ssl" */
		public String args = "";
		/** 归一化地址: 通配地址为 * */
		public String address = "*";
		public String port = "";
		public boolean ssl;

		/** 用于展示的地址:端口 */
		public String endpoint() {
			return "*".equals(address) ? port : address + ":" + port;
		}

		/** 去掉 ssl 参数后的 listen 参数, 用于生成跳转站点 */
		public String plainArgs() {
			String plain = StrUtil.nullToEmpty(args).replaceAll("(?i)\\bssl\\b", "")
					.replaceAll("\\s+", " ").trim();
			return StrUtil.isEmpty(plain) ? port : plain;
		}
	}

	public NginxInstanceEntry instance;
	/** 站点所在的配置文件 */
	public File sourceConf;
	/** server 关键字在文件中的起始下标 */
	public int blockStart;
	/** server 块开括号 { 的下标 */
	public int blockOpen;
	/** server 块闭括号 } 的下标 */
	public int blockClose;

	public List<String> serverNames = new ArrayList<String>();
	public List<Listen> listens = new ArrayList<Listen>();
	/** 配置中声明的证书条目(未声明时为 null) */
	public CertItem cert;

	/** 主域名: 第一个有效的 server_name; 仅 _ 时为 _; 未声明时给出提示文案 */
	public String domain() {
		for (String name : serverNames) {
			if (StrUtil.isNotEmpty(name) && !"_".equals(name) && !"*".equals(name)) {
				return name;
			}
		}
		if (!serverNames.isEmpty()) {
			String first = serverNames.get(0);
			return StrUtil.emptyToDefault(first, "(未声明 server_name)");
		}
		return "(未声明 server_name)";
	}

	/**
	 * 表格展示域名: 已配置 HTTPS 且证书可解析出域名时, 优先展示证书中的域名;
	 * 否则回退到 server_name.
	 */
	public String displayDomain() {
		if (hasHttps() && cert != null && cert.info != null && !cert.info.domains.isEmpty()) {
			String name = cert.info.domains.get(0);
			if (StrUtil.isNotEmpty(name)) {
				return name;
			}
		}
		return domain();
	}

	/** 是否已声明 SSL 监听 */
	public boolean hasSslListen() {
		for (Listen listen : listens) {
			if (listen.ssl) {
				return true;
			}
		}
		return false;
	}

	/** 是否已是 HTTPS 站点(存在 ssl 监听或已声明证书) */
	public boolean hasHttps() {
		return hasSslListen() || cert != null;
	}

	/** 当前监听端口(去重, 保持声明顺序) */
	public List<String> ports() {
		Set<String> ports = new LinkedHashSet<String>();
		for (Listen listen : listens) {
			if (StrUtil.isNotEmpty(listen.port)) {
				ports.add(listen.port);
			}
		}
		return new ArrayList<String>(ports);
	}

	public String portsText() {
		List<String> ports = ports();
		if (ports.isEmpty()) {
			return "(未声明 listen)";
		}
		return String.join(", ", ports);
	}

	/** 表格展示: 80, 443 (SSL) */
	public String portsWithSslText() {
		List<String> ports = new ArrayList<String>();
		for (Listen listen : listens) {
			String p = listen.ssl ? listen.port + " (SSL)" : listen.port;
			if (!ports.contains(p)) {
				ports.add(p);
			}
		}
		return ports.isEmpty() ? "(未声明 listen)" : String.join(", ", ports);
	}

	public List<String> serverNameTextList() {
		List<String> names = new ArrayList<String>();
		for (String name : serverNames) {
			if (StrUtil.isNotEmpty(name) && !names.contains(name)) {
				names.add(name);
			}
		}
		return names;
	}

	public String serverNamesText() {
		List<String> names = serverNameTextList();
		return names.isEmpty() ? "(未声明)" : String.join(", ", names);
	}
}
