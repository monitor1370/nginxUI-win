package com.nginxui.utils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.W32Service;
import com.sun.jna.platform.win32.W32ServiceManager;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.Winsvc;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;

/**
 * Windows 服务控制管理器(SCM)中的 nginx 服务发现与控制.
 *
 * Windows 上 nginx 自身不能直接注册为服务, 通常由 winsw 包装器(nginx_service.exe)
 * 以 LocalSystem 托管: 服务进程为包装器, 其再启动 nginx master/worker,
 * 此时命令行/exe路径可能无法被普通权限读取. 本类通过 SCM 拿到稳定的服务名/状态/PID,
 * 并从包装器同目录(或 nginx_service.xml)推断 prefix/nginx exe/conf.
 */
public class WindowsNginxServiceUtils {

	/** SERVICE_WIN32 = OWN_PROCESS(0x10) | SHARE_PROCESS(0x20) */
	private static final int SERVICE_WIN32 = 0x00000030;

	private static final Pattern XML_EXECUTABLE = Pattern.compile(
			"<executable>\\s*(.*?)\\s*</executable>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	private static final Pattern XML_ARGUMENTS = Pattern.compile(
			"<arguments>\\s*(.*?)\\s*</arguments>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	private static final Pattern XML_WORKINGDIR = Pattern.compile(
			"<workingdirectory>\\s*(.*?)\\s*</workingdirectory>",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	/** WinSW xml 中解析出的启动要素(可能为null) */
	private static class XmlSpec {
		String executable;
		String prefixArg;
		String confArg;
		String workDir;
	}

	/** 一个注册在 SCM 中的 nginx 服务 */
	public static class WinNginxService {
		/** SCM 服务名(机器级唯一且稳定, 作为实例身份锚点) */
		public String serviceName;
		public String displayName;
		/** Winsvc.SERVICE_* 状态 */
		public int state;
		/** 运行中时为包装器进程pid, 停止时为0 */
		public int wrapperPid;
		/** 服务包装器可执行文件(规范化) */
		public String wrapperExe;
		/** 推断的 nginx prefix 目录(规范化) */
		public String prefixDir;
		/** 推断的实际 nginx 可执行文件(规范化) */
		public String nginxExe;
		/** 推断的配置文件(规范化), 可能为空(安装不完整/自定义布局) */
		public String confPath = "";

		public boolean running() {
			return state == Winsvc.SERVICE_RUNNING;
		}
	}

	/**
	 * 枚举所有 nginx 相关 Windows 服务(含已停止), 按服务名排序.
	 * 仅返回包装器/prefix 目录实际存在的服务; SCM 不可用时返回空列表.
	 */
	public static List<WinNginxService> list() {
		List<WinNginxService> result = new ArrayList<WinNginxService>();
		if (!SystemTool.isWindows()) {
			return result;
		}
		W32ServiceManager sm = new W32ServiceManager();
		try {
			sm.open(Winsvc.SC_MANAGER_ENUMERATE_SERVICE | Winsvc.SC_MANAGER_CONNECT);
			Winsvc.ENUM_SERVICE_STATUS_PROCESS[] rows =
					sm.enumServicesStatusExProcess(SERVICE_WIN32, Winsvc.SERVICE_STATE_ALL, null);
			if (rows == null) {
				return result;
			}
			for (Winsvc.ENUM_SERVICE_STATUS_PROCESS row : rows) {
				try {
					WinNginxService svc = resolve(row.lpServiceName, row.lpDisplayName,
							row.ServiceStatusProcess.dwCurrentState,
							row.ServiceStatusProcess.dwProcessId);
					if (svc != null) {
						result.add(svc);
					}
				} catch (Exception e) {
					// 单个服务解析失败不影响其余
				}
			}
		} catch (Exception e) {
			// SCM 枚举失败(权限/系统异常): 退化为无服务
		} finally {
			closeQuietly(sm);
		}
		result.sort(new java.util.Comparator<WinNginxService>() {
			@Override
			public int compare(WinNginxService a, WinNginxService b) {
				return a.serviceName.compareToIgnoreCase(b.serviceName);
			}
		});
		return result;
	}

	/** 按服务名查询当前状态(Winsvc.SERVICE_*), 失败返回 -1 */
	public static int queryState(String serviceName) {
		if (!SystemTool.isWindows() || StrUtil.isEmpty(serviceName)) {
			return -1;
		}
		W32ServiceManager sm = new W32ServiceManager();
		try {
			sm.open(Winsvc.SC_MANAGER_CONNECT);
			W32Service svc = sm.openService(serviceName, Winsvc.SERVICE_QUERY_STATUS);
			try {
				return svc.queryStatus().dwCurrentState;
			} finally {
				closeQuietly(svc);
			}
		} catch (Exception e) {
			return -1;
		} finally {
			closeQuietly(sm);
		}
	}

	/** 启动服务(同步), 已在运行视为成功 */
	public static void start(String serviceName) throws Win32Exception {
		W32ServiceManager sm = new W32ServiceManager();
		try {
			sm.open(Winsvc.SC_MANAGER_CONNECT);
			W32Service svc = sm.openService(serviceName,
					Winsvc.SERVICE_START | Winsvc.SERVICE_QUERY_STATUS);
			try {
				svc.startService();
			} catch (Win32Exception e) {
				// 1056: 服务已在运行
				if (e.getErrorCode() != 1056) {
					throw e;
				}
			} finally {
				closeQuietly(svc);
			}
		} finally {
			closeQuietly(sm);
		}
	}

	/** 停止服务(等待退出), 已停止视为成功 */
	public static void stop(String serviceName, long timeoutMillis) throws Win32Exception {
		W32ServiceManager sm = new W32ServiceManager();
		try {
			sm.open(Winsvc.SC_MANAGER_CONNECT);
			W32Service svc = sm.openService(serviceName,
					Winsvc.SERVICE_STOP | Winsvc.SERVICE_QUERY_STATUS);
			try {
				svc.stopService(timeoutMillis);
			} catch (Win32Exception e) {
				// 1062: 服务未运行
				if (e.getErrorCode() != 1062) {
					throw e;
				}
			} finally {
				closeQuietly(svc);
			}
		} finally {
			closeQuietly(sm);
		}
	}

	/** 轮询等待服务进入指定状态, 超时返回false */
	public static boolean waitState(String serviceName, int expectedState, long timeoutMillis) {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (System.currentTimeMillis() <= deadline) {
			int state = queryState(serviceName);
			if (state == expectedState) {
				return true;
			}
			try {
				Thread.sleep(800L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return queryState(serviceName) == expectedState;
	}

	/** 按服务名从 SCM 找到解析后的 nginx 服务(含已停止); 不存在/非nginx返回null */
	public static WinNginxService find(String serviceName) {
		if (StrUtil.isEmpty(serviceName)) {
			return null;
		}
		for (WinNginxService svc : list()) {
			if (svc.serviceName.equalsIgnoreCase(serviceName.trim())) {
				return svc;
			}
		}
		return null;
	}

	/** 包装器同级是否存在 WinSW xml(同名或 nginx_service.xml) */
	public static boolean isWinSWWrapper(WinNginxService svc) {
		if (svc == null || StrUtil.isEmpty(svc.wrapperExe)) {
			return false;
		}
		return hasWrapperXml(new File(svc.wrapperExe));
	}

	/** 包装器同级是否存在同名 xml / 旧版 nginx_service.xml */
	private static boolean hasWrapperXml(File wrapper) {
		File dir = wrapper.getParentFile();
		if (dir == null) {
			return false;
		}
		String mainName = FileUtil.mainName(wrapper);
		if (StrUtil.isNotEmpty(mainName) && new File(dir, mainName + ".xml").isFile()) {
			return true;
		}
		return new File(dir, "nginx_service.xml").isFile();
	}

	/**
	 * 卸载 nginx Windows 服务(不删除任何磁盘文件):
	 * WinSW 包装器执行 {@code <wrapper> stop} + {@code <wrapper> uninstall};
	 * 非 WinSW 服务仅在 allowScDelete=true 时走 sc delete, 否则返回提示信息.
	 *
	 * @return null 表示成功; 否则为面向用户的错误说明
	 */
	public static String uninstallService(String serviceName, boolean allowScDelete) {
		WinNginxService svc = find(serviceName);
		if (svc == null) {
			// 服务已不存在视为成功
			if (queryState(serviceName) == -1) {
				return null;
			}
			return "服务无法解析(可能不是 nginx 服务或安装目录已失效): " + serviceName;
		}
		try {
			if (isWinSWWrapper(svc)) {
				File wrapper = new File(svc.wrapperExe);
				runWrapper(wrapper, 30_000L, "stop");
				runWrapper(wrapper, 60_000L, "uninstall");
				if (!waitState(serviceName, Winsvc.SERVICE_STOPPED, 8_000L)
						&& queryState(serviceName) != -1) {
					// WinSW 标记删除后通常在 SCM 关闭句柄后消失, 再等一轮
					Thread.sleep(2_000L);
					if (queryState(serviceName) != -1) {
						return "服务卸载命令已执行但 SCM 中仍存在: " + serviceName;
					}
				}
				return null;
			}
			if (allowScDelete) {
				ProcessBuilder pb = new ProcessBuilder("sc", "delete", serviceName);
				pb.redirectErrorStream(true);
				final Process process = pb.start();
				String out = drain(process);
				boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
				if (!finished) {
					process.destroyForcibly();
					return "sc delete 执行超时";
				}
				if (process.exitValue() == 0 || queryState(serviceName) == -1) {
					return null;
				}
				return "sc delete 失败: " + out.trim();
			}
			return "该服务不是标准 WinSW 托管服务，已跳过服务卸载"
					+ "（如确需强制删除其 SCM 注册项，可勾选【强制删除非标准服务(sc delete)】）";
		} catch (Exception e) {
			return "卸载服务失败: " + e.getMessage();
		}
	}

	private static void runWrapper(File wrapper, long timeoutMs, String arg) throws Exception {
		ProcessBuilder pb = new ProcessBuilder(wrapper.getAbsolutePath(), arg);
		pb.directory(wrapper.getParentFile());
		pb.redirectErrorStream(true);
		Process process = pb.start();
		String out = drain(process);
		boolean finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
		if (!finished) {
			process.destroyForcibly();
			throw new IllegalStateException(wrapper.getName() + " " + arg + " 执行超时");
		}
		if (process.exitValue() != 0 && !out.toLowerCase().contains("specified service does not exist")
				&& !out.contains("服务不存在") && !out.contains("1060") && !out.contains("1062")) {
			throw new IllegalStateException(wrapper.getName() + " " + arg + " 退出码 "
					+ process.exitValue() + ": " + out.trim());
		}
	}

	private static String drain(Process process) {
		try {
			java.io.InputStream in = process.getInputStream();
			java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
			byte[] tmp = new byte[2048];
			int n;
			while ((n = in.read(tmp)) > 0) {
				buf.write(tmp, 0, n);
				if (buf.size() > 65536) {
					break;
				}
			}
			return new String(buf.toByteArray(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			return "";
		}
	}

	/** 权限不足等错误的友好提示 */
	public static String friendlyError(Win32Exception e) {
		int code = e == null ? 0 : e.getErrorCode();
		if (code == 5) {
			return "服务控制权限被拒绝，请以管理员身份运行 nginxUI";
		}
		if (code == 1053) {
			return "服务启动超时(1053)，请检查该 nginx 服务的配置与日志";
		}
		if (code == 1058) {
			return "服务已被禁用(1058)，无法启动";
		}
		if (code == 1069) {
			return "服务因登录账户问题无法启动(1069)，请检查服务属性中的登录身份";
		}
		return "Windows 服务操作失败(" + code + "): " + (e == null ? "" : e.getMessage());
	}

	/**
	 * 解析单个服务: 读注册表 ImagePath → 判定 nginx 服务 → 推断布局.
	 * @return nginx 服务元数据; 非nginx服务/目录不存在时返回null
	 */
	private static WinNginxService resolve(String serviceName, String displayName, int state, int pid) {
		String imagePath = readImagePath(serviceName);
		if (StrUtil.isEmpty(imagePath)) {
			return null;
		}
		String wrapperExe = firstExeToken(imagePath);
		if (StrUtil.isEmpty(wrapperExe)) {
			return null;
		}
		String wrapperBase = FileUtil.getName(wrapperExe).toLowerCase();
		File wrapper = new File(wrapperExe);
		File dir = wrapper.getParentFile();
		if (dir == null || !dir.isDirectory()) {
			// 注册残留(安装目录已删除): 不展示
			return null;
		}
		// 包装器名含 nginx 直接视为候选; 自定义实例名的包装器(如 mynginx01.exe)
		// 需同级存在 WinSW xml 才继续解析, 避免把无关服务误判为 nginx
		if (!wrapperBase.contains("nginx") && !hasWrapperXml(wrapper)) {
			return null;
		}

		WinNginxService svc = new WinNginxService();
		svc.serviceName = serviceName;
		svc.displayName = StrUtil.emptyToDefault(displayName, serviceName);
		svc.state = state;
		svc.wrapperPid = pid;
		svc.wrapperExe = NginxInstanceRegistry.canonical(wrapperExe);

		// 优先读 WinSW xml(与wrapper同名 → 旧版 nginx_service.xml), 支持程序目录与prefix分离布局
		XmlSpec spec = readXmlSpec(dir, wrapperExe);

		File nginxExe = null;
		String specPrefix = null;
		if (spec != null) {
			if (StrUtil.isNotEmpty(spec.executable)) {
				File configured = new File(expandBase(spec.executable.trim(), dir));
				String configuredBase = configured.getName().toLowerCase();
				if (configured.isFile() && configuredBase.contains("nginx")
						&& !samePath(configured, wrapper)) {
					nginxExe = configured;
				}
			}
			String prefixRaw = StrUtil.emptyToDefault(spec.prefixArg, spec.workDir);
			if (StrUtil.isNotEmpty(prefixRaw)) {
				File pd = new File(expandBase(prefixRaw.trim(), dir));
				if (pd.isDirectory()) {
					specPrefix = NginxInstanceRegistry.canonical(pd.getAbsolutePath());
				}
			}
		}
		if (nginxExe == null) {
			nginxExe = resolveNginxExe(dir, wrapperExe);
		}
		if (nginxExe == null || !nginxExe.isFile()) {
			// 目录里没有任何 nginx 可执行文件, 视为无效注册
			return null;
		}
		svc.nginxExe = NginxInstanceRegistry.canonical(nginxExe.getAbsolutePath());
		svc.prefixDir = specPrefix != null ? specPrefix
				: NginxInstanceRegistry.canonical(dir.getAbsolutePath());

		// conf: xml -c 参数(相对prefix解析) → prefix/conf/nginx.conf → wrapper同级(旧布局)
		String conf = null;
		if (spec != null && StrUtil.isNotEmpty(spec.confArg)) {
			File cf = new File(expandBase(spec.confArg.trim(), dir));
			if (!cf.isAbsolute()) {
				cf = new File(svc.prefixDir, spec.confArg.trim());
			}
			if (cf.isFile()) {
				conf = NginxInstanceRegistry.canonical(cf.getAbsolutePath());
			}
		}
		if (conf == null) {
			File std = new File(svc.prefixDir, "conf" + File.separator + "nginx.conf");
			if (std.isFile()) {
				conf = NginxInstanceRegistry.canonical(std.getAbsolutePath());
			}
		}
		if (conf == null && specPrefix == null) {
			File legacy = new File(dir, "conf" + File.separator + "nginx.conf");
			if (legacy.isFile()) {
				conf = NginxInstanceRegistry.canonical(legacy.getAbsolutePath());
			}
		}
		svc.confPath = StrUtil.nullToEmpty(conf);
		return svc;
	}

	private static boolean samePath(File a, File b) {
		try {
			return a.getCanonicalPath().equalsIgnoreCase(b.getCanonicalPath());
		} catch (Exception e) {
			return a.getAbsolutePath().equalsIgnoreCase(b.getAbsolutePath());
		}
	}

	private static String expandBase(String value, File baseDir) {
		return value.replace("%BASE%", baseDir.getAbsolutePath());
	}

	/**
	 * 读 WinSW 配置: 优先与包装器同名的 xml(如 nginx-win-site-1.30.1-1.exe → .xml),
	 * 回退旧版固定名 nginx_service.xml; 都不存在时返回null.
	 */
	private static XmlSpec readXmlSpec(File dir, String wrapperExe) {
		File xml = null;
		try {
			String mainName = FileUtil.mainName(wrapperExe);
			if (StrUtil.isNotEmpty(mainName)) {
				File sameName = new File(dir, mainName + ".xml");
				if (sameName.isFile()) {
					xml = sameName;
				}
			}
			if (xml == null) {
				File legacy = new File(dir, "nginx_service.xml");
				if (legacy.isFile()) {
					xml = legacy;
				}
			}
		} catch (Exception e) {
			return null;
		}
		if (xml == null) {
			return null;
		}
		try {
			String text = FileUtil.readString(xml, StandardCharsets.UTF_8);
			XmlSpec spec = new XmlSpec();
			Matcher m = XML_EXECUTABLE.matcher(text);
			if (m.find()) {
				spec.executable = m.group(1).trim();
			}
			m = XML_ARGUMENTS.matcher(text);
			String args = m.find() ? m.group(1) : "";
			List<String> tokens = tokenizeArgs(args);
			for (int i = 0; i < tokens.size() - 1; i++) {
				String t = tokens.get(i);
				if (("-p".equals(t) || "-prefix".equals(t)) && StrUtil.isEmpty(spec.prefixArg)) {
					spec.prefixArg = tokens.get(i + 1);
				} else if (("-c".equals(t)) && StrUtil.isEmpty(spec.confArg)) {
					spec.confArg = tokens.get(i + 1);
				}
			}
			m = XML_WORKINGDIR.matcher(text);
			if (m.find()) {
				spec.workDir = m.group(1).trim();
			}
			return spec;
		} catch (Exception e) {
			return null;
		}
	}

	/** 按引号/空白切分命令行参数(去掉包裹引号) */
	private static List<String> tokenizeArgs(String args) {
		List<String> tokens = new ArrayList<String>();
		Matcher m = Pattern.compile("\"([^\"]*)\"|(\\S+)").matcher(args);
		while (m.find()) {
			tokens.add(m.group(1) != null ? m.group(1) : m.group(2));
		}
		return tokens;
	}

	/**
	 * xml 未给出可用 exe 时的目录推断(兜底):
	 * 仅当包装器本身就是标准名 nginx.exe/nginx_basic.exe(如 nssm 直接托管),
	 * 或目录下存在这两个文件时命中; 自定义命名的 WinSW 包装器(如
	 * nginx-win-site-1.30.1-1.exe)不能把自身误判为 nginx 程序.
	 */
	private static File resolveNginxExe(File dir, String wrapperExe) {
		String wrapperBase = FileUtil.getName(wrapperExe).toLowerCase();
		if (wrapperBase.equals("nginx.exe") || wrapperBase.equals("nginx_basic.exe")) {
			File self = new File(wrapperExe);
			if (self.isFile()) {
				return self;
			}
		}
		File basic = new File(dir, "nginx_basic.exe");
		if (basic.isFile()) {
			return basic;
		}
		File std = new File(dir, "nginx.exe");
		if (std.isFile()) {
			return std;
		}
		return null;
	}

	/** 读取服务注册表项 ImagePath(HKLM\SYSTEM\CurrentControlSet\Services\<name>) */
	private static String readImagePath(String serviceName) {
		try {
			return Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE,
					"SYSTEM\\CurrentControlSet\\Services\\" + serviceName, "ImagePath");
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * 取命令行中的可执行文件 token:
	 * 带引号取引号内; 否则取第一个空格前(Windows 服务路径约定).
	 */
	private static String firstExeToken(String commandLine) {
		String s = commandLine.trim();
		if (s.isEmpty()) {
			return null;
		}
		if (s.charAt(0) == '"') {
			int end = s.indexOf('"', 1);
			return end > 1 ? s.substring(1, end) : null;
		}
		int sp = s.indexOf(' ');
		return sp > 0 ? s.substring(0, sp) : s;
	}

	private static void closeQuietly(java.io.Closeable closeable) {
		if (closeable != null) {
			try {
				closeable.close();
			} catch (Exception e) {
				// ignore
			}
		}
	}

}
