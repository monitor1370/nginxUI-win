package com.nginxui.ext;

/**
 * 多实例overview结果: 仪表盘数据 + 更新后的实例注册表JSON(由调用方持久化).
 */
public class NginxOverviewResult {

	private NginxDashboard dashboard;

	private String registryJson;

	public NginxDashboard getDashboard() {
		return dashboard;
	}

	public void setDashboard(NginxDashboard dashboard) {
		this.dashboard = dashboard;
	}

	public String getRegistryJson() {
		return registryJson;
	}

	public void setRegistryJson(String registryJson) {
		this.registryJson = registryJson;
	}

}
