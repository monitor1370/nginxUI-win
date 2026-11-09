package com.nginxui;

import java.awt.GraphicsEnvironment;
import java.awt.EventQueue;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

import javax.swing.JOptionPane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nginxui.ui.MainFrame;
import com.nginxui.ui.Theme;
import com.nginxui.utils.ErrorReporter;

/**
 * nginxUI Win 纯 Swing 离线版入口.
 *
 * 启动顺序 (每步失败都应在 log 文件可见):
 *   1. 解析 app 所在目录, 设置 -Dnginxui.log.dir → logback 才能初始化文件 appender
 *   2. 单实例互斥 (cmd /c tasklist)
 *   3. 无桌面环境拦截
 *   4. 启动 Swing 主窗口
 */
public class Main {

	public static void main(String[] args) {
		// --- 1) 日志目录: app 同目录下 logs/, 必须在 SLF4J LoggerFactory.getLogger 之前设置 ---
		String home = resolveHome();
		File logsDir = new File(home, "logs");
		if (!logsDir.exists()) logsDir.mkdirs();
		System.setProperty("nginxui.log.dir", logsDir.getAbsolutePath());

		Logger log = LoggerFactory.getLogger(Main.class);
		// 全局兜底: Swing 事件线程/后台线程的未捕获异常统一记日志并提示一次
		ErrorReporter.installGlobalHandler();
		log.info("========== nginxUI 启动 ==========");
		log.info("app home = {}", home);
		log.info("log dir  = {}", logsDir.getAbsolutePath());
		log.info("user.dir = {}", System.getProperty("user.dir"));
		log.info("os.name  = {} / arch={}", System.getProperty("os.name"), System.getProperty("os.arch"));

		// --- 2) 单实例互斥 (cmd /c tasklist) ---
		try {
			int count = countNginxUiProcesses();
			log.info("tasklist nginxUI.exe count = {} (>2 则阻塞)", count);
			if (count > 2) {
				log.warn("已有 nginxUI 实例在运行, 阻止本次启动");
				JOptionPane.showMessageDialog(null,
						"nginxUI 已在运行, 请检查任务栏。",
						"nginxUI",
						JOptionPane.INFORMATION_MESSAGE);
				return;
			}
		} catch (Exception e) {
			log.error("进程检测失败, 放行启动", e);
		}

		// --- 3) 无桌面拦截 ---
		if (GraphicsEnvironment.isHeadless()) {
			log.error("当前环境无桌面, nginxUI Win 无法启动");
			System.err.println("当前环境无桌面, nginxUI Win 无法启动");
			return;
		}

		// --- 4) 启动 Swing ---
		log.info("初始化 Swing UI...");
		EventQueue.invokeLater(() -> {
			try {
				Theme.install();
			} catch (Exception e) {
				log.warn("无法安装 FlatLaf 主题, 使用默认外观", e);
			}
			try {
				MainFrame frame = new MainFrame();
				frame.setVisible(true);
				log.info("MainFrame 已显示");
			} catch (Exception e) {
				ErrorReporter.reportUser("nginxUI 启动失败", e);
			}
		});

		// 进程退出钩子
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			log.info("========== nginxUI 退出 ==========");
		}, "nginxUI-shutdown"));
	}

	/**
	 * 统计 tasklist 中 nginxUI.exe 的行数.
	 * jpackage 单实例 = 2 个进程 (launcher + JVM) → count ≤ 2 放行.
	 */
	private static int countNginxUiProcesses() throws Exception {
		String os = System.getProperty("os.name", "").toLowerCase();
		if (!os.contains("windows")) return 0;

		ProcessBuilder pb = new ProcessBuilder(
				"cmd", "/c", "tasklist /FI \"IMAGENAME eq nginxUI.exe\" /FO CSV /NH");
		pb.redirectErrorStream(true);
		Process p = pb.start();
		int count = 0;
		try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "GBK"))) {
			String line;
			while ((line = r.readLine()) != null) {
				if (line.startsWith("\"nginxUI.exe\"")) count++;
			}
		}
		p.destroy();
		return count;
	}

	/**
	 * 定位 app 所在目录 (与 MainFrame.resolveHome 同源):
	 *   jpackage: codeSource -> dist/nginxUI/app/xxx.jar -> 跳过 app/ → dist/nginxUI/
	 *   fat jar:   codeSource -> target/xxx.jar           -> parent = target/
	 *   IDE:       codeSource -> target/classes/...        -> 跳过 classes/ → 项目根
	 * -Dnginxui.home=... 显式指定优先.
	 */
	private static String resolveHome() {
		String dir = System.getProperty("nginxui.home");
		if (dir != null && !dir.isEmpty()) return dir;

		try {
			java.net.URL loc = Main.class.getProtectionDomain().getCodeSource().getLocation();
			if (loc != null) {
				File codeFile = new File(loc.toURI());
				File p = codeFile.getParentFile();
				while (p != null && "app".equalsIgnoreCase(p.getName())) p = p.getParentFile();
				while (p != null && "classes".equalsIgnoreCase(p.getName())) p = p.getParentFile();
				if (p != null) return p.getAbsolutePath();
			}
		} catch (Exception ignored) {}
		return System.getProperty("user.dir");
	}
}
