package com.nginxui.utils;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;

/**
 * nginx Windows 标准版安装包来源管理:
 * 在线查询(nginx.org 稳定线偶数次版本) / 下载 / zip 上传校验 / 安全解压.
 *
 * 所有临时产物位于 {工具目录}/upgrade-cache, 解压到其中的 pkg/nginx-x.y.z;
 * 绿色软件约定: 缓存跟着工具目录走, 不写固定盘符.
 * 安全策略: zip 扩展名+20MB 上限, 必含 nginx-x.y.z/nginx.exe,
 * 拒绝盘符/绝对路径/.. 穿越条目(ZipSlip), 解压总字节上限防 zip bomb,
 * 下载完成比对 Content-Length 并落盘 SHA-256 校验基线(复用前校验),
 * 解压后执行 nginx -v 校验版本.
 */
public class NginxPackageManager {

	public static final long MAX_ZIP_BYTES = 20L * 1024 * 1024;
	/** 解压后总大小上限(官方 zip 解压约 7MB, 留足余量) */
	public static final long MAX_UNZIP_BYTES = 50L * 1024 * 1024;

	public static final int QUERY_TIMEOUT_MS = 10_000;
	/** 安装包下载连接超时: 10 秒连不上视为网络异常 */
	public static final int DOWNLOAD_CONNECT_TIMEOUT_MS = 10_000;
	/** 安装包下载总时长上限: 超过 180 秒仍未下载完成即中止并提示下载超时 */
	public static final int DOWNLOAD_TIMEOUT_MS = 180_000;

	/** 公网访问(含 nginx.org)失败时的兜底提示 */
	public static final String NET_FAIL_TIP = "网络异常，请访问 https://nginx.org/en/download.html 查看下载";

	/** 按目标程序目录(规范化路径)串行化解压, 防止安装/升级并发互相污染 */
	private static final java.util.concurrent.ConcurrentHashMap<String, Object> UNPACK_LOCKS =
			new java.util.concurrent.ConcurrentHashMap<String, Object>();

	public static final String DOWNLOAD_BASE = "https://nginx.org/download/";

	private static final Pattern VERSION_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)$");
	private static final Pattern ZIP_HREF = Pattern.compile("nginx-(\\d+\\.\\d+\\.\\d+)\\.zip");
	private static final Pattern TOP_DIR_PATTERN = Pattern.compile("nginx-(\\d+\\.\\d+\\.\\d+)$");
	private static final Pattern NGINX_V = Pattern.compile("nginx/(\\d+\\.\\d+\\.\\d+)");
	private static final Pattern DRIVE_PATTERN = Pattern.compile("^[A-Za-z]:[\\\\/]");

	/** 上传/下载校验后的包信息 */
	public static class PackageInfo {
		public String version;
		/** workDir 内的 zip 文件名(对外只暴露这个 token, 不暴露绝对路径) */
		public String token;
		public long size;
	}

	/** 解压结果 */
	public static class UnpackResult {
		public String version;
		public File programDir;
		public File nginxExe;
	}

	/**
	 * 查询 nginx.org 稳定版(偶数次版本线)列表, 版本号降序去重.
	 * 离线/超时抛出带中文说明的 IllegalStateException.
	 */
	public static List<String> queryStableVersions() {
		String html;
		HttpResponse resp;
		try {
			resp = HttpRequest.get(DOWNLOAD_BASE).timeout(QUERY_TIMEOUT_MS).execute();
		} catch (Exception e) {
			throw new IllegalStateException("公网请求 " + (QUERY_TIMEOUT_MS / 1000)
					+ " 秒无响应: " + e.getMessage() + "。" + NET_FAIL_TIP);
		}
		try {
			if (!resp.isOk()) {
				throw new IllegalStateException("nginx.org 返回 HTTP " + resp.getStatus()
						+ "。" + NET_FAIL_TIP);
			}
			html = resp.body();
		} finally {
			resp.close();
		}

		Set<String> versions = new LinkedHashSet<String>();
		Matcher m = ZIP_HREF.matcher(StrUtil.nullToEmpty(html));
		while (m.find()) {
			String ver = m.group(1);
			int minor = Integer.parseInt(ver.split("\\.")[1]);
			if (minor % 2 == 0) {
				versions.add(ver);
			}
		}
		if (versions.isEmpty()) {
			throw new IllegalStateException("未从 nginx.org 解析到任何稳定版 zip，请改用本地上传");
		}
		List<String> list = new ArrayList<String>(versions);
		list.sort(new Comparator<String>() {
			@Override
			public int compare(String a, String b) {
				List<Integer> ka = versionKey(a);
				List<Integer> kb = versionKey(b);
				for (int i = 0; i < Math.min(ka.size(), kb.size()); i++) {
					int c = Integer.compare(ka.get(i), kb.get(i));
					if (c != 0) {
						return c;
					}
				}
				return Integer.compare(ka.size(), kb.size());
			}
		});
		java.util.Collections.reverse(list);
		return list;
	}

	/** 升级地址连通性检测结果 */
	public static class Reachability {
		/** 能正常访问 nginx.org 下载页(在线升级可用) */
		public boolean online;
		/** 检测耗时(毫秒) */
		public long elapsedMs;
		/** 成功/失败说明(失败时为中文原因) */
		public String detail;
		/** 稳定版列表(仅 online=true 时非空) */
		public List<String> versions = new ArrayList<String>();
	}

	/**
	 * 检测升级地址(nginx.org 下载页)是否可正常连通, 并顺带取回稳定版列表.
	 * 与版本查询共用同一次请求; 外层用 10 秒硬超时兜底, 保证检测总耗时不超过 10 秒
	 * (connect/read 各自超时会叠加, 单靠 HttpRequest.timeout 无法保证).
	 */
	public static Reachability probeUpgradeSource() {
		Reachability r = new Reachability();
		long start = System.currentTimeMillis();
		ExecutorService executor = Executors.newSingleThreadExecutor(run -> {
			Thread t = new Thread(run, "nginxui-upgrade-probe");
			t.setDaemon(true);
			return t;
		});
		Future<List<String>> future = null;
		try {
			future = executor.submit(NginxPackageManager::queryStableVersions);
			r.versions = future.get(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
			r.online = true;
			r.detail = "已连接 " + DOWNLOAD_BASE;
		} catch (TimeoutException e) {
			if (future != null) {
				future.cancel(true);
			}
			r.online = false;
			r.detail = "连通性检测超过 " + (QUERY_TIMEOUT_MS / 1000) + " 秒无响应";
		} catch (ExecutionException e) {
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			r.online = false;
			r.detail = StrUtil.emptyToDefault(cause.getMessage(), NET_FAIL_TIP);
		} catch (Exception e) {
			r.online = false;
			r.detail = StrUtil.emptyToDefault(e.getMessage(), NET_FAIL_TIP);
		} finally {
			executor.shutdownNow();
			r.elapsedMs = System.currentTimeMillis() - start;
		}
		return r;
	}

	/** 安装包缓存目录: {工具目录}/upgrade-cache (Main 已把工具目录写入 nginxui.home) */
	public static File workDir() {
		String home = System.getProperty("nginxui.home");
		File base = StrUtil.isNotEmpty(home) ? new File(home)
				: new File(System.getProperty("java.io.tmpdir"));
		return FileUtil.mkdir(new File(base, "upgrade-cache"));
	}

	public static File zipPath(String token) {
		// token 仅允许纯文件名, 防止路径穿越
		String name = new File(token).getName();
		if (!name.toLowerCase().endsWith(".zip")) {
			throw new IllegalArgumentException("非法的安装包标识");
		}
		return new File(workDir(), name);
	}

	/** 安装包 SHA-256 校验基线文件(与 zip 同名 + .sha256) */
	private static File hashFileOf(File zip) {
		return new File(zip.getParentFile(), zip.getName() + ".sha256");
	}

	/**
	 * 本地包与校验基线比对: 基线存在 + SHA-256 一致 + 结构校验出同版本才可信.
	 * 无基线(历史遗留/上传覆盖)一律视为不可信, 由调用方删除重下.
	 */
	private static boolean verifyAgainstBaseline(File zip, String expectVer) {
		File hashFile = hashFileOf(zip);
		if (!hashFile.isFile()) {
			return false;
		}
		try {
			String stored = StrUtil.trim(FileUtil.readUtf8String(hashFile));
			if (StrUtil.isEmpty(stored)
					|| !stored.equalsIgnoreCase(DigestUtil.sha256Hex(zip))) {
				return false;
			}
			return expectVer.equals(inspectZip(zip));
		} catch (Exception e) {
			return false;
		}
	}

	/** 上传覆盖同名安装包后调用: 原校验基线失效, 删除避免误信 */
	public static void invalidateHash(File zip) {
		FileUtil.del(hashFileOf(zip));
	}

	/**
	 * 下载指定版本 zip 到 workDir; 已存在且结构合法并匹配 SHA-256 校验基线才复用,
	 * 否则删除后重新下载(防本地包被篡改后静默复用).
	 */
	public static PackageInfo download(String version, Consumer<String> log) {
		String ver = normalizeVersion(version);
		File target = new File(workDir(), "nginx-" + ver + ".zip");
		if (target.isFile()) {
			if (verifyAgainstBaseline(target, ver)) {
				log(log, "已存在相同版本安装包且校验通过，直接复用: " + target.getName());
				return info(ver, target);
			}
			log(log, "本地安装包缺少校验基线或校验失败，重新下载: " + target.getName());
			FileUtil.del(target);
			FileUtil.del(hashFileOf(target));
		}

		String url = DOWNLOAD_BASE + "nginx-" + ver + ".zip";
		File part = new File(workDir(), "nginx-" + ver + ".zip.part");
		log(log, "开始下载: " + url);
		long startMs = System.currentTimeMillis();
		try {
			// 异步模式: execute 只完成建连+响应头, body 由下方循环流式写盘,
			// 从而可在循环内实施总时长预算(180 秒未完成即中止)与大小实时拦截
			HttpResponse resp;
			try {
				resp = HttpRequest.get(url)
						.setConnectionTimeout(DOWNLOAD_CONNECT_TIMEOUT_MS)
						.setReadTimeout(DOWNLOAD_TIMEOUT_MS)
						.executeAsync();
			} catch (Exception ex) {
				if (System.currentTimeMillis() - startMs >= DOWNLOAD_TIMEOUT_MS) {
					throw new IllegalStateException("下载超时(超过 " + (DOWNLOAD_TIMEOUT_MS / 1000)
							+ " 秒未完成)。" + NET_FAIL_TIP);
				}
				throw new IllegalStateException("公网请求 " + (DOWNLOAD_CONNECT_TIMEOUT_MS / 1000)
						+ " 秒无响应: " + ex.getMessage() + "。" + NET_FAIL_TIP);
			}
			try {
				if (!resp.isOk()) {
					throw new IllegalStateException("nginx.org 返回 HTTP " + resp.getStatus()
							+ "。" + NET_FAIL_TIP);
				}
				long expected = resp.contentLength();
				long written = 0L;
				try (InputStream in = resp.bodyStream();
						java.io.OutputStream out = Files.newOutputStream(part.toPath())) {
					byte[] buf = new byte[8192];
					int n;
					while ((n = in.read(buf)) != -1) {
						out.write(buf, 0, n);
						written += n;
						if (written > MAX_ZIP_BYTES) {
							throw new IllegalStateException("安装包超出 "
									+ (MAX_ZIP_BYTES / 1024 / 1024) + "MB 上限: " + written);
						}
						if (System.currentTimeMillis() - startMs >= DOWNLOAD_TIMEOUT_MS) {
							throw new IllegalStateException("下载超时(超过 " + (DOWNLOAD_TIMEOUT_MS / 1000)
									+ " 秒未完成)。" + NET_FAIL_TIP);
						}
					}
				}
				// 传输完整性: 实际字节数与服务器声明一致(分块/未知长度时跳过)
				if (expected > 0 && written != expected) {
					throw new IllegalStateException("下载不完整: 服务器声明 " + expected
							+ " 字节, 实际写入 " + written + " 字节。" + NET_FAIL_TIP);
				}
			} finally {
				resp.close();
			}
		} catch (Exception e) {
			FileUtil.del(part);
			if (e instanceof IllegalStateException && String.valueOf(e.getMessage()).contains(NET_FAIL_TIP)) {
				throw (IllegalStateException) e;
			}
			if (System.currentTimeMillis() - startMs >= DOWNLOAD_TIMEOUT_MS) {
				throw new IllegalStateException("下载超时(超过 " + (DOWNLOAD_TIMEOUT_MS / 1000)
						+ " 秒未完成)。" + NET_FAIL_TIP);
			}
			throw new IllegalStateException("下载 nginx-" + ver + ".zip 失败（可能离线或网络受限）: "
					+ e.getMessage(), e);
		}

		long size = part.length();
		if (size > MAX_ZIP_BYTES) {
			FileUtil.del(part);
			throw new IllegalStateException("安装包超出 "
					+ (MAX_ZIP_BYTES / 1024 / 1024) + "MB 上限: " + size);
		}
		FileUtil.move(part, target, true);
		// 落盘 SHA-256 校验基线, 供后续"本地包复用"前校验, 防篡改
		try {
			FileUtil.writeUtf8String(DigestUtil.sha256Hex(target), hashFileOf(target));
		} catch (Exception e) {
			FileUtil.del(target);
			throw new IllegalStateException("安装包校验基线生成失败: " + e.getMessage(), e);
		}
		log(log, "下载完成: " + (size / 1024) + " KB (SHA-256 已记录)");
		return info(ver, target);
	}

	/**
	 * 校验上传落盘的 zip: 扩展名/大小/结构(必含 nginx-x.y.z/nginx.exe), 返回识别版本.
	 */
	public static String inspectZip(File zip) {
		if (zip == null || !zip.isFile()) {
			throw new IllegalArgumentException("安装包不存在");
		}
		if (!zip.getName().toLowerCase().endsWith(".zip")) {
			throw new IllegalArgumentException("仅支持 .zip 安装包");
		}
		if (zip.length() > MAX_ZIP_BYTES) {
			throw new IllegalArgumentException("安装包超出 "
					+ (MAX_ZIP_BYTES / 1024 / 1024) + "MB 上限: " + zip.length());
		}
		String top = null;
		boolean hasNginxeExe = false;
		long total = 0;
		try (ZipFile zf = new ZipFile(zip, StandardCharsets.UTF_8)) {
			java.util.Enumeration<? extends ZipEntry> en = zf.entries();
			while (en.hasMoreElements()) {
				ZipEntry e = en.nextElement();
				if (e.isDirectory()) {
					continue;
				}
				String normalized = normalizeEntryName(e.getName());
				rejectUnsafe(normalized);
				String[] parts = normalized.split("/");
				if (parts.length < 2) {
					throw new IllegalArgumentException("非法条目(不在 nginx-x.y.z 目录内): " + e.getName());
				}
				Matcher tm = TOP_DIR_PATTERN.matcher(parts[0]);
				if (!tm.matches()) {
					throw new IllegalArgumentException("安装包顶层目录必须为 nginx-x.y.z: " + parts[0]);
				}
				if (top == null) {
					top = tm.group(1);
				} else if (!top.equals(tm.group(1))) {
					throw new IllegalArgumentException("安装包内存在多个版本顶层目录");
				}
				if (("nginx-" + top + "/nginx.exe").equals(normalized)) {
					hasNginxeExe = true;
				}
				total += e.getSize() > 0 ? e.getSize() : 0;
				if (total > MAX_UNZIP_BYTES) {
					throw new IllegalArgumentException("解压后总大小超出 "
							+ (MAX_UNZIP_BYTES / 1024 / 1024) + "MB 上限");
				}
			}
		} catch (IllegalArgumentException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalArgumentException("zip 解析失败(文件可能已损坏): " + e.getMessage(), e);
		}
		if (top == null || !hasNginxeExe) {
			throw new IllegalArgumentException("安装包缺少 nginx-" + (top == null ? "x.y.z" : top)
					+ "/nginx.exe，不是官方标准版 nginx zip");
		}
		return top;
	}

	/**
	 * 安全解压到安装根, 剥离顶层 nginx-x.y.z 目录, 然后 nginx -v 验证.
	 * 已存在的程序目录直接复用(不覆盖).
	 * 按"目标程序目录"维度加进程内锁, 并先解压到同级临时目录再原子改名,
	 * 防止安装/升级并发时半成品目录被另一方复用或误删.
	 */
	public static UnpackResult unpack(File zip, File root, Consumer<String> log) {
		String ver = inspectZip(zip);
		File programDir = new File(root, "nginx-" + ver);
		File exe = new File(programDir, "nginx.exe");
		String lockKey;
		try {
			lockKey = programDir.getCanonicalPath().toLowerCase();
		} catch (Exception e) {
			lockKey = programDir.getAbsolutePath().toLowerCase();
		}
		Object lock = UNPACK_LOCKS.computeIfAbsent(lockKey, k -> new Object());
		synchronized (lock) {
			if (exe.isFile()) {
				log(log, "程序目录已存在，复用: " + programDir.getAbsolutePath());
				String detected = detectVersion(exe);
				if (!ver.equals(detected)) {
					throw new IllegalStateException("已存在目录的版本(" + detected
							+ ")与安装包版本(" + ver + ")不一致，请先处理该目录");
				}
				UnpackResult r = new UnpackResult();
				r.version = ver;
				r.programDir = programDir;
				r.nginxExe = exe;
				return r;
			}

			File stageDir = new File(root, ".nginx-" + ver + ".tmp-"
					+ java.util.UUID.randomUUID().toString().substring(0, 8));
			boolean success = false;
			try {
				long total = 0;
				FileUtil.mkdir(stageDir);
				try (ZipFile zf = new ZipFile(zip, StandardCharsets.UTF_8)) {
					java.util.Enumeration<? extends ZipEntry> en = zf.entries();
					while (en.hasMoreElements()) {
						ZipEntry e = en.nextElement();
						String normalized = normalizeEntryName(e.getName());
						if (normalized.endsWith("/")) {
							normalized = normalized.substring(0, normalized.length() - 1);
						}
						if (normalized.isEmpty()) {
							continue;
						}
						rejectUnsafe(normalized);
						// 顶层目录条目(nginx-x.y.z/)没有子路径, 若不跳过会在程序目录内
						// 多建一个同名空子目录
						int slash = normalized.indexOf('/');
						if (slash < 0) {
							continue;
						}
						String rel = normalized.substring(slash + 1);
						if (rel.isEmpty()) {
							continue;
						}
						// 目录条目也要落盘: 官方包内 logs/、temp/ 是空目录,
						// 跳过会导致 nginx -t 无法创建 error.log/nginx.pid
						if (e.isDirectory()) {
							FileUtil.mkdir(new File(stageDir, rel));
							continue;
						}
						File target = new File(stageDir, rel);
						String canonicalTarget = target.getCanonicalPath();
						String canonicalBase = stageDir.getCanonicalPath();
						if (!canonicalTarget.toLowerCase().startsWith(
								canonicalBase.toLowerCase() + File.separator)
								&& !canonicalTarget.equalsIgnoreCase(canonicalBase)) {
							throw new IllegalArgumentException("检测到路径穿越条目: " + e.getName());
						}
						FileUtil.mkdir(target.getParentFile());
						try (InputStream in = zf.getInputStream(e)) {
							Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
						}
						total += target.length();
						if (total > MAX_UNZIP_BYTES) {
							throw new IllegalArgumentException("解压后总大小超出 "
							+ (MAX_UNZIP_BYTES / 1024 / 1024) + "MB 上限");
						}
					}
				}
				File stagedExe = new File(stageDir, "nginx.exe");
				if (!stagedExe.isFile()) {
					throw new IllegalStateException("解压完成但未找到 nginx.exe");
				}
				String detected = detectVersion(stagedExe);
				log(log, "nginx -v = " + detected);
				if (!ver.equals(detected)) {
					throw new IllegalStateException("安装包目录版本(" + ver
							+ ")与 nginx -v 实际版本(" + detected + ")不一致");
				}
				// 原子就位: 此时 programDir 必然不存在(锁内复检过), 无需 REPLACE_EXISTING
				try {
					Files.move(stageDir.toPath(), programDir.toPath());
				} catch (Exception moveError) {
					throw new IllegalStateException("程序目录就位失败: " + moveError.getMessage(), moveError);
				}
				UnpackResult r = new UnpackResult();
				r.version = detected;
				r.programDir = programDir;
				r.nginxExe = exe;
				success = true;
				return r;
			} catch (IllegalArgumentException | IllegalStateException e) {
				throw e;
			} catch (Exception e) {
				throw new IllegalStateException("解压失败: " + e.getMessage(), e);
			} finally {
				if (!success) {
					// 失败清理半成品临时目录(保留 zip 供排查)
					FileUtil.del(stageDir);
				}
			}
		}
	}

	/** 执行 nginx -v 解析版本(8s 超时, 失败抛异常) */
	public static String detectVersion(File exe) {
		try {
			ProcessBuilder pb = new ProcessBuilder(exe.getAbsolutePath(), "-v");
			pb.directory(exe.getParentFile());
			pb.redirectErrorStream(true);
			Process process = pb.start();
			String out = IoUtil.read(process.getInputStream(), StandardCharsets.UTF_8);
			boolean finished = process.waitFor(8, TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly();
				throw new IllegalStateException("nginx -v 执行超时");
			}
			Matcher m = NGINX_V.matcher(StrUtil.nullToEmpty(out));
			if (m.find()) {
				return m.group(1);
			}
			throw new IllegalStateException("无法从 nginx -v 输出解析版本: " + out.trim());
		} catch (IllegalStateException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException("执行 nginx -v 失败: " + e.getMessage(), e);
		}
	}

	private static PackageInfo info(String ver, File zip) {
		PackageInfo pi = new PackageInfo();
		pi.version = ver;
		pi.token = zip.getName();
		pi.size = zip.length();
		return pi;
	}

	private static String normalizeVersion(String version) {
		String ver = StrUtil.trim(version);
		if (!VERSION_PATTERN.matcher(StrUtil.nullToEmpty(ver)).matches()) {
			throw new IllegalArgumentException("版本号格式不正确，应为 x.y.z: " + ver);
		}
		return ver;
	}

	/** zip 条目名统一为 / 分隔 */
	private static String normalizeEntryName(String name) {
		return name.replace('\\', '/');
	}

	/** ZipSlip / 绝对盘符条目防护 */
	private static void rejectUnsafe(String normalizedName) {
		if (normalizedName.startsWith("/") || DRIVE_PATTERN.matcher(normalizedName).find()) {
			throw new IllegalArgumentException("检测到绝对路径条目: " + normalizedName);
		}
		String[] parts = normalizedName.split("/");
		for (String p : parts) {
			if ("..".equals(p)) {
				throw new IllegalArgumentException("检测到路径穿越(..)条目: " + normalizedName);
			}
		}
	}

	private static List<Integer> versionKey(String v) {
		List<Integer> key = new ArrayList<Integer>();
		for (String p : v.split("\\.")) {
			key.add(Integer.parseInt(p));
		}
		return key;
	}

	private static void log(Consumer<String> sink, String msg) {
		if (sink != null) {
			sink.accept(msg);
		}
	}
}
