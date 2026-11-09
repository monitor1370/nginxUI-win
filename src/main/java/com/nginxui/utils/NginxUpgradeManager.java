package com.nginxui.utils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nginxui.ext.NginxInstanceEntry;
import com.nginxui.ext.NginxUpgradeRecord;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;

/**
 * 单实例 nginx 版本升级/回滚(Windows, 就地替换可执行文件).
 *
 * 设计取舍: nginx for Windows 的程序与配置同目录, 升级只替换 nginx.exe,
 * 保留 conf/html/logs/instances 配置; 不移动目录, 因此:
 *  - 实例的 prefix/conf/exe 路径不变, 注册表身份与 stub 端口延续;
 *  - WinSW 托管的服务无需改 xml, 停服务→换 exe→启服务即可;
 *  - 回滚 = 从备份文件恢复 exe.
 *
 * 安全顺序: 新版本 nginx -t 预检(无改动) → 停止受影响的运行实例 →
 * 备份旧 exe → 替换 → 复验版本与配置 → 恢复运行 → 写升级历史.
 * 替换/复验任一步失败都自动恢复备份并重启原实例.
 */
public class NginxUpgradeManager {

	private static final Logger LOG = LoggerFactory.getLogger(NginxUpgradeManager.class);

	private static final String HISTORY_NAME = "upgrade-history.json";
	private static final int HISTORY_LIMIT = 100;

	/** 升级过程回调(在后台线程调用, UI 侧自行切回 EDT) */
	public interface Progress {
		void log(String message);

		void stage(int percent, String label);
	}

	/** 升级/回滚结果 */
	public static class UpgradeResult {
		public boolean success;
		public String message;
		public String fromVersion;
		public String toVersion;
		/** 原本运行中的实例是否已重新启动成功 */
		public boolean restarted;
		/** 在线升级时安装包下载超时(>180 秒): UI 据此停用在线升级入口 */
		public boolean downloadTimeout;
	}

	private final File home;
	private final List<NginxInstanceEntry> allEntries;

	public NginxUpgradeManager(File home, List<NginxInstanceEntry> allEntries) {
		this.home = home;
		this.allEntries = allEntries == null ? new ArrayList<NginxInstanceEntry>()
				: allEntries;
	}

	public File historyFile() {
		return new File(home, HISTORY_NAME);
	}

	/** 检测升级地址(nginx.org)连通性并取回稳定版列表, 网络失败时 online=false 并带回原因 */
	public static NginxPackageManager.Reachability checkUpgradeSource() {
		return NginxPackageManager.probeUpgradeSource();
	}

	/** 当前实例 exe 实际版本; 读不到时返回空串 */
	public String currentVersion(NginxInstanceEntry entry) {
		String exe = StrUtil.nullToEmpty(entry.getNginxExe());
		if (StrUtil.isEmpty(exe) || !new File(exe).isFile()) {
			return "";
		}
		try {
			return NginxPackageManager.detectVersion(new File(exe));
		} catch (Exception e) {
			return "";
		}
	}

	/**
	 * 升级到指定版本.
	 *
	 * @param entry        目标实例(其它共用同一 exe 的注册实例会一并停机/重启)
	 * @param targetVersion 目标版本, 本地包模式可传空(以包内版本为准)
	 * @param localZip     本地 zip 安装包, null 表示按版本在线下载
	 */
	public UpgradeResult upgrade(NginxInstanceEntry entry, String targetVersion, File localZip, Progress p) {
		UpgradeResult r = new UpgradeResult();
		String exePath = StrUtil.nullToEmpty(entry.getNginxExe());
		String conf = StrUtil.nullToEmpty(entry.getConfPath());
		File exe = new File(exePath);
		if (!exe.isFile()) {
			return fail(r, "nginx 可执行文件不存在: " + exePath, p);
		}
		if (StrUtil.isEmpty(conf) || !new File(conf).isFile()) {
			return fail(r, "实例配置文件不存在: " + StrUtil.emptyToDefault(conf, "(未识别)"), p);
		}

		String fromVersion = currentVersion(entry);
		r.fromVersion = fromVersion;
		log(p, "当前版本: " + StrUtil.emptyToDefault(fromVersion, "未知") + "，程序: " + exePath);

		// --- 1) 取得安装包 ---
		File zip;
		String target;
		try {
			if (localZip != null) {
				stage(p, 3, "校验本地安装包");
				target = NginxPackageManager.inspectZip(localZip);
				zip = localZip;
				log(p, "本地安装包版本: " + target + " (" + (localZip.length() / 1024) + " KB)");
			} else {
				target = StrUtil.trim(targetVersion);
				if (StrUtil.isEmpty(target)) {
					return fail(r, "请选择目标版本", p);
				}
				stage(p, 5, "下载 nginx-" + target + ".zip");
				zip = NginxPackageManager.zipPath(NginxPackageManager.download(target, m -> log(p, m)).token);
			}
		} catch (Exception e) {
			if (localZip == null && isDownloadTimeout(e)) {
				r.downloadTimeout = true;
			}
			return fail(r, "获取安装包失败: " + e.getMessage(), p);
		}
		r.toVersion = target;

		if (target.equals(fromVersion)) {
			return fail(r, "当前已是 " + target + "，无需升级", p);
		}
		if (StrUtil.isNotEmpty(fromVersion) && compareVersion(target, fromVersion) < 0) {
			log(p, "提示: 目标版本低于当前版本，本次操作将执行降级");
		}

		// --- 2) 解压(独立版本目录, 与官方 zip 结构校验一致) ---
		File newExe;
		try {
			stage(p, 25, "解压安装包");
			NginxPackageManager.UnpackResult pkg = NginxPackageManager.unpack(zip,
					new File(NginxPackageManager.workDir(), "pkg"), m -> log(p, m));
			newExe = pkg.nginxExe;
		} catch (Exception e) {
			return fail(r, "解压失败: " + e.getMessage(), p);
		}

		// --- 3) 用新版本预检配置: 不通过则完全不改动现有文件 ---
		stage(p, 40, "新版本配置预检 (nginx -t)");
		NginxDashboardUtils.ConfigTestResult pre =
				NginxDashboardUtils.testInstanceConfigWithExe(entry, newExe.getAbsolutePath());
		log(p, pre.output);
		if (!pre.success) {
			return fail(r, "新版本无法通过配置检测，已中止(未改动任何文件): " + pre.message, p);
		}

		// --- 4) 找出共用该 exe 的实例并记录运行状态 ---
		List<NginxInstanceEntry> affected = affectedEntries(exePath, entry);
		List<NginxInstanceEntry> wasRunning = new ArrayList<NginxInstanceEntry>();
		for (NginxInstanceEntry e : affected) {
			if (NginxDashboardUtils.isInstanceRunning(e)) {
				wasRunning.add(e);
			}
		}
		if (affected.size() > 1) {
			log(p, "共用该程序文件的实例: " + affected.size() + " 个，其中运行中 " + wasRunning.size() + " 个");
		}

		// --- 5) 停止 ---
		stage(p, 52, "停止运行中的实例 (" + wasRunning.size() + ")");
		List<NginxInstanceEntry> stopped = new ArrayList<NginxInstanceEntry>();
		for (NginxInstanceEntry e : wasRunning) {
			NginxDashboardUtils.ControlResult cr = NginxDashboardUtils.controlInstance(e, "stop");
			if (cr.success) {
				stopped.add(e);
				log(p, "已停止: " + label(e));
			} else {
				restartAll(stopped, p);
				return fail(r, "停止实例失败，已中止升级: " + label(e) + " - " + cr.message, p);
			}
		}

		// --- 6) 备份 ---
		String safeVer = fromVersion.matches("\\d+(\\.\\d+)+") ? fromVersion : "unknown";
		File backup = new File(exePath + "." + safeVer + ".bak");
		stage(p, 62, "备份旧程序");
		try {
			Files.copy(exe.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
			log(p, "备份: " + backup.getName());
		} catch (Exception e) {
			restartAll(stopped, p);
			return fail(r, "备份失败，已中止升级: " + e.getMessage(), p);
		}

		// --- 7) 替换 ---
		stage(p, 72, "替换 nginx 程序");
		File temp = new File(exe.getParentFile(), exe.getName() + ".ngui-new");
		try {
			Files.copy(newExe.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
			try {
				Files.move(temp.toPath(), exe.toPath(), StandardCopyOption.REPLACE_EXISTING);
			} catch (Exception moveError) {
				// 运行中的其它 nginx 进程仍占用文件时 move 会失败: 直接覆盖尝试一次
				Files.copy(temp.toPath(), exe.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
			FileUtil.del(temp);
		} catch (Exception e) {
			FileUtil.del(temp);
			restoreQuietly(backup, exe, p);
			restartAll(stopped, p);
			return fail(r, "替换失败(程序文件可能仍被其它进程占用): " + e.getMessage(), p);
		}

		// --- 8) 复验 ---
		stage(p, 84, "校验新版本");
		String detected = currentVersion(entry);
		NginxDashboardUtils.ConfigTestResult post =
				NginxDashboardUtils.testInstanceConfigWithExe(entry, exePath);
		log(p, post.output);
		if (!target.equals(detected) || !post.success) {
			restoreQuietly(backup, exe, p);
			restartAll(stopped, p);
			return fail(r, "新版本复验失败(版本=" + StrUtil.emptyToDefault(detected, "未知")
					+ ")，已回滚并恢复原实例: " + post.message, p);
		}
		log(p, "版本校验通过: " + detected);

		// --- 9) 恢复运行 ---
		stage(p, 92, "启动实例");
		List<String> startErrors = restartAll(stopped, p);
		r.restarted = startErrors.isEmpty();

		stage(p, 100, "完成");
		r.success = true;
		r.message = "升级完成: " + StrUtil.emptyToDefault(fromVersion, "未知") + " → " + detected
				+ (startErrors.isEmpty() ? "" : "；部分实例启动失败: " + StrUtil.join("；", startErrors));
		writeHistory(record(entry, "upgrade", r.fromVersion, r.toVersion, exePath, backup, r.success, null));
		return r;
	}

	/**
	 * 回滚该实例最近一次成功的升级(要求当前版本仍是升级后的版本且备份文件存在).
	 */
	public UpgradeResult rollback(NginxInstanceEntry entry, Progress p) {
		UpgradeResult r = new UpgradeResult();
		NginxUpgradeRecord rec = findRollbackRecord(entry);
		if (rec == null) {
			return fail(r, "没有可回滚的升级备份(或已经回滚过)", p);
		}
		File exe = new File(exePath(entry));
		File backup = new File(rec.getBackupExe());
		if (!exe.isFile() || !backup.isFile()) {
			return fail(r, "程序或备份文件不存在: " + backup.getAbsolutePath(), p);
		}

		r.fromVersion = currentVersion(entry);
		r.toVersion = rec.getFromVersion();
		log(p, "回滚: " + StrUtil.emptyToDefault(r.fromVersion, "未知") + " → "
				+ StrUtil.emptyToDefault(r.toVersion, "未知"));

		// 回滚前同样用备份程序预检配置
		stage(p, 15, "备份版本配置预检 (nginx -t)");
		NginxDashboardUtils.ConfigTestResult pre =
				NginxDashboardUtils.testInstanceConfigWithExe(entry, backup.getAbsolutePath());
		log(p, pre.output);
		if (!pre.success) {
			return fail(r, "备份版本无法通过配置检测，已中止(未改动任何文件): " + pre.message, p);
		}

		List<NginxInstanceEntry> affected = affectedEntries(exe.getAbsolutePath(), entry);
		List<NginxInstanceEntry> wasRunning = new ArrayList<NginxInstanceEntry>();
		for (NginxInstanceEntry e : affected) {
			if (NginxDashboardUtils.isInstanceRunning(e)) {
				wasRunning.add(e);
			}
		}

		stage(p, 35, "停止运行中的实例 (" + wasRunning.size() + ")");
		List<NginxInstanceEntry> stopped = new ArrayList<NginxInstanceEntry>();
		for (NginxInstanceEntry e : wasRunning) {
			NginxDashboardUtils.ControlResult cr = NginxDashboardUtils.controlInstance(e, "stop");
			if (cr.success) {
				stopped.add(e);
			} else {
				restartAll(stopped, p);
				return fail(r, "停止实例失败，已中止回滚: " + label(e) + " - " + cr.message, p);
			}
		}

		stage(p, 55, "恢复备份程序");
		// 回滚前把当前程序另存一份, 便于再次升级失败时排查
		File current = new File(rec.getBackupExe() + ".current");
		try {
			Files.copy(exe.toPath(), current.toPath(), StandardCopyOption.REPLACE_EXISTING);
			Files.copy(backup.toPath(), exe.toPath(), StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception e) {
			restartAll(stopped, p);
			return fail(r, "恢复备份失败: " + e.getMessage(), p);
		}

		stage(p, 75, "校验回滚版本");
		String detected = currentVersion(entry);
		NginxDashboardUtils.ConfigTestResult post =
				NginxDashboardUtils.testInstanceConfigWithExe(entry, exe.getAbsolutePath());
		log(p, post.output);
		if (!StrUtil.equals(detected, rec.getFromVersion()) || !post.success) {
			// 恢复失败: 把回滚前的程序还原回去
			try {
				Files.copy(current.toPath(), exe.toPath(), StandardCopyOption.REPLACE_EXISTING);
			} catch (Exception ignored) {
			}
			restartAll(stopped, p);
			return fail(r, "回滚复验失败(版本=" + StrUtil.emptyToDefault(detected, "未知") + "): " + post.message, p);
		}

		stage(p, 90, "启动实例");
		List<String> startErrors = restartAll(stopped, p);
		r.restarted = startErrors.isEmpty();
		stage(p, 100, "完成");
		r.success = true;
		r.message = "回滚完成: " + StrUtil.emptyToDefault(r.fromVersion, "未知") + " → " + detected
				+ (startErrors.isEmpty() ? "" : "；部分实例启动失败: " + StrUtil.join("；", startErrors));
		writeHistory(record(entry, "rollback", r.fromVersion, r.toVersion, exe.getAbsolutePath(),
				backup, r.success, null));
		return r;
	}

	/** 最近一次可回滚的升级记录(最近一次操作必须是成功升级且当前版本仍是升级后版本), 无则 null */
	public NginxUpgradeRecord findRollbackRecord(NginxInstanceEntry entry) {
		String current = currentVersion(entry);
		if (StrUtil.isEmpty(current)) {
			return null;
		}
		List<NginxUpgradeRecord> history = loadHistory();
		for (int i = history.size() - 1; i >= 0; i--) {
			NginxUpgradeRecord rec = history.get(i);
			if (!StrUtil.equals(rec.getInstanceId(), entry.getId())) {
				continue;
			}
			// 只看该实例最近一条操作: 已回滚/上次失败都不再提供回滚, 避免来回打转
			if (!"upgrade".equals(rec.getDirection()) || !"success".equals(rec.getResult())) {
				return null;
			}
			if (!current.equals(rec.getToVersion())) {
				return null;
			}
			if (StrUtil.isNotEmpty(rec.getBackupExe()) && new File(rec.getBackupExe()).isFile()) {
				return rec;
			}
			return null;
		}
		return null;
	}

	/** 升级历史(旧→新) */
	public List<NginxUpgradeRecord> loadHistory() {
		try {
			List<NginxUpgradeRecord> list = JsonFileStore.read(historyFile(), json -> {
				List<NginxUpgradeRecord> parsed = JSONUtil.toList(json, NginxUpgradeRecord.class);
				return parsed == null ? new ArrayList<NginxUpgradeRecord>() : parsed;
			});
			return list == null ? new ArrayList<NginxUpgradeRecord>() : list;
		} catch (Exception e) {
			LOG.error("读取升级历史失败: {}", historyFile(), e);
			return new ArrayList<NginxUpgradeRecord>();
		}
	}

	// ==================== 内部 ====================

	/** 注册表中与给定 exe 共用的实例(保证包含目标实例自身, 按 id 去重) */
	private List<NginxInstanceEntry> affectedEntries(String exePath, NginxInstanceEntry self) {
		String target = NginxInstanceRegistry.canonical(exePath);
		List<NginxInstanceEntry> result = new ArrayList<NginxInstanceEntry>();
		Set<String> seen = new HashSet<String>();
		boolean hasSelf = false;
		for (NginxInstanceEntry e : allEntries) {
			if (e == null) {
				continue;
			}
			String p = NginxInstanceRegistry.canonical(e.getNginxExe());
			if (StrUtil.isNotEmpty(p) && p.equals(target) && seen.add(StrUtil.nullToEmpty(e.getId()))) {
				result.add(e);
				if (StrUtil.equals(e.getId(), self.getId())) {
					hasSelf = true;
				}
			}
		}
		if (!hasSelf) {
			result.add(self);
		}
		return result;
	}

	/** 按原顺序重启之前停止的实例, 返回失败说明列表 */
	private List<String> restartAll(List<NginxInstanceEntry> entries, Progress p) {
		List<String> errors = new ArrayList<String>();
		for (NginxInstanceEntry e : entries) {
			NginxDashboardUtils.ControlResult cr = NginxDashboardUtils.controlInstance(e, "start");
			if (cr.success) {
				log(p, "已启动: " + label(e));
			} else {
				errors.add(label(e) + " - " + cr.message);
				log(p, "启动失败: " + label(e) + " - " + cr.message);
			}
		}
		return errors;
	}

	private void restoreQuietly(File backup, File exe, Progress p) {
		try {
			Files.copy(backup.toPath(), exe.toPath(), StandardCopyOption.REPLACE_EXISTING);
			log(p, "已恢复备份程序: " + backup.getName());
		} catch (Exception e) {
			log(p, "严重: 恢复备份失败，请手工把 " + backup.getAbsolutePath()
					+ " 复制为 " + exe.getAbsolutePath() + " (" + e.getMessage() + ")");
		}
	}

	private NginxUpgradeRecord record(NginxInstanceEntry entry, String direction,
			String fromVersion, String toVersion, String exePath, File backup,
			boolean success, String error) {
		NginxUpgradeRecord rec = new NginxUpgradeRecord();
		rec.setDirection(direction);
		rec.setInstanceId(entry.getId());
		rec.setInstanceName(StrUtil.emptyToDefault(entry.getName(),
				StrUtil.emptyToDefault(entry.getWinServiceName(), entry.getPrefixDir())));
		rec.setTime(System.currentTimeMillis());
		rec.setFromVersion(fromVersion);
		rec.setToVersion(toVersion);
		rec.setFromExe(exePath);
		rec.setToExe(exePath);
		rec.setBackupExe(backup == null ? null : backup.getAbsolutePath());
		rec.setResult(success ? "success" : "failed");
		rec.setError(error);
		return rec;
	}

	private void writeHistory(NginxUpgradeRecord rec) {
		try {
			List<NginxUpgradeRecord> history = loadHistory();
			history.add(rec);
			while (history.size() > HISTORY_LIMIT) {
				history.remove(0);
			}
			JsonFileStore.write(historyFile(), JSONUtil.toJsonPrettyStr(history));
		} catch (Exception e) {
			// 历史写入失败不影响升级结果
			LOG.error("保存升级历史失败: {}", historyFile(), e);
		}
	}

	private static String exePath(NginxInstanceEntry entry) {
		return StrUtil.nullToEmpty(entry.getNginxExe());
	}

	private static String label(NginxInstanceEntry e) {
		return StrUtil.emptyToDefault(e.getName(), StrUtil.emptyToDefault(e.getWinServiceName(),
				StrUtil.emptyToDefault(e.getPrefixDir(), e.getId())));
	}

	private static UpgradeResult fail(UpgradeResult r, String message, Progress p) {
		r.success = false;
		r.message = message;
		log(p, "失败: " + message);
		return r;
	}

	/** 下载耗时超过 180 秒: 直接判定下载失败, 由 UI 停用在线升级入口 */
	private static boolean isDownloadTimeout(Throwable e) {
		return e != null && String.valueOf(e.getMessage()).contains("下载超时");
	}

	private static void log(Progress p, String message) {
		if (p != null && StrUtil.isNotEmpty(message)) {
			p.log(message);
		}
	}

	private static void stage(Progress p, int percent, String label) {
		if (p != null) {
			p.stage(percent, label);
		}
	}

	/** 版本号数值比较: 1.10.0 > 1.9.2 */
	private static int compareVersion(String a, String b) {
		String[] pa = StrUtil.nullToEmpty(a).split("\\.");
		String[] pb = StrUtil.nullToEmpty(b).split("\\.");
		int n = Math.max(pa.length, pb.length);
		for (int i = 0; i < n; i++) {
			int va = i < pa.length ? parseInt(pa[i]) : 0;
			int vb = i < pb.length ? parseInt(pb[i]) : 0;
			if (va != vb) {
				return Integer.compare(va, vb);
			}
		}
		return 0;
	}

	private static int parseInt(String s) {
		try {
			return Integer.parseInt(StrUtil.trim(s));
		} catch (Exception e) {
			return 0;
		}
	}
}
