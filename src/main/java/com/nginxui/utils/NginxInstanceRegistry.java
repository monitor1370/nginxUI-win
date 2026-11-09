package com.nginxui.utils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nginxui.ext.NginxInfo;
import com.nginxui.ext.NginxInstanceEntry;
import com.nginxui.ext.NginxInstanceIgnore;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.json.JSONUtil;

/**
 * nginx多实例注册表: 发现实例与持久化记录的合并, stub端口/别名/默认命名策略.
 * 内容以JSON存入setting(nginxInstanceRegistryV1), 不引入新表.
 *
 * 实例身份: 首次注册时由"prefix目录+配置文件位置"组合指纹得到12位id, 之后永不变;
 * 目录迁移/换启动方式导致指纹变化时, 通过同一可执行文件路径归并到旧条目,
 * 旧指纹记入altIds, 保证别名/stub端口/监控曲线连续.
 */
public class NginxInstanceRegistry {

	private static final Logger LOG = LoggerFactory.getLogger(NginxInstanceRegistry.class);

	public static final String SETTING_KEY = "nginxInstanceRegistryV1";

	public static final int BASE_STUB_PORT = 9527;

	/** 实例来源: 用户手动添加 */
	public static final String SOURCE_MANUAL = "manual";
	/** 实例来源: 运行进程/文件系统发现 */
	public static final String SOURCE_DISCOVERED = "discovered";
	/** 实例来源: Windows SCM 服务 */
	public static final String SOURCE_SERVICE = "service";

	/** Windows 8.3短路径段(如 NGINX~1.1)转换结果缓存 */
	private static final Map<String, String> LONG_PATH_CACHE = new ConcurrentHashMap<String, String>();

	/** kernel32 仅需的一个API(jna-platform的Kernel32接口未暴露GetLongPathNameW) */
	private interface WinKernel extends com.sun.jna.Library {
		WinKernel INSTANCE = com.sun.jna.Native.load("kernel32", WinKernel.class);

		int GetLongPathNameW(String shortPath, char[] longPath, int bufferSize);
	}

	private final List<NginxInstanceEntry> entries = new ArrayList<NginxInstanceEntry>();

	/** 宽松加载: JSON 损坏时按空表重建(实例会被重新发现), 仅记录告警 */
	public static NginxInstanceRegistry load(String json) {
		try {
			return parseStrict(json);
		} catch (Exception e) {
			LOG.warn("注册表 JSON 解析失败, 按空表重建: {}", e.getMessage());
			return new NginxInstanceRegistry();
		}
	}

	/**
	 * 严格解析: JSON 非法时抛出异常.
	 * 供 JsonFileStore 判定文件损坏并回退 *.bak 备份, 避免静默丢数据.
	 */
	public static NginxInstanceRegistry parseStrict(String json) throws Exception {
		NginxInstanceRegistry registry = new NginxInstanceRegistry();
		if (StrUtil.isNotEmpty(json)) {
			List<NginxInstanceEntry> list = JSONUtil.toList(json, NginxInstanceEntry.class);
			if (list != null) {
				for (NginxInstanceEntry entry : list) {
					if (StrUtil.isNotEmpty(entry.getId())
							&& (StrUtil.isNotEmpty(entry.getConfPath())
									|| StrUtil.isNotEmpty(entry.getNginxExe()))) {
						registry.entries.add(entry);
					}
				}
			}
		}
		return registry;
	}

	public String toJson() {
		return JSONUtil.toJsonStr(entries);
	}

	/**
	 * 乐观并发提交: 把本次探测得到的注册表合并进最新持久化状态 latestJson.
	 *
	 * 后台 10s 采样与页面请求并发执行, 探测期间用户可能已改名/改 stub 端口/
	 * 升级切了 exe/取消了纳管. 合并规则:
	 *  - 最新表中已存在的条目: 仅回写实体字段(winServiceName/路径/altIds/
	 *    stubPort/stubUri), 保留用户字段(name/source)与升级期间写入的 nginxExe;
	 *    stubPort/stubUri 仅在本轮探测到与既有值不同时对齐;
	 *  - 最新表中不存在的条目(新发现): 若已命中忽略名单则丢弃, 否则新增.
	 *
	 * 运行时刷新字段(lastSeen / lastMasterPid)不写入 latest —— 它们不需要持久化,
	 * 每次启动/定时扫描会重新得到最新值.
	 *
	 * @return true 表示合并产生了需要持久化的实体变化(新条目 / 路径补全 /
	 *         stub端口对齐 / altIds 追加). 运行时刷新不算脏.
	 */
	public boolean mergeIntoLatest(String latestJson, NginxInstanceIgnoreStore ignoreStore) {
		NginxInstanceRegistry latest = NginxInstanceRegistry.load(latestJson);
		boolean dirty = false;
		for (NginxInstanceEntry computed : new ArrayList<NginxInstanceEntry>(entries)) {
			NginxInstanceEntry current = latest.find(computed.getId());
			if (current == null) {
				if (ignoreStore != null && ignoreStore.matches(computed.getId(),
						computed.getWinServiceName(), computed.getNginxExe(),
						computed.getConfPath(),
						StrUtil.emptyToDefault(computed.getPrefixDir(), computed.getNginxDir()))) {
					continue;
				}
				latest.entries.add(computed);
				dirty = true;
				continue;
			}
			if (computed.getAltIds() != null) {
				for (String alt : computed.getAltIds()) {
					if (StrUtil.isNotEmpty(alt) && !current.getAltIds().contains(alt)) {
						current.getAltIds().add(alt);
						dirty = true;
					}
				}
			}
			if (StrUtil.isEmpty(current.getWinServiceName())
					&& StrUtil.isNotEmpty(computed.getWinServiceName())) {
				current.setWinServiceName(computed.getWinServiceName());
				dirty = true;
			}
			if (StrUtil.isEmpty(current.getConfPath())
					&& StrUtil.isNotEmpty(computed.getConfPath())) {
				current.setConfPath(computed.getConfPath());
				dirty = true;
			}
			if (StrUtil.isEmpty(current.getPrefixDir())
					&& StrUtil.isNotEmpty(computed.getPrefixDir())) {
				current.setPrefixDir(computed.getPrefixDir());
				dirty = true;
			}
			if (StrUtil.isEmpty(current.getNginxDir())
					&& StrUtil.isNotEmpty(computed.getNginxDir())) {
				current.setNginxDir(computed.getNginxDir());
				dirty = true;
			}
			// stub端口/路径: 本轮探测值与既有值不同时才对齐(实例配置里手工写过
			// stub_status块时校正为配置实际声明的端口/路径); 未检测到配置声明时
			// computed与current一致, 不会误改用户通过开关写入的值.
			if (computed.getStubPort() != null && !computed.getStubPort().equals(current.getStubPort())) {
				current.setStubPort(computed.getStubPort());
				dirty = true;
			}
			if (StrUtil.isNotEmpty(computed.getStubUri()) && !computed.getStubUri().equals(current.getStubUri())) {
				current.setStubUri(computed.getStubUri());
				dirty = true;
			}
			// nginxExe 仅由安装/升级/手动添加显式写入, 探测结果不覆盖,
			// 避免升级重启窗口内用旧快照回滚指针值

			// 运行时刷新字段 —— 不写进 latest, 避免每次启动/定时刷新都污染磁盘
		}
		// 关键: 把合并结果回写到 this.entries, 否则 caller 看到的还是旧列表!
		this.entries.clear();
		this.entries.addAll(latest.entries);
		return dirty;
	}

	public List<NginxInstanceEntry> entries() {
		return entries;
	}

	/** 只读快照: 供后台扫描使用, 避免与刷新线程同时增删条目时抛 ConcurrentModificationException */
	public List<NginxInstanceEntry> snapshot() {
		return new ArrayList<NginxInstanceEntry>(entries);
	}

	public NginxInstanceEntry find(String id) {
		if (StrUtil.isEmpty(id)) {
			return null;
		}
		for (NginxInstanceEntry entry : entries) {
			if (id.equals(entry.getId()) || entry.getAltIds().contains(id)) {
				return entry;
			}
		}
		return null;
	}

	/**
	 * 用本轮运行实例获取/归并注册表条目.
	 *
	 * 匹配顺序: 组合指纹直接命中 → altIds历史指纹命中 → 同一可执行文件归并;
	 * exe归并要求该exe本轮只对应一个指纹(防止"同目录不同配置双实例并存"被误并).
	 * 归并不改变条目的稳定id, 新指纹写入altIds, 别名/端口分配/监控曲线保持连续.
	 *
	 * @param fid          本轮为实例计算的组合指纹(fingerprint)
	 * @param info         检测到的运行实例
	 * @param liveExeFids  本轮 规范化exe路径 -> 运行指纹集合
	 */
	public NginxInstanceEntry acquire(String fid, NginxInfo info,
			Map<String, java.util.Set<String>> liveExeFids) {
		NginxInstanceEntry entry = find(fid);
		if (entry == null) {
			entry = matchByExe(info, liveExeFids);
		}

		boolean isNew = entry == null;
		if (isNew) {
			entry = new NginxInstanceEntry();
			entry.setId(fid);
			entry.setStubPort(nextPort());
			entry.setName(uniqueName(defaultName(canonical(info.getNginxPath()), entries.size())));
			entry.setSource(SOURCE_DISCOVERED);
			entries.add(entry);
		} else if (!fid.equals(entry.getId()) && !entry.getAltIds().contains(fid)) {
			// 身份迁移(指纹变化但确认是同一实例): 稳定id不变, 记录历史指纹
			entry.getAltIds().add(fid);
		}

		String confPath = canonical(info.getNginxPath());
		String prefix = canonical(info.getNginxDir());
		if (StrUtil.isNotEmpty(confPath)) {
			entry.setConfPath(confPath);
		}
		if (StrUtil.isNotEmpty(prefix)) {
			entry.setPrefixDir(prefix);
		}
		entry.setNginxExe(StrUtil.emptyToDefault(canonical(info.getNginxExe()), entry.getNginxExe()));
		entry.setNginxDir(StrUtil.emptyToDefault(prefix, entry.getNginxDir()));
		if (StrUtil.isNotEmpty(info.getWinServiceName())) {
			entry.setWinServiceName(info.getWinServiceName());
			String sfid = serviceFid(info.getWinServiceName());
			if (!sfid.equals(entry.getId()) && !entry.getAltIds().contains(sfid)) {
				entry.getAltIds().add(sfid);
			}
		}
		if (entry.getStubPort() == null) {
			entry.setStubPort(nextPort());
		}
		if (StrUtil.isEmpty(entry.getStubUri())) {
			entry.setStubUri(stubUriOf(entry.getStubPort()));
		}
		entry.setLastMasterPid(info.getMasterPid());
		entry.setLastSeen(System.currentTimeMillis());
		return entry;
	}

	/**
	 * 同一可执行文件归并: 仅当该exe本轮只出现一个运行指纹,
	 * 且注册表中只有一个条目使用该exe时才命中, 有歧义一律不归并.
	 */
	private NginxInstanceEntry matchByExe(NginxInfo info,
			Map<String, java.util.Set<String>> liveExeFids) {
		String exe = canonical(info.getNginxExe());
		if (StrUtil.isEmpty(exe)) {
			return null;
		}
		java.util.Set<String> fids = liveExeFids == null ? null : liveExeFids.get(exe);
		if (fids != null && fids.size() > 1) {
			return null;
		}
		return matchByExePath(exe);
	}

	/** 注册表里按规范化exe路径找唯一条目, 多个条目共用该exe时存在歧义, 不命中 */
	private NginxInstanceEntry matchByExePath(String exe) {
		if (StrUtil.isEmpty(exe)) {
			return null;
		}
		NginxInstanceEntry hit = null;
		for (NginxInstanceEntry candidate : entries) {
			if (exe.equals(canonical(candidate.getNginxExe()))) {
				if (hit != null) {
					return null;
				}
				hit = candidate;
			}
		}
		return hit;
	}

	/** Windows服务名指纹: 服务名机器级唯一稳定(SCM名大小写不敏感) */
	public static String serviceFid(String serviceName) {
		return idOf("win-svc:" + StrUtil.nullToEmpty(serviceName).trim().toLowerCase());
	}

	/**
	 * 用SCM中发现的nginx服务(通常为停止态)获取/归并注册表条目.
	 *
	 * 匹配顺序: 服务名指纹 → 配置组合指纹 → 同一可执行文件(唯一无歧义);
	 * 归并后服务名指纹与配置指纹均记入altIds, 使"服务托管/命令行启动"
	 * 两种方式切换时身份/别名/端口分配保持连续.
	 */
	public NginxInstanceEntry acquireService(WindowsNginxServiceUtils.WinNginxService svc) {
		String sfid = serviceFid(svc.serviceName);
		String cfid = StrUtil.isNotEmpty(svc.confPath) ? fingerprint(svc.prefixDir, svc.confPath) : null;

		NginxInstanceEntry entry = find(sfid);
		if (entry == null && cfid != null) {
			entry = find(cfid);
		}
		if (entry == null) {
			entry = matchByExePath(svc.nginxExe);
		}

		boolean isNew = entry == null;
		if (isNew) {
			entry = new NginxInstanceEntry();
			entry.setId(sfid);
			entry.setStubPort(nextPort());
			String base = StrUtil.emptyToDefault(svc.displayName, svc.serviceName);
			entry.setName(uniqueName(base));
			entry.setSource(SOURCE_SERVICE);
			entries.add(entry);
		}

		if (!sfid.equals(entry.getId()) && !entry.getAltIds().contains(sfid)) {
			entry.getAltIds().add(sfid);
		}
		if (cfid != null && !cfid.equals(entry.getId()) && !entry.getAltIds().contains(cfid)) {
			entry.getAltIds().add(cfid);
		}

		entry.setWinServiceName(svc.serviceName);
		if (StrUtil.isNotEmpty(svc.confPath)) {
			entry.setConfPath(svc.confPath);
		}
		if (StrUtil.isNotEmpty(svc.prefixDir)) {
			entry.setPrefixDir(svc.prefixDir);
			if (StrUtil.isEmpty(entry.getNginxDir())) {
				entry.setNginxDir(svc.prefixDir);
			}
		}
		if (StrUtil.isNotEmpty(svc.nginxExe)) {
			entry.setNginxExe(svc.nginxExe);
		}
		if (entry.getStubPort() == null) {
			entry.setStubPort(nextPort());
		}
		if (StrUtil.isEmpty(entry.getStubUri())) {
			entry.setStubUri(stubUriOf(entry.getStubPort()));
		}
		return entry;
	}

	/**
	 * 手动登记实例(用户在页面填写 exe/conf/prefix).
	 * 校验路径存在 → 规范化 → 计算指纹 → 同指纹去重(返回既有条目, 不产生重复)
	 * → 分配默认名/stub端口. 同指纹重复登记只刷新路径并标记来源为 manual.
	 *
	 * @throws IllegalArgumentException 路径不合法/文件不存在
	 */
	public NginxInstanceEntry manualRegister(String exePath, String confPath, String prefixPath,
			String displayName) {
		if (StrUtil.isEmpty(exePath)) {
			throw new IllegalArgumentException("nginx 可执行文件路径不能为空");
		}
		File exe = new File(exePath.trim());
		if (!exe.isFile()) {
			throw new IllegalArgumentException("nginx 可执行文件不存在: " + exePath);
		}
		if (StrUtil.isEmpty(confPath)) {
			throw new IllegalArgumentException("配置文件路径不能为空");
		}
		File conf = new File(confPath.trim());
		if (!conf.isFile()) {
			throw new IllegalArgumentException("配置文件不存在: " + confPath);
		}
		String prefix = StrUtil.trimToEmpty(prefixPath);
		File prefixDir;
		if (prefix.isEmpty()) {
			// 缺省 prefix: conf 的上两级(标准布局 <prefix>/conf/nginx.conf)
			File confDir = conf.getParentFile();
			prefixDir = confDir != null && confDir.getParentFile() != null
					? confDir.getParentFile() : confDir;
		} else {
			prefixDir = new File(prefix);
		}
		if (prefixDir == null || !prefixDir.isDirectory()) {
			throw new IllegalArgumentException("prefix 目录不存在: " + prefixPath);
		}

		String exeCanonical = canonical(exe.getAbsolutePath());
		String confCanonical = canonical(conf.getAbsolutePath());
		String prefixCanonical = canonical(prefixDir.getAbsolutePath());
		String fid = fingerprint(prefixCanonical, confCanonical);

		NginxInstanceEntry entry = find(fid);
		boolean isNew = entry == null;
		if (isNew) {
			entry = new NginxInstanceEntry();
			entry.setId(fid);
			entry.setStubPort(nextPort());
			String base = StrUtil.isNotEmpty(displayName) ? displayName.trim()
					: defaultName(confCanonical, entries.size());
			entry.setName(uniqueName(base));
			entries.add(entry);
		}
		entry.setSource(SOURCE_MANUAL);
		entry.setNginxExe(exeCanonical);
		entry.setNginxDir(prefixCanonical);
		entry.setPrefixDir(prefixCanonical);
		entry.setConfPath(confCanonical);
		if (StrUtil.isNotEmpty(displayName) && !displayName.trim().equals(entry.getName())) {
			entry.setName(uniqueName(displayName.trim()));
		}
		if (entry.getStubPort() == null) {
			entry.setStubPort(nextPort());
		}
		if (StrUtil.isEmpty(entry.getStubUri())) {
			entry.setStubUri(stubUriOf(entry.getStubPort()));
		}
		entry.setLastSeen(System.currentTimeMillis());
		return entry;
	}

	/**
	 * 从注册表移除条目(仅取消纳管, 不删除任何文件/服务).
	 * @return 被移除的条目; id 不存在时返回 null
	 */
	public NginxInstanceEntry remove(String id) {
		NginxInstanceEntry entry = find(id);
		if (entry != null) {
			entries.remove(entry);
		}
		return entry;
	}

	/**
	 * 服务改名后更新身份(升级时顺带改名): 保持稳定 id/别名/stub 端口不变,
	 * 仅把 winServiceName 指向新名, 并把新旧服务名指纹记入 altIds,
	 * 使 SCM 以新名重新发现时仍归并到同一条目(身份连续).
	 */
	public void renameServiceIdentity(String instanceId, String oldName, String newName) {
		NginxInstanceEntry entry = find(instanceId);
		if (entry == null || StrUtil.isEmpty(newName)) {
			return;
		}
		entry.setWinServiceName(newName.trim());
		String nfid = serviceFid(newName);
		if (!nfid.equals(entry.getId()) && !entry.getAltIds().contains(nfid)) {
			entry.getAltIds().add(nfid);
		}
		if (StrUtil.isNotEmpty(oldName)) {
			String ofid = serviceFid(oldName);
			if (!ofid.equals(entry.getId()) && !entry.getAltIds().contains(ofid)) {
				entry.getAltIds().add(ofid);
			}
		}
	}

	/**
	 * 把从忽略名单恢复的实例重新登记回注册表(保持原 fid 身份不变,
	 * 别名/升级历史/stub 端口延续).
	 *
	 * 可恢复条件(任一): 磁盘上 nginx exe 仍存在; Windows 服务仍注册在 SCM(停止态也算).
	 * 两者都不满足(目录与服务均已删除)时返回 null, 调用方只移出忽略名单即可.
	 * 已按 id/altId/exe+conf 存在同一条目时归并(补 altId)不新建.
	 */
	public NginxInstanceEntry restoreIgnored(NginxInstanceIgnore item) {
		if (item == null || StrUtil.isEmpty(item.getFid())) {
			return null;
		}
		NginxInstanceEntry existed = find(item.getFid());
		if (existed != null) {
			return existed;
		}

		String exe = canonical(item.getNginxExe());
		String conf = canonical(item.getConfPath());
		String prefix = canonical(item.getPrefixDir());
		boolean exeOk = StrUtil.isNotEmpty(exe) && new File(exe).isFile();
		boolean confOk = StrUtil.isEmpty(conf) || new File(conf).isFile();
		boolean prefixOk = StrUtil.isEmpty(prefix) || new File(prefix).isDirectory();

		WindowsNginxServiceUtils.WinNginxService svc = null;
		if (SystemTool.isWindows() && StrUtil.isNotEmpty(item.getServiceName())) {
			svc = WindowsNginxServiceUtils.find(item.getServiceName());
		}
		if (!exeOk && svc == null) {
			return null;
		}

		// 同 exe+conf 已存在条目(如共享程序目录的另一个指纹): 归并身份, 不新建
		if (StrUtil.isNotEmpty(exe)) {
			for (NginxInstanceEntry candidate : entries) {
				if (exe.equals(canonical(candidate.getNginxExe()))
						&& StrUtil.isNotEmpty(conf)
						&& conf.equals(canonical(candidate.getConfPath()))) {
					if (!candidate.getAltIds().contains(item.getFid())) {
						candidate.getAltIds().add(item.getFid());
					}
					return candidate;
				}
			}
		}

		NginxInstanceEntry entry = new NginxInstanceEntry();
		entry.setId(item.getFid());
		entry.setSource(SOURCE_MANUAL);
		String base = StrUtil.isNotEmpty(item.getName()) ? item.getName().trim()
				: defaultName(StrUtil.emptyToDefault(conf, exe), entries.size());
		entry.setName(uniqueName(base));
		entry.setStubPort(nextPort());
		entry.setStubUri(stubUriOf(entry.getStubPort()));
		if (exeOk) {
			entry.setNginxExe(exe);
		} else if (svc != null) {
			entry.setNginxExe(svc.nginxExe);
		}
		if (confOk && StrUtil.isNotEmpty(conf)) {
			entry.setConfPath(conf);
		} else if (svc != null && StrUtil.isNotEmpty(svc.confPath)) {
			entry.setConfPath(svc.confPath);
		}
		String effPrefix = prefixOk ? prefix : null;
		if (effPrefix == null && svc != null) {
			effPrefix = svc.prefixDir;
		}
		if (StrUtil.isNotEmpty(effPrefix)) {
			entry.setPrefixDir(effPrefix);
			entry.setNginxDir(effPrefix);
		}
		if (svc != null) {
			entry.setWinServiceName(svc.serviceName);
			String sfid = serviceFid(svc.serviceName);
			if (!sfid.equals(entry.getId()) && !entry.getAltIds().contains(sfid)) {
				entry.getAltIds().add(sfid);
			}
		}
		if (StrUtil.isNotEmpty(effPrefix) && StrUtil.isNotEmpty(entry.getConfPath())) {
			String cfid = fingerprint(effPrefix, entry.getConfPath());
			if (!cfid.equals(entry.getId()) && !entry.getAltIds().contains(cfid)) {
				entry.getAltIds().add(cfid);
			}
		}
		entry.setLastSeen(System.currentTimeMillis());
		entries.add(entry);
		return entry;
	}

	/** 保证别名不重复 */
	private String uniqueName(String base) {
		String name = base;
		int suffix = 2;
		while (nameTaken(name)) {
			name = base + "-" + suffix;
			suffix++;
		}
		return name;
	}

	private boolean nameTaken(String name) {
		for (NginxInstanceEntry entry : entries) {
			if (name.equalsIgnoreCase(entry.getName())) {
				return true;
			}
		}
		return false;
	}

	/** 新实例端口: 9527起, 取已分配端口最大值+1 */
	private int nextPort() {
		int port = BASE_STUB_PORT;
		for (NginxInstanceEntry entry : entries) {
			if (entry.getStubPort() != null && entry.getStubPort() >= port) {
				port = entry.getStubPort() + 1;
			}
		}
		return port;
	}

	/**
	 * 默认实例名: 首个注册实例为"默认实例"; 否则取配置文件名,
	 * nginx.conf则取prefix目录名.
	 */
	public static String defaultName(String confPath, int index) {
		if (index == 0) {
			return "默认实例";
		}
		File conf = new File(confPath);
		String fileName = conf.getName();
		int dot = fileName.lastIndexOf('.');
		String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
		if ("nginx".equalsIgnoreCase(stem)) {
			File confDir = conf.getParentFile();
			if (confDir != null) {
				File prefix = confDir.getParentFile();
				if (prefix != null && StrUtil.isNotEmpty(prefix.getName())) {
					return prefix.getName();
				}
			}
			return "nginx-" + (index + 1);
		}
		return stem;
	}

	public static String stubUriOf(int port) {
		return "/_nxs_" + port + "_stub_status";
	}

	/** 指纹/id: 任意特征串的md5前12位 */
	public static String idOf(String fingerprintKey) {
		return SecureUtil.md5(StrUtil.nullToEmpty(fingerprintKey)).substring(0, 12);
	}

	/**
	 * 实例组合指纹:
	 * 标准布局(prefix/conf/nginx.conf)退化为prefix单键 —— 不带-c启动/显式-c
	 * 指向同一标准配置都算同一身份;
	 * 自定义配置用"prefix + 配置相对prefix的位置"双键 —— 同一安装目录用不同配置
	 * 启动的多个实例仍可区分; prefix外的配置回退为绝对路径.
	 */
	public static String fingerprint(String nginxDir, String confPath) {
		String prefix = canonical(nginxDir);
		String conf = canonical(confPath);
		if (StrUtil.isEmpty(conf)) {
			return idOf("p:" + StrUtil.nullToEmpty(prefix));
		}
		if (StrUtil.isEmpty(prefix)) {
			return idOf("c:" + conf);
		}
		String standard = canonical(new File(nginxDir, "conf/nginx.conf").getPath());
		if (conf.equals(standard)) {
			return idOf("p:" + prefix);
		}
		String rel = relativize(prefix, conf);
		return idOf("p:" + prefix + "|c:" + (StrUtil.isNotEmpty(rel) ? rel : conf));
	}

	/** conf 相对 prefix 的相对路径(用/分隔); 不在prefix内或跨盘符时返回null */
	private static String relativize(String canonicalPrefix, String canonicalConf) {
		try {
			java.nio.file.Path rel = new File(canonicalPrefix).toPath()
					.relativize(new File(canonicalConf).toPath());
			if (rel.toString().startsWith("..")) {
				return null;
			}
			return rel.toString().replace('\\', '/');
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * 规范化路径(绝对路径/Windows大小写/8.3短名), 作为实例身份特征.
	 * Windows服务方式启动时命令行可能出现 PROGRA~1 形式的短路径,
	 * 不经展开会与资源管理器/命令行启动的长路径被误判为两个实例.
	 */
	public static String canonical(String path) {
		if (StrUtil.isEmpty(path)) {
			return "";
		}
		String normalized;
		try {
			normalized = new File(path).getCanonicalPath();
		} catch (Exception e) {
			normalized = FileUtil.normalize(path);
		}
		if (SystemTool.isWindows()) {
			normalized = expandLongPath(normalized);
			normalized = normalized.toLowerCase();
		}
		return normalized;
	}

	/**
	 * Windows 8.3短路径转长路径(Kernel32.GetLongPathNameW).
	 * 仅对含"~数字"短名段的路径调用, 结果缓存; 转换失败原样返回.
	 */
	private static String expandLongPath(String path) {
		if (path == null || path.indexOf('~') < 0 || !path.matches(".*[\\w\\]]~\\d.*")) {
			return path;
		}
		String cached = LONG_PATH_CACHE.get(path);
		if (cached != null) {
			return cached;
		}
		String result = path;
		try {
			char[] buffer = new char[4096];
			int len = WinKernel.INSTANCE.GetLongPathNameW(path, buffer, buffer.length);
			if (len > 0 && len < buffer.length) {
				result = new String(buffer, 0, len);
			}
		} catch (Throwable t) {
			// JNA不可用或调用失败时退化为原路径, 不影响检测
		}
		LONG_PATH_CACHE.put(path, result);
		return result;
	}

}
