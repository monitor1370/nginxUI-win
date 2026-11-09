package com.nginxui.utils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nginxui.ext.NginxInstanceEntry;
import com.nginxui.utils.NginxCertReplaceManager.CertEntry;
import com.nginxui.utils.NginxCertReplaceManager.CertInfo;
import com.nginxui.utils.NginxCertReplaceManager.CertItem;
import com.nginxui.utils.NginxCertReplaceManager.Inspect;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

/**
 * 证书资源库: 以证书为核心的全生命周期管理.
 *
 *  - 存储: home/certs/&lt;id&gt;/ 下保存 cert.pem / key.pem, 元数据与绑定关系写入 home/certs/index.json;
 *  - 绑定: 一张证书可绑定多个端口配置项, 绑定时把证书内容写入各端口现有证书/私钥路径(备份后可回滚);
 *  - 生效: 绑定或更新证书后, 遍历所有绑定端口原子写入, 统一 nginx -t 校验, 并对运行中的实例重启;
 *  - 一致性: 任一端口写入/校验/重启失败即整体回滚文件, 不留下部分端口生效的中间状态;
 *  - 兼容: 不改变 nginx 配置中的证书路径, 原有的单端口单证书模式继续可用;
 *  - 日志: 导入/更新/绑定/解除/删除/回滚全部写入 home/logs/cert-lifecycle.log.
 */
public class CertificateStoreManager {

	/** 到期提醒阈值(天) */
	public static final int REMIND_DAYS = 30;

	private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final File home;

	public CertificateStoreManager(File home) {
		this.home = home;
	}

	// ==================== 数据模型 ====================

	/** 证书与某个端口配置项的绑定关系 */
	public static class Binding {
		public String instanceId = "";
		public String instanceName = "";
		public String confPath = "";
		public String pemPath = "";
		public String keyPath = "";
		public String serverName = "";
		public String boundAt = "";
		public List<String> ports = new ArrayList<String>();

		public String identity() {
			return key(confPath, pemPath, keyPath);
		}

		public String portsText() {
			return ports.isEmpty() ? "-" : String.join(", ", ports);
		}

		public String label() {
			String name = StrUtil.isEmpty(serverName) ? "(未声明 server_name)" : serverName;
			return instanceName + " · " + name + (ports.isEmpty() ? "" : " : " + portsText());
		}
	}

	/** 证书库中的一张证书 */
	public static class ManagedCert {
		public String id = "";
		public String name = "";
		public String note = "";
		public String keyType = "";
		public String sha256 = "";
		public String importedFrom = "";
		public String createdAt = "";
		public String updatedAt = "";
		/** 从文件实时解析, 不持久化 */
		public CertInfo info;
		public List<Binding> bindings = new ArrayList<Binding>();

		public String certFileName() {
			return "cert.pem";
		}

		public int daysLeft() {
			return info == null ? -1 : (int) info.daysLeft;
		}

		public String statusText() {
			if (info == null) {
				return "无法解析";
			}
			if (info.expired) {
				return "已过期";
			}
			if (info.notYetValid) {
				return "未生效";
			}
			if (info.expiringSoon) {
				return "即将到期";
			}
			return "正常";
		}

		public String displayName() {
			if (StrUtil.isNotEmpty(name)) {
				return name;
			}
			return info == null || StrUtil.isEmpty(info.cn()) ? id : info.cn();
		}

		public boolean needsReminder() {
			return info != null && (info.expired || info.expiringSoon);
		}
	}

	public static class ImportResult {
		public boolean success;
		/** 已存在同内容证书时返回该证书 */
		public ManagedCert cert;
		public String message;
		public List<String> warnings = new ArrayList<String>();
	}

	/** 导入/更新/绑定/同步的统一结果 */
	public static class ApplyResult {
		public boolean success;
		public boolean rolledBack;
		public String message = "";
		public String output = "";
		public List<String> affected = new ArrayList<String>();
		public List<String> restarted = new ArrayList<String>();
		public List<String> backups = new ArrayList<String>();
		public List<String> warnings = new ArrayList<String>();
	}

	/** 证书的实际使用情况: 活配置命中 + 绑定记录 */
	public static class Usage {
		public CertEntry entry;
		public boolean live;
		public boolean bound;

		public String stateText() {
			if (bound && live) {
				return "已绑定";
			}
			return bound ? "已绑定(配置已变更)" : "使用中";
		}
	}

	// ==================== 存储 ====================

	public File storeDir() {
		return new File(home, "certs");
	}

	public File indexFile() {
		return new File(storeDir(), "index.json");
	}

	public File certDir(String id) {
		return new File(storeDir(), id);
	}

	public File certFile(String id) {
		return new File(certDir(id), "cert.pem");
	}

	public File keyFile(String id) {
		return new File(certDir(id), "key.pem");
	}

	/** 证书库列表(按到期日升序, 无法解析的排最后) */
	public synchronized List<ManagedCert> list() {
		List<ManagedCert> certs = new ArrayList<ManagedCert>();
		for (ManagedCert c : readIndexRaw()) {
			if (StrUtil.isNotEmpty(c.id)) {
				certs.add(c);
			}
		}
		for (ManagedCert c : certs) {
			refreshInfo(c);
		}
		certs.sort((a, b) -> {
			long da = a.info == null ? Long.MAX_VALUE : a.info.notAfter.getTime();
			long db = b.info == null ? Long.MAX_VALUE : b.info.notAfter.getTime();
			return Long.compare(da, db);
		});
		return certs;
	}

	public synchronized ManagedCert find(String id) {
		if (StrUtil.isEmpty(id)) {
			return null;
		}
		for (ManagedCert c : list()) {
			if (id.equals(c.id)) {
				return c;
			}
		}
		return null;
	}

	/** 已过期/即将到期的证书(用于提醒) */
	public List<ManagedCert> reminders() {
		List<ManagedCert> out = new ArrayList<ManagedCert>();
		for (ManagedCert c : list()) {
			if (c.needsReminder()) {
				out.add(c);
			}
		}
		return out;
	}

	/** 名称去重: 与已有证书重名时追加颁发日期(YYYYMMDD), 仍重名再加序号 */
	private String uniqueName(String base, Date notBefore) {
		String name = StrUtil.trim(base);
		if (StrUtil.isEmpty(name)) {
			name = "certificate";
		}
		if (!nameUsed(name, null)) {
			return name;
		}
		String suffix = DateUtil.format(notBefore == null ? new Date() : notBefore, "yyyyMMdd");
		String candidate = name + "_" + suffix;
		int seq = 2;
		while (nameUsed(candidate, null)) {
			candidate = name + "_" + suffix + "-" + seq++;
		}
		return candidate;
	}

	/** 名称是否已被其它证书占用(忽略大小写与首尾空白) */
	private boolean nameUsed(String name, String excludeId) {
		String target = StrUtil.trim(name);
		for (ManagedCert c : list()) {
			if (excludeId != null && excludeId.equals(c.id)) {
				continue;
			}
			if (target.equalsIgnoreCase(StrUtil.trim(c.displayName()))) {
				return true;
			}
		}
		return false;
	}

	private void refreshInfo(ManagedCert c) {
		File f = certFile(c.id);
		c.info = NginxCertReplaceManager.parseCertFile(f);
		if (c.info != null && StrUtil.isEmpty(c.sha256)) {
			c.sha256 = hashOfFile(f);
		}
	}

	/** 导入证书: 校验格式与有效期后落盘; 同内容已存在时直接返回既有证书 */
	public ImportResult importCert(String name, String note, String pem, String key, String source) {
		ImportResult r = new ImportResult();
		Inspect pc = NginxCertReplaceManager.inspectPem(pem);
		if (!pc.ok()) {
			r.message = pc.error;
			return r;
		}
		Inspect kc = NginxCertReplaceManager.inspectKey(key, null);
		if (!kc.ok()) {
			r.message = kc.error;
			return r;
		}
		r.warnings.addAll(pc.warnings);

		// 唯一性: 证书文件内容 SHA-256(统一换行与首尾空白); 相同即同一张证书, 不允许重复入库
		String sha = sha256(pem);
		for (ManagedCert c : list()) {
			if (sha.equalsIgnoreCase(StrUtil.nullToEmpty(c.sha256))) {
				r.success = false;
				r.cert = c;
				r.message = "证书库中已存在此证书（" + c.displayName() + "），请勿重复导入。";
				return r;
			}
		}

		String id = newId();
		ManagedCert c = new ManagedCert();
		c.id = id;
		c.sha256 = sha;
		c.keyType = kc.keyType;
		c.importedFrom = StrUtil.nullToEmpty(source);
		String now = now();
		c.createdAt = now;
		c.updatedAt = now;
		String baseName = StrUtil.emptyToDefault(StrUtil.trim(name),
				pc.cert.cn().isEmpty() ? id : pc.cert.cn());
		c.name = uniqueName(baseName, pc.cert.notBefore);
		c.note = StrUtil.nullToEmpty(note);
		try {
			File dir = certDir(id);
			FileUtil.mkdir(dir);
			FileUtil.writeString(pem, certFile(id), StandardCharsets.UTF_8);
			FileUtil.writeString(key, keyFile(id), StandardCharsets.UTF_8);
			List<ManagedCert> all = readIndexRaw();
			all.add(c);
			writeIndex(all);
		} catch (Exception e) {
			log("ERROR", "导入证书失败: " + e.getMessage(), e);
			r.message = "写入证书库失败: " + e.getMessage();
			return r;
		}
		refreshInfo(c);
		r.success = true;
		r.cert = c;
		r.message = "证书已导入证书库";
		log("INFO", "导入证书 id=" + id + " name=" + c.name + " cn=" + (c.info == null ? "" : c.info.cn())
				+ " 到期=" + (c.info == null ? "" : c.info.notAfterText()) + " 来源=" + c.importedFrom, null);
		return r;
	}

	/** 修改名称/备注 */
	public boolean updateMeta(String id, String name, String note) {
		List<ManagedCert> all = readIndexRaw();
		for (ManagedCert c : all) {
			if (id.equals(c.id)) {
				c.name = StrUtil.trim(name);
				c.note = StrUtil.nullToEmpty(note);
				c.updatedAt = now();
				try {
					writeIndex(all);
					log("INFO", "编辑证书信息 id=" + id + " name=" + c.name, null);
					return true;
				} catch (Exception e) {
					log("ERROR", "保存证书信息失败: " + e.getMessage(), e);
					return false;
				}
			}
		}
		return false;
	}

	/**
	 * 更换证书文件并同步所有绑定端口.
	 * 证书库文件先更新; 同步失败时恢复证书库原内容, 保证"库与端口"一致.
	 */
	public ApplyResult updateCert(String id, String pem, String key, List<NginxInstanceEntry> entries) {
		ApplyResult r = new ApplyResult();
		ManagedCert cert = find(id);
		if (cert == null) {
			r.message = "证书不存在: " + id;
			return r;
		}
		Inspect pc = NginxCertReplaceManager.inspectPem(pem);
		if (!pc.ok()) {
			r.message = pc.error;
			return r;
		}
		Inspect kc = NginxCertReplaceManager.inspectKey(key, null);
		if (!kc.ok()) {
			r.message = kc.error;
			return r;
		}
		r.warnings.addAll(pc.warnings);

		File cf = certFile(id);
		File kf = keyFile(id);
		String oldPem = safeRead(cf);
		String oldKey = safeRead(kf);
		boolean hadCert = cf.isFile();
		boolean hadKey = kf.isFile();
		try {
			if (hadCert) {
				r.backups.add(backup(cf, "certbak"));
			}
			if (hadKey) {
				r.backups.add(backup(kf, "certbak"));
			}
			FileUtil.writeString(pem, cf, StandardCharsets.UTF_8);
			FileUtil.writeString(key, kf, StandardCharsets.UTF_8);
		} catch (Exception e) {
			restore(cf, hadCert, oldPem);
			restore(kf, hadKey, oldKey);
			r.message = "写入证书库失败: " + e.getMessage();
			return r;
		}

		List<ManagedCert> all = readIndexRaw();
		ManagedCert stored = null;
		for (ManagedCert c : all) {
			if (id.equals(c.id)) {
				stored = c;
				break;
			}
		}
		if (stored == null) {
			restore(cf, hadCert, oldPem);
			restore(kf, hadKey, oldKey);
			r.message = "证书索引缺失: " + id;
			return r;
		}
		String oldSha = stored.sha256;
		String oldKeyType = stored.keyType;
		stored.sha256 = sha256(pem);
		stored.keyType = kc.keyType;
		stored.updatedAt = now();
		writeIndexQuiet(all);

		ApplyResult sync = syncBindings(stored, entries);
		if (sync.success) {
			r.success = true;
			r.affected.addAll(sync.affected);
			r.restarted.addAll(sync.restarted);
			r.output = sync.output;
			r.backups.addAll(sync.backups);
			r.warnings.addAll(sync.warnings);
			r.message = "证书已更新，并同步到 " + sync.affected.size() + " 个端口";
			log("INFO", "更新证书 id=" + id + " name=" + stored.name + " 同步端口="
					+ sync.affected.size(), null);
			return r;
		}

		// 同步失败: 恢复证书库文件与索引, 端口侧已由 syncBindings 自行回滚
		restore(cf, hadCert, oldPem);
		restore(kf, hadKey, oldKey);
		stored.sha256 = oldSha;
		stored.keyType = oldKeyType;
		stored.updatedAt = now();
		writeIndexQuiet(all);
		r.rolledBack = sync.rolledBack;
		r.message = "同步绑定端口失败，已回滚证书库与端口：" + sync.message;
		log("ERROR", "更新证书失败并回滚 id=" + id + ": " + sync.message, null);
		return r;
	}

	/** 批量绑定端口: 写入证书内容 → nginx -t → 重启 → 记录绑定 */
	public ApplyResult bind(String certId, List<CertEntry> targets) {
		ApplyResult r = new ApplyResult();
		ManagedCert cert = find(certId);
		if (cert == null) {
			r.message = "证书不存在: " + certId;
			return r;
		}
		File cf = certFile(certId);
		File kf = keyFile(certId);
		if (!cf.isFile() || !kf.isFile()) {
			r.message = "证书库文件缺失，请重新导入: " + certId;
			return r;
		}
		List<CertEntry> valid = new ArrayList<CertEntry>();
		List<String> problems = new ArrayList<String>();
		List<Binding> newBindings = new ArrayList<Binding>();
		for (CertEntry ce : targets == null ? new ArrayList<CertEntry>() : targets) {
			if (ce == null || ce.item == null || ce.instance == null) {
				continue;
			}
			if (StrUtil.isEmpty(ce.item.pemPath)) {
				problems.add(labelOf(ce) + " 未配置 ssl_certificate");
				continue;
			}
			if (StrUtil.isEmpty(ce.item.keyPath)) {
				problems.add(labelOf(ce) + " 未配置 ssl_certificate_key");
				continue;
			}
			valid.add(ce);
			Binding b = new Binding();
			b.instanceId = StrUtil.nullToEmpty(ce.instance.getId());
			b.instanceName = StrUtil.emptyToDefault(ce.instance.getName(), ce.instance.getConfPath());
			b.confPath = ce.item.sourceConf == null ? "" : ce.item.sourceConf.getAbsolutePath();
			b.pemPath = ce.item.pemPath;
			b.keyPath = ce.item.keyPath;
			b.serverName = StrUtil.nullToEmpty(ce.item.domain);
			b.ports.addAll(ce.item.ports);
			b.boundAt = now();
			newBindings.add(b);
		}
		if (!problems.isEmpty()) {
			r.message = "以下端口无法绑定：" + String.join("；", problems);
			return r;
		}
		if (valid.isEmpty()) {
			r.message = "未选择任何端口";
			return r;
		}

		// 同一证书文件不得同时归属两张证书
		List<String> conflicts = new ArrayList<String>();
		for (ManagedCert other : list()) {
			if (other.id.equals(certId)) {
				continue;
			}
			for (Binding ob : other.bindings) {
				for (Binding nb : newBindings) {
					if (ob.identity().equals(nb.identity())) {
						conflicts.add(nb.label() + "（已绑定证书「" + other.displayName() + "」）");
					}
				}
			}
		}
		if (!conflicts.isEmpty()) {
			r.message = "存在绑定冲突，请先解除原绑定：" + String.join("；", conflicts);
			return r;
		}

		ApplyResult core = applyToPorts(cert, safeRead(cf), safeRead(kf), valid);
		if (!core.success) {
			return core;
		}

		List<ManagedCert> all = readIndexRaw();
		for (ManagedCert c : all) {
			if (certId.equals(c.id)) {
				for (Binding nb : newBindings) {
					replaceBinding(c.bindings, nb);
				}
			}
		}
		writeIndexQuiet(all);
		core.message = "已绑定 " + valid.size() + " 个端口";
		log("INFO", "绑定证书 id=" + certId + " name=" + cert.displayName()
				+ " 端口=" + core.affected, null);
		return core;
	}

	/** 证书更新后遍历绑定端口同步写入(供 updateCert 内部使用) */
	private ApplyResult syncBindings(ManagedCert cert, List<NginxInstanceEntry> entries) {
		ApplyResult r = new ApplyResult();
		if (cert.bindings.isEmpty()) {
			r.success = true;
			r.message = "没有绑定端口";
			return r;
		}
		Map<String, NginxInstanceEntry> byId = new LinkedHashMap<String, NginxInstanceEntry>();
		if (entries != null) {
			for (NginxInstanceEntry e : entries) {
				if (e != null && StrUtil.isNotEmpty(e.getId())) {
					byId.put(e.getId(), e);
				}
			}
		}
		List<CertEntry> targets = new ArrayList<CertEntry>();
		List<String> missing = new ArrayList<String>();
		for (Binding b : cert.bindings) {
			NginxInstanceEntry e = byId.get(b.instanceId);
			if (e == null) {
				missing.add(b.label() + "（实例已移除）");
				continue;
			}
			CertItem item = new CertItem();
			item.sourceConf = new File(b.confPath);
			item.domain = b.serverName;
			item.ports.addAll(b.ports);
			item.pemPath = b.pemPath;
			item.keyPath = b.keyPath;
			CertEntry ce = new CertEntry();
			ce.instance = e;
			ce.item = item;
			targets.add(ce);
		}
		if (!missing.isEmpty()) {
			r.warnings.add("以下绑定项已失效并跳过：" + String.join("；", missing));
		}
		if (targets.isEmpty()) {
			r.success = true;
			r.message = "绑定端口均已失效，仅更新证书库文件";
			return r;
		}
		return applyToPorts(cert, safeRead(certFile(cert.id)), safeRead(keyFile(cert.id)), targets);
	}

	/**
	 * 原子写入多个端口: 全部备份 → 写入 → 各实例 nginx -t → 运行中实例重启 → 任一失败整体回滚.
	 * 返回结果中的 affected 为受影响的端口标签.
	 */
	private ApplyResult applyToPorts(ManagedCert cert, String pem, String key, List<CertEntry> targets) {
		ApplyResult r = new ApplyResult();
		Map<String, String> originals = new LinkedHashMap<String, String>();
		List<File> created = new ArrayList<File>();
		Set<String> written = new LinkedHashSet<String>();
		List<NginxInstanceEntry> instances = new ArrayList<NginxInstanceEntry>();
		Set<String> instanceIds = new LinkedHashSet<String>();

		try {
			for (CertEntry ce : targets) {
				File pemFile = new File(ce.item.pemPath);
				File keyFile = new File(ce.item.keyPath);
				backupAndRemember(pemFile, originals, created, r.backups);
				writeFile(pemFile, pem, written);
				backupAndRemember(keyFile, originals, created, r.backups);
				writeFile(keyFile, key, written);
				r.affected.add(labelOf(ce));
				if (instanceIds.add(StrUtil.nullToEmpty(ce.instance.getId()))) {
					instances.add(ce.instance);
				}
			}

			// nginx -t 门禁: 任一实例不通过即整体回滚
			for (NginxInstanceEntry e : instances) {
				NginxDashboardUtils.ConfigTestResult t = NginxDashboardUtils.testInstanceConfig(e);
				r.output = StrUtil.nullToEmpty(t.output);
				if (!t.success) {
					rollback(originals, created, written);
					r.rolledBack = true;
					r.message = "实例「" + displayName(e) + "」配置检测未通过，已回滚全部端口："
							+ StrUtil.emptyToDefault(t.message, t.output);
					log("ERROR", "绑定/同步被配置检测拦截: 实例=" + displayName(e)
							+ " 证书=" + cert.displayName() + " 端口=" + r.affected, null);
					return r;
				}
			}

			// 运行中实例立即生效(统一走重启; Windows 下平滑重载不可靠, 已移除 reload)
			List<String> failures = new ArrayList<String>();
			List<NginxInstanceEntry> restartedInstances = new ArrayList<NginxInstanceEntry>();
			for (NginxInstanceEntry e : instances) {
				if (!NginxDashboardUtils.isInstanceRunning(e)) {
					continue;
				}
				NginxDashboardUtils.ControlResult cr = NginxDashboardUtils.controlInstance(e, "restart");
				if (cr != null && cr.success) {
					restartedInstances.add(e);
					r.restarted.add(displayName(e) + " 重启");
				} else {
					failures.add(displayName(e) + ": " + (cr == null ? "无响应" : cr.message));
				}
			}
			if (!failures.isEmpty()) {
				rollback(originals, created, written);
				for (NginxInstanceEntry e : restartedInstances) {
					try {
						NginxDashboardUtils.controlInstance(e, "restart");
					} catch (Exception ignored) {
					}
				}
				r.rolledBack = true;
				r.message = "实例生效失败，已回滚全部端口：" + String.join("；", failures);
				log("ERROR", "证书生效失败并回滚: 证书=" + cert.displayName() + " " + r.message, null);
				return r;
			}
			r.success = true;
			r.message = "已写入 " + targets.size() + " 个端口";
			log("INFO", "证书写入端口: 证书=" + cert.displayName() + " 端口=" + r.affected
					+ " 重启=" + r.restarted, null);
			return r;
		} catch (Exception e) {
			rollback(originals, created, written);
			r.rolledBack = !written.isEmpty();
			r.message = "写入端口失败" + (r.rolledBack ? "，已回滚" : "") + "：" + e.getMessage();
			log("ERROR", "写入端口失败: 证书=" + cert.displayName(), e);
			return r;
		}
	}

	/** 解除单个绑定: 只删除关联记录, 不改动端口现有证书文件 */
	public boolean unbind(String certId, String identity) {
		List<ManagedCert> all = readIndexRaw();
		for (ManagedCert c : all) {
			if (!certId.equals(c.id)) {
				continue;
			}
			for (int i = c.bindings.size() - 1; i >= 0; i--) {
				if (c.bindings.get(i).identity().equals(identity)) {
					Binding removed = c.bindings.remove(i);
					writeIndexQuiet(all);
					log("INFO", "解除绑定 证书=" + c.displayName() + " 端口=" + removed.label(), null);
					return true;
				}
			}
		}
		return false;
	}

	/** 删除证书: 关联记录移除, 文件移入 certs/_deleted 便于恢复 */
	public ImportResult delete(String certId) {
		ImportResult r = new ImportResult();
		ManagedCert cert = find(certId);
		if (cert == null) {
			r.message = "证书不存在: " + certId;
			return r;
		}
		File dir = certDir(certId);
		File trash = new File(storeDir(), "_deleted");
		File dest = new File(trash, certId + "-" + DateUtil.format(new Date(), "yyyyMMddHHmmss"));
		try {
			FileUtil.mkdir(trash);
			if (dir.isDirectory()) {
				FileUtil.move(dir, dest, true);
			}
		} catch (Exception e) {
			log("ERROR", "删除证书文件失败: " + e.getMessage(), e);
			r.message = "删除失败: " + e.getMessage();
			return r;
		}
		List<ManagedCert> all = readIndexRaw();
		for (int i = all.size() - 1; i >= 0; i--) {
			if (certId.equals(all.get(i).id)) {
				all.remove(i);
			}
		}
		writeIndexQuiet(all);
		r.success = true;
		r.cert = cert;
		r.message = "证书已删除（文件移入 certs/_deleted）";
		log("INFO", "删除证书 id=" + certId + " name=" + cert.displayName()
				+ " 原绑定=" + cert.bindings.size() + " 处(端口文件保持不变)", null);
		return r;
	}

	// ==================== 关联查询 ====================

	/** 汇总证书的关联端口: 活配置内容命中 + 绑定记录, 去重后返回 */
	public List<Usage> usageOf(ManagedCert cert, List<CertEntry> scanned) {
		List<Usage> out = new ArrayList<Usage>();
		Set<String> seen = new LinkedHashSet<String>();
		if (cert == null) {
			return out;
		}
		Map<String, Binding> byIdentity = new LinkedHashMap<String, Binding>();
		for (Binding b : cert.bindings) {
			byIdentity.put(b.identity(), b);
		}
		for (CertEntry ce : scanned == null ? new ArrayList<CertEntry>() : scanned) {
			if (ce == null || ce.item == null || StrUtil.isEmpty(ce.item.pemPath)) {
				continue;
			}
			String id = key(ce.item.sourceConf == null ? "" : ce.item.sourceConf.getAbsolutePath(),
					ce.item.pemPath, ce.item.keyPath);
			boolean bound = byIdentity.containsKey(id);
			boolean live = ce.item.pemExists && StrUtil.isNotEmpty(cert.sha256)
					&& cert.sha256.equalsIgnoreCase(ce.item.pemSha256);
			if (!bound && !live) {
				continue;
			}
			if (seen.add(id)) {
				Usage u = new Usage();
				u.entry = ce;
				u.bound = bound;
				u.live = live;
				out.add(u);
			}
		}
		// 绑定记录中尚未被扫描到的项(实例离线/配置已改)也要展示
		for (Binding b : cert.bindings) {
			if (!seen.add(b.identity())) {
				continue;
			}
			CertItem item = new CertItem();
			item.sourceConf = new File(b.confPath);
			item.domain = b.serverName;
			item.ports.addAll(b.ports);
			item.pemPath = b.pemPath;
			item.keyPath = b.keyPath;
			item.pemExists = new File(b.pemPath).isFile();
			CertEntry ce = new CertEntry();
			ce.item = item;
			// 实例已离线/配置已变更时扫描不到, 用绑定记录还原实例信息供界面展示
			NginxInstanceEntry synthetic = new NginxInstanceEntry();
			synthetic.setId(b.instanceId);
			synthetic.setName(StrUtil.emptyToDefault(b.instanceName, b.confPath));
			synthetic.setConfPath(b.confPath);
			ce.instance = synthetic;
			Usage u = new Usage();
			u.entry = ce;
			u.bound = true;
			out.add(u);
		}
		return out;
	}

	// ==================== 回滚 / 日志 ====================

	private void backupAndRemember(File src, Map<String, String> originals, List<File> created,
			List<String> backups) {
		if (src == null) {
			return;
		}
		if (src.isFile()) {
			String content = FileUtil.readString(src, StandardCharsets.UTF_8);
			originals.put(src.getAbsolutePath(), content);
			backups.add(backup(src, "certbak"));
		} else {
			created.add(src);
		}
	}

	private void writeFile(File file, String content, Set<String> written) {
		FileUtil.mkdir(file.getParentFile());
		FileUtil.writeString(StrUtil.nullToEmpty(content), file, StandardCharsets.UTF_8);
		written.add(file.getAbsolutePath());
	}

	private void rollback(Map<String, String> originals, List<File> created, Set<String> written) {
		for (File f : created) {
			if (!f.exists()) {
				continue;
			}
			try {
				FileUtil.del(f);
			} catch (Exception e) {
				log("ERROR", "回滚删除文件失败: " + f.getAbsolutePath(), e);
			}
		}
		for (Map.Entry<String, String> e : originals.entrySet()) {
			if (!written.contains(e.getKey())) {
				continue;
			}
			try {
				FileUtil.writeString(e.getValue(), new File(e.getKey()), StandardCharsets.UTF_8);
			} catch (Exception ex) {
				log("ERROR", "回滚恢复文件失败: " + e.getKey(), ex);
			}
		}
		if (!written.isEmpty() || !created.isEmpty()) {
			log("WARN", "已回滚 " + (originals.size() + created.size()) + " 处端口文件变更", null);
		}
	}

	private static void restore(File f, boolean existed, String oldContent) {
		try {
			if (existed) {
				FileUtil.writeString(StrUtil.nullToEmpty(oldContent), f, StandardCharsets.UTF_8);
			} else if (f.exists()) {
				FileUtil.del(f);
			}
		} catch (Exception ignored) {
		}
	}

	/** 备份到 home/certbak/&lt;name&gt;.&lt;yyyyMMddHHmmss&gt;-replace */
	public String backup(File src, String subdir) {
		if (src == null || !src.isFile()) {
			return null;
		}
		File dir = new File(home, subdir);
		FileUtil.mkdir(dir);
		String ts = DateUtil.format(new Date(), "yyyyMMddHHmmss");
		File bak = new File(dir, src.getName() + "." + ts + "-replace");
		int i = 2;
		while (bak.exists()) {
			bak = new File(dir, src.getName() + "." + ts + "-replace-" + i);
			i++;
		}
		FileUtil.copy(src, bak, true);
		return bak.getAbsolutePath();
	}

	public File logFile() {
		return new File(new File(home, "logs"), "cert-lifecycle.log");
	}

	public synchronized void log(String level, String message, Throwable error) {
		try {
			File file = logFile();
			FileUtil.mkdir(file.getParentFile());
			StringBuilder sb = new StringBuilder();
			sb.append("[").append(LocalDateTime.now().format(TS_FMT)).append("] [")
					.append(level).append("] ").append(StrUtil.nullToEmpty(message))
					.append(System.lineSeparator());
			if (error != null) {
				java.io.StringWriter sw = new java.io.StringWriter();
				error.printStackTrace(new java.io.PrintWriter(sw));
				sb.append(sw).append(System.lineSeparator());
			}
			Files.write(file.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8),
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (Exception ignored) {
		}
	}

	// ==================== 索引读写 ====================

	private List<ManagedCert> readIndexRaw() {
		List<ManagedCert> certs = new ArrayList<ManagedCert>();
		try {
			List<ManagedCert> parsed = JsonFileStore.read(indexFile(), this::parseIndex);
			if (parsed != null) {
				certs.addAll(parsed);
			}
		} catch (Exception e) {
			log("ERROR", "读取证书库索引失败: " + e.getMessage(), e);
		}
		return certs;
	}

	/** 解析证书索引; JSON 非法时抛出, 由 JsonFileStore 回退 *.bak */
	private List<ManagedCert> parseIndex(String json) {
		List<ManagedCert> certs = new ArrayList<ManagedCert>();
		JSONObject root = JSONUtil.parseObj(json);
		JSONArray arr = root.getJSONArray("certs");
		if (arr != null) {
			for (Object o : arr) {
				if (o instanceof JSONObject) {
					certs.add(parseCert((JSONObject) o));
				}
			}
		}
		return certs;
	}

	private void writeIndex(List<ManagedCert> certs) throws IOException {
		JSONArray arr = new JSONArray();
		for (ManagedCert c : certs) {
			JSONObject o = new JSONObject();
			o.set("id", c.id);
			o.set("name", c.name);
			o.set("note", c.note);
			o.set("keyType", c.keyType);
			o.set("sha256", c.sha256);
			o.set("importedFrom", c.importedFrom);
			o.set("createdAt", c.createdAt);
			o.set("updatedAt", c.updatedAt);
			JSONArray bs = new JSONArray();
			for (Binding b : c.bindings) {
				JSONObject bo = new JSONObject();
				bo.set("instanceId", b.instanceId);
				bo.set("instanceName", b.instanceName);
				bo.set("confPath", b.confPath);
				bo.set("pemPath", b.pemPath);
				bo.set("keyPath", b.keyPath);
				bo.set("serverName", b.serverName);
				bo.set("boundAt", b.boundAt);
				bo.set("ports", new JSONArray(b.ports));
				bs.add(bo);
			}
			o.set("bindings", bs);
			arr.add(o);
		}
		JSONObject root = new JSONObject();
		root.set("version", 1);
		root.set("certs", arr);
		File idx = indexFile();
		JsonFileStore.write(idx, JSONUtil.toJsonPrettyStr(root));
	}

	private void writeIndexQuiet(List<ManagedCert> certs) {
		try {
			writeIndex(certs);
		} catch (Exception e) {
			log("ERROR", "保存证书索引失败: " + e.getMessage(), e);
		}
	}

	private static ManagedCert parseCert(JSONObject o) {
		ManagedCert c = new ManagedCert();
		c.id = o.getStr("id", "");
		c.name = o.getStr("name", "");
		c.note = o.getStr("note", "");
		c.keyType = o.getStr("keyType", "");
		c.sha256 = o.getStr("sha256", "");
		c.importedFrom = o.getStr("importedFrom", "");
		c.createdAt = o.getStr("createdAt", "");
		c.updatedAt = o.getStr("updatedAt", "");
		JSONArray bs = o.getJSONArray("bindings");
		if (bs != null) {
			for (Object bo : bs) {
				if (!(bo instanceof JSONObject)) {
					continue;
				}
				JSONObject bj = (JSONObject) bo;
				Binding b = new Binding();
				b.instanceId = bj.getStr("instanceId", "");
				b.instanceName = bj.getStr("instanceName", "");
				b.confPath = bj.getStr("confPath", "");
				b.pemPath = bj.getStr("pemPath", "");
				b.keyPath = bj.getStr("keyPath", "");
				b.serverName = bj.getStr("serverName", "");
				b.boundAt = bj.getStr("boundAt", "");
				JSONArray ports = bj.getJSONArray("ports");
				if (ports != null) {
					for (Object p : ports) {
						if (p != null) {
							b.ports.add(String.valueOf(p));
						}
					}
				}
				c.bindings.add(b);
			}
		}
		return c;
	}

	private static void replaceBinding(List<Binding> list, Binding nb) {
		for (int i = 0; i < list.size(); i++) {
			if (list.get(i).identity().equals(nb.identity())) {
				list.set(i, nb);
				return;
			}
		}
		list.add(nb);
	}

	// ==================== 工具 ====================

	private static String newId() {
		return "c" + DateUtil.format(new Date(), "yyyyMMddHHmmss") + "-"
				+ UUID.randomUUID().toString().substring(0, 4);
	}

	private static String now() {
		return DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss");
	}

	private static String labelOf(CertEntry ce) {
		String name = StrUtil.isEmpty(ce.item.domain) ? "(未声明 server_name)" : ce.item.domain;
		String inst = StrUtil.emptyToDefault(ce.instance.getName(), ce.instance.getConfPath());
		return inst + " · " + name + (ce.item.ports.isEmpty() ? "" : ":" + ce.item.portsText());
	}

	private static String displayName(NginxInstanceEntry entry) {
		return StrUtil.emptyToDefault(entry.getName(), entry.getConfPath());
	}

	/** 绑定唯一键: 配置文件 + 证书路径 + 私钥路径 */
	private static String key(String confPath, String pemPath, String keyPath) {
		return canonicalPath(confPath) + "|" + canonicalPath(pemPath) + "|" + canonicalPath(keyPath);
	}

	/** 端口配置项对应的绑定唯一键(供界面解除绑定时使用) */
	public static String identityOf(CertItem item) {
		return item == null ? ""
				: key(item.sourceConf == null ? "" : item.sourceConf.getAbsolutePath(),
						item.pemPath, item.keyPath);
	}

	private static String canonicalPath(String path) {
		if (StrUtil.isEmpty(path)) {
			return "";
		}
		try {
			return new File(path).getCanonicalPath().toLowerCase(Locale.ROOT);
		} catch (Exception e) {
			return new File(path).getAbsolutePath().toLowerCase(Locale.ROOT);
		}
	}

	private static String sha256(String pem) {
		return SecureUtil.sha256(StrUtil.nullToEmpty(pem).replace("\r\n", "\n").trim());
	}

	private static String hashOfFile(File f) {
		try {
			return sha256(FileUtil.readString(f, StandardCharsets.UTF_8));
		} catch (Exception e) {
			return "";
		}
	}

	private static String safeRead(File f) {
		try {
			return f != null && f.isFile() ? FileUtil.readString(f, StandardCharsets.UTF_8) : "";
		} catch (Exception e) {
			return "";
		}
	}
}
