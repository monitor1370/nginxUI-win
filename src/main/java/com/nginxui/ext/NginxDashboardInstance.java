package com.nginxui.ext;

import java.util.List;

/**
 * 单个nginx实例的仪表盘数据(多实例).
 * 复用NginxDashboard全部运行指标, 另带实例身份/配置/stub分配信息.
 */
public class NginxDashboardInstance extends NginxDashboard {

	/** 实例id(按配置文件路径生成, 稳定) */
	private String id;

	/** 实例显示名(可自定义别名) */
	private String name;

	/** 实例状态: running/stopped */
	private String status = "running";

	/** 配置解析出的监听地址(原样首段, 如 80 / 443 / 127.0.0.1:9527) */
	private List<String> listenPorts;

	/** 分配的stub_status端口 */
	private Integer stubPort;

	/** 分配的stub_status路径 */
	private String stubUri;

	/** 该stub端口是否由本实例配置声明(否则即使有响应也属于其他实例, 不予采信) */
	private Boolean stubOwned;

	/** 配置中是否存在stub_status声明(决定"监控开关"能否操作) */
	private Boolean stubApplicable;

	/** 是否存在本系统写入的受标记stub块(决定"移除监控配置"是否可操作) */
	private Boolean stubManaged;

	/** 监控采集开关(展示/采集语义, 非null; 关闭时仅不采集不展示, 不改写配置) */
	private Boolean stubMonitor;

	/** 最近一次运行时master pid(停止实例仍可展示) */
	private String lastMasterPid;

	/** 最近一次在线时间(毫秒) */
	private Long lastSeen;

	/** 该实例的进程明细 */
	private List<NginxProc> processes;

	/** 托管该实例的Windows服务名(非空表示由SCM服务托管) */
	private String winServiceName;

	/** 来源: manual/discovered/service */
	private String source;

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public List<String> getListenPorts() {
		return listenPorts;
	}

	public void setListenPorts(List<String> listenPorts) {
		this.listenPorts = listenPorts;
	}

	public Integer getStubPort() {
		return stubPort;
	}

	public void setStubPort(Integer stubPort) {
		this.stubPort = stubPort;
	}

	public String getStubUri() {
		return stubUri;
	}

	public void setStubUri(String stubUri) {
		this.stubUri = stubUri;
	}

	public Boolean getStubOwned() {
		return stubOwned;
	}

	public void setStubOwned(Boolean stubOwned) {
		this.stubOwned = stubOwned;
	}

	public String getLastMasterPid() {
		return lastMasterPid;
	}

	public void setLastMasterPid(String lastMasterPid) {
		this.lastMasterPid = lastMasterPid;
	}

	public Long getLastSeen() {
		return lastSeen;
	}

	public void setLastSeen(Long lastSeen) {
		this.lastSeen = lastSeen;
	}

	public Boolean getStubApplicable() {
		return stubApplicable;
	}

	public void setStubApplicable(Boolean stubApplicable) {
		this.stubApplicable = stubApplicable;
	}

	public Boolean getStubManaged() {
		return stubManaged;
	}

	public void setStubManaged(Boolean stubManaged) {
		this.stubManaged = stubManaged;
	}

	public Boolean getStubMonitor() {
		return stubMonitor;
	}

	public void setStubMonitor(Boolean stubMonitor) {
		this.stubMonitor = stubMonitor;
	}

	public List<NginxProc> getProcesses() {
		return processes;
	}

	public void setProcesses(List<NginxProc> processes) {
		this.processes = processes;
	}

	public String getWinServiceName() {
		return winServiceName;
	}

	public void setWinServiceName(String winServiceName) {
		this.winServiceName = winServiceName;
	}

	public String getSource() {
		return source;
	}

	public void setSource(String source) {
		this.source = source;
	}

}
