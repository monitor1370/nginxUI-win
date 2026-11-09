package com.nginxui.ext;

/**
 * nginx 实例升级/回滚历史条目(持久化在 setting nginxUpgradeHistoryV1).
 */
public class NginxUpgradeRecord {

	/** upgrade / rollback */
	private String direction;
	private String instanceId;
	private String instanceName;
	private long time;
	private String fromVersion;
	private String toVersion;
	private String fromExe;
	private String toExe;
	/** 升级前程序文件的备份路径(回滚依据) */
	private String backupExe;
	/** success / failed */
	private String result;
	private String error;

	public String getDirection() {
		return direction;
	}

	public void setDirection(String direction) {
		this.direction = direction;
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

	public long getTime() {
		return time;
	}

	public void setTime(long time) {
		this.time = time;
	}

	public String getFromVersion() {
		return fromVersion;
	}

	public void setFromVersion(String fromVersion) {
		this.fromVersion = fromVersion;
	}

	public String getToVersion() {
		return toVersion;
	}

	public void setToVersion(String toVersion) {
		this.toVersion = toVersion;
	}

	public String getFromExe() {
		return fromExe;
	}

	public void setFromExe(String fromExe) {
		this.fromExe = fromExe;
	}

	public String getToExe() {
		return toExe;
	}

	public void setToExe(String toExe) {
		this.toExe = toExe;
	}

	public String getBackupExe() {
		return backupExe;
	}

	public void setBackupExe(String backupExe) {
		this.backupExe = backupExe;
	}

	public String getResult() {
		return result;
	}

	public void setResult(String result) {
		this.result = result;
	}

	public String getError() {
		return error;
	}

	public void setError(String error) {
		this.error = error;
	}
}
