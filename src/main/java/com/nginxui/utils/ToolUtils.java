package com.nginxui.utils;

import java.io.File;
import java.util.Date;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;

public class ToolUtils {

	/**
	 * 处理conf一些语法问题
	 * 
	 * @param path
	 * @return
	 */
	public static String handleConf(String path) {
		if (StrUtil.isEmpty(path)) {
			return path;
		}
		return path.replace("};", "  }");
	}

	/**
	 * 处理路径的斜杠和空格
	 * 
	 * @param path
	 * @return
	 */
	public static String handlePath(String path) {
		if (StrUtil.isEmpty(path)) {
			return path;
		}
		return path.replace("\\", "/") // 替换反斜杠
				.replace("//", "/") // 替换双斜杠
				.replaceAll("[\\s?<>|\"#&;'`]", ""); // 删除空格和特殊字符
	}

	/**
	 * 处理目录最后的斜杠
	 * 
	 * @param path
	 * @return
	 */
	public static String endDir(String path) {
		if (StrUtil.isEmpty(path)) {
			return path;
		}
		if (!path.endsWith("/")) {
			path += "/";
		}

		return path;
	}

	/**
	 * 目标文件名已存在时, 在扩展名前插入时间戳(yyyyMMddHHmmss): a.pem -> a_20261019103045.pem;
	 * 同一秒内再次撞名则再追加 _1 / _2; 无扩展名则整体追加.
	 * (替代旧的"末尾追加 _1", 避免破坏文件扩展名)
	 */
	public static File uniqueFile(File dir, String fileName) {
		File target = new File(dir, fileName);
		if (!target.exists()) {
			return target;
		}

		String name = fileName;
		String ext = "";
		int dot = fileName.lastIndexOf('.');
		if (dot > 0) {
			name = fileName.substring(0, dot);
			ext = fileName.substring(dot);
		}

		String stamp = DateUtil.format(new Date(), "yyyyMMddHHmmss");
		File candidate = new File(dir, name + "_" + stamp + ext);
		if (!candidate.exists()) {
			return candidate;
		}

		for (int i = 1; i <= 9999; i++) {
			candidate = new File(dir, name + "_" + stamp + "_" + i + ext);
			if (!candidate.exists()) {
				return candidate;
			}
		}

		return new File(dir, name + "_" + System.currentTimeMillis() + ext);
	}
}
