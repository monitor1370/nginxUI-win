package com.nginxui.ext;

import java.util.ArrayList;
import java.util.List;

/**
 * 本机多实例nginx检测结果: 每个master进程代表一个实例.
 */
public class NginxDetectResult {

	/** 检测到的运行实例, 按master pid升序 */
	private List<NginxInfo> instances = new ArrayList<NginxInfo>();

	/** 无法归属到任何master的nginx进程pid(孤儿worker等) */
	private List<String> orphanPids = new ArrayList<String>();

	/** 检测异常信息 */
	private String message;

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public List<NginxInfo> getInstances() {
		return instances;
	}

	public void setInstances(List<NginxInfo> instances) {
		this.instances = instances;
	}

	public List<String> getOrphanPids() {
		return orphanPids;
	}

	public void setOrphanPids(List<String> orphanPids) {
		this.orphanPids = orphanPids;
	}

}
