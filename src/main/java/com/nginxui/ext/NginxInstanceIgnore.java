package com.nginxui.ext;

/**
 * 实例忽略名单条目(持久化为 setting(nginxInstanceIgnoreV1) 中的 JSON 数组):
 * 用户显式忽略的实例特征, 进程/SCM/文件系统扫描三路发现均跳过.
 * 任一非空特征命中即视为同一实例(指纹优先, 服务名/路径兜底).
 */
public class NginxInstanceIgnore {

	/** 主指纹(组合指纹或服务指纹) */
	private String fid;

	/** Windows 服务名(可空) */
	private String serviceName;

	/** nginx 可执行文件规范化路径(可空) */
	private String nginxExe;

	/** 配置文件规范化路径(可空) */
	private String confPath;

	/** prefix 规范化路径(可空) */
	private String prefixDir;

	/** 展示用名称(实例别名或候选名) */
	private String name;

	/** 加入忽略的时间(毫秒) */
	private Long time;

	public String getFid() {
		return fid;
	}

	public void setFid(String fid) {
		this.fid = fid;
	}

	public String getServiceName() {
		return serviceName;
	}

	public void setServiceName(String serviceName) {
		this.serviceName = serviceName;
	}

	public String getNginxExe() {
		return nginxExe;
	}

	public void setNginxExe(String nginxExe) {
		this.nginxExe = nginxExe;
	}

	public String getConfPath() {
		return confPath;
	}

	public void setConfPath(String confPath) {
		this.confPath = confPath;
	}

	public String getPrefixDir() {
		return prefixDir;
	}

	public void setPrefixDir(String prefixDir) {
		this.prefixDir = prefixDir;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public Long getTime() {
		return time;
	}

	public void setTime(Long time) {
		this.time = time;
	}

}
