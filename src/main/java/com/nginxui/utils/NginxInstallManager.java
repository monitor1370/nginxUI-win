package com.nginxui.utils;

import java.io.File;
import java.nio.file.Files;

import com.nginxui.ext.NginxInstanceEntry;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;

/**
 * 全新 nginx 实例安装管理器: 从官方 zip 包解压 → 生成标准 conf/html/logs 目录 →
 * 按标准实例名(固定前缀 nginx-win-ngui- + 变量 N)注册实例并注册 Windows 服务.
 *
 * 安装流程:
 *  1. 解压官方 nginx-x.y.z.zip 到 {安装根目录}/{实例名};
 *  2. 内部校验 nginx -v、默认 nginx.conf 结构完整;
 *  3. 手动注册到 NginxInstanceRegistry, 自动分配唯一 stub 端口;
 *  4. 生成同名 WinSW 包装器/服务配置/停止脚本并注册 Windows 服务(SCM);
 *  5. 返回新建实例条目(已带 winServiceName, 后续启停走服务控制).
 *
 *  离线安装: 直接使用用户上传的本地 zip;
 *  在线安装: 先 download 稳定版 zip, 再走相同解压/注册流程.
 */
public class NginxInstallManager {

	private final File home;
	private final NginxInstanceRegistry registry;

	public NginxInstallManager(File home, NginxInstanceRegistry registry) {
		this.home = home;
		this.registry = registry;
	}

	/** 安装根目录: {工具目录}/instances, 所有全新安装的实例集中存放 */
	public File instancesRoot() {
		return FileUtil.mkdir(new File(home, "instances"));
	}

	/**
	 * 安装结果.
	 * 无论在线/离线, 解压失败会自动清理半成品目录; 成功直接返回可纳管实例.
	 */
	public static class InstallResult {
		public boolean success;
		public String message;
		/** 全新生成的实例条目(仅 success=true 时有效) */
		public NginxInstanceEntry entry;
		public String installedVersion;
		/** 在线安装时超过 180 秒下载超时:  UI 据此直接引导用户走离线上传 */
		public boolean downloadTimeout;
	}

	/** 进度回调(后台线程调用, 调用方切回 EDT) */
	public interface Progress {
		void log(String message);
		void stage(String label);
	}

	/**
	 * 全新安装: 从指定 zip 安装包在 installRoot 下创建独立实例.
	 *
	 * @param localZip 已校验完成的本地 nginx-x.y.z.zip; null 表示从在线下载 targetVersion
	 * @param targetVersion 在线安装时指定的稳定版本号, localZip!=null 时可传空
	 * @param installRoot 安装根目录(用户可选), 实例最终落在其下
	 * @param instanceName 实例名称(目录名=服务名); 空表示按标准规则自动生成 nginx-win-ngui-N
	 */
	public InstallResult install(String targetVersion, File localZip, File installRoot,
			String instanceName, Progress p) {
		InstallResult r = new InstallResult();
		if (installRoot == null) {
			return fail(r, "请选择安装位置", p);
		}
		File root;
		try {
			root = FileUtil.mkdir(installRoot.getAbsoluteFile());
		} catch (Exception e) {
			return fail(r, "安装位置不可用: " + e.getMessage(), p);
		}
		if (!root.isDirectory() || !root.canWrite()) {
			return fail(r, "安装位置不可写: " + root.getAbsolutePath(), p);
		}
		File zip;
		String ver;

		// --- 1) 获取安装包 ---
		try {
			if (localZip != null) {
				stage(p, "校验本地安装包");
				ver = NginxPackageManager.inspectZip(localZip);
				zip = localZip;
				log(p, "本地安装包版本: " + ver + " (" + (localZip.length() / 1024) + " KB)");
			} else {
				ver = StrUtil.trim(targetVersion);
				if (StrUtil.isEmpty(ver)) {
					return fail(r, "请选择目标版本", p);
				}
				stage(p, "下载 nginx-" + ver + ".zip");
				NginxPackageManager.PackageInfo info = NginxPackageManager.download(ver,
						m -> log(p, m));
				zip = NginxPackageManager.zipPath(info.token);
			}
		} catch (Exception e) {
			if (localZip == null && isDownloadTimeout(e)) {
				r.downloadTimeout = true;
			}
			return fail(r, "获取安装包失败: " + e.getMessage(), p);
		}
		r.installedVersion = ver;

		// --- 2) 实例名称: 用户自定义(格式+唯一性双重校验) 或 标准命名 nginx-win-ngui-N ---
		String serviceName;
		String customName = StrUtil.trimToEmpty(instanceName);
		if (StrUtil.isNotEmpty(customName)) {
			String nameError = NginxWinServiceInstaller.checkInstanceName(customName, root);
			if (nameError != null) {
				return fail(r, nameError, p);
			}
			serviceName = customName;
			log(p, "自定义实例名称: " + serviceName);
		} else {
			serviceName = NginxWinServiceInstaller.nextServiceName(root);
			log(p, "标准实例命名: " + serviceName);
		}
		File programDir = new File(root, serviceName);
		if (programDir.exists()) {
			return fail(r, "实例目录已存在: " + programDir.getAbsolutePath(), p);
		}

		// --- 3) 安全解压到同盘临时目录, 再原子就位到实例目录 ---
		File stageRoot = new File(root, ".install-"
				+ java.util.UUID.randomUUID().toString().substring(0, 8));
		try {
			stage(p, "解压安装包");
			NginxPackageManager.UnpackResult pkg = NginxPackageManager.unpack(zip, stageRoot,
					m -> log(p, m));
			File rawDir = pkg.programDir;
			try {
				Files.move(rawDir.toPath(), programDir.toPath());
			} catch (Exception moveError) {
				// 跨目录/被占用时回退到复制后删除
				stage(p, "整理实例目录");
				FileUtil.copyContent(rawDir, programDir, true);
			}
			FileUtil.del(stageRoot);
			log(p, "实例目录就位: " + programDir.getAbsolutePath());
		} catch (Exception e) {
			FileUtil.del(stageRoot);
			FileUtil.del(programDir);
			return fail(r, "解压失败: " + e.getMessage(), p);
		}

		// --- 4) 最低完整性校验: 存在 nginx.exe / conf/nginx.conf / html/index.html ---
		File exe = new File(programDir, "nginx.exe");
		File conf = new File(new File(programDir, "conf"), "nginx.conf");
		File html = new File(new File(programDir, "html"), "index.html");
		if (!exe.isFile()) {
			FileUtil.del(programDir);
			return fail(r, "安装包内缺少 nginx.exe", p);
		}
		if (!conf.isFile()) {
			FileUtil.del(programDir);
			return fail(r, "安装包内缺少 conf/nginx.conf", p);
		}
		if (!html.isFile()) {
			FileUtil.del(programDir);
			return fail(r, "安装包内缺少 html/index.html", p);
		}

		// --- 5) 用新解压出的 nginx.exe 执行 nginx -t 预检: 标准包必须通过 ---
		if (!new File(programDir, "logs").isDirectory()) {
			FileUtil.mkdir(new File(programDir, "logs"));
		}
		stage(p, "配置预检 (nginx -t)");
		NginxInstanceEntry probe = new NginxInstanceEntry();
		probe.setNginxExe(exe.getAbsolutePath());
		probe.setConfPath(conf.getAbsolutePath());
		probe.setNginxDir(programDir.getAbsolutePath());
		probe.setPrefixDir(programDir.getAbsolutePath());
		NginxDashboardUtils.ConfigTestResult pre = NginxDashboardUtils.testInstanceConfigWithExe(
				probe, exe.getAbsolutePath());
		log(p, pre.output);
		if (!pre.success) {
			FileUtil.del(programDir);
			return fail(r, "标准安装包配置预检未通过(已清理): " + pre.message, p);
		}

		// --- 6) 按标准名注册到实例注册表, 再注册为 Windows 服务 ---
		NginxInstanceEntry entry;
		try {
			stage(p, "注册实例");
			entry = registry.manualRegister(exe.getAbsolutePath(), conf.getAbsolutePath(),
					programDir.getAbsolutePath(), serviceName);
		} catch (Exception e) {
			FileUtil.del(programDir);
			return fail(r, "注册实例失败(已清理): " + e.getMessage(), p);
		}

		// --- 7) 标准服务注册: 同名 WinSW 包装器 + <服务名>.xml + stop.bat ---
		stage(p, "注册 Windows 服务");
		NginxWinServiceInstaller.Result svc =
				NginxWinServiceInstaller.install(programDir, exe, serviceName, m -> log(p, m));
		if (!svc.ok) {
			registry.remove(entry.getId());
			FileUtil.del(programDir);
			return fail(r, "服务注册失败(已清理): " + svc.message, p);
		}
		entry.setWinServiceName(serviceName);
		r.entry = entry;
		r.success = true;
		r.message = "全新安装完成: " + ver + " → " + serviceName + "（已注册 Windows 服务）";
		log(p, "实例已注册: id=" + entry.getId() + ", name=" + serviceName
				+ ", stubPort=" + entry.getStubPort() + ", 服务=" + serviceName);
		return r;
	}

	private static boolean isDownloadTimeout(Throwable e) {
		return e != null && String.valueOf(e.getMessage()).contains("下载超时");
	}

	private InstallResult fail(InstallResult r, String msg, Progress p) {
		r.success = false;
		r.message = msg;
		log(p, "失败: " + msg);
		return r;
	}

	private static void log(Progress p, String m) {
		if (p != null) {
			p.log(m);
		}
	}

	private static void stage(Progress p, String label) {
		if (p != null) {
			p.stage(label);
		}
	}
}
