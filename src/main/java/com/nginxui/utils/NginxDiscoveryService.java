package com.nginxui.utils;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nginxui.ext.NginxDashboard;
import com.nginxui.ext.NginxDashboardInstance;
import com.nginxui.ext.NginxDetectResult;
import com.nginxui.ext.NginxInfo;
import com.nginxui.ext.NginxInstanceEntry;
import com.nginxui.ext.NginxOverviewResult;
import com.nginxui.ext.NginxProc;

import cn.hutool.core.util.StrUtil;

/**
 * 发现模块: 进程/SCM 检测结果的实例归并、注册表合并、仪表盘聚合与多实例告警.
 */
public final class NginxDiscoveryService {

	private static final Logger LOG = LoggerFactory.getLogger(NginxDiscoveryService.class);

	private NginxDiscoveryService() {
	}

	/**
	 * 多实例仪表盘汇总(带忽略名单).
	 *
	 * @param settingConfPath 配置文件路径(设置项), 进程检测失败时兜底
	 * @param stubUrl         兼容老设置项的stub_status地址(仅首个实例生效)
	 * @param registryJson    已持久化的实例注册表JSON, null表示空表
	 * @param ignoreJson      已持久化的忽略名单JSON(nginxInstanceIgnoreV1), null表示不过滤
	 */
	public static NginxOverviewResult overview(String settingConfPath, String stubUrl, String registryJson,
			String ignoreJson) {
		NginxOverviewResult result = new NginxOverviewResult();
		NginxDashboard d = new NginxDashboard();
		NginxInstanceRegistry registry = NginxInstanceRegistry.load(registryJson);
		NginxInstanceIgnoreStore ignoreStore = NginxInstanceIgnoreStore.load(ignoreJson);

		try {
			NginxDetectResult detected = NginxUtils.detectAll();
			List<NginxInfo> live = detected.getInstances();
			int cores = Runtime.getRuntime().availableProcessors();
			d.setCoreCount(cores);

			List<NginxProc> allProcs = live.isEmpty() ? new ArrayList<NginxProc>()
					: NginxProbeService.sampleProcesses();

			// 同配置(同规范化conf路径)的多个master视为同一逻辑实例(重复启动), 合并pid;
			// 配置无法解析的组保持独立的临时实例, 不写入注册表.
			List<LiveGroup> groups = coalesceLive(live);

			// Windows: 枚举SCM中的nginx服务, 用包装器pid关联托管进程组,
			// 补全服务账户下读不到的exe/prefix/conf(原proc-*临时实例升级为正式实例);
			// 已停止的服务登记到注册表, 以停止实例展示并支持服务方式启停.
			List<WindowsNginxServiceUtils.WinNginxService> winSvcsRaw = SystemTool.isWindows()
					? WindowsNginxServiceUtils.list()
					: new ArrayList<WindowsNginxServiceUtils.WinNginxService>();
			// 忽略名单过滤(指纹/服务名/路径)
			List<WindowsNginxServiceUtils.WinNginxService> winSvcs =
					new ArrayList<WindowsNginxServiceUtils.WinNginxService>();
			for (WindowsNginxServiceUtils.WinNginxService svc : winSvcsRaw) {
				String sfid = NginxInstanceRegistry.serviceFid(svc.serviceName);
				String cfid = StrUtil.isNotEmpty(svc.confPath)
						? NginxInstanceRegistry.fingerprint(svc.prefixDir, svc.confPath) : null;
				if (!ignoreStore.matches(sfid, svc.serviceName, svc.nginxExe,
						svc.confPath, svc.prefixDir)
						&& (cfid == null || !ignoreStore.matches(cfid, svc.serviceName,
								svc.nginxExe, svc.confPath, svc.prefixDir))) {
					winSvcs.add(svc);
				}
			}
			if (!winSvcs.isEmpty()) {
				enrichByWindowsServices(groups, winSvcs);
				// 补全后可能出现同配置多组(命令行启动与服务托管并存), 重新按配置合并
				List<NginxInfo> enriched = new ArrayList<NginxInfo>();
				for (LiveGroup g : groups) {
					enriched.add(g.primary);
				}
				groups = coalesceLive(enriched);
			}

			// 第一轮: 为可解析组计算组合指纹, 并统计本轮 exe->指纹集合,
			// 供注册表区分"目录迁移/换启动方式"(同exe单指纹, 可归并)
			// 与"同目录不同配置多实例并存"(同exe多指纹, 禁止归并).
			String[] fids = new String[groups.size()];
			Map<String, Set<String>> liveExeFids = new java.util.HashMap<String, Set<String>>();
			for (int g = 0; g < groups.size(); g++) {
				NginxInfo info = groups.get(g).primary;
				if (StrUtil.isNotEmpty(info.getNginxPath()) && new File(info.getNginxPath()).isFile()) {
					String fid = NginxInstanceRegistry.fingerprint(info.getNginxDir(), info.getNginxPath());
					fids[g] = fid;
					String exe = NginxInstanceRegistry.canonical(info.getNginxExe());
					if (StrUtil.isNotEmpty(exe)) {
						Set<String> set = liveExeFids.get(exe);
						if (set == null) {
							set = new java.util.HashSet<String>();
							liveExeFids.put(exe, set);
						}
						set.add(fid);
					}
				}
			}

			List<NginxDashboardInstance> instances = new ArrayList<NginxDashboardInstance>();
			Set<String> liveIds = new java.util.HashSet<String>();
			List<String> duplicateWarnings = new ArrayList<String>();

			int index = 0;
			for (int g = 0; g < groups.size(); g++) {
				LiveGroup group = groups.get(g);
				NginxInfo info = group.primary;
				if (StrUtil.isNotEmpty(fids[g])) {
					if (ignoreStore.matches(fids[g], info.getWinServiceName(),
							info.getNginxExe(), info.getNginxPath(), info.getNginxDir())) {
						// 用户显式忽略: 不登记不展示, 下轮仍会被检测到但继续跳过
						continue;
					}
					NginxInstanceEntry entry = registry.acquire(fids[g], info, liveExeFids);
					if (!group.extraMasterPids.isEmpty()) {
						duplicateWarnings.add(I18n.t("dash.duplicateMaster", info.getNginxPath(),
								group.extraMasterPids.size() + 1, info.getMasterPid() + "、"
										+ StrUtil.join("、", group.extraMasterPids)));
					}
					// 兼容老设置项stubStatusUrl(完整URL), 仅首个实例覆盖探测地址
					String stubOverride = index == 0 ? stubUrl : null;
					instances.add(buildInstance(info, entry, cores, allProcs, stubOverride));
					liveIds.add(entry.getId());
				} else {
					instances.add(buildUnresolvedInstance(info, cores, allProcs));
				}
				index++;
			}

			// 已停止的Windows服务: 登记/刷新注册表条目, 随后统一按停止实例输出
			for (WindowsNginxServiceUtils.WinNginxService svc : winSvcs) {
				if (svc.running()) {
					// 运行中的服务应由上面的进程组登记; 无nginx进程组(启动失败)时跳过, 等下轮检测
					continue;
				}
				// 用户显式取消纳管(自动入忽略名单)的服务不自动复活
				String cfid = StrUtil.isNotEmpty(svc.confPath)
						? NginxInstanceRegistry.fingerprint(svc.prefixDir, svc.confPath) : null;
				if (ignoreStore.matches(cfid, svc.serviceName,
						svc.nginxExe, svc.confPath, svc.prefixDir)) {
					continue;
				}
				registry.acquireService(svc);
			}

			// 注册表里存在但当前无进程的实例: 停止态(忽略名单中的不展示)
			for (NginxInstanceEntry entry : registry.entries()) {
				if (liveIds.contains(entry.getId())) {
					continue;
				}
				if (ignoreStore.containsEntry(entry)) {
					continue;
				}
				instances.add(buildStoppedInstance(entry, cores));
			}

			// 进程归属标注 + 孤儿进程
			Set<String> ownedPids = new java.util.HashSet<String>();
			for (NginxDashboardInstance in : instances) {
				if (inProcesses(in.getProcesses())) {
					for (NginxProc p : in.getProcesses()) {
						ownedPids.add(p.getPid());
					}
				}
			}
			List<NginxProc> orphans = new ArrayList<NginxProc>();
			for (NginxProc p : allProcs) {
				if (ownedPids.contains(p.getPid())) {
					continue;
				}
				// nginx_service.exe等服务包装器是已知伴随进程, 不算异常残留
				if (StrUtil.nullToEmpty(p.getName()).toLowerCase().contains("service")) {
					p.setRole("other");
					continue;
				}
				p.setRole("orphan");
				orphans.add(p);
			}
			d.setOrphanProcesses(orphans);

			aggregate(d, instances, orphans, detected.getOrphanPids());
			List<String> warnings = d.getWarnings() == null ? new ArrayList<String>() : d.getWarnings();
			warnings.addAll(0, duplicateWarnings);
			d.setWarnings(warnings);
			d.setInstances(instances);

			result.setRegistryJson(registry.toJson());
		} catch (Exception e) {
			LOG.error("[{}] 仪表盘采集失败", NginxErrorCode.INTERNAL_ERROR, e);
			d.setMessage(NginxErrorCode.message(NginxErrorCode.INTERNAL_ERROR, e.getMessage()));
			result.setRegistryJson(registry.toJson());
		}

		d.setLastUpdate(cn.hutool.core.date.DateUtil.format(new java.util.Date(), "yyyy-MM-dd HH:mm:ss"));
		result.setDashboard(d);
		return result;
	}

	/**
	 * 单实例详情: 复用主窗口本轮已采集的进程/SCM 数据, 不再重复全量检测.
	 *
	 * @param entry   注册表条目
	 * @param running 当前是否运行(取自主窗口本轮刷新结果)
	 * @param live    对应的运行实例(可空: 服务已停止/首轮检测未完成)
	 * @param svc     对应的Windows服务(可空)
	 */
	public static NginxDashboardInstance detailOf(NginxInstanceEntry entry, boolean running,
			NginxInfo live, WindowsNginxServiceUtils.WinNginxService svc) {
		int cores = Runtime.getRuntime().availableProcessors();
		NginxDashboardInstance in = new NginxDashboardInstance();
		in.setId(entry.getId());
		in.setName(entry.getName());
		in.setSource(entry.getSource());
		in.setCoreCount(cores);
		in.setWinServiceName(StrUtil.emptyToDefault(entry.getWinServiceName(),
				svc == null ? null : svc.serviceName));
		in.setLastSeen(entry.getLastSeen());
		in.setLastMasterPid(entry.getLastMasterPid());

		String exe = firstNonEmpty(live == null ? null : live.getNginxExe(),
				entry.getNginxExe(), svc == null ? null : svc.nginxExe);
		String dir = firstNonEmpty(live == null ? null : live.getNginxDir(),
				entry.getPrefixDir(), entry.getNginxDir(), svc == null ? null : svc.prefixDir);
		String confPath = firstExisting(entry.getConfPath(),
				live == null ? null : live.getNginxPath(), svc == null ? null : svc.confPath);
		if (StrUtil.isEmpty(dir) && StrUtil.isNotEmpty(exe)) {
			dir = new File(exe).getParent();
		}

		in.setRunning(running);
		in.setStatus(running ? "running" : "stopped");
		in.setNginxExe(exe);
		in.setNginxDir(dir);
		in.setNginxPath(confPath);
		if (running) {
			in.setPids(live == null ? null : live.getPids());
			in.setMasterPid(live == null ? null : live.getMasterPid());
			if (StrUtil.isNotEmpty(in.getMasterPid())) {
				in.setLastMasterPid(in.getMasterPid());
			}
			List<NginxProc> procs = procsOf(in.getPids(), entry, in.getName());
			in.setProcesses(procs);
			NginxProbeService.fillProcessCounts(in, procs);
			if (live != null && StrUtil.isNotEmpty(live.getVersion())) {
				in.setVersion(live.getVersion());
			}
		} else {
			in.setProcesses(new ArrayList<NginxProc>());
		}

		// 编译信息/版本(按exe缓存, 首次约300ms, 之后为0)
		NginxInfo probe = new NginxInfo();
		probe.setNginxExe(exe);
		NginxProbeService.fillBuildInfo(in, probe);

		int stubPort = entry.getStubPort() == null
				? NginxInstanceRegistry.BASE_STUB_PORT : entry.getStubPort();
		String stubUri = StrUtil.isNotEmpty(entry.getStubUri()) ? entry.getStubUri()
				: NginxInstanceRegistry.stubUriOf(stubPort);
		String confText = null;
		if (StrUtil.isNotEmpty(confPath) && new File(confPath).isFile()) {
			confText = NginxConfigService.readConfig(new File(confPath));
			if (confText != null) {
				NginxConfigService.fillConfConfig(in, confText, cores);
				in.setListenPorts(NginxConfigService.extractListenPorts(confText));
			}
		}
		// 配置自身声明的stub端口优先于注册表分配值(与buildInstance口径一致)
		NginxConfigService.StubDecl decl = NginxConfigService.detectStubDecl(confText);
		if (decl != null) {
			stubPort = decl.port;
			stubUri = StrUtil.isNotEmpty(decl.uri) ? decl.uri
					: NginxInstanceRegistry.stubUriOf(stubPort);
			entry.setStubPort(stubPort);
			entry.setStubUri(stubUri);
		}
		in.setStubPort(stubPort);
		in.setStubUri(stubUri);
		in.setStubUrl("http://127.0.0.1:" + stubPort + stubUri);

		boolean applicable = decl != null;
		boolean monitorOn = entry.getStubMonitor() == null ? applicable : entry.getStubMonitor();
		in.setStubApplicable(applicable);
		in.setStubManaged(NginxConfigService.isStubManaged(confText));
		in.setStubMonitor(monitorOn);
		boolean owned = running && monitorOn
				&& (decl != null || NginxConfigService.listenDeclares(in.getListenPorts(), stubPort));
		in.setStubOwned(owned);
		if (owned) {
			NginxProbeService.fillStubStatus(in, in.getStubUrl());
		}
		in.setLastUpdate(cn.hutool.core.date.DateUtil.format(new java.util.Date(), "yyyy-MM-dd HH:mm:ss"));
		return in;
	}

	/** 由检测分组的pid列表还原进程清单(master在首位, 其余为worker) */
	private static List<NginxProc> procsOf(String pidsCsv, NginxInstanceEntry entry, String instanceName) {
		List<NginxProc> procs = new ArrayList<NginxProc>();
		List<String> pids = splitCsv(pidsCsv);
		for (int i = 0; i < pids.size(); i++) {
			NginxProc p = new NginxProc();
			p.setPid(pids.get(i));
			p.setName("nginx.exe");
			p.setRole(i == 0 ? "master" : "worker");
			p.setInstanceId(entry.getId());
			p.setInstanceName(instanceName);
			procs.add(p);
		}
		return procs;
	}

	private static String firstNonEmpty(String... values) {
		for (String v : values) {
			if (StrUtil.isNotEmpty(v)) {
				return v;
			}
		}
		return null;
	}

	private static String firstExisting(String... paths) {
		for (String p : paths) {
			if (StrUtil.isNotEmpty(p) && new File(p).isFile()) {
				return p;
			}
		}
		return null;
	}

	private static boolean inProcesses(List<NginxProc> procs) {
		return procs != null && !procs.isEmpty();
	}

	/**
	 * 构建单个运行实例的仪表盘数据
	 */
	private static NginxDashboardInstance buildInstance(NginxInfo info, NginxInstanceEntry entry, int cores,
			List<NginxProc> allProcs, String stubOverride) {
		NginxDashboardInstance in = new NginxDashboardInstance();
		in.setId(entry.getId());
		in.setName(entry.getName());
		in.setStatus("running");
		in.setLastMasterPid(info.getMasterPid());
		in.setLastSeen(entry.getLastSeen());
		in.setWinServiceName(entry.getWinServiceName());
		in.setSource(entry.getSource());

		in.setRunning(true);
		in.setPids(info.getPids());
		in.setMasterPid(info.getMasterPid());
		in.setNginxExe(info.getNginxExe());
		in.setNginxDir(info.getNginxDir());
		in.setNginxPath(info.getNginxPath());
		in.setVersion(info.getVersion());
		in.setCoreCount(cores);

		// 该实例的进程(按检测分组pid集合匹配采样结果)
		Set<String> pidSet = new java.util.HashSet<String>(splitCsv(info.getPids()));
		List<NginxProc> mine = new ArrayList<NginxProc>();
		for (NginxProc p : allProcs) {
			if (pidSet.contains(p.getPid())) {
				p.setInstanceId(entry.getId());
				p.setInstanceName(entry.getName());
				mine.add(p);
			}
		}
		in.setProcesses(mine);
		NginxProbeService.fillProcessCounts(in, mine);

		NginxProbeService.fillBuildInfo(in, info);

		String confPath = StrUtil.isNotEmpty(info.getNginxPath()) ? info.getNginxPath() : entry.getConfPath();
		String confText = null;
		if (StrUtil.isNotEmpty(confPath) && new File(confPath).isFile()) {
			in.setNginxPath(confPath);
			confText = NginxConfigService.readConfig(new File(confPath));
			if (confText != null) {
				NginxConfigService.fillConfConfig(in, confText, cores);
				in.setListenPorts(NginxConfigService.extractListenPorts(confText));
			}
		}

		// 配置自身声明的stub端口优先于注册表分配值: 手工在conf里写过 stub_status 的
		// 实例, 分配端口常与实际监听端口不一致 —— 以分配值为准会既显示错误端口,
		// 又因该端口不在本实例配置中而采不到数据. 检测到时同步回写注册表.
		NginxConfigService.StubDecl decl = NginxConfigService.detectStubDecl(confText);
		int stubPort;
		String stubUri;
		if (decl != null) {
			stubPort = decl.port;
			stubUri = StrUtil.isNotEmpty(decl.uri) ? decl.uri : NginxInstanceRegistry.stubUriOf(stubPort);
			entry.setStubPort(stubPort);
			entry.setStubUri(stubUri);
		} else {
			stubPort = entry.getStubPort() == null ? NginxInstanceRegistry.BASE_STUB_PORT : entry.getStubPort();
			stubUri = StrUtil.isNotEmpty(entry.getStubUri()) ? entry.getStubUri()
					: NginxInstanceRegistry.stubUriOf(stubPort);
		}
		in.setStubPort(stubPort);
		in.setStubUri(stubUri);
		String stubUrl = StrUtil.isNotEmpty(stubOverride) ? stubOverride
				: "http://127.0.0.1:" + stubPort + stubUri;
		in.setStubUrl(stubUrl);

		// 监控开关为"展示/采集"语义: 配置中存在stub声明才可操作; 未显式设定时默认跟随"配置中是否有声明"
		boolean applicable = decl != null;
		boolean monitorOn = entry.getStubMonitor() == null ? applicable : entry.getStubMonitor();
		in.setStubApplicable(applicable);
		in.setStubManaged(NginxConfigService.isStubManaged(confText));
		in.setStubMonitor(monitorOn);

		// 仅当本实例配置自身声明了该端口监听才采信stub数据, 避免探测到同机其他实例
		boolean owned = monitorOn && (decl != null
				|| NginxConfigService.listenDeclares(in.getListenPorts(), stubPort)
				|| (StrUtil.isNotEmpty(stubOverride) && in.getListenPorts() != null
						&& !in.getListenPorts().isEmpty()));
		in.setStubOwned(owned);
		if (owned) {
			NginxProbeService.fillStubStatus(in, stubUrl);
		} else {
			in.setStubEnabled(false);
			in.setMessage(monitorOn ? I18n.t("dash.stubNotEnabled", stubPort)
					: I18n.t("dash.stubMonitorOff"));
		}
		return in;
	}

	/**
	 * 构建停止态实例: 身份/别名/配置保留, 指标为空; 配置文件可读时仍解析容量与监听.
	 */
	private static NginxDashboardInstance buildStoppedInstance(NginxInstanceEntry entry, int cores) {
		NginxDashboardInstance in = new NginxDashboardInstance();
		in.setId(entry.getId());
		in.setName(entry.getName());
		in.setStatus("stopped");
		in.setRunning(false);
		in.setNginxExe(entry.getNginxExe());
		in.setNginxDir(entry.getNginxDir());
		in.setNginxPath(entry.getConfPath());
		in.setStubPort(entry.getStubPort());
		in.setStubUri(entry.getStubUri());
		in.setStubUrl(stubUrlOf(entry));
		in.setLastMasterPid(entry.getLastMasterPid());
		in.setLastSeen(entry.getLastSeen());
		in.setWinServiceName(entry.getWinServiceName());
		in.setSource(entry.getSource());
		in.setCoreCount(cores);
		in.setProcesses(new ArrayList<NginxProc>());

		boolean applicable = false;
		boolean managed = false;
		if (StrUtil.isNotEmpty(entry.getConfPath()) && new File(entry.getConfPath()).isFile()) {
			String conf = NginxConfigService.readConfig(new File(entry.getConfPath()));
			if (conf != null) {
				NginxConfigService.fillConfConfig(in, conf, cores);
				in.setListenPorts(NginxConfigService.extractListenPorts(conf));
				// 停止态同样以配置声明为准, 保证与运行态的展示口径一致
				NginxConfigService.StubDecl decl = NginxConfigService.detectStubDecl(conf);
				applicable = decl != null;
				managed = NginxConfigService.isStubManaged(conf);
				if (decl != null) {
					String uri = StrUtil.isNotEmpty(decl.uri) ? decl.uri
							: NginxInstanceRegistry.stubUriOf(decl.port);
					in.setStubPort(decl.port);
					in.setStubUri(uri);
					in.setStubUrl("http://127.0.0.1:" + decl.port + uri);
					entry.setStubPort(decl.port);
					entry.setStubUri(uri);
				}
			}
		}
		in.setStubApplicable(applicable);
		in.setStubManaged(managed);
		in.setStubMonitor(entry.getStubMonitor() == null ? applicable : entry.getStubMonitor());
		return in;
	}

	/** 运行中检测组的逻辑合并结果: 主实例信息 + 同配置冗余master的pid */
	private static class LiveGroup {
		NginxInfo primary;
		List<String> extraMasterPids = new ArrayList<String>();
	}

	/**
	 * 按规范化配置路径合并重复启动的master组; 配置不可解析的组各自独立.
	 * 同配置组合并时保留能读到exe路径的组为主, pid取并集.
	 */
	private static List<LiveGroup> coalesceLive(List<NginxInfo> live) {
		List<LiveGroup> ordered = new ArrayList<LiveGroup>();
		Map<String, LiveGroup> byConf = new LinkedHashMap<String, LiveGroup>();

		for (NginxInfo info : live) {
			String key = StrUtil.isNotEmpty(info.getNginxPath())
					? NginxInstanceRegistry.canonical(info.getNginxPath())
					: null;

			if (StrUtil.isEmpty(key) || !new File(info.getNginxPath()).isFile()) {
				LiveGroup g = new LiveGroup();
				g.primary = info;
				ordered.add(g);
				continue;
			}

			LiveGroup existing = byConf.get(key);
			if (existing == null) {
				LiveGroup g = new LiveGroup();
				g.primary = info;
				byConf.put(key, g);
				ordered.add(g);
			} else {
				// 优先以能读到exe的组为主
				boolean preferNew = StrUtil.isEmpty(existing.primary.getNginxExe())
						&& StrUtil.isNotEmpty(info.getNginxExe());
				NginxInfo keep = preferNew ? info : existing.primary;
				NginxInfo other = preferNew ? existing.primary : info;
				if (preferNew) {
					existing.extraMasterPids.add(existing.primary.getMasterPid());
					existing.primary = info;
				} else {
					existing.extraMasterPids.add(info.getMasterPid());
				}
				// pid并集
				Set<String> pids = new LinkedHashSet<String>(splitCsv(keep.getPids()));
				pids.addAll(splitCsv(other.getPids()));
				keep.setPids(StrUtil.join(",", pids));
			}
		}
		return ordered;
	}

	/**
	 * 用SCM服务信息补全Windows进程组:
	 * master父pid命中服务包装器pid即归属该服务;
	 * 兜底: 恰有一个运行服务且恰有一个配置未解析组时直接归并.
	 */
	private static void enrichByWindowsServices(List<LiveGroup> groups,
			List<WindowsNginxServiceUtils.WinNginxService> winSvcs) {
		Map<String, WindowsNginxServiceUtils.WinNginxService> byWrapperPid =
				new java.util.HashMap<String, WindowsNginxServiceUtils.WinNginxService>();
		List<WindowsNginxServiceUtils.WinNginxService> running =
				new ArrayList<WindowsNginxServiceUtils.WinNginxService>();
		for (WindowsNginxServiceUtils.WinNginxService svc : winSvcs) {
			if (svc.running() && svc.wrapperPid > 0) {
				byWrapperPid.put(String.valueOf(svc.wrapperPid), svc);
				running.add(svc);
			}
		}

		List<NginxInfo> unresolved = new ArrayList<NginxInfo>();
		Set<String> claimedServiceNames = new java.util.HashSet<String>();
		for (LiveGroup group : groups) {
			NginxInfo info = group.primary;
			WindowsNginxServiceUtils.WinNginxService svc = StrUtil.isNotEmpty(info.getMasterPpid())
					? byWrapperPid.get(info.getMasterPpid())
					: null;
			if (svc != null) {
				applyWindowsService(info, svc);
				claimedServiceNames.add(svc.serviceName);
			} else if (StrUtil.isEmpty(info.getNginxPath())
					|| !new File(info.getNginxPath()).isFile()) {
				unresolved.add(info);
			}
		}

		// 兜底: 唯一运行服务 + 唯一未解析组(包装器pid读取失败的服务托管场景)
		if (unresolved.size() == 1 && running.size() == 1) {
			WindowsNginxServiceUtils.WinNginxService only = running.get(0);
			if (!claimedServiceNames.contains(only.serviceName)) {
				applyWindowsService(unresolved.get(0), only);
			}
		}
	}

	/** 用服务元数据补全实例检测信息(已有有效值不覆盖) */
	private static void applyWindowsService(NginxInfo info,
			WindowsNginxServiceUtils.WinNginxService svc) {
		info.setWinServiceName(svc.serviceName);
		if (StrUtil.isEmpty(info.getNginxExe()) || !new File(info.getNginxExe()).isFile()) {
			info.setNginxExe(svc.nginxExe);
		}
		if (StrUtil.isEmpty(info.getNginxDir()) || !new File(info.getNginxDir()).isDirectory()) {
			info.setNginxDir(svc.prefixDir);
		}
		if ((StrUtil.isEmpty(info.getNginxPath()) || !new File(info.getNginxPath()).isFile())
				&& StrUtil.isNotEmpty(svc.confPath)) {
			info.setNginxPath(svc.confPath);
		}
		if (StrUtil.isEmpty(info.getVersion())) {
			NginxProbeService.fillVersionByExe(info, svc.nginxExe);
		}
	}

	/**
	 * 配置无法解析的运行实例(如服务托管进程读不到路径): 给临时身份,
	 * 只展示进程/资源, 不持久化到注册表, 也无法开关stub_status.
	 */
	private static NginxDashboardInstance buildUnresolvedInstance(NginxInfo info, int cores,
			List<NginxProc> allProcs) {
		NginxDashboardInstance in = new NginxDashboardInstance();
		String transientId = "proc-" + info.getMasterPid();
		String transientName = I18n.t("dash.unknownInstance", info.getMasterPid());
		in.setId(transientId);
		in.setName(transientName);
		in.setStatus("running");
		in.setRunning(true);
		in.setPids(info.getPids());
		in.setMasterPid(info.getMasterPid());
		in.setNginxExe(info.getNginxExe());
		in.setNginxDir(info.getNginxDir());
		in.setVersion(info.getVersion());
		in.setCoreCount(cores);
		in.setMessage(I18n.t("dash.unresolvedInstance"));
		// 临时实例不落注册表, 监控开关与配置写入均不可用
		in.setStubApplicable(false);
		in.setStubManaged(false);
		in.setStubMonitor(false);

		Set<String> pidSet = new java.util.HashSet<String>(splitCsv(info.getPids()));
		List<NginxProc> mine = new ArrayList<NginxProc>();
		for (NginxProc p : allProcs) {
			if (pidSet.contains(p.getPid())) {
				p.setInstanceId(transientId);
				p.setInstanceName(transientName);
				mine.add(p);
			}
		}
		in.setProcesses(mine);
		NginxProbeService.fillProcessCounts(in, mine);
		return in;
	}

	private static String stubUrlOf(NginxInstanceEntry entry) {
		int port = entry.getStubPort() == null ? NginxInstanceRegistry.BASE_STUB_PORT : entry.getStubPort();
		String uri = StrUtil.isNotEmpty(entry.getStubUri()) ? entry.getStubUri()
				: NginxInstanceRegistry.stubUriOf(port);
		return "http://127.0.0.1:" + port + uri;
	}

	private static List<String> splitCsv(String csv) {
		List<String> list = new ArrayList<String>();
		if (StrUtil.isEmpty(csv)) {
			return list;
		}
		for (String p : csv.split(",")) {
			if (StrUtil.isNotEmpty(p.trim())) {
				list.add(p.trim());
			}
		}
		return list;
	}

	/**
	 * 聚合所有实例的总量指标, 并产出多实例告警.
	 */
	private static void aggregate(NginxDashboard d, List<NginxDashboardInstance> instances, List<NginxProc> orphans,
			List<String> orphanPids) {
		int procTotal = 0;
		int masterCount = 0;
		int workerCount = 0;
		int otherCount = 0;
		long maxConn = 0;
		long active = 0;
		long accepts = 0;
		long handled = 0;
		long requests = 0;
		long reading = 0;
		long writing = 0;
		long waiting = 0;
		int runningCount = 0;
		int stoppedCount = 0;
		boolean allStubEnabled = !instances.isEmpty();

		Set<String> versions = new LinkedHashSet<String>();
		Set<String> exes = new LinkedHashSet<String>();
		Set<String> dirs = new LinkedHashSet<String>();
		Set<String> confs = new LinkedHashSet<String>();
		List<String> allPids = new ArrayList<String>();
		String singleMasterPid = null;

		for (NginxDashboardInstance in : instances) {
			if ("running".equals(in.getStatus())) {
				runningCount++;
				procTotal += nz(in.getProcTotal());
				masterCount += nz(in.getMasterCount());
				workerCount += nz(in.getWorkerCount());
				otherCount += nz(in.getOtherCount());
				maxConn += in.getMaxConnections() == null ? 0 : in.getMaxConnections();
				if (Boolean.TRUE.equals(in.getStubEnabled())) {
					active += nl(in.getActiveConnections());
					accepts += nl(in.getAccepts());
					handled += nl(in.getHandled());
					requests += nl(in.getRequests());
					reading += nl(in.getReading());
					writing += nl(in.getWriting());
					waiting += nl(in.getWaiting());
				} else {
					allStubEnabled = false;
				}
				if (StrUtil.isNotEmpty(in.getVersion())) {
					versions.add(in.getVersion());
				}
				if (StrUtil.isNotEmpty(in.getNginxExe())) {
					exes.add(in.getNginxExe());
				}
				if (StrUtil.isNotEmpty(in.getNginxDir())) {
					dirs.add(in.getNginxDir());
				}
				if (StrUtil.isNotEmpty(in.getNginxPath())) {
					confs.add(in.getNginxPath());
				}
				allPids.addAll(splitCsv(in.getPids()));
				singleMasterPid = runningCount == 1 ? in.getMasterPid() : null;
			} else {
				stoppedCount++;
				allStubEnabled = false;
			}
		}

		d.setRunning(runningCount > 0);
		d.setPids(StrUtil.join(",", allPids));
		d.setMasterPid(runningCount == 1 ? singleMasterPid : null);
		d.setNginxExe(exes.size() == 1 ? exes.iterator().next() : null);
		d.setNginxDir(dirs.size() == 1 ? dirs.iterator().next() : null);
		d.setNginxPath(confs.size() == 1 ? confs.iterator().next() : null);
		d.setVersion(versions.size() == 1 ? versions.iterator().next() : null);

		d.setProcTotal(procTotal);
		d.setMasterCount(masterCount);
		d.setWorkerCount(workerCount);
		d.setOtherCount(otherCount);
		if (maxConn > 0) {
			d.setMaxConnections(maxConn);
		}

		d.setStubEnabled(runningCount > 0 && allStubEnabled);
		if (active > 0 || allStubEnabled) {
			d.setActiveConnections(active);
			d.setAccepts(accepts);
			d.setHandled(handled);
			d.setRequests(requests);
			d.setReading(reading);
			d.setWriting(writing);
			d.setWaiting(waiting);
			if (maxConn > 0) {
				d.setConnUsagePercent(NginxExecUtils.round(active * 100.0 / maxConn, 2));
			}
			if (handled > 0) {
				d.setReqPerConn(NginxExecUtils.round(requests * 1.0 / handled, 2));
			}
		}

		d.setInstanceCount(instances.size());
		d.setRunningCount(runningCount);
		d.setStoppedCount(stoppedCount);

		List<String> warnings = new ArrayList<String>();
		if (versions.size() > 1) {
			warnings.add(I18n.t("dash.versionMismatch", StrUtil.join("、", versions)));
		}
		warnings.addAll(listenConflictWarnings(instances));
		for (NginxDashboardInstance in : instances) {
			if ("stopped".equals(in.getStatus())) {
				warnings.add(I18n.t("dash.instanceStopped", in.getName(),
						StrUtil.nullToEmpty(in.getNginxPath())));
			}
		}
		if (!orphans.isEmpty() || (orphanPids != null && !orphanPids.isEmpty())) {
			List<String> ids = new ArrayList<String>();
			for (NginxProc p : orphans) {
				ids.add(p.getPid());
			}
			for (String pid : orphanPids) {
				if (!ids.contains(pid)) {
					ids.add(pid);
				}
			}
			warnings.add(I18n.t("dash.orphanProcesses", ids.size(), StrUtil.join(",", ids)));
		}
		d.setWarnings(warnings);
	}

	private static int nz(Integer value) {
		return value == null ? 0 : value;
	}

	private static long nl(Long value) {
		return value == null ? 0L : value;
	}

	/**
	 * 监听端口冲突检测: 同端口被多个实例监听, 或通配地址(* / 0.0.0.0 / [::])与任意具体地址并存.
	 */
	private static List<String> listenConflictWarnings(List<NginxDashboardInstance> instances) {
		List<String> warnings = new ArrayList<String>();
		// port -> 监听条目
		Map<String, List<String>> portNames = new LinkedHashMap<String, List<String>>();
		Map<String, Boolean> portWildcard = new java.util.HashMap<String, Boolean>();

		for (NginxDashboardInstance in : instances) {
			if (!"running".equals(in.getStatus()) || in.getListenPorts() == null) {
				continue;
			}
			Set<String> portsOfInstance = new java.util.HashSet<String>();
			boolean wildcardOfInstance = false;
			for (String raw : in.getListenPorts()) {
				String[] socket = NginxConfigService.parseListen(raw);
				if (socket == null) {
					continue;
				}
				portsOfInstance.add(socket[1]);
				if ("*".equals(socket[0])) {
					wildcardOfInstance = true;
				}
			}
			for (String port : portsOfInstance) {
				List<String> names = portNames.get(port);
				if (names == null) {
					names = new ArrayList<String>();
					portNames.put(port, names);
					portWildcard.put(port, false);
				}
				names.add(in.getName() + (wildcardOfInstance ? "(*)" : ""));
				if (wildcardOfInstance) {
					portWildcard.put(port, true);
				}
			}
		}

		for (Map.Entry<String, List<String>> e : portNames.entrySet()) {
			List<String> names = e.getValue();
			// 去掉实例名重复(同实例多地址只算一次)
			Set<String> uniq = new LinkedHashSet<String>(names);
			String plain = e.getKey().split("/")[0];
			if (uniq.size() > 1) {
				warnings.add(I18n.t("dash.listenConflict", plain, StrUtil.join("、", uniq),
						Boolean.TRUE.equals(portWildcard.get(e.getKey()))
								? I18n.t("dash.listenWildcard") : ""));
			}
		}
		return warnings;
	}
}
