package com.nginxui.utils;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.hutool.json.JSONUtil;

/**
 * JSON 配置文件持久化: 原子写 + 自动备份 + 损坏恢复.
 *
 * 写: 先备份现有文件为 *.bak, 再写同目录临时文件并 force 落盘,
 *     最后整体替换目标文件; 平台不支持原子移动时回退普通替换.
 * 读: 主文件解析失败(JSON 损坏)时, 把损坏文件保留为 *.corrupt-时间戳,
 *     并尝试从 *.bak 恢复, 恢复成功则同步回主文件.
 *
 * 仅用于工具自身的 JSON 状态文件, 不处理证书/配置等业务文件.
 */
public final class JsonFileStore {

	private static final Logger LOG = LoggerFactory.getLogger(JsonFileStore.class);
	private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	/** 串行化写入: 后台刷新与界面操作可能同时保存同一个状态文件 */
	private static final Object WRITE_LOCK = new Object();

	/** 解析回调: 内容不合法时应抛出异常, 由本类判定文件损坏并回退备份 */
	public interface Parser<T> {
		T parse(String json) throws Exception;
	}

	private JsonFileStore() {
	}

	/**
	 * 读取 JSON 文件; 主文件损坏时自动回退备份.
	 *
	 * @return 解析结果; 主文件与备份都不存在时返回 null
	 * @throws IOException 文件存在但无法读取, 或主文件与备份均无法解析
	 */
	public static <T> T read(File file, Parser<T> parser) throws IOException {
		if (file == null || parser == null) {
			return null;
		}
		File bak = bakFile(file);

		if (file.isFile()) {
			String content;
			try {
				content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
			} catch (IOException e) {
				LOG.error("读取 JSON 文件失败: {}", file, e);
				throw e;
			}
			try {
				return parser.parse(content);
			} catch (Exception e) {
				LOG.warn("JSON 文件损坏, 尝试从备份恢复: {} ({})", file, e.toString());
				preserveCorrupt(file);
				if (bak.isFile()) {
					try {
						T value = parser.parse(Files.readString(bak.toPath(), StandardCharsets.UTF_8));
						restore(bak, file);
						LOG.warn("已从备份恢复 JSON 文件: {} (备份: {})", file, bak);
						return value;
					} catch (Exception be) {
						LOG.error("备份文件同样无法解析: {} ({})", bak, be.toString());
						throw new IOException("JSON 文件与备份均无法解析: " + file, e);
					}
				}
				throw new IOException("JSON 文件无法解析且无可用备份: " + file, e);
			}
		}

		// 主文件缺失(异常退出打断了旧版非原子写)但备份还在: 直接用备份恢复
		if (bak.isFile()) {
			try {
				T value = parser.parse(Files.readString(bak.toPath(), StandardCharsets.UTF_8));
				restore(bak, file);
				LOG.warn("主文件缺失, 已从备份恢复: {} (备份: {})", file, bak);
				return value;
			} catch (Exception e) {
				LOG.warn("备份文件无法解析: {} ({})", bak, e.toString());
			}
		}
		return null;
	}

	/**
	 * 原子写入 JSON 文件(UTF-8), 写前把现有文件备份为 *.bak.
	 * 内容与现有文件完全一致时直接跳过, 避免定时刷新造成的无谓写盘.
	 */
	public static void write(File file, String content) throws IOException {
		synchronized (WRITE_LOCK) {
			writeLocked(file, content);
		}
	}

	private static void writeLocked(File file, String content) throws IOException {
		if (file == null) {
			throw new IOException("JSON 写入目标为空");
		}
		if (content == null) {
			throw new IOException("JSON 写入内容为空: " + file);
		}
		File target = file.getAbsoluteFile();
		File dir = target.getParentFile();
		if (dir == null) {
			throw new IOException("无法定位 JSON 文件目录: " + file);
		}
		if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
			throw new IOException("无法创建 JSON 文件目录: " + dir);
		}

		// 内容未变化时不写盘, 也不覆盖备份
		if (target.isFile()) {
			try {
				if (content.equals(Files.readString(target.toPath(), StandardCharsets.UTF_8))) {
					return;
				}
			} catch (IOException ignored) {
				// 读不到旧内容时继续走正常写入流程
			}
		}

		backup(target);

		Path tmp = Files.createTempFile(dir.toPath(), "nxjson-", ".tmp");
		try {
			byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
			try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE,
					StandardOpenOption.TRUNCATE_EXISTING)) {
				ByteBuffer buf = ByteBuffer.wrap(bytes);
				while (buf.hasRemaining()) {
					ch.write(buf);
				}
				// 保证临时文件内容先落盘, 再做改名替换
				ch.force(true);
			}
			moveIntoPlace(tmp, target.toPath());
		} finally {
			try {
				Files.deleteIfExists(tmp);
			} catch (IOException ignored) {
				// 临时文件残留不影响主文件
			}
		}
	}

	/** 备份现有文件: 现有内容不是合法 JSON 时保留上一次可用备份, 不被损坏内容覆盖 */
	private static void backup(File file) {
		if (!file.isFile()) {
			return;
		}
		File bak = bakFile(file);
		try {
			String old = Files.readString(file.toPath(), StandardCharsets.UTF_8);
			if (!looksLikeJson(old)) {
				LOG.warn("跳过备份: 现有文件不是有效 JSON, 保留上一次可用备份: {}", file);
				return;
			}
			Files.copy(file.toPath(), bak.toPath(),
					StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
		} catch (Exception e) {
			// 备份失败不阻断本次写入(下次写入还会重试备份)
			LOG.warn("备份 JSON 文件失败: {} ({})", file, e.toString());
		}
	}

	/** 临时文件整体替换目标文件, 优先原子移动 */
	private static void moveIntoPlace(Path tmp, Path target) throws IOException {
		try {
			Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			// 部分平台目标存在时拒绝 ATOMIC_MOVE, 回退普通替换
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** 把损坏主文件改名保留, 供排查原因; 原主文件位置随后交给备份恢复 */
	private static void preserveCorrupt(File file) {
		File corrupt = new File(file.getParentFile(),
				file.getName() + ".corrupt-" + LocalDateTime.now().format(TS_FMT));
		try {
			Files.move(file.toPath(), corrupt.toPath(), StandardCopyOption.REPLACE_EXISTING);
			LOG.warn("已保留损坏的 JSON 文件: {}", corrupt);
		} catch (Exception e) {
			LOG.warn("保留损坏的 JSON 文件失败: {} ({})", file, e.toString());
			try {
				Files.deleteIfExists(file.toPath());
			} catch (IOException ignored) {
				// 删除失败也不影响后续写入
			}
		}
	}

	/** 从备份复制回主文件(备份本身保留) */
	private static void restore(File bak, File file) {
		try {
			Files.copy(bak.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			LOG.warn("回写备份到主文件失败: {} ({})", file, e.toString());
		}
	}

	private static File bakFile(File file) {
		return new File(file.getParentFile(), file.getName() + ".bak");
	}

	private static boolean looksLikeJson(String text) {
		if (text == null || text.trim().isEmpty()) {
			return false;
		}
		try {
			JSONUtil.parse(text);
			return true;
		} catch (Exception e) {
			return false;
		}
	}
}
