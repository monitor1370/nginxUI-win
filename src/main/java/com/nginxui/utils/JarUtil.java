package com.nginxui.utils;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.CodeSource;
import java.security.ProtectionDomain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JarUtil {

	private static final Logger LOG = LoggerFactory.getLogger(JarUtil.class);
	/** Spring Boot 可执行 jar 启动时, CodeSource 的 scheme(形如 nested:\path\nginxUI-<version>.jar\!\BOOT-INF\classes\!) */
	private static final String NESTED_PREFIX = "nested:";

	public static String getCurrentFilePath() {
		String path = codeSourcePath();

		if (path != null && path.startsWith(NESTED_PREFIX)) {
			// Spring Boot 可执行 jar 启动: nested 路径不是可用的文件路径, 需还原出当前 jar
			File jar = launcherJar();
			if (jar == null) {
				jar = parseNestedJar(path);
			}
			if (jar == null || !jar.exists()) {
				throw new IllegalStateException("Unable to determine code source archive from " + path);
			}
			return jar.getAbsolutePath();
		}

		if (path == null) {
			throw new IllegalStateException("Unable to determine code source archive");
		}
		File root = new File(path);
		if (!root.exists()) {
			throw new IllegalStateException("Unable to determine code source archive from " + root);
		}

		return root.getAbsolutePath();
	}

	/** java -jar 启动时 java.class.path 即为当前可执行 jar */
	private static File launcherJar() {
		String classPath = System.getProperty("java.class.path", "");
		for (String entry : classPath.split(File.pathSeparator)) {
			if (entry.endsWith(".jar")) {
				File file = new File(entry);
				if (file.isFile()) {
					return file;
				}
			}
		}
		return null;
	}

	/** 从 nested 路径中截取 jar 部分(到第一个 .jar 为止), 并去掉 Windows 盘符前多出的分隔符 */
	private static File parseNestedJar(String path) {
		String body = path.substring(NESTED_PREFIX.length());
		int end = body.indexOf(".jar");
		if (end < 0) {
			return null;
		}

		String jarPath = body.substring(0, end + ".jar".length());
		if (jarPath.startsWith("\\") || jarPath.matches("^/[A-Za-z]:.*")) {
			jarPath = jarPath.substring(1);
		}
		return new File(jarPath);
	}

	private static String codeSourcePath() {
		ProtectionDomain protectionDomain = JarUtil.class.getProtectionDomain();
		CodeSource codeSource = protectionDomain.getCodeSource();
		try {
			URI location = (codeSource == null ? null : codeSource.getLocation().toURI());
			return (location == null ? null : location.getSchemeSpecificPart());
		} catch (URISyntaxException e) {
			LOG.warn("解析 code source 路径失败", e);
			return null;
		}
	}

	public static File getCurrentFile() {
		return new File(getCurrentFilePath());
	}

}
