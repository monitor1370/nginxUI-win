package com.nginxui.ext;

/**
 * nginx单个进程信息(仪表盘"进程信息"页签).
 */
public class NginxProc {

	/** 进程pid */
	private String pid;

	/** 父进程pid */
	private String ppid;

	/** 进程名 */
	private String name;

	/** 角色: master/worker/other */
	private String role;

	/** 所属nginx实例id(多实例), 无归属(孤儿进程)为null */
	private String instanceId;

	/** 所属nginx实例名 */
	private String instanceName;

	public String getPid() {
		return pid;
	}

	public void setPid(String pid) {
		this.pid = pid;
	}

	public String getPpid() {
		return ppid;
	}

	public void setPpid(String ppid) {
		this.ppid = ppid;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getRole() {
		return role;
	}

	public void setRole(String role) {
		this.role = role;
	}

	public String getInstanceId() {
		return instanceId;
	}

	public void setInstanceId(String instanceId) {
		this.instanceId = instanceId;
	}

	public String getInstanceName() {
		return instanceName;
	}

	public void setInstanceName(String instanceName) {
		this.instanceName = instanceName;
	}

}
