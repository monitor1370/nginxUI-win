package com.nginxui.ext;

/**
 * 本机nginx进程及安装信息检测结果.
 */
public class NginxInfo {

	/** nginx是否正在运行 */
	private Boolean running = false;

	/** 所有nginx进程pid, 逗号分隔 */
	private String pids;

	/** master进程pid */
	private String masterPid;

	/** master进程的父进程pid(Windows下用于关联服务包装器) */
	private String masterPpid;

	/** 托管该实例的Windows服务名(仅服务托管实例非空) */
	private String winServiceName;

	/** nginx可执行文件绝对路径 */
	private String nginxExe;

	/** nginx安装目录(prefix) */
	private String nginxDir;

	/** nginx配置文件路径 */
	private String nginxPath;

	/** nginx版本 */
	private String version;

	/** 检测提示信息 */
	private String message;

	public Boolean getRunning() {
		return running;
	}

	public void setRunning(Boolean running) {
		this.running = running;
	}

	public String getPids() {
		return pids;
	}

	public void setPids(String pids) {
		this.pids = pids;
	}

	public String getMasterPid() {
		return masterPid;
	}

	public void setMasterPid(String masterPid) {
		this.masterPid = masterPid;
	}

	public String getMasterPpid() {
		return masterPpid;
	}

	public void setMasterPpid(String masterPpid) {
		this.masterPpid = masterPpid;
	}

	public String getWinServiceName() {
		return winServiceName;
	}

	public void setWinServiceName(String winServiceName) {
		this.winServiceName = winServiceName;
	}

	public String getNginxExe() {
		return nginxExe;
	}

	public void setNginxExe(String nginxExe) {
		this.nginxExe = nginxExe;
	}

	public String getNginxDir() {
		return nginxDir;
	}

	public void setNginxDir(String nginxDir) {
		this.nginxDir = nginxDir;
	}

	public String getNginxPath() {
		return nginxPath;
	}

	public void setNginxPath(String nginxPath) {
		this.nginxPath = nginxPath;
	}

	public String getVersion() {
		return version;
	}

	public void setVersion(String version) {
		this.version = version;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

}
