package com.nginxui.utils;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.nginxui.ext.NginxInstanceEntry;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;

/**
 * 实例日志定位与尾部读取:
 * 从 nginx.conf 解析 error_log / access_log 指令(相对路径按 prefix 解析),
 * 读不到时回退到标准布局的 logs/error.log、logs/access.log.
 *
 * 大文件策略: 只从文件末尾按窗口读取, 窗口 256KB 起按需倍增(上限 4MB),
 * 日志跟随只读新增字节; 单行超过 2000 字符截断, 避免超大单行拖垮渲染.
 */
public class NginxLogUtils {

	private static final Pattern ERROR_LOG = Pattern.compile("(?m)^\\s*error_log\\s+([^;]+);");
	private static final Pattern ACCESS_LOG = Pattern.compile("(?m)^\\s*access_log\\s+([^;]+);");
	/** 尾部读取初始窗口 */
	private static final long MIN_WINDOW_BYTES = 256 * 1024L;
	/** 尾部读取窗口上限(单行过长时窗口倍增到此为止) */
	private static final long MAX_WINDOW_BYTES = 4 * 1024 * 1024L;
	/** 单行展示长度上限 */
	private static final int MAX_LINE_CHARS = 2000;

	/** 实例日志文件位置 */
	public static class Logs {
		public File errorLog;
		public File accessLog;
		/** access_log off 等无文件场景的说明 */
		public String accessNote;
	}

	/** 一次读取结果 */
	public static class Snapshot {
		/** reset=true 时是最新 maxLines 行; reset=false 时是本次新增行 */
		public final List<String> lines;
		/** 需要整页替换(首次读取/文件被截断或轮转/增量过大) */
		public final boolean reset;
		/** 文件长度未变, 无需重绘 */
		public final boolean unchanged;

		Snapshot(List<String> lines, boolean reset, boolean unchanged) {
			this.lines = lines;
			this.reset = reset;
			this.unchanged = unchanged;
		}
	}

	/**
	 * 有状态的尾部读取器: 记住上次读到的位置, 跟随模式下只读新增字节.
	 * 文件变短(截断/轮转/换文件)或新增超过 4MB 时自动退回整页重读.
	 */
	public static class TailReader {
		private final int maxLines;
		private File file;
		/** 上次读到且落在行边界上的位置, -1 表示尚未读取 */
		private long readTo = -1;
		/** readTo 是否正好位于换行之后(否则下一段开头可能是半行) */
		private boolean atLineStart;

		public TailReader(int maxLines) {
			this.maxLines = Math.max(1, maxLines);
		}

		/**
		 * 丢弃已读位置: 下一次读取必定按整页重读.
		 * 面板内容被清空后必须调用, 保证读取状态与展示内容一致, 否则下一次读取可能返回
		 * "文件未变化"而被跳过, 面板就会一直空白.
		 */
		public synchronized void reset() {
			file = null;
			readTo = -1;
			atLineStart = false;
		}

		public synchronized Snapshot read(File f) throws IOException {
			try {
				return doRead(f);
			} catch (EOFException e) {
				// 读取期间文件被截断/轮转: 重试一次, 此次按新文件长度整页读取
				return doRead(f);
			}
		}

		private Snapshot doRead(File f) throws IOException {
			if (f == null || !f.isFile()) {
				file = f;
				readTo = -1;
				atLineStart = false;
				return new Snapshot(new ArrayList<String>(), true, false);
			}
			long len = f.length();
			boolean same = f.equals(file) && readTo >= 0;
			if (same && len == readTo) {
				return new Snapshot(Collections.<String>emptyList(), false, true);
			}
			if (same && atLineStart && len > readTo && len - readTo <= MAX_WINDOW_BYTES) {
				Snapshot delta = readDelta(f, readTo, len);
				if (delta != null) {
					file = f;
					return delta;
				}
			}
			return readFull(f, len);
		}

		/** 只读 [from, to) 的新增字节; 返回 null 表示增量过大, 应整页重载 */
		private Snapshot readDelta(File f, long from, long to) throws IOException {
			int size = (int) (to - from);
			byte[] buf = new byte[size];
			try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
				raf.seek(from);
				raf.readFully(buf);
			}
			int lastNl = -1;
			for (int i = size - 1; i >= 0; i--) {
				if (buf[i] == '\n') {
					lastNl = i;
					break;
				}
			}
			if (lastNl < 0) {
				// 新增内容还没有完整行: 不推进读位, 等下次有新行再取
				return new Snapshot(Collections.<String>emptyList(), false, true);
			}
			List<String> lines = splitLines(new String(buf, 0, lastNl + 1, StandardCharsets.UTF_8));
			if (lines.size() > maxLines) {
				return null;
			}
			readTo = from + lastNl + 1;
			atLineStart = true;
			return new Snapshot(trimLines(lines), false, false);
		}

		private Snapshot readFull(File f, long len) throws IOException {
			List<String> lines = readTail(f, maxLines);
			readTo = len;
			atLineStart = endsWithNewline(f, len);
			file = f;
			return new Snapshot(trimLines(lines), true, false);
		}
	}

	private static boolean endsWithNewline(File f, long len) {
		if (len <= 0) {
			return false;
		}
		try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
			raf.seek(len - 1);
			return raf.read() == '\n';
		} catch (IOException e) {
			return false;
		}
	}

	/** 从尾部按窗口读 maxLines 行; 行数不够时窗口倍增, 最多读 4MB */
	private static List<String> readTail(File f, int maxLines) throws IOException {
		long len = f.length();
		long window = MIN_WINDOW_BYTES;
		try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
			while (true) {
				long start = Math.max(0, len - window);
				int size = (int) (len - start);
				byte[] buf = new byte[size];
				raf.seek(start);
				raf.readFully(buf);
				String text = new String(buf, StandardCharsets.UTF_8);
				if (start > 0) {
					// 从文件中间开始读, 丢弃首个不完整行
					int nl = text.indexOf('\n');
					text = nl >= 0 ? text.substring(nl + 1) : "";
				}
				List<String> lines = splitLines(text);
				if (lines.size() >= maxLines || start == 0 || window >= MAX_WINDOW_BYTES) {
					int from = Math.max(0, lines.size() - maxLines);
					return new ArrayList<String>(lines.subList(from, lines.size()));
				}
				window = Math.min(MAX_WINDOW_BYTES, window * 2);
			}
		}
	}

	/** 按 \n 切行(兼容 \r\n, 保留空行, 丢弃末尾换行后的空串) */
	private static List<String> splitLines(String text) {
		List<String> lines = new ArrayList<String>();
		int n = text.length();
		if (n == 0) {
			return lines;
		}
		int start = 0;
		for (int i = 0; i < n; i++) {
			if (text.charAt(i) != '\n') {
				continue;
			}
			int end = i;
			if (end > start && text.charAt(end - 1) == '\r') {
				end--;
			}
			lines.add(text.substring(start, end));
			start = i + 1;
		}
		if (start < n) {
			int end = n;
			if (end > start && text.charAt(end - 1) == '\r') {
				end--;
			}
			lines.add(text.substring(start, end));
		}
		return lines;
	}

	/** 单行过长截断: 只影响展示, 不影响读取位置 */
	private static List<String> trimLines(List<String> lines) {
		for (int i = 0; i < lines.size(); i++) {
			String s = lines.get(i);
			if (s.length() > MAX_LINE_CHARS) {
				lines.set(i, s.substring(0, MAX_LINE_CHARS) + " …[已截断, 原长 " + s.length() + " 字符]");
			}
		}
		return lines;
	}

	/** 解析实例日志路径(只读 nginx.conf, 不做进程检测) */
	public static Logs resolve(NginxInstanceEntry entry) {
		Logs logs = new Logs();
		String confPath = StrUtil.nullToEmpty(entry.getConfPath());
		String prefix = StrUtil.emptyToDefault(entry.getPrefixDir(), entry.getNginxDir());
		if (StrUtil.isEmpty(prefix) && StrUtil.isNotEmpty(confPath)) {
			File conf = new File(confPath);
			if (conf.getParentFile() != null && conf.getParentFile().getParentFile() != null) {
				prefix = conf.getParentFile().getParentFile().getAbsolutePath();
			}
		}

		String conf = "";
		if (StrUtil.isNotEmpty(confPath) && new File(confPath).isFile()) {
			try {
				conf = FileUtil.readString(confPath, StandardCharsets.UTF_8);
			} catch (Exception ignored) {
			}
		}

		boolean accessDeclared = ACCESS_LOG.matcher(conf).find();
		String errorToken = firstToken(conf, ERROR_LOG);
		String accessToken = firstToken(conf, ACCESS_LOG);

		logs.errorLog = toFile(errorToken, prefix);
		logs.accessLog = toFile(accessToken, prefix);

		if (logs.errorLog == null && StrUtil.isNotEmpty(prefix)) {
			logs.errorLog = new File(prefix, "logs" + File.separator + "error.log");
		}
		if (logs.accessLog == null) {
			if (accessDeclared) {
				// 已声明但未落文件(off/syslog:/memory:), 给出说明而不是指向不存在的文件
				logs.accessNote = "配置中 access_log 未写入文件(off/syslog/memory)，无访问日志可读";
			} else if (StrUtil.isNotEmpty(prefix)) {
				logs.accessLog = new File(prefix, "logs" + File.separator + "access.log");
			}
		}
		return logs;
	}

	// ==================== 内部 ====================

	/** 指令第一参数; off/syslog:/stderr/memory: 视为无文件返回 null */
	private static String firstToken(String conf, Pattern pattern) {
		if (StrUtil.isEmpty(conf)) {
			return null;
		}
		Matcher m = pattern.matcher(conf);
		if (!m.find()) {
			return null;
		}
		String value = m.group(1).trim();
		if (value.startsWith("\"")) {
			int end = value.indexOf('"', 1);
			return end > 0 ? value.substring(1, end) : null;
		}
		String token = value.split("\\s+")[0];
		String low = token.toLowerCase();
		if (low.startsWith("off") || low.startsWith("syslog:") || low.startsWith("stderr")
				|| low.startsWith("memory:")) {
			return null;
		}
		return token;
	}

	private static File toFile(String token, String prefix) {
		if (StrUtil.isEmpty(token)) {
			return null;
		}
		File f = new File(token);
		if (!f.isAbsolute() && StrUtil.isNotEmpty(prefix)) {
			f = new File(prefix, token);
		}
		return f;
	}
}
