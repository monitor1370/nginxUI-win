package com.nginxui.utils;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nginxui.ext.NginxInstanceEntry;
import com.nginxui.ext.NginxInstanceIgnore;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;

/**
 * 实例忽略名单: 持久化在 setting(nginxInstanceIgnoreV1),
 * 进程检测/SCM 枚举/文件系统扫描产出的候选在装配前按此名单过滤.
 *
 * 命中规则(任一非空特征相等即命中):
 * 指纹(含altIds) → Windows服务名(大小写不敏感) → exe+conf 规范化路径.
 */
public class NginxInstanceIgnoreStore {

	private static final Logger LOG = LoggerFactory.getLogger(NginxInstanceIgnoreStore.class);

	public static final String SETTING_KEY = "nginxInstanceIgnoreV1";

	private final List<NginxInstanceIgnore> items = new ArrayList<NginxInstanceIgnore>();

	/** 宽松加载: JSON 损坏时从空表重建, 仅记录告警 */
	public static NginxInstanceIgnoreStore load(String json) {
		try {
			return parseStrict(json);
		} catch (Exception e) {
			LOG.warn("忽略名单 JSON 解析失败, 按空表重建: {}", e.getMessage());
			return new NginxInstanceIgnoreStore();
		}
	}

	/**
	 * 严格解析: JSON 非法时抛出异常.
	 * 供 JsonFileStore 判定文件损坏并回退 *.bak 备份.
	 */
	public static NginxInstanceIgnoreStore parseStrict(String json) throws Exception {
		NginxInstanceIgnoreStore store = new NginxInstanceIgnoreStore();
		if (StrUtil.isNotEmpty(json)) {
			List<NginxInstanceIgnore> list = JSONUtil.toList(json, NginxInstanceIgnore.class);
			if (list != null) {
				for (NginxInstanceIgnore item : list) {
					if (item != null && StrUtil.isNotEmpty(item.getFid())) {
						store.items.add(item);
					}
				}
			}
		}
		return store;
	}

	public String toJson() {
		return JSONUtil.toJsonStr(items);
	}

	public List<NginxInstanceIgnore> items() {
		return items;
	}

	/** 加入忽略(同指纹去重, 刷新特征与时间) */
	public NginxInstanceIgnore add(String fid, String serviceName, String nginxExe,
			String confPath, String prefixDir, String name) {
		if (StrUtil.isEmpty(fid)) {
			return null;
		}
		NginxInstanceIgnore existed = findByFid(fid);
		if (existed == null) {
			existed = new NginxInstanceIgnore();
			existed.setFid(fid);
			items.add(existed);
		}
		existed.setServiceName(StrUtil.emptyToNull(serviceName));
		existed.setNginxExe(StrUtil.emptyToNull(emptyToNullCanonical(nginxExe)));
		existed.setConfPath(StrUtil.emptyToNull(emptyToNullCanonical(confPath)));
		existed.setPrefixDir(StrUtil.emptyToNull(emptyToNullCanonical(prefixDir)));
		existed.setName(StrUtil.emptyToNull(name));
		existed.setTime(System.currentTimeMillis());
		return existed;
	}

	/** 从注册表条目构造忽略特征 */
	public NginxInstanceIgnore addFromEntry(NginxInstanceEntry entry) {
		if (entry == null) {
			return null;
		}
		NginxInstanceIgnore item = add(entry.getId(), entry.getWinServiceName(),
				entry.getNginxExe(), entry.getConfPath(),
				StrUtil.emptyToDefault(entry.getPrefixDir(), entry.getNginxDir()),
				entry.getName());
		// 历史指纹一并登记, 目录迁移后的旧路径仍命中
		if (item != null && entry.getAltIds() != null) {
			for (String altId : entry.getAltIds()) {
				if (StrUtil.isNotEmpty(altId) && findByFid(altId) == null) {
					NginxInstanceIgnore alt = new NginxInstanceIgnore();
					alt.setFid(altId);
					alt.setServiceName(item.getServiceName());
					alt.setNginxExe(item.getNginxExe());
					alt.setConfPath(item.getConfPath());
					alt.setPrefixDir(item.getPrefixDir());
					alt.setName(item.getName());
					alt.setTime(item.getTime());
					items.add(alt);
				}
			}
		}
		return item;
	}

	/**
	 * 移出忽略: key 可以是指纹(含altId)或服务名(大小写不敏感),
	 * 同时移除共享同一服务名的其它指纹项.
	 * @return 实际被移除的条目(空列表表示没有匹配项); 调用方可据此重新纳管
	 */
	public List<NginxInstanceIgnore> remove(String key) {
		List<NginxInstanceIgnore> removed = new ArrayList<NginxInstanceIgnore>();
		if (StrUtil.isEmpty(key)) {
			return removed;
		}
		String k = key.trim();
		NginxInstanceIgnore hit = null;
		for (NginxInstanceIgnore item : items) {
			if (k.equals(item.getFid())
					|| (StrUtil.isNotEmpty(item.getServiceName())
							&& item.getServiceName().equalsIgnoreCase(k))) {
				hit = item;
				break;
			}
		}
		if (hit == null) {
			return removed;
		}
		final String serviceNameRef = hit.getServiceName();
		final NginxInstanceIgnore hitRef = hit;
		items.removeIf(item -> {
			boolean match = k.equals(item.getFid())
					|| (StrUtil.isNotEmpty(serviceNameRef) && StrUtil.isNotEmpty(item.getServiceName())
							&& item.getServiceName().equalsIgnoreCase(serviceNameRef)
							&& StrUtil.equals(item.getNginxExe(), hitRef.getNginxExe())
							&& StrUtil.equals(item.getConfPath(), hitRef.getConfPath()));
			if (match) {
				removed.add(item);
			}
			return match;
		});
		return removed;
	}

	public boolean isEmpty() {
		return items.isEmpty();
	}

	private NginxInstanceIgnore findByFid(String fid) {
		for (NginxInstanceIgnore item : items) {
			if (fid.equals(item.getFid())) {
				return item;
			}
		}
		return null;
	}

	/**
	 * 判断一个发现候选是否被忽略.
	 * @param fid         本轮计算的组合指纹/服务指纹(可空)
	 * @param serviceName Windows 服务名(可空)
	 * @param nginxExe    nginx exe 原始路径(可空, 内部规范化)
	 * @param confPath    配置文件原始路径(可空)
	 * @param prefixDir   prefix 原始路径(可空)
	 */
	public boolean matches(String fid, String serviceName, String nginxExe,
			String confPath, String prefixDir) {
		if (items.isEmpty()) {
			return false;
		}
		String exe = emptyToNullCanonical(nginxExe);
		String conf = emptyToNullCanonical(confPath);
		String prefix = emptyToNullCanonical(prefixDir);
		for (NginxInstanceIgnore item : items) {
			if (StrUtil.isNotEmpty(fid) && fid.equals(item.getFid())) {
				return true;
			}
			if (StrUtil.isNotEmpty(serviceName) && StrUtil.isNotEmpty(item.getServiceName())
					&& serviceName.equalsIgnoreCase(item.getServiceName())) {
				return true;
			}
			if (StrUtil.isNotEmpty(exe) && StrUtil.isNotEmpty(conf)
					&& exe.equals(item.getNginxExe()) && conf.equals(item.getConfPath())) {
				return true;
			}
			// 停止态服务候选可能缺conf: 服务名已判过, 这里用 exe+prefix 兜底
			if (StrUtil.isNotEmpty(exe) && StrUtil.isNotEmpty(prefix)
					&& exe.equals(item.getNginxExe()) && prefix.equals(item.getPrefixDir())
					&& StrUtil.isEmpty(item.getConfPath())) {
				return true;
			}
		}
		return false;
	}

	/** 注册表条目是否命中忽略(含altIds) */
	public boolean containsEntry(NginxInstanceEntry entry) {
		if (entry == null) {
			return false;
		}
		if (matches(entry.getId(), entry.getWinServiceName(), entry.getNginxExe(),
				entry.getConfPath(), StrUtil.emptyToDefault(entry.getPrefixDir(), entry.getNginxDir()))) {
			return true;
		}
		if (entry.getAltIds() != null) {
			for (String altId : entry.getAltIds()) {
				if (matches(altId, entry.getWinServiceName(), entry.getNginxExe(),
						entry.getConfPath(),
						StrUtil.emptyToDefault(entry.getPrefixDir(), entry.getNginxDir()))) {
					return true;
				}
			}
		}
		return false;
	}

	private static String emptyToNullCanonical(String path) {
		if (StrUtil.isEmpty(path)) {
			return null;
		}
		String c = NginxInstanceRegistry.canonical(path);
		return StrUtil.isEmpty(c) ? null : c;
	}

}
