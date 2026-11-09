package com.nginxui.utils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nginxui.ext.NginxDashboard;
import com.nginxui.ext.NginxInfo;
import com.nginxui.ext.NginxProc;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;

/**
 * 探针模块: 进程采样与角色归类、nginx -V 编译信息、stub_status HTTP 采集.
 * 只负责"取数", 不参与实例注册与生命周期控制.
 */
public final class NginxProbeService {

	private static final Logger LOG = LoggerFactory.getLogger(NginxProbeService.class);

	private static final Pattern VERSION_PATTERN = Pattern.compile("nginx version:\\s*(?:nginx/)?(\\S+)");
	private static final Pattern ACTIVE_PATTERN = Pattern.compile("Active connections:\\s*(\\d+)");
	private static final Pattern REQ_PATTERN = Pattern
			.compile("server accepts handled requests\\D+?(\\d+)\\s+(\\d+)\\s+(\\d+)");
	private static final Pattern RWW_PATTERN = Pattern
			.compile("Reading:\\s*(\\d+)\\s+Writing:\\s*(\\d+)\\s+Waiting:\\s*(\\d+)");

	/** nginx -V 输出按可执行文件路径缓存(多实例各自缓存), 避免轮询时反复起进程 */
	private static class BuildCache {
		String info;
		String args;
		List<String> modules;
	}

	private static final Map<String, BuildCache> BUILD_CACHE = new ConcurrentHashMap<String, BuildCache>();
	/** stub_status 不可达负缓存: url -> 允许再次探测的时间戳, 避免未启用时每轮轮询都等满超时 */
	private static final Map<String, Long> STUB_FAIL_UNTIL = new ConcurrentHashMap<String, Long>();
	private static final long STUB_FAIL_TTL_MS = 30_000L;

	private NginxProbeService() {
	}

	/**
	 * 进程明细采样.
	 */
	public static List<NginxProc> sampleProcesses() {
		try {
			if (SystemTool.isWindows()) {
				return sampleWindows();
			}
			if (SystemTool.isLinux()) {
				return sampleLinux();
			}
			return sampleByPs();
		} catch (Exception e) {
			LOG.warn("[{}] 进程采样失败: {}", NginxErrorCode.PROCESS_SAMPLE_FAILED, e.getMessage(), e);
			return new ArrayList<NginxProc>();
		}
	}

	/**
	 * Windows: 通过 oshi(JNI) 枚举 nginx 进程.
	 * 取代 PowerShell+CIM 方案(冷启动/WMI 查询耗时数十秒).
	 */
	private static List<NginxProc> sampleWindows() {
		List<NginxProc> procs = new ArrayList<>();
		for (oshi.software.os.OSProcess p : nginxOsProcesses()) {
			NginxProc proc = new NginxProc();
			proc.setPid(String.valueOf(p.getProcessID()));
			proc.setPpid(String.valueOf(p.getParentProcessID()));
			String procName = StrUtil.nullToEmpty(p.getName());
			if (SystemTool.isWindows() && !procName.toLowerCase().endsWith(".exe")) {
				procName = procName + ".exe";
			}
			proc.setName(procName);
			procs.add(proc);
		}
		classify(procs);
		return procs;
	}

	/**
	 * oshi 快照中所有 nginx* 进程
	 */
	private static List<oshi.software.os.OSProcess> nginxOsProcesses() {
		List<oshi.software.os.OSProcess> nginxProcs = new ArrayList<>();
		List<oshi.software.os.OSProcess> all = cn.hutool.system.oshi.OshiUtil.getOs().getProcesses();
		if (all != null) {
			for (oshi.software.os.OSProcess p : all) {
				String low = StrUtil.nullToEmpty(p.getName()).toLowerCase();
				if (low.endsWith(".exe")) low = low.substring(0, low.length() - 4);
				if (low.equals("nginx") || low.equals("nginx_basic")) {
					nginxProcs.add(p);
				}
			}
		}
		return nginxProcs;
	}

	/**
	 * Linux: /proc/<pid>/ 枚举 nginx 进程, 角色由 cmdline 判定.
	 */
	private static List<NginxProc> sampleLinux() {
		List<NginxProc> procs = new ArrayList<>();
		for (File dir : nginxPidDirs()) {
			NginxProc p = new NginxProc();
			p.setPid(dir.getName());
			p.setName("nginx");
			procs.add(p);
		}
		classifyLinux(procs);
		return procs;
	}

	private static List<File> nginxPidDirs() {
		List<File> list = new ArrayList<>();
		File[] dirs = new File("/proc").listFiles();
		if (dirs == null) {
			return list;
		}
		for (File dir : dirs) {
			if (!dir.getName().matches("\\d+")) {
				continue;
			}
			File comm = new File(dir, "comm");
			if (comm.isFile() && "nginx".equalsIgnoreCase(FileUtil.readString(comm, StandardCharsets.UTF_8).trim())) {
				list.add(dir);
			}
		}
		return list;
	}

	/**
	 * Linux按/proc/<pid>/cmdline中的改写标题区分master/worker
	 */
	private static void classifyLinux(List<NginxProc> procs) {
		for (NginxProc p : procs) {
			try {
				String cmdline = FileUtil.readString(new File("/proc/" + p.getPid() + "/cmdline"),
						StandardCharsets.UTF_8);
				String joined = cmdline.replace('\u0000', ' ').trim();
				if (joined.contains("nginx: master process") || joined.startsWith("nginx: master")) {
					p.setRole("master");
				} else if (joined.startsWith("nginx:")) {
					p.setRole("worker");
				} else {
					p.setRole("other");
					p.setName(NginxExecUtils.firstToken(joined));
				}
			} catch (Exception e) {
				LOG.debug("[{}] 无法读取 /proc/{}/cmdline", NginxErrorCode.READ_LOG_FAILED, p.getPid());
				p.setRole("other");
			}
		}
	}

	/**
	 * Mac等: ps即时采样
	 */
	private static List<NginxProc> sampleByPs() {
		String out = NginxExecUtils.exec(20, "ps", "-Ao", "pid,ppid,comm");
		List<NginxProc> procs = new ArrayList<>();
		for (String line : out.split("\\r?\\n")) {
			String[] f = line.trim().split("\\s+", 3);
			if (f.length < 3 || !f[0].matches("\\d+") || !"nginx".equalsIgnoreCase(f[2])) {
				continue;
			}
			NginxProc p = new NginxProc();
			p.setPid(f[0]);
			p.setPpid(f[1]);
			p.setName(f[2]);
			procs.add(p);
		}
		classify(procs);
		return procs;
	}

	/**
	 * Windows/Mac通用: service进程为other, 父进程不在nginx集合中的为master, 其余worker.
	 * oshi 在 Windows 上进程名不带 .exe 后缀, 判定不以后缀为准.
	 */
	private static void classify(List<NginxProc> procs) {
		java.util.Set<String> nginxPids = new java.util.HashSet<>();
		for (NginxProc p : procs) {
			String name = StrUtil.nullToEmpty(p.getName()).toLowerCase();
			if (name.endsWith(".exe")) name = name.substring(0, name.length() - 4);
			boolean isNginx = name.equals("nginx") || name.equals("nginx_basic");
			if (isNginx) {
				nginxPids.add(p.getPid());
			}
		}

		for (NginxProc p : procs) {
			String name = StrUtil.nullToEmpty(p.getName()).toLowerCase();
			if (name.contains("service")) {
				p.setRole("other");
			} else if (p.getPpid() == null || !nginxPids.contains(p.getPpid())) {
				p.setRole("master");
			} else {
				p.setRole("worker");
			}
		}
	}

	static void fillProcessCounts(NginxDashboard d, List<NginxProc> procs) {
		int master = 0;
		int worker = 0;
		int other = 0;
		for (NginxProc p : procs) {
			if ("master".equals(p.getRole())) {
				master++;
			} else if ("worker".equals(p.getRole())) {
				worker++;
			} else {
				other++;
			}
		}
		d.setProcTotal(procs.size());
		d.setMasterCount(master);
		d.setWorkerCount(worker);
		d.setOtherCount(other);
	}

	static void fillBuildInfo(NginxDashboard d, NginxInfo info) {
		String exe = info.getNginxExe();
		if (StrUtil.isEmpty(exe) || !new File(exe).exists()) {
			return;
		}

		String out;
		BuildCache cached = BUILD_CACHE.get(exe);
		if (cached != null && StrUtil.isNotEmpty(cached.info)) {
			d.setBuildInfo(cached.info);
			d.setConfigureArgs(cached.args);
			d.setModules(cached.modules == null ? null : new ArrayList<>(cached.modules));
			out = cached.info;
		} else {
			out = NginxExecUtils.exec(15, exe, "-V");
			if (StrUtil.isEmpty(out)) {
				return;
			}

			int idx = out.indexOf("configure arguments:");
			if (idx >= 0) {
				d.setBuildInfo(out.substring(0, idx).trim());
				d.setConfigureArgs(out.substring(idx + "configure arguments:".length()).trim());
			} else {
				d.setBuildInfo(out.trim());
			}

			// 编译参数中显式启用的模块
			List<String> modules = new ArrayList<>();
			String args = StrUtil.nullToEmpty(d.getConfigureArgs());
			Matcher m = Pattern.compile("--with-(\\S*?module\\S*)").matcher(args);
			while (m.find()) {
				String name = "ngx_" + m.group(1);
				if (!modules.contains(name)) {
					modules.add(name);
				}
			}
			Collections.sort(modules);
			d.setModules(modules);

			BuildCache bc = new BuildCache();
			bc.info = d.getBuildInfo();
			bc.args = d.getConfigureArgs();
			bc.modules = modules;
			BUILD_CACHE.put(exe, bc);
		}

		if (StrUtil.isEmpty(d.getVersion())) {
			Matcher vm = VERSION_PATTERN.matcher(out);
			if (vm.find()) {
				d.setVersion(vm.group(1));
			}
		}
	}

	/** 取(或探测)nginx -V 的 configure arguments, 供 stub_status 模块能力判断 */
	static String configureArgs(String exe) {
		BuildCache cached = BUILD_CACHE.get(exe);
		String args = cached == null ? null : cached.args;
		if (StrUtil.isNotEmpty(args)) {
			return args;
		}
		String out = NginxExecUtils.exec(15, exe, "-V");
		int idx = out.indexOf("configure arguments:");
		return idx >= 0 ? out.substring(idx + "configure arguments:".length()).trim() : "";
	}

	/** 服务补全exe后尝试补版本(nginx -v) */
	static void fillVersionByExe(NginxInfo info, String exe) {
		try {
			if (StrUtil.isEmpty(exe) || !new File(exe).isFile()) {
				return;
			}
			String out = NginxExecUtils.exec(new File(exe).getParentFile(), 8, exe, "-v");
			Matcher m = VERSION_PATTERN.matcher(StrUtil.nullToEmpty(out));
			if (m.find()) {
				info.setVersion(m.group(1));
			}
		} catch (Exception e) {
			LOG.warn("[{}] 版本探测失败: {}", NginxErrorCode.VERSION_PROBE_FAILED, exe, e);
		}
	}

	static void fillStubStatus(NginxDashboard d, String url) {
		Long failUntil = STUB_FAIL_UNTIL.get(url);
		if (failUntil != null && System.currentTimeMillis() < failUntil) {
			d.setStubEnabled(false);
			return;
		}

		String body = httpGet(url);
		if (StrUtil.isEmpty(body) || !body.contains("Active connections")) {
			d.setStubEnabled(false);
			STUB_FAIL_UNTIL.put(url, System.currentTimeMillis() + STUB_FAIL_TTL_MS);
			return;
		}

		STUB_FAIL_UNTIL.remove(url);
		d.setStubEnabled(true);
		Matcher am = ACTIVE_PATTERN.matcher(body);
		if (am.find()) {
			d.setActiveConnections(Long.parseLong(am.group(1)));
		}
		Matcher rm = REQ_PATTERN.matcher(body);
		if (rm.find()) {
			d.setAccepts(Long.parseLong(rm.group(1)));
			d.setHandled(Long.parseLong(rm.group(2)));
			d.setRequests(Long.parseLong(rm.group(3)));
		}
		Matcher rwm = RWW_PATTERN.matcher(body);
		if (rwm.find()) {
			d.setReading(Long.parseLong(rwm.group(1)));
			d.setWriting(Long.parseLong(rwm.group(2)));
			d.setWaiting(Long.parseLong(rwm.group(3)));
		}

		if (d.getMaxConnections() != null && d.getActiveConnections() != null && d.getMaxConnections() > 0) {
			d.setConnUsagePercent(NginxExecUtils.round(d.getActiveConnections() * 100.0 / d.getMaxConnections(), 2));
		}
		if (d.getHandled() != null && d.getHandled() > 0 && d.getRequests() != null) {
			d.setReqPerConn(NginxExecUtils.round(d.getRequests() * 1.0 / d.getHandled(), 2));
		}
	}

	/** 清除stub_status不可达负缓存(开关切换后下一轮立即重新探测) */
	public static void forgetStubFailure(String url) {
		if (StrUtil.isNotEmpty(url)) {
			STUB_FAIL_UNTIL.remove(url);
		}
	}

	private static String httpGet(String url) {
		HttpURLConnection conn = null;
		try {
			conn = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
			conn.setConnectTimeout(1000);
			conn.setReadTimeout(1000);
			conn.setRequestMethod("GET");
			try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
				byte[] buffer = new byte[4096];
				int len;
				while ((len = in.read(buffer)) != -1) {
					out.write(buffer, 0, len);
				}
				return new String(out.toByteArray(), StandardCharsets.UTF_8);
			}
		} catch (Exception e) {
			// 未启用 stub_status 时连接失败属预期, 只留排查日志, 不打扰用户
			LOG.debug("[{}] {} ({})", NginxErrorCode.STUB_PROBE_FAILED, url, e.getMessage());
			return null;
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}
	}
}
