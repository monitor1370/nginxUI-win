package com.nginxui.utils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nginxui.ext.NginxDetectResult;
import com.nginxui.ext.NginxInfo;
import com.nginxui.ext.NginxInstanceEntry;

import com.sun.jna.platform.win32.Winsvc;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;

/**
 * 控制模块: 实例 start/stop/restart, Windows 服务托管控制, 启动前配置检测,
 * 运行状态确认与 sockapi 单实例冲突诊断.
 */
public final class NginxControlService {

	private static final Logger LOG = LoggerFactory.getLogger(NginxControlService.class);

	private static final Map<String, Boolean> SOCKAPI_MARKER_CACHE = new ConcurrentHashMap<String, Boolean>();

	private NginxControlService() {
	}

	/**
	 * 实例生命周期控制(start/stop/restart), 串行执行避免信号交叉.
	 * 统一以prefix为工作目录并显式传 -c/-p; 信号类用nginx内建 -s 指令;
	 * 操作后轮询进程检测确认真实状态, 不以前台命令退出码为准.
	 */
	public static synchronized NginxDashboardUtils.ControlResult controlInstance(NginxInstanceEntry entry,
			String action) {
		NginxDashboardUtils.ControlResult result = new NginxDashboardUtils.ControlResult();

		if (SystemTool.isWindows() && StrUtil.isNotEmpty(entry.getWinServiceName())) {
			return controlByService(entry, action);
		}

		String exe = StrUtil.nullToEmpty(entry.getNginxExe());
		String conf = StrUtil.nullToEmpty(entry.getConfPath());
		String dir = StrUtil.emptyToDefault(entry.getNginxDir(), entry.getPrefixDir());
		if (!new File(exe).isFile()) {
			result.code = NginxErrorCode.EXE_NOT_FOUND;
			result.message = NginxErrorCode.message(result.code, exe);
			return result;
		}
		if (StrUtil.isEmpty(conf) || !new File(conf).isFile()) {
			result.code = NginxErrorCode.CONF_NOT_FOUND;
			result.message = NginxErrorCode.message(result.code, conf);
			return result;
		}
		File workdir = StrUtil.isNotEmpty(dir) && new File(dir).isDirectory() ? new File(dir) : null;

		try {
			if ("start".equals(action)) {
				NginxInfo live = findLiveInstance(entry);
				if (live != null) {
					result.success = true;
					result.running = true;
					result.masterPid = live.getMasterPid();
					result.message = I18n.t("dash.control.alreadyRunning");
					return result;
				}
				// 启动前自动配置检测(nginx_basic.exe -t 优先, 其次 nginx.exe -t);
				// 检测不通过也尝试启动一次: 启动成功给告警, 启动失败回显检测结果.
				NginxDashboardUtils.ConfigTestResult pre = NginxConfigService.runNginxTest(workdir,
						NginxConfigService.resolveTestExe(workdir, exe), conf, dir);
				String out = startNginx(workdir, exe, conf, dir);
				NginxInfo now = waitLive(entry, 10);
				if (now != null) {
					result.success = true;
					result.running = true;
					result.masterPid = now.getMasterPid();
					String warn = preflightSummary(pre);
					if (warn != null) {
						result.warning = true;
						result.message = I18n.t("dash.control.startWarning", warn);
					} else {
						result.message = I18n.t("dash.control.startOk");
					}
				} else {
					result.code = NginxErrorCode.START_FAILED;
					result.message = appendErrorLogTail(
							appendPreflight(NginxErrorCode.message(result.code, detail(out)), pre), workdir);
				}
			} else if ("stop".equals(action)) {
				NginxInfo live = findLiveInstance(entry);
				if (live == null) {
					result.success = true;
					result.running = false;
					result.message = I18n.t("dash.control.alreadyStopped");
					return result;
				}
				String out = sendSignal(workdir, exe, "stop", conf, dir);
				if (waitGone(entry, 10)) {
					result.success = true;
					result.message = I18n.t("dash.control.stopOk");
				} else {
					result.code = NginxErrorCode.STOP_FAILED;
					result.message = NginxErrorCode.message(result.code, detail(out));
				}
			} else if ("restart".equals(action)) {
				// 重启前先做配置检测(仅采集结果, 不阻断后续动作)
				NginxDashboardUtils.ConfigTestResult pre = NginxConfigService.runNginxTest(workdir,
						NginxConfigService.resolveTestExe(workdir, exe), conf, dir);
				NginxInfo live = findLiveInstance(entry);
				if (live != null) {
					sendSignal(workdir, exe, "stop", conf, dir);
					if (!waitGone(entry, 10)) {
						result.code = NginxErrorCode.OLD_PROCESS_ALIVE;
						result.message = NginxErrorCode.message(result.code);
						return result;
					}
				}
				String out = startNginx(workdir, exe, conf, dir);
				NginxInfo now = waitLive(entry, 12);
				if (now != null) {
					result.success = true;
					result.running = true;
					result.masterPid = now.getMasterPid();
					String warn = preflightSummary(pre);
					if (warn != null) {
						result.warning = true;
						result.message = I18n.t("dash.control.restartWarning", warn);
					} else {
						result.message = I18n.t("dash.control.restartOk");
					}
				} else {
					result.code = NginxErrorCode.RESTART_FAILED;
					result.message = appendErrorLogTail(
							appendPreflight(NginxErrorCode.message(result.code, detail(out)), pre), workdir);
				}
			} else {
				result.code = NginxErrorCode.ACTION_UNSUPPORTED;
				result.message = NginxErrorCode.message(result.code, action);
			}
		} catch (Exception e) {
			LOG.warn("[{}] 实例{}失败: {}", NginxErrorCode.CONTROL_EXCEPTION, action, e.getMessage(), e);
			result.code = NginxErrorCode.CONTROL_EXCEPTION;
			result.message = NginxErrorCode.message(result.code, e.getMessage());
		}
		return result;
	}

	/**
	 * Windows 服务托管实例的控制: start/stop/restart 走 SCM 服务控制管理器.
	 * 状态确认以 SCM 查询为准, 不依赖跨账户的进程命令行读取.
	 */
	private static NginxDashboardUtils.ControlResult controlByService(NginxInstanceEntry entry, String action) {
		NginxDashboardUtils.ControlResult result = new NginxDashboardUtils.ControlResult();
		String svcName = entry.getWinServiceName();

		int state = WindowsNginxServiceUtils.queryState(svcName);
		if (state < 0) {
			result.code = NginxErrorCode.SERVICE_NOT_FOUND;
			result.message = NginxErrorCode.message(result.code, svcName);
			return result;
		}

		try {
			if ("start".equals(action)) {
				if (state == Winsvc.SERVICE_RUNNING) {
					result.success = true;
					result.running = true;
					result.masterPid = serviceMasterPid(svcName);
					result.message = I18n.t("dash.service.alreadyRunning");
					return result;
				}
				NginxDashboardUtils.ConfigTestResult pre = servicePreflight(entry);
				String verb = I18n.t("dash.service.verbStart");
				if (startServiceOrDiagnose(entry, svcName, pre, verb, result)) {
					awaitServiceStable(entry, svcName, result, pre, verb);
				}
			} else if ("stop".equals(action)) {
				if (state == Winsvc.SERVICE_STOPPED) {
					result.success = true;
					result.running = false;
					result.message = I18n.t("dash.service.alreadyStopped");
					return result;
				}
				WindowsNginxServiceUtils.stop(svcName, 15000L);
				if (WindowsNginxServiceUtils.waitState(svcName, Winsvc.SERVICE_STOPPED, 15000L)) {
					result.success = true;
					result.message = I18n.t("dash.service.stopOk");
				} else {
					result.code = NginxErrorCode.SERVICE_STOP_TIMEOUT;
					result.message = NginxErrorCode.message(result.code);
				}
			} else if ("restart".equals(action)) {
				NginxDashboardUtils.ConfigTestResult pre = servicePreflight(entry);
				if (state != Winsvc.SERVICE_STOPPED) {
					WindowsNginxServiceUtils.stop(svcName, 15000L);
					if (!WindowsNginxServiceUtils.waitState(svcName, Winsvc.SERVICE_STOPPED, 15000L)) {
						result.code = NginxErrorCode.OLD_SERVICE_ALIVE;
						result.message = NginxErrorCode.message(result.code);
						return result;
					}
					NginxExecUtils.sleepQuietly(1000L);
				}
				String verb = I18n.t("dash.service.verbRestart");
				if (startServiceOrDiagnose(entry, svcName, pre, verb, result)) {
					awaitServiceStable(entry, svcName, result, pre, verb);
				}
			} else {
				result.code = NginxErrorCode.ACTION_UNSUPPORTED;
				result.message = NginxErrorCode.message(result.code, action);
			}
		} catch (com.sun.jna.platform.win32.Win32Exception e) {
			LOG.warn("[{}] 服务{}失败: {}", NginxErrorCode.SERVICE_EXCEPTION, action, e.getMessage(), e);
			result.code = NginxErrorCode.SERVICE_EXCEPTION;
			result.message = NginxErrorCode.message(result.code, WindowsNginxServiceUtils.friendlyError(e));
		} catch (Exception e) {
			LOG.warn("[{}] 服务{}失败: {}", NginxErrorCode.SERVICE_EXCEPTION, action, e.getMessage(), e);
			result.code = NginxErrorCode.SERVICE_EXCEPTION;
			result.message = NginxErrorCode.message(result.code, e.getMessage());
		}
		return result;
	}

	/** 服务实例启动/重启前配置检测; exe/conf 无法定位时返回null(跳过, 交由SCM结果说话). */
	private static NginxDashboardUtils.ConfigTestResult servicePreflight(NginxInstanceEntry entry) {
		String exe = StrUtil.nullToEmpty(entry.getNginxExe());
		String conf = StrUtil.nullToEmpty(entry.getConfPath());
		String dir = StrUtil.emptyToDefault(entry.getNginxDir(), entry.getPrefixDir());
		if (!new File(exe).isFile() || StrUtil.isEmpty(conf) || !new File(conf).isFile()) {
			return null;
		}
		File workdir = StrUtil.isNotEmpty(dir) && new File(dir).isDirectory() ? new File(dir) : null;
		return NginxConfigService.runNginxTest(workdir, NginxConfigService.resolveTestExe(workdir, exe), conf, dir);
	}

	/** 启动失败时拼接配置检测结果(单行化, 截断保护) */
	private static String appendPreflight(String base, NginxDashboardUtils.ConfigTestResult pre) {
		if (pre == null || pre.success) {
			return base;
		}
		String text = StrUtil.nullToEmpty(pre.output).replaceAll("\\s*\\r?\\n\\s*", " | ").trim();
		if (text.length() > 600) {
			text = text.substring(0, 600) + "…";
		}
		return base + I18n.t("dash.control.preflightSuffix", text);
	}

	/** 普通实例启动失败时追加 logs/error.log 尾部 */
	private static String appendErrorLogTail(String base, File workdir) {
		if (workdir == null) {
			return base;
		}
		String tail = NginxExecUtils.tailText(new File(workdir, "logs" + File.separator + "error.log"), 8, 700);
		if (StrUtil.isEmpty(tail)) {
			return base;
		}
		return base + I18n.t("dash.control.errorLogSuffix", tail);
	}

	/** 启动/重启前的配置检测摘要(成功为null) */
	private static String preflightSummary(NginxDashboardUtils.ConfigTestResult pre) {
		if (pre == null || pre.success) {
			return null;
		}
		String detail = StrUtil.nullToEmpty(pre.message);
		if (StrUtil.isEmpty(detail)) {
			detail = StrUtil.nullToEmpty(pre.output);
		}
		return detail.replaceAll("\\s*\\r?\\n\\s*", " | ");
	}

	/**
	 * 判断nginx可执行文件是否为 ecsds/vnginx sockapi 改版
	 * (机器级全局共享套接字区 nginx_shared_sockets_zone, 同机只允许一个master持有).
	 */
	private static boolean isSockapiBuild(String exe) {
		if (StrUtil.isEmpty(exe) || !new File(exe).isFile()) {
			return false;
		}
		String key = NginxInstanceRegistry.canonical(exe);
		Boolean cached = SOCKAPI_MARKER_CACHE.get(key);
		if (cached != null) {
			return cached;
		}
		boolean marker = false;
		try {
			byte[] data = FileUtil.readBytes(exe);
			marker = new String(data, StandardCharsets.ISO_8859_1).contains("nginx_shared_sockets_zone");
		} catch (Exception e) {
			LOG.debug("[{}] 读取可执行文件失败: {}", NginxErrorCode.VERSION_PROBE_FAILED, exe);
			marker = false;
		}
		SOCKAPI_MARKER_CACHE.put(key, marker);
		return marker;
	}

	/**
	 * 枚举正在运行的其他 sockapi 版 nginx master(全局区占用方),
	 * 来源: 进程检测 + SCM 运行中的服务, 去重.
	 */
	private static String detectSockapiPeers(NginxInstanceEntry self) {
		String selfDir = NginxInstanceRegistry
				.canonical(StrUtil.emptyToDefault(self.getPrefixDir(), self.getNginxDir()));
		List<String> peers = new ArrayList<String>();
		try {
			NginxDetectResult detected = NginxUtils.detectAll();
			if (detected != null && detected.getInstances() != null) {
				for (NginxInfo info : detected.getInstances()) {
					String dir = NginxInstanceRegistry.canonical(info.getNginxDir());
					if (StrUtil.isNotEmpty(selfDir) && selfDir.equals(dir)) {
						continue;
					}
					String exe = info.getNginxExe();
					if (isSockapiBuild(exe)) {
						peers.add(I18n.t("dash.service.peerProcess", info.getMasterPid(), exe));
					}
				}
			}
		} catch (Exception e) {
			// 进程检测不可用时退回 SCM 视角
			LOG.debug("[{}] 进程检测失败, 退回 SCM 视角: {}", NginxErrorCode.PROCESS_SAMPLE_FAILED, e.getMessage());
		}
		try {
			if (SystemTool.isWindows()) {
				for (WindowsNginxServiceUtils.WinNginxService svc : WindowsNginxServiceUtils.list()) {
					if (!svc.running()) {
						continue;
					}
					String dir = NginxInstanceRegistry.canonical(svc.prefixDir);
					if (StrUtil.isNotEmpty(selfDir) && selfDir.equals(dir)) {
						continue;
					}
					if (isSockapiBuild(svc.nginxExe)) {
						String item = I18n.t("dash.service.peerService", svc.serviceName, svc.nginxExe);
						if (!peers.contains(item)) {
							peers.add(item);
						}
					}
				}
			}
		} catch (Exception e) {
			// SCM 不可用
			LOG.debug("[{}] SCM 枚举失败: {}", NginxErrorCode.READ_LOG_FAILED, e.getMessage());
		}
		return peers.isEmpty() ? I18n.t("dash.service.peerFallback") : String.join("; ", peers);
	}

	/**
	 * 服务进入RUNNING后又在稳定期内秒退时的诊断:
	 * 识别 sockapi 全局区单实例冲突(assertion ngx_shared_sockets->pid==pid / 0xc0000409),
	 * 其余情况回捞包装器stderr与nginx error.log尾部.
	 */
	private static String serviceCrashDiagnosis(NginxInstanceEntry entry, NginxDashboardUtils.ConfigTestResult pre,
			String verb) {
		String dir = StrUtil.emptyToDefault(entry.getNginxDir(), entry.getPrefixDir());
		String errTail = StrUtil.isNotEmpty(dir)
				? NginxExecUtils.tailText(new File(dir, "nginx_service.err.log"), 4, 400)
				: "";
		String logTail = StrUtil.isNotEmpty(dir)
				? NginxExecUtils.tailText(new File(dir, "logs" + File.separator + "error.log"), 8, 700)
				: "";
		boolean sockapiConflict = errTail.contains("ngx_shared_sockets")
				|| errTail.contains("0xc0000409")
				|| isSockapiBuild(entry.getNginxExe());

		StringBuilder sb = new StringBuilder();
		sb.append(I18n.t("dash.service.crashBase", verb));
		if (sockapiConflict) {
			sb.append(I18n.t("dash.service.crashSockapi", detectSockapiPeers(entry)));
		}
		if (StrUtil.isNotEmpty(errTail)) {
			sb.append(I18n.t("dash.service.crashWrapper", errTail));
		}
		if (StrUtil.isNotEmpty(logTail)) {
			sb.append(I18n.t("dash.service.crashLog", logTail));
		}
		return appendPreflight(sb.toString(), pre);
	}

	/**
	 * 启动服务并兼容"启动调用即失败": winsw 拉起子进程后若秒崩(如sockapi单实例冲突断言),
	 * StartService RPC 会直接抛 Win32Exception; 此时若SCM查询已为STOPPED, 转入崩溃诊断.
	 * @return true 已正常发起启动, 调用方继续等待稳定; false 已写入诊断结果
	 */
	private static boolean startServiceOrDiagnose(NginxInstanceEntry entry, String svcName,
			NginxDashboardUtils.ConfigTestResult pre, String verb, NginxDashboardUtils.ControlResult result) {
		try {
			WindowsNginxServiceUtils.start(svcName);
			return true;
		} catch (com.sun.jna.platform.win32.Win32Exception se) {
			// 1056: 已在运行/启动中; 5: 权限; 其余按SCM实时状态判断是否秒崩
			if (se.getErrorCode() == 1056) {
				result.code = NginxErrorCode.SERVICE_STARTING;
				result.message = NginxErrorCode.message(result.code);
				return false;
			}
			NginxExecUtils.sleepQuietly(1500L);
			if (se.getErrorCode() != 5
					&& WindowsNginxServiceUtils.queryState(svcName) == Winsvc.SERVICE_STOPPED) {
				result.code = NginxErrorCode.SERVICE_START_FAILED;
				result.message = NginxErrorCode.prefix(result.code, serviceCrashDiagnosis(entry, pre, verb));
				return false;
			}
			result.code = NginxErrorCode.SERVICE_START_FAILED;
			result.message = NginxErrorCode.prefix(result.code,
					WindowsNginxServiceUtils.friendlyError(se));
			return false;
		} catch (RuntimeException re) {
			// JNA startService() 在服务最终不为RUNNING时抛 RuntimeException("Unable to start the service")
			NginxExecUtils.sleepQuietly(1500L);
			if (WindowsNginxServiceUtils.queryState(svcName) == Winsvc.SERVICE_STOPPED) {
				result.code = NginxErrorCode.SERVICE_START_FAILED;
				result.message = NginxErrorCode.prefix(result.code, serviceCrashDiagnosis(entry, pre, verb));
				return false;
			}
			result.code = NginxErrorCode.SERVICE_START_FAILED;
			result.message = NginxErrorCode.message(result.code, re.getMessage());
			return false;
		}
	}

	/**
	 * 等服务进入RUNNING并经过3秒稳定期, 防止 winsw 刚拉起子进程就崩溃时误报启动成功.
	 * @return true=稳定运行; false=超时或秒退(诊断已写入result.message)
	 */
	private static boolean awaitServiceStable(NginxInstanceEntry entry, String svcName,
			NginxDashboardUtils.ControlResult result, NginxDashboardUtils.ConfigTestResult pre, String verb) {
		if (!WindowsNginxServiceUtils.waitState(svcName, Winsvc.SERVICE_RUNNING, 25000L)) {
			result.code = NginxErrorCode.SERVICE_TIMEOUT;
			result.message = appendPreflight(NginxErrorCode.message(result.code, verb), pre);
			return false;
		}
		NginxExecUtils.sleepQuietly(3000L);
		if (WindowsNginxServiceUtils.queryState(svcName) != Winsvc.SERVICE_RUNNING) {
			result.code = NginxErrorCode.SERVICE_START_FAILED;
			result.message = NginxErrorCode.prefix(result.code, serviceCrashDiagnosis(entry, pre, verb));
			return false;
		}
		result.success = true;
		result.running = true;
		result.masterPid = serviceMasterPid(svcName);
		String warn = preflightSummary(pre);
		result.warning = warn != null;
		result.message = warn != null
				? I18n.t("dash.service.actionWarning", verb, warn)
				: I18n.t("dash.service.actionOk", verb);
		return true;
	}

	/** 服务运行中时, 按包装器pid找到其托管的 nginx master pid; 取不到返回null */
	private static String serviceMasterPid(String serviceName) {
		try {
			WindowsNginxServiceUtils.WinNginxService target = null;
			for (WindowsNginxServiceUtils.WinNginxService svc : WindowsNginxServiceUtils.list()) {
				if (svc.serviceName.equalsIgnoreCase(serviceName)) {
					target = svc;
					break;
				}
			}
			if (target == null || !target.running() || target.wrapperPid <= 0) {
				return null;
			}
			List<NginxInfo> live = NginxUtils.detectAll().getInstances();
			if (live == null) {
				return null;
			}
			String wrapperPid = String.valueOf(target.wrapperPid);
			for (NginxInfo info : live) {
				if (wrapperPid.equals(info.getMasterPpid())) {
					return info.getMasterPid();
				}
			}
		} catch (Exception e) {
			// 探测失败不影响控制结果
			LOG.debug("[{}] 服务 master pid 探测失败: {}", NginxErrorCode.PROCESS_SAMPLE_FAILED, e.getMessage());
		}
		return null;
	}

	/** 启动nginx: Windows用cmd start脱离, Linux依赖nginx自身daemon化; cwd固定为prefix */
	private static String startNginx(File workdir, String exe, String conf, String dir) {
		List<String> cmd = new ArrayList<String>();
		if (SystemTool.isWindows()) {
			cmd.add("cmd");
			cmd.add("/c");
			cmd.add("start");
			// 空标题: start会把第一个"带引号"参数当作窗口标题,
			// 不提供标题时含空格的exe路径(被ProcessBuilder自动加引号)会被误判为标题
			cmd.add("");
			cmd.add(exe);
		} else {
			cmd.add(exe);
		}
		cmd.add("-c");
		cmd.add(conf);
		if (StrUtil.isNotEmpty(dir)) {
			cmd.add("-p");
			cmd.add(dir);
		}
		return NginxExecUtils.exec(workdir, 15, cmd.toArray(new String[0]));
	}

	/** nginx内建信号: -s stop/quit */
	private static String sendSignal(File workdir, String exe, String signal, String conf, String dir) {
		List<String> cmd = new ArrayList<String>();
		cmd.add(exe);
		cmd.add("-s");
		cmd.add(signal);
		cmd.add("-c");
		cmd.add(conf);
		if (StrUtil.isNotEmpty(dir)) {
			cmd.add("-p");
			cmd.add(dir);
		}
		return NginxExecUtils.exec(workdir, 30, cmd.toArray(new String[0]));
	}

	private static String detail(String output) {
		String text = StrUtil.nullToEmpty(output).trim();
		if (StrUtil.isEmpty(text)) {
			return I18n.t("dash.control.logHint");
		}
		if (text.length() > 300) {
			text = text.substring(text.length() - 300);
		}
		return ": " + text.replace("\r", " ").replace("\n", " ");
	}

	/**
	 * 实例当前是否在运行: 服务托管以 SCM 状态为准, 其余以进程检测为准.
	 */
	public static boolean isInstanceRunning(NginxInstanceEntry entry) {
		if (SystemTool.isWindows() && StrUtil.isNotEmpty(entry.getWinServiceName())) {
			return WindowsNginxServiceUtils.queryState(entry.getWinServiceName()) == Winsvc.SERVICE_RUNNING;
		}
		return findLiveInstance(entry) != null;
	}

	private static NginxInfo waitLive(NginxInstanceEntry entry, int timeoutSeconds) {
		NginxInfo info = findLiveInstance(entry);
		for (int i = 0; i < timeoutSeconds && info == null; i++) {
			NginxExecUtils.sleepQuietly(1000L);
			info = findLiveInstance(entry);
		}
		return info;
	}

	private static boolean waitGone(NginxInstanceEntry entry, int timeoutSeconds) {
		boolean gone = findLiveInstance(entry) == null;
		for (int i = 0; i < timeoutSeconds && !gone; i++) {
			NginxExecUtils.sleepQuietly(1000L);
			gone = findLiveInstance(entry) == null;
		}
		return gone;
	}

	/**
	 * 在本轮进程检测中定位注册表实例对应的运行进程:
	 * 优先组合指纹(含altIds历史指纹), 指纹缺失时退回 exe+配置路径双匹配.
	 */
	static NginxInfo findLiveInstance(NginxInstanceEntry entry) {
		List<NginxInfo> live = NginxUtils.detectAll().getInstances();
		if (live == null || live.isEmpty()) {
			return null;
		}
		for (NginxInfo info : live) {
			String fid = NginxInstanceRegistry.fingerprint(info.getNginxDir(), info.getNginxPath());
			if (fid.equals(entry.getId()) || entry.getAltIds().contains(fid)) {
				return info;
			}
		}
		String canonicalExe = NginxInstanceRegistry.canonical(entry.getNginxExe());
		String canonicalConf = NginxInstanceRegistry.canonical(entry.getConfPath());
		if (StrUtil.isEmpty(canonicalExe) || StrUtil.isEmpty(canonicalConf)) {
			return null;
		}
		for (NginxInfo info : live) {
			if (canonicalExe.equals(NginxInstanceRegistry.canonical(info.getNginxExe()))
					&& canonicalConf.equals(NginxInstanceRegistry.canonical(info.getNginxPath()))) {
				return info;
			}
		}
		return null;
	}
}
