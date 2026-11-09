package com.nginxui.utils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.nginxui.ext.NginxDashboard;
import com.nginxui.ext.NginxDashboardInstance;
import com.nginxui.ext.NginxInfo;
import com.nginxui.ext.NginxInstanceEntry;
import com.nginxui.ext.NginxOverviewResult;
import com.nginxui.ext.NginxProc;

import cn.hutool.core.util.StrUtil;

/**
 * 仪表盘能力的兼容门面.
 *
 * 原 2600+ 行实现已按职责拆分为四个模块:
 * <ul>
 * <li>{@link NginxDiscoveryService} 发现: 实例归并/注册表合并/仪表盘聚合</li>
 * <li>{@link NginxControlService} 控制: start/stop/restart 与服务托管控制</li>
 * <li>{@link NginxConfigService} 配置: 配置解析/stub 块写入/nginx -t 门禁</li>
 * <li>{@link NginxProbeService} 探针: 进程采样/编译信息/stub_status 采集</li>
 * </ul>
 * 保留本类以维持既有 UI 调用方式不变; 新代码建议直接依赖对应模块.
 * 结果对象统一携带 code 错误码(见 {@link NginxErrorCode}).
 */
public final class NginxDashboardUtils {

	public static final String MARK_BEGIN = "# >>> nginxUI stub_status >>>";
	public static final String MARK_END = "# <<< nginxUI stub_status <<<";
	/** 旧品牌标记(nginxWebUI): 仅用于兼容识别与清理历史写入的 stub_status 块 */
	public static final String LEGACY_MARK_BEGIN = "# >>> nginxWebUI stub_status >>>";
	public static final String LEGACY_MARK_END = "# <<< nginxWebUI stub_status <<<";

	public static final String DEFAULT_STUB_PORT = "9527";
	public static final String DEFAULT_STUB_URI = "/_nxs_9527_stub_status";

	private NginxDashboardUtils() {
	}

	/** 配置中是否含本系统写入的 stub_status 标记块(兼容新旧品牌标记) */
	public static boolean isStubManaged(String confText) {
		return NginxConfigService.isStubManaged(confText);
	}

	/**
	 * 单实例兼容入口(老界面/老调用): 不读写实例注册表, 运行结果为多实例时返回聚合视图.
	 */
	public static NginxDashboard overview(String settingConfPath, String stubUrl) {
		return overview(settingConfPath, stubUrl, null, null).getDashboard();
	}

	/** 多实例仪表盘汇总: 进程按master分组 → 注册表合并 → 逐实例采集 → 聚合总量与告警. */
	public static NginxOverviewResult overview(String settingConfPath, String stubUrl, String registryJson) {
		return overview(settingConfPath, stubUrl, registryJson, null);
	}

	/** 多实例仪表盘汇总(带忽略名单). */
	public static NginxOverviewResult overview(String settingConfPath, String stubUrl, String registryJson,
			String ignoreJson) {
		return NginxDiscoveryService.overview(settingConfPath, stubUrl, registryJson, ignoreJson);
	}

	/** 单实例详情: 复用主窗口本轮已采集的进程/SCM 数据, 不再重复全量检测. */
	public static NginxDashboardInstance detailOf(NginxInstanceEntry entry, boolean running,
			NginxInfo live, WindowsNginxServiceUtils.WinNginxService svc) {
		return NginxDiscoveryService.detailOf(entry, running, live, svc);
	}

	/** 读取实例主配置中声明的监听端口(只解析主配置, 不触发检测); 无配置时返回空列表. */
	public static List<String> listenPortsOf(NginxInstanceEntry entry) {
		if (entry == null) {
			return new ArrayList<String>();
		}
		String conf = entry.getConfPath();
		if (StrUtil.isEmpty(conf)) {
			return new ArrayList<String>();
		}
		File file = new File(conf);
		if (!file.isFile()) {
			return new ArrayList<String>();
		}
		String text = NginxConfigService.readConfig(file);
		return text == null ? new ArrayList<String>() : NginxConfigService.extractListenPorts(text);
	}

	/** 进程明细采样(探针模块). */
	public static List<NginxProc> sampleProcesses() {
		return NginxProbeService.sampleProcesses();
	}

	/** stub监控配置操作结果(error为null表示成功) */
	public static class StubConfigResult {
		/** 统一错误码(成功为null) */
		public String code;
		public String error;
		/** 生效端口(纳管时为配置声明端口, 写入时为自动分配端口) */
		public Integer port;
		public String uri;
		/** 配置中已存在stub声明, 直接纳管未改写配置 */
		public boolean adopted;
		/** 是否执行了重启使其生效 */
		public boolean applied;
		/** 生效方式: restart (未运行时为null, 下次启动生效) */
		public String appliedBy;
	}

	/** 写入stub_status监控配置(显式动作, 非开关副作用) */
	public static synchronized StubConfigResult writeStubConfig(NginxInstanceEntry entry) {
		return NginxConfigService.writeStubConfig(entry);
	}

	/** 移除本系统写入的受标记stub块(绝不触碰手工声明的块), 测试通过后生效 */
	public static synchronized StubConfigResult removeStubConfig(NginxInstanceEntry entry) {
		return NginxConfigService.removeStubConfig(entry);
	}

	/** 参数优化: 调整worker_processes/worker_connections, 测试并生效 */
	public static String tune(NginxInstanceEntry entry, String workerProcesses, Integer workerConnections) {
		return NginxConfigService.tune(entry, workerProcesses, workerConnections);
	}

	/** nginx是否编译了 http_stub_status_module */
	public static boolean supportsStubStatus(String nginxExe, String prefixDir) {
		return NginxConfigService.supportsStubStatus(nginxExe, prefixDir);
	}

	/** stub端口是否空闲 */
	public static boolean isStubPortFree(int port) {
		return NginxConfigService.isStubPortFree(port);
	}

	/** 从from起向后查找第一个空闲TCP端口(最多尝试20个), 全占用返回-1 */
	public static int nextFreePort(int from) {
		return NginxConfigService.nextFreePort(from);
	}

	/** 实例生命周期操作结果 */
	public static class ControlResult {
		/** 统一错误码(成功为null) */
		public String code;
		public boolean success;
		public String message;
		public boolean running;
		public String masterPid;
		/** 操作成功但存在需用户知晓的告警(如启动前配置检测未通过) */
		public boolean warning;
	}

	/** nginx -t 配置检测结果 */
	public static class ConfigTestResult {
		/** 统一错误码(成功为null) */
		public String code;
		public boolean success;
		/** nginx -t 原始输出(syntax/test 两行或 emerg 错误明细) */
		public String output;
		/** 失败时的原因归类(成功为空) */
		public String message;
	}

	/** 执行 nginx -t 检测实例配置语法与文件可达性(不影响运行中的实例). */
	public static synchronized ConfigTestResult testInstanceConfig(NginxInstanceEntry entry) {
		return NginxConfigService.testInstanceConfig(entry);
	}

	/** 用指定可执行文件对实例配置执行 nginx -t. */
	public static synchronized ConfigTestResult testInstanceConfigWithExe(NginxInstanceEntry entry, String exePath) {
		return NginxConfigService.testInstanceConfigWithExe(entry, exePath);
	}

	/** 用给定文本临时落盘到实例配置同目录并执行 nginx -t(不改动真实配置). */
	public static synchronized ConfigTestResult testConfigContent(NginxInstanceEntry entry, String content) {
		return NginxConfigService.testConfigContent(entry, content);
	}

	/** 实例生命周期控制(start/stop/restart), 串行执行避免信号交叉. */
	public static synchronized ControlResult controlInstance(NginxInstanceEntry entry, String action) {
		return NginxControlService.controlInstance(entry, action);
	}

	/** 实例当前是否在运行: 服务托管以 SCM 状态为准, 其余以进程检测为准. */
	public static boolean isInstanceRunning(NginxInstanceEntry entry) {
		return NginxControlService.isInstanceRunning(entry);
	}

	/** 清除stub_status不可达负缓存(开关切换后下一轮立即重新探测) */
	public static void forgetStubFailure(String url) {
		NginxProbeService.forgetStubFailure(url);
	}

	public static String defaultStubUrl() {
		return "http://127.0.0.1:" + DEFAULT_STUB_PORT + DEFAULT_STUB_URI;
	}

	/**
	 * 一轮进程检测结果构建的运行索引: 批量判定多个实例状态时复用同一次检测,
	 * 避免每条实例都做一次全量进程枚举(含SCM枚举+WMI取路径).
	 */
	public static final class LiveIndex {
		private final Set<String> fids = new HashSet<String>();
		private final Set<String> exeConf = new HashSet<String>();

		/** 实例在本次运行集合中即视为运行中 */
		public boolean matches(NginxInstanceEntry entry) {
			if (entry == null) {
				return false;
			}
			String id = StrUtil.nullToEmpty(entry.getId());
			if (StrUtil.isNotEmpty(id) && fids.contains(id)) {
				return true;
			}
			for (String alt : entry.getAltIds()) {
				if (StrUtil.isNotEmpty(alt) && fids.contains(alt)) {
					return true;
				}
			}
			String exe = NginxInstanceRegistry.canonical(entry.getNginxExe());
			String conf = NginxInstanceRegistry.canonical(entry.getConfPath());
			if (StrUtil.isNotEmpty(exe) && StrUtil.isNotEmpty(conf)) {
				return exeConf.contains(exe + "|" + conf);
			}
			return false;
		}
	}

	/** 用一次检测到的运行实例列表构建运行索引 */
	public static LiveIndex buildLiveIndex(List<NginxInfo> live) {
		LiveIndex index = new LiveIndex();
		if (live == null) {
			return index;
		}
		for (NginxInfo info : live) {
			String fid = NginxInstanceRegistry.fingerprint(info.getNginxDir(), info.getNginxPath());
			if (StrUtil.isNotEmpty(fid)) {
				index.fids.add(fid);
			}
			String exe = NginxInstanceRegistry.canonical(info.getNginxExe());
			String conf = NginxInstanceRegistry.canonical(info.getNginxPath());
			if (StrUtil.isNotEmpty(exe) && StrUtil.isNotEmpty(conf)) {
				index.exeConf.add(exe + "|" + conf);
			}
		}
		return index;
	}
}
