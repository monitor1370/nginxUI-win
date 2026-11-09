package com.nginxui.utils;

import java.io.File;
import java.io.FileFilter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.nginxui.ext.NginxDetectResult;
import com.nginxui.ext.NginxInfo;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.RuntimeUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.system.oshi.OshiUtil;
import oshi.software.os.OSProcess;

public class NginxUtils {

	private static final Pattern VERSION_PATTERN = Pattern.compile("nginx version:\\s*(?:nginx/)?(\\S+)");
	private static final Pattern PREFIX_PATTERN = Pattern.compile("--prefix=([^\\s]+)");
	private static final Pattern CONF_PATTERN = Pattern.compile("--conf-path=([^\\s]+)");
	private static final Pattern TOKEN_PATTERN = Pattern.compile("\"([^\"]*)\"|(\\S+)");

	/** nginx -V 输出不随运行状态变化, 按可执行文件路径缓存, 多实例/轮询时不重复起进程 */
	private static final Map<String, String> vCache = new java.util.concurrent.ConcurrentHashMap<String, String>();

	/**
	 * 判断nginx是否运行
	 * 
	 * @return
	 */
	public static boolean isRun() {
		boolean isRun = false;
		if (SystemTool.isWindows()) {
			// csv输出首列为镜像名, 兼容nginx_basic.exe等改名重打包版本
			String rs = exec(10, "tasklist", "/fo", "csv", "/nh");
			for (String line : rs.split("\\r?\\n")) {
				String image = csvFirst(line);
				if (image != null) {
					String low = image.toLowerCase();
					if (low.endsWith(".exe")) low = low.substring(0, low.length() - 4);
					if (low.equals("nginx") || low.equals("nginx_basic")) {
						isRun = true;
						break;
					}
				}
			}
		} else {
			String[] command = { "/bin/sh", "-c", "ps -ef|grep nginx" };
			String rs = RuntimeUtil.execForStr(command);
			isRun = rs.contains("nginx: master process") || rs.contains("nginx: worker process");
		}

		return isRun;
	}

	/**
	 * 自动检测本机nginx进程(兼容单实例老调用, 多实例时返回master pid最小的实例).
	 *
	 * @return 检测结果
	 */
	public static NginxInfo detect() {
		NginxDetectResult result = detectAll();
		if (result.getInstances().isEmpty()) {
			NginxInfo empty = new NginxInfo();
			if (StrUtil.isNotEmpty(result.getMessage())) {
				empty.setMessage(result.getMessage());
			}
			return empty;
		}
		return result.getInstances().get(0);
	}

	/**
	 * 检测本机全部nginx实例: 每个master进程(其worker按ppid挂载)为一个实例,
	 * 并解析各自的exe/prefix/配置文件/版本. 无master归属的nginx进程计入orphanPids.
	 */
	public static NginxDetectResult detectAll() {
		return detectAll(null);
	}

	/**
	 * 检测本机全部nginx实例(可复用已枚举的SCM服务列表, 避免重复枚举).
	 *
	 * @param services 已枚举的SCM服务; null=内部自行枚举
	 */
	public static NginxDetectResult detectAll(List<WindowsNginxServiceUtils.WinNginxService> services) {
		NginxDetectResult result = new NginxDetectResult();
		try {
			List<RawGroup> groups;
			List<String> orphans;
			if (SystemTool.isWindows()) {
				// 先枚举SCM: WinSW包装器(尤其自定义命名 nginx-site-*.exe)不能参与
				// nginx 进程分组, 否则真实master会被挂到包装器下, 导致服务关联/exe指向错误
				java.util.Set<String> excludePids = new java.util.HashSet<String>();
				java.util.Set<String> excludeExes = new java.util.HashSet<String>();
				java.util.Map<String, WindowsNginxServiceUtils.WinNginxService> svcByWrapperPid =
						new java.util.HashMap<String, WindowsNginxServiceUtils.WinNginxService>();
				List<WindowsNginxServiceUtils.WinNginxService> svcs = services != null
						? services : WindowsNginxServiceUtils.list();
				for (WindowsNginxServiceUtils.WinNginxService svc : svcs) {
					if (svc.wrapperPid > 0) {
						excludePids.add(String.valueOf(svc.wrapperPid));
						svcByWrapperPid.put(String.valueOf(svc.wrapperPid), svc);
					}
					if (StrUtil.isNotEmpty(svc.wrapperExe)) {
						excludeExes.add(NginxInstanceRegistry.canonical(svc.wrapperExe));
					}
				}
				windowsGroups wg = collectWindows(excludePids, excludeExes, svcByWrapperPid);
				groups = wg.groups;
				orphans = wg.orphans;
			} else if (SystemTool.isLinux()) {
				List<RawProc> procs = collectLinuxProcs();
				groups = groupByMaster(procs, true);
				orphans = findOrphans(procs, groups);
			} else {
				List<RawProc> procs = collectPsProcs();
				groups = groupByMaster(procs, false);
				orphans = findOrphans(procs, groups);
			}

			List<NginxInfo> infos = new ArrayList<NginxInfo>();
			for (RawGroup group : groups) {
				infos.add(toInfo(group));
			}
			result.setInstances(infos);
			result.setOrphanPids(orphans);
		} catch (Exception e) {
			result.setMessage(e.getMessage());
		}
		return result;
	}

	/**
	 * 把一个master分组补齐为实例检测信息
	 */
	private static NginxInfo toInfo(RawGroup group) {
		NginxInfo info = new NginxInfo();
		info.setRunning(true);

		List<String> pids = new ArrayList<String>();
		pids.add(group.masterPid);
		for (RawProc member : group.members) {
			if (!pids.contains(member.pid)) {
				pids.add(member.pid);
			}
		}
		info.setPids(StrUtil.join(",", pids));
		info.setMasterPid(group.masterPid);
		info.setMasterPpid(group.masterPpid);

		if (group.masterArgv != null) {
			parseRuntimeArgs(group.masterArgv, info);
		}
		String exe = group.masterExe;
		if (StrUtil.isEmpty(exe) || !new File(exe).isAbsolute()) {
			String whichPath = which();
			if (StrUtil.isNotEmpty(whichPath)) {
				exe = whichPath;
			}
		}
		info.setNginxExe(exe);
		info.setWinServiceName(group.masterServiceName);

		fillByNginxV(info);
		fillDefaults(info);
		return info;
	}

	/**
	 * 按master对候选进程分组: master为根(ppid不在候选集合中)且至少有一个nginx子进程,
	 * 其余进程沿ppid向上挂载; 挂载不到任何master的即为孤儿进程.
	 */
	private static List<RawGroup> groupByMaster(List<RawProc> procs, boolean masterFromCmdTitle) {
		Map<String, RawProc> byPid = new HashMap<String, RawProc>();
		for (RawProc p : procs) {
			byPid.put(p.pid, p);
		}

		List<RawGroup> groups = new ArrayList<RawGroup>();
		Map<String, RawGroup> owner = new HashMap<String, RawGroup>();
		for (RawProc p : procs) {
			boolean isMaster;
			if (masterFromCmdTitle) {
				isMaster = p.masterArgv != null;
			} else {
				isMaster = p.ppid == null || !byPid.containsKey(p.ppid);
			}
			if (!isMaster) {
				continue;
			}

			RawGroup group = new RawGroup();
			group.masterPid = p.pid;
			group.masterPpid = p.ppid;
			group.masterExe = p.exe;
			group.masterServiceName = p.serviceName;
			group.masterArgv = p.masterArgv != null ? p.masterArgv : tokenize(p.cmd);
			groups.add(group);
			owner.put(p.pid, group);
		}

		// Windows: 没有子进程的根进程视为孤儿(通常是master已退出的残留worker)
		if (!masterFromCmdTitle) {
			List<RawGroup> fakeMasters = new ArrayList<RawGroup>();
			for (RawGroup group : groups) {
				boolean hasChild = false;
				for (RawProc p : procs) {
					if (group.masterPid.equals(p.ppid)) {
						hasChild = true;
						break;
					}
				}
				if (!hasChild) {
					fakeMasters.add(group);
				}
			}
			groups.removeAll(fakeMasters);
			for (RawGroup g : fakeMasters) {
				owner.remove(g.masterPid);
			}
		}

		// 迭代挂载子进程(支持多级, 覆盖重启过渡期的罕见父子结构)
		boolean changed = true;
		while (changed) {
			changed = false;
			for (RawProc p : procs) {
				if (owner.containsKey(p.pid) || p.ppid == null) {
					continue;
				}
				RawGroup parent = owner.get(p.ppid);
				if (parent != null) {
					parent.members.add(p);
					owner.put(p.pid, parent);
					changed = true;
				}
			}
		}

		groups.sort(new java.util.Comparator<RawGroup>() {
			@Override
			public int compare(RawGroup a, RawGroup b) {
				return Long.compare(parsePid(a.masterPid), parsePid(b.masterPid));
			}
		});
		return groups;
	}

	private static List<String> findOrphans(List<RawProc> procs, List<RawGroup> groups) {
		Set<String> owned = new HashSet<String>();
		for (RawGroup g : groups) {
			owned.add(g.masterPid);
			for (RawProc m : g.members) {
				owned.add(m.pid);
			}
		}
		List<String> orphans = new ArrayList<String>();
		for (RawProc p : procs) {
			if (!owned.contains(p.pid)) {
				orphans.add(p.pid);
			}
		}
		return orphans;
	}

	private static long parsePid(String pid) {
		try {
			return Long.parseLong(pid);
		} catch (Exception e) {
			return Long.MAX_VALUE;
		}
	}

	/**
	 * 根进程是否直接挂在某个SCM服务的包装器下(master.ppid = 包装器pid).
	 * 命中即可用服务元数据解析, 省掉单进程秒级的WMI读取.
	 */
	private static WindowsNginxServiceUtils.WinNginxService serviceByParent(RawProc root,
			Map<String, WindowsNginxServiceUtils.WinNginxService> svcByWrapperPid) {
		if (svcByWrapperPid == null || svcByWrapperPid.isEmpty() || StrUtil.isEmpty(root.ppid)) {
			return null;
		}
		return svcByWrapperPid.get(root.ppid);
	}

	/**
	 * 用服务元数据合成启动参数, 复用 -p/-c 解析(位置无关).
	 * 服务XML里配置了自定义prefix/conf时, 这里比猜 exe 同级目录可靠.
	 */
	private static List<String> serviceArgv(WindowsNginxServiceUtils.WinNginxService svc) {
		List<String> argv = new ArrayList<String>();
		if (StrUtil.isNotEmpty(svc.prefixDir)) {
			argv.add("-p");
			argv.add(svc.prefixDir);
		}
		if (StrUtil.isNotEmpty(svc.confPath)) {
			argv.add("-c");
			argv.add(svc.confPath);
		}
		return argv.isEmpty() ? null : argv;
	}

	/**
	 * Windows: 用 Toolhelp32 快照取 pid/parentPid/镜像名(约15ms).
	 * 只做轻量枚举, exe/命令行由调用方按需补取.
	 */
	private static List<RawProc> collectWindowsProcs() {
		List<RawProc> procs = new ArrayList<RawProc>();
		com.sun.jna.platform.win32.WinNT.HANDLE snapshot = null;
		try {
			snapshot = com.sun.jna.platform.win32.Kernel32.INSTANCE
					.CreateToolhelp32Snapshot(
							new com.sun.jna.platform.win32.WinDef.DWORD(0x00000002),
							new com.sun.jna.platform.win32.WinDef.DWORD(0));
			if (snapshot == null
					|| com.sun.jna.platform.win32.WinBase.INVALID_HANDLE_VALUE.equals(snapshot)) {
				return procs;
			}
			com.sun.jna.platform.win32.Tlhelp32.PROCESSENTRY32 entry =
					new com.sun.jna.platform.win32.Tlhelp32.PROCESSENTRY32();
			boolean ok = com.sun.jna.platform.win32.Kernel32.INSTANCE.Process32First(snapshot, entry);
			while (ok) {
				RawProc p = new RawProc();
				p.pid = String.valueOf(entry.th32ProcessID.intValue());
				p.ppid = String.valueOf(entry.th32ParentProcessID.intValue());
				p.name = StrUtil.nullToEmpty(com.sun.jna.Native.toString(entry.szExeFile))
						.trim().toLowerCase();
				procs.add(p);
				ok = com.sun.jna.platform.win32.Kernel32.INSTANCE.Process32Next(snapshot, entry);
			}
		} catch (Throwable t) {
			// JNA/接口不可用时返回空表, 由上层退化为 oshi 枚举
		} finally {
			if (snapshot != null
					&& !com.sun.jna.platform.win32.WinBase.INVALID_HANDLE_VALUE.equals(snapshot)) {
				com.sun.jna.platform.win32.Kernel32.INSTANCE.CloseHandle(snapshot);
			}
		}
		return procs;
	}

	/** Toolhelp32 不可用时的兜底: oshi 全量进程枚举(较慢) */
	private static List<RawProc> collectWindowsProcsByOshi() {
		List<RawProc> procs = new ArrayList<RawProc>();
		List<OSProcess> osProcesses = OshiUtil.getOs().getProcesses();
		if (osProcesses != null) {
			for (OSProcess osProcess : osProcesses) {
				RawProc p = new RawProc();
				p.pid = String.valueOf(osProcess.getProcessID());
				p.ppid = String.valueOf(osProcess.getParentProcessID());
				p.name = StrUtil.nullToEmpty(osProcess.getName()).toLowerCase();
				procs.add(p);
			}
		}
		return procs;
	}

	/** 按pid取单个进程句柄(懒加载, 避免全量枚举) */
	private static OSProcess oshiProcess(String pid) {
		try {
			return OshiUtil.getOs().getProcess(Integer.parseInt(pid));
		} catch (Exception e) {
			return null;
		}
	}

	/** 未加工的进程信息(跨平台统一结构) */
	private static class RawProc {
		String pid;
		String ppid;
		String name;
		String exe;
		String cmd;
		/** 仅master进程非空: master标题改写后还原出的原始启动参数 */
		List<String> masterArgv;
		/** Windows: exe/命令行是否已读取(仅对根进程按需读取) */
		boolean resolved;
		/** Windows: 若根进程可确认归属某个SCM服务, 记录服务名(免WMI读取) */
		String serviceName;
	}

	private static class RawGroup {
		String masterPid;
		String masterPpid;
		String masterExe;
		String masterServiceName;
		List<String> masterArgv;
		List<RawProc> members = new ArrayList<RawProc>();
	}

	private static class windowsGroups {
		List<RawGroup> groups = new ArrayList<RawGroup>();
		List<String> orphans = new ArrayList<String>();
	}

	/**
	 * Windows: 通过oshi(JNI)采集nginx原始进程, 毫秒级返回.
	 * 兼容官方nginx.exe及nginx_basic.exe等改名重打包版本; nginx_service.exe等包装器剔除.
	 */
	private static windowsGroups collectWindows(java.util.Set<String> excludePids,
			java.util.Set<String> excludeExes,
			java.util.Map<String, WindowsNginxServiceUtils.WinNginxService> svcByWrapperPid) {
		windowsGroups result = new windowsGroups();
		List<RawProc> candidates = new ArrayList<RawProc>();
		// 第一遍只取廉价字段(名字/pid/ppid): Toolhelp32 快照约15ms,
		// 比 oshi 全量进程枚举(数百进程逐个解析名称, 约1.8s)快两个数量级.
		List<RawProc> raw = collectWindowsProcs();
		if (raw.isEmpty()) {
			raw = collectWindowsProcsByOshi();
		}
		for (RawProc proc : raw) {
			String lowName = StrUtil.nullToEmpty(proc.name);
			if (StrUtil.isEmpty(lowName)) {
				continue;
			}
			if (lowName.endsWith(".exe")) {
				lowName = lowName.substring(0, lowName.length() - 4);
			}
			if (!lowName.equals("nginx") && !lowName.equals("nginx_basic")) {
				continue;
			}
			lowName = lowName.endsWith(".exe") ? lowName : lowName + ".exe";
			if (lowName.contains("service")) {
				// nginx_service.exe等服务包装器不参与实例分组
				continue;
			}
			// SCM 注册的 WinSW 包装器(自定义命名, 如 nginx-site-1.30.1-1.exe): 按pid剔除
			if (excludePids != null && excludePids.contains(proc.pid)) {
				continue;
			}
			// Windows 上进程名不带 .exe, 统一补齐与 tasklist/WMI 口径一致
			proc.name = lowName;
			candidates.add(proc);
		}

		if (candidates.isEmpty()) {
			return result;
		}

		// 仅对"根进程"(ppid 不在候选集)读取 exe/命令行: 只有 master 需要 exe 与 -p/-c,
		// worker 的 exe/cmd 下游并未使用, 跳过可把 WMI 调用从每进程降到每实例一次.
		// 未按pid剔除的 WinSW 包装器也是根进程, 需读 exe 才可按 excludeExes 剔除;
		// 剔除后其子进程(真实 master)成为新根, 故用不动点迭代(服务嵌套层级很浅).
		for (int pass = 0; pass < 4; pass++) {
			java.util.Set<String> livePids = new java.util.HashSet<String>();
			for (RawProc p : candidates) {
				livePids.add(p.pid);
			}
			List<RawProc> newRoots = new ArrayList<RawProc>();
			for (RawProc p : candidates) {
				if (p.resolved) {
					continue;
				}
				if (p.ppid == null || !livePids.contains(p.ppid)) {
					newRoots.add(p);
				}
			}
			if (newRoots.isEmpty()) {
				break;
			}

			// 无nginx子进程的根不可能是master(nginx master必起worker):
			// 直接交给groupByMaster按孤儿处理, 免掉一次秒级WMI读取
			java.util.Set<String> parentPids = new java.util.HashSet<String>();
			for (RawProc p : candidates) {
				if (p.ppid != null) {
					parentPids.add(p.ppid);
				}
			}

			boolean removed = false;
			for (RawProc root : newRoots) {
				root.resolved = true;
				if (!parentPids.contains(root.pid)) {
					continue;
				}
				// SCM 服务托管的 master: 服务元数据已给出 exe/prefix/conf, 直接采用,
				// 免掉 Windows 上单进程秒级的 WMI 读取(getPath/getCommandLine)
				WindowsNginxServiceUtils.WinNginxService svc = serviceByParent(root, svcByWrapperPid);
				if (svc != null) {
					root.exe = StrUtil.nullToEmpty(svc.nginxExe);
					root.masterArgv = serviceArgv(svc);
					root.serviceName = svc.serviceName;
					continue;
				}
				// 非服务托管的根进程(命令行直接启动): 仍需 WMI 读 exe/命令行,
				// 仅对该pid单独取句柄, 不再枚举全部进程
				OSProcess handle = oshiProcess(root.pid);
				if (handle == null) {
					continue;
				}
				root.exe = StrUtil.nullToEmpty(handle.getPath());
				root.cmd = StrUtil.nullToEmpty(handle.getCommandLine());
				if (excludeExes != null && StrUtil.isNotEmpty(root.exe)
						&& excludeExes.contains(NginxInstanceRegistry.canonical(root.exe))) {
					candidates.remove(root);
					removed = true;
				}
			}
			if (!removed) {
				break;
			}
		}

		result.groups = groupByMaster(candidates, false);
		result.orphans = findOrphans(candidates, result.groups);
		return result;
	}

	/**
	 * Linux: 扫描/proc采集nginx进程, master通过改写后的进程标题识别,
	 * exe路径取/proc/&lt;pid&gt;/exe软链接.
	 */
	private static List<RawProc> collectLinuxProcs() {
		List<RawProc> procs = new ArrayList<RawProc>();

		File[] pidDirs = new File("/proc").listFiles(new FileFilter() {
			@Override
			public boolean accept(File file) {
				return file.isDirectory() && file.getName().matches("\\d+");
			}
		});
		if (pidDirs == null) {
			return procs;
		}

		for (File pidDir : pidDirs) {
			File commFile = new File(pidDir, "comm");
			if (!commFile.exists()) {
				continue;
			}
			String comm;
			try {
				comm = FileUtil.readString(commFile, StandardCharsets.UTF_8).trim();
			} catch (Exception e) {
				continue;
			}
			if (!"nginx".equalsIgnoreCase(comm)) {
				continue;
			}

			RawProc proc = new RawProc();
			proc.pid = pidDir.getName();
			proc.name = "nginx";
			proc.ppid = linuxPpid(proc.pid);

			try {
				String cmdline = FileUtil.readString(new File(pidDir, "cmdline"), StandardCharsets.UTF_8);
				proc.cmd = cmdline.replace('\u0000', ' ').trim();
				List<String> argv = masterArgv(splitArgs(cmdline));
				proc.masterArgv = (argv != null && !argv.isEmpty()) ? argv : null;
			} catch (Exception e) {
				// 单个进程读取失败不影响整体检测
			}

			try {
				File exeFile = new File("/proc/" + proc.pid + "/exe");
				if (Files.exists(exeFile.toPath())) {
					String real = Files.readSymbolicLink(exeFile.toPath()).toString();
					real = real.replaceFirst("\\s*\\(deleted\\)$", "");
					if (new File(real).exists()) {
						proc.exe = real;
					}
				}
			} catch (Exception e) {
				// 忽略软链接解析异常
			}

			procs.add(proc);
		}
		return procs;
	}

	/** 读取/proc/<pid>/stat中的父进程pid(ppid为')'后第2个字段) */
	private static String linuxPpid(String pid) {
		try {
			String stat = FileUtil.readString(new File("/proc/" + pid + "/stat"), StandardCharsets.UTF_8);
			int rp = stat.lastIndexOf(')');
			String[] fields = stat.substring(rp + 2).split("\\s+");
			return fields[1];
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Mac OS等无/proc文件系统的环境, 通过ps命令采集(pid,ppid,args),
	 * master通过改写后的进程标题识别.
	 */
	private static List<RawProc> collectPsProcs() {
		List<RawProc> procs = new ArrayList<RawProc>();
		String out = exec(20, "ps", "-Ao", "pid,ppid,comm,args");
		for (String line : out.split("\\r?\\n")) {
			line = line.trim();
			if (line.isEmpty() || line.toUpperCase().startsWith("PID")) {
				continue;
			}

			String[] parts = line.split("\\s+", 4);
			if (parts.length < 4 || !parts[0].matches("\\d+") || !"nginx".equalsIgnoreCase(parts[2])) {
				continue;
			}

			RawProc proc = new RawProc();
			proc.pid = parts[0];
			proc.ppid = parts[1];
			proc.name = parts[2];
			proc.cmd = parts[3];
			List<String> argv = masterArgv(tokenize(parts[3]));
			proc.masterArgv = (argv != null && !argv.isEmpty()) ? argv : null;
			if (proc.masterArgv == null) {
				continue;
			}
			proc.exe = proc.masterArgv.get(0);
			procs.add(proc);
		}

		// master已收录, 再补worker(标题为nginx: worker/cache等, 上面被跳过)
		for (String line : out.split("\\r?\\n")) {
			line = line.trim();
			if (line.isEmpty() || line.toUpperCase().startsWith("PID")) {
				continue;
			}
			String[] parts = line.split("\\s+", 4);
			if (parts.length < 4 || !parts[0].matches("\\d+") || !"nginx".equalsIgnoreCase(parts[2])) {
				continue;
			}
			boolean exists = false;
			for (RawProc p : procs) {
				if (p.pid.equals(parts[0])) {
					exists = true;
					break;
				}
			}
			if (!exists) {
				RawProc proc = new RawProc();
				proc.pid = parts[0];
				proc.ppid = parts[1];
				proc.name = parts[2];
				proc.cmd = parts[3];
				procs.add(proc);
			}
		}
		return procs;
	}

	private static String unquote(String value) {
		if (value == null) {
			return null;
		}
		value = value.trim();
		if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
			return value.substring(1, value.length() - 1);
		}
		return value;
	}

	private static String csvFirst(String line) {
		line = line.trim();
		if (line.startsWith("\"")) {
			int end = line.indexOf('"', 1);
			if (end > 0) {
				return line.substring(1, end);
			}
		}
		int comma = line.indexOf(',');
		return comma > 0 ? line.substring(0, comma) : line;
	}

	/**
	 * 执行 nginx -V (按可执行文件路径缓存, 多实例时各自只执行一次)
	 */
	private static String nginxVOutput(String cmd) {
		String cached = vCache.get(cmd);
		if (StrUtil.isNotEmpty(cached)) {
			return cached;
		}
		String out = exec(15, cmd, "-V");
		if (StrUtil.isNotEmpty(out)) {
			vCache.put(cmd, out);
		}
		return out;
	}

	/**
	 * 执行nginx -V, 补充编译时prefix、配置文件路径及版本信息
	 */
	private static void fillByNginxV(NginxInfo info) {
		String exe = info.getNginxExe();
		if (StrUtil.isEmpty(exe)) {
			return;
		}

		boolean exeExists = new File(exe).exists();
		String cmd = exeExists ? exe : (SystemTool.isWindows() ? "nginx.exe" : "nginx");
		String versionOut = nginxVOutput(cmd);
		if (StrUtil.isEmpty(versionOut)) {
			return;
		}

		Matcher versionMatcher = VERSION_PATTERN.matcher(versionOut);
		if (versionMatcher.find()) {
			info.setVersion(versionMatcher.group(1));
		}

		String buildPrefix = match(PREFIX_PATTERN, versionOut);
		String buildConf = match(CONF_PATTERN, versionOut);

		String dir = info.getNginxDir();
		if (StrUtil.isEmpty(dir)) {
			if (SystemTool.isWindows() && exeExists) {
				// Windows编译时prefix是构建机路径, 运行时prefix默认为exe所在目录
				dir = new File(exe).getParent();
			} else {
				dir = buildPrefix;
			}
		}
		if (StrUtil.isNotEmpty(dir)) {
			info.setNginxDir(trimEndSlash(dir));
		}

		String conf = StrUtil.isNotEmpty(info.getNginxPath()) ? info.getNginxPath() : buildConf;
		if (StrUtil.isEmpty(conf)) {
			if (StrUtil.isNotEmpty(info.getNginxDir())) {
				conf = new File(info.getNginxDir(), "conf/nginx.conf").getPath();
			}
		} else if (!new File(conf).isAbsolute() && StrUtil.isNotEmpty(info.getNginxDir())) {
			// 相对路径基于prefix目录解析
			conf = new File(info.getNginxDir(), conf).getPath();
		}
		if (StrUtil.isNotEmpty(conf)) {
			info.setNginxPath(conf);
		}
	}

	/**
	 * 仍未获取到的目录信息使用默认规则补齐
	 */
	private static void fillDefaults(NginxInfo info) {
		if (StrUtil.isEmpty(info.getNginxDir()) && StrUtil.isNotEmpty(info.getNginxExe())) {
			File exe = new File(info.getNginxExe());
			if (exe.exists()) {
				info.setNginxDir(exe.getParent());
			}
		}

		if (StrUtil.isEmpty(info.getNginxPath()) && StrUtil.isNotEmpty(info.getNginxDir())) {
			info.setNginxPath(new File(info.getNginxDir(), "conf/nginx.conf").getPath());
		}
	}

	/**
	 * 解析nginx运行参数中的-c配置文件路径和-p prefix目录
	 */
	private static void parseRuntimeArgs(List<String> argv, NginxInfo info) {
		if (argv == null) {
			return;
		}

		for (int i = 0; i < argv.size(); i++) {
			String arg = argv.get(i);
			if ("-c".equals(arg) && i + 1 < argv.size()) {
				info.setNginxPath(argv.get(i + 1));
			}
			if ("-p".equals(arg) && i + 1 < argv.size()) {
				info.setNginxDir(argv.get(i + 1));
			}
		}
	}

	/**
	 * 从进程参数中识别master进程的原始启动参数.
	 * nginx会改写进程标题, master为"nginx: master process 原启动命令",
	 * worker/cache进程没有后续启动命令.
	 * 
	 * @return master原始启动参数; 非master进程返回null
	 */
	private static List<String> masterArgv(List<String> rawArgs) {
		List<String> tokens = new ArrayList<>();
		for (String raw : rawArgs) {
			tokens.addAll(tokenize(raw));
		}

		if (tokens.size() >= 3 && "nginx:".equals(tokens.get(0)) && "master".equals(tokens.get(1))
				&& "process".equals(tokens.get(2))) {
			return new ArrayList<>(tokens.subList(3, tokens.size()));
		}

		if (!tokens.isEmpty() && tokens.get(0).startsWith("nginx:")) {
			// worker process / cache manager process / cache loader process
			return null;
		}

		return tokens;
	}

	/**
	 * 按空白拆分命令行, 支持双引号包裹的路径
	 */
	private static List<String> tokenize(String command) {
		List<String> list = new ArrayList<>();
		if (StrUtil.isEmpty(command)) {
			return list;
		}

		Matcher matcher = TOKEN_PATTERN.matcher(command);
		while (matcher.find()) {
			list.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
		}
		return list;
	}

	/**
	 * 拆分/proc/<pid>/cmdline的NUL分隔参数
	 */
	private static List<String> splitArgs(String cmdline) {
		List<String> list = new ArrayList<>();
		if (cmdline == null) {
			return list;
		}

		for (String arg : cmdline.split("\u0000")) {
			if (StrUtil.isNotEmpty(arg)) {
				list.add(arg);
			}
		}
		return list;
	}

	/**
	 * 在PATH中查找nginx可执行文件
	 */
	private static String which() {
		try {
			String out;
			if (SystemTool.isWindows()) {
				out = exec(10, "where", "nginx");
			} else {
				out = exec(10, "which", "nginx");
			}

			for (String line : out.split("\\r?\\n")) {
				line = line.trim();
				if (StrUtil.isNotEmpty(line) && new File(line).exists()) {
					return line;
				}
			}
		} catch (Exception e) {
			// 忽略查找异常
		}

		return null;
	}

	private static String match(Pattern pattern, String content) {
		Matcher matcher = pattern.matcher(content);
		return matcher.find() ? matcher.group(1) : null;
	}

	private static String trimEndSlash(String path) {
		if (path == null) {
			return null;
		}
		while (path.length() > 3 && (path.endsWith("/") || path.endsWith("\\"))) {
			path = path.substring(0, path.length() - 1);
		}
		return path;
	}

	/**
	 * 执行系统命令并合并标准错误输出, 超时后强制销毁进程.
	 * 输出在独立线程消费, 保证 waitFor 超时对挂起进程真正生效.
	 */
	private static String exec(long timeoutSeconds, String... command) {
		Process process = null;
		try {
			process = new ProcessBuilder(command).redirectErrorStream(true).start();
			StringBuilder result = new StringBuilder();

			Process current = process;
			Thread reader = new Thread(() -> {
				try (InputStream inputStream = current.getInputStream()) {
					byte[] buffer = new byte[4096];
					int length;
					while ((length = inputStream.read(buffer)) != -1) {
						result.append(new String(buffer, 0, length, StandardCharsets.UTF_8));
					}
				} catch (Exception e) {
					// 进程被销毁时读取中断, 忽略
				}
			}, "nginxui-exec-read");
			reader.setDaemon(true);
			reader.start();

			boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly();
				process.waitFor(2, TimeUnit.SECONDS);
			}
			reader.join(1000);
			return result.toString();
		} catch (Exception e) {
			return "";
		} finally {
			if (process != null) {
				process.destroyForcibly();
			}
		}
	}
}
