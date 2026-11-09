package com.nginxui.ext;

import java.util.List;

/**
 * Nginx仪表盘聚合数据.
 */
public class NginxDashboard {

	/** nginx是否正在运行 */
	private Boolean running = false;

	/** 所有nginx进程pid, 逗号分隔 */
	private String pids;

	/** master进程pid */
	private String masterPid;

	/** nginx可执行文件绝对路径 */
	private String nginxExe;

	/** nginx安装目录(prefix) */
	private String nginxDir;

	/** nginx配置文件路径 */
	private String nginxPath;

	/** nginx版本 */
	private String version;

	/** 进程总数 */
	private Integer procTotal = 0;

	/** master进程数 */
	private Integer masterCount = 0;

	/** worker进程数 */
	private Integer workerCount = 0;

	/** 其他进程数(服务包装器等) */
	private Integer otherCount = 0;

	/** CPU逻辑核心数 */
	private Integer coreCount;

	/** 配置中的worker_processes原始值, 可能为auto */
	private String workerProcesses;

	/** 生效的worker_processes数量(auto按核心数折算) */
	private Integer workerProcessesEff;

	/** 配置中的worker_connections */
	private Integer workerConnections;

	/** 理论最大连接数 = workerProcessesEff * workerConnections */
	private Long maxConnections;

	/** 当前活动连接占最大连接数百分比 */
	private Double connUsagePercent;

	/** 每次连接平均请求数 = requests / handled */
	private Double reqPerConn;

	/** stub_status是否已启用(成功取数) */
	private Boolean stubEnabled = false;

	/** stub_status访问地址 */
	private String stubUrl;

	/** Active connections */
	private Long activeConnections;

	/** accepts 握手总数 */
	private Long accepts;

	/** handled 连接总数 */
	private Long handled;

	/** requests 总请求数 */
	private Long requests;

	/** Reading */
	private Long reading;

	/** Writing */
	private Long writing;

	/** Waiting */
	private Long waiting;

	/** nginx -V 编译信息 */
	private String buildInfo;

	/** nginx -V configure arguments 原文 */
	private String configureArgs;

	/** 编译参数中显式启用的模块 */
	private List<String> modules;

	/** 数据刷新时间 */
	private String lastUpdate;

	/** 检测到的实例总数(含已停止) */
	private Integer instanceCount = 0;

	/** 运行中的实例数 */
	private Integer runningCount = 0;

	/** 已停止的实例数 */
	private Integer stoppedCount = 0;

	/** 实例明细(多实例, 按发现顺序) */
	private List<NginxDashboardInstance> instances;

	/** 多实例告警(端口冲突/版本不一致/孤儿进程/实例停止等) */
	private List<String> warnings;

	/** 无法归属到任何实例的nginx进程(异常残留) */
	private List<NginxProc> orphanProcesses;

	/** 提示信息 */
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

	public Integer getProcTotal() {
		return procTotal;
	}

	public void setProcTotal(Integer procTotal) {
		this.procTotal = procTotal;
	}

	public Integer getMasterCount() {
		return masterCount;
	}

	public void setMasterCount(Integer masterCount) {
		this.masterCount = masterCount;
	}

	public Integer getWorkerCount() {
		return workerCount;
	}

	public void setWorkerCount(Integer workerCount) {
		this.workerCount = workerCount;
	}

	public Integer getOtherCount() {
		return otherCount;
	}

	public void setOtherCount(Integer otherCount) {
		this.otherCount = otherCount;
	}

	public Integer getCoreCount() {
		return coreCount;
	}

	public void setCoreCount(Integer coreCount) {
		this.coreCount = coreCount;
	}

	public String getWorkerProcesses() {
		return workerProcesses;
	}

	public void setWorkerProcesses(String workerProcesses) {
		this.workerProcesses = workerProcesses;
	}

	public Integer getWorkerProcessesEff() {
		return workerProcessesEff;
	}

	public void setWorkerProcessesEff(Integer workerProcessesEff) {
		this.workerProcessesEff = workerProcessesEff;
	}

	public Integer getWorkerConnections() {
		return workerConnections;
	}

	public void setWorkerConnections(Integer workerConnections) {
		this.workerConnections = workerConnections;
	}

	public Long getMaxConnections() {
		return maxConnections;
	}

	public void setMaxConnections(Long maxConnections) {
		this.maxConnections = maxConnections;
	}

	public Double getConnUsagePercent() {
		return connUsagePercent;
	}

	public void setConnUsagePercent(Double connUsagePercent) {
		this.connUsagePercent = connUsagePercent;
	}

	public Double getReqPerConn() {
		return reqPerConn;
	}

	public void setReqPerConn(Double reqPerConn) {
		this.reqPerConn = reqPerConn;
	}

	public Boolean getStubEnabled() {
		return stubEnabled;
	}

	public void setStubEnabled(Boolean stubEnabled) {
		this.stubEnabled = stubEnabled;
	}

	public String getStubUrl() {
		return stubUrl;
	}

	public void setStubUrl(String stubUrl) {
		this.stubUrl = stubUrl;
	}

	public Long getActiveConnections() {
		return activeConnections;
	}

	public void setActiveConnections(Long activeConnections) {
		this.activeConnections = activeConnections;
	}

	public Long getAccepts() {
		return accepts;
	}

	public void setAccepts(Long accepts) {
		this.accepts = accepts;
	}

	public Long getHandled() {
		return handled;
	}

	public void setHandled(Long handled) {
		this.handled = handled;
	}

	public Long getRequests() {
		return requests;
	}

	public void setRequests(Long requests) {
		this.requests = requests;
	}

	public Long getReading() {
		return reading;
	}

	public void setReading(Long reading) {
		this.reading = reading;
	}

	public Long getWriting() {
		return writing;
	}

	public void setWriting(Long writing) {
		this.writing = writing;
	}

	public Long getWaiting() {
		return waiting;
	}

	public void setWaiting(Long waiting) {
		this.waiting = waiting;
	}

	public String getBuildInfo() {
		return buildInfo;
	}

	public void setBuildInfo(String buildInfo) {
		this.buildInfo = buildInfo;
	}

	public String getConfigureArgs() {
		return configureArgs;
	}

	public void setConfigureArgs(String configureArgs) {
		this.configureArgs = configureArgs;
	}

	public List<String> getModules() {
		return modules;
	}

	public void setModules(List<String> modules) {
		this.modules = modules;
	}

	public Integer getInstanceCount() {
		return instanceCount;
	}

	public void setInstanceCount(Integer instanceCount) {
		this.instanceCount = instanceCount;
	}

	public Integer getRunningCount() {
		return runningCount;
	}

	public void setRunningCount(Integer runningCount) {
		this.runningCount = runningCount;
	}

	public Integer getStoppedCount() {
		return stoppedCount;
	}

	public void setStoppedCount(Integer stoppedCount) {
		this.stoppedCount = stoppedCount;
	}

	public List<NginxDashboardInstance> getInstances() {
		return instances;
	}

	public void setInstances(List<NginxDashboardInstance> instances) {
		this.instances = instances;
	}

	public List<String> getWarnings() {
		return warnings;
	}

	public void setWarnings(List<String> warnings) {
		this.warnings = warnings;
	}

	public List<NginxProc> getOrphanProcesses() {
		return orphanProcesses;
	}

	public void setOrphanProcesses(List<NginxProc> orphanProcesses) {
		this.orphanProcesses = orphanProcesses;
	}

	public String getLastUpdate() {
		return lastUpdate;
	}

	public void setLastUpdate(String lastUpdate) {
		this.lastUpdate = lastUpdate;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

}
