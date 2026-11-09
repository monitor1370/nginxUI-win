package com.nginxui.ext;

import java.util.ArrayList;
import java.util.List;

/**
 * nginx实例注册表条目(持久化为setting中的JSON):
 * 实例停止后仍保留身份/别名/配置路径/stub端口分配.
 */
public class NginxInstanceEntry {

	/** 实例稳定id(首次注册时的指纹md5前12位, 分配后不再改变) */
	private String id;

	/** 实例别名(用户可改) */
	private String name;

	/** 配置文件绝对路径(实例特征之一) */
	private String confPath;

	/** 最近一次识别到的prefix目录(规范化) */
	private String prefixDir;

	/** 历史身份指纹(目录迁移/换配置后, 旧指纹归入同一实例) */
	private List<String> altIds = new ArrayList<String>();

	/** nginx可执行文件路径 */
	private String nginxExe;

	/** prefix目录 */
	private String nginxDir;

	/** 分配的stub_status端口 */
	private Integer stubPort;

	/** 分配的stub_status路径 */
	private String stubUri;

	/** stub_status 监控展示开关(null=自动: 配置中有stub声明即展示; true/false=用户显式设定, 不改写配置) */
	private Boolean stubMonitor;

	/** 最近一次运行的master pid */
	private String lastMasterPid;

	/** 最近一次在线时间(毫秒) */
	private Long lastSeen;

	/** 托管该实例的Windows服务名(非空时start/stop/restart走SCM服务控制) */
	private String winServiceName;

	/**
	 * 实例来源: manual(用户手动添加) / discovered(进程/文件系统发现) /
	 * service(SCM服务发现), 默认 discovered.
	 */
	private String source = "discovered";

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

	public List<String> getAltIds() {
		return altIds;
	}

	public void setAltIds(List<String> altIds) {
		this.altIds = altIds == null ? new ArrayList<String>() : altIds;
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

	public Boolean getStubMonitor() {
		return stubMonitor;
	}

	public void setStubMonitor(Boolean stubMonitor) {
		this.stubMonitor = stubMonitor;
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
