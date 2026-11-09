package com.nginxui.utils;

/**
 * nginx 实例操作与配置维护的统一错误码.
 *
 * 所有对外返回的错误信息统一为 "[NEX-1001] 可读说明" 的形式:
 * 用户可把错误码直接反馈给运维定位, 日志侧也按同一错误码检索.
 * 文案模板统一放在 i18n/messages*.properties 的 error.&lt;code&gt; 键下.
 */
public final class NginxErrorCode {

	/** 实例配置文件不存在 */
	public static final String CONF_NOT_FOUND = "NEX-1001";
	/** nginx 可执行文件不存在 */
	public static final String EXE_NOT_FOUND = "NEX-1002";
	/** nginx -t 配置检测未通过 */
	public static final String CONFIG_TEST_FAILED = "NEX-1003";
	/** nginx -t 执行异常 */
	public static final String TEST_EXEC_ERROR = "NEX-1004";
	/** 当前 nginx 未编译 http_stub_status_module */
	public static final String STUB_MODULE_MISSING = "NEX-1005";
	/** stub_status 自动分配端口耗尽 */
	public static final String STUB_PORT_EXHAUSTED = "NEX-1006";
	/** 配置中缺少 http 块 */
	public static final String HTTP_BLOCK_MISSING = "NEX-1007";
	/** 配置中没有本系统写入的 stub_status 块 */
	public static final String STUB_NOT_MANAGED = "NEX-1008";
	/** 生命周期操作异常 */
	public static final String CONTROL_EXCEPTION = "NEX-1009";
	/** 不支持的操作 */
	public static final String ACTION_UNSUPPORTED = "NEX-1010";
	/** Windows 服务不存在或无法访问 */
	public static final String SERVICE_NOT_FOUND = "NEX-1011";
	/** 服务控制异常 */
	public static final String SERVICE_EXCEPTION = "NEX-1012";
	/** 服务未能进入运行状态 */
	public static final String SERVICE_START_FAILED = "NEX-1013";
	/** 等待服务状态超时 */
	public static final String SERVICE_TIMEOUT = "NEX-1014";
	/** 配置文件写入失败 */
	public static final String CONFIG_WRITE_FAILED = "NEX-1015";
	/** 内部错误 */
	public static final String INTERNAL_ERROR = "NEX-1016";
	/** 启动失败 */
	public static final String START_FAILED = "NEX-1017";
	/** 停止失败 */
	public static final String STOP_FAILED = "NEX-1018";
	/** 重启失败 */
	public static final String RESTART_FAILED = "NEX-1019";
	/** 旧进程未退出, 中止重启 */
	public static final String OLD_PROCESS_ALIVE = "NEX-1020";
	/** 服务停止超时 */
	public static final String SERVICE_STOP_TIMEOUT = "NEX-1021";
	/** 旧服务未退出, 中止重启 */
	public static final String OLD_SERVICE_ALIVE = "NEX-1022";
	/** 配置检测未通过, 已回滚 */
	public static final String TEST_ROLLBACK = "NEX-1023";
	/** 配置检测临时文件写入失败 */
	public static final String TEST_TMP_WRITE_FAILED = "NEX-1024";
	/** worker_processes 取值非法 */
	public static final String TUNE_INVALID_WORKER = "NEX-1025";
	/** 配置已写入但重启失败 */
	public static final String RESTART_AFTER_WRITE_FAILED = "NEX-1026";
	/** 服务正在启动中 */
	public static final String SERVICE_STARTING = "NEX-1027";

	/** stub_status 探测失败(仅日志) */
	public static final String STUB_PROBE_FAILED = "NEX-1030";
	/** 进程采样失败(仅日志) */
	public static final String PROCESS_SAMPLE_FAILED = "NEX-1031";
	/** 版本探测失败(仅日志) */
	public static final String VERSION_PROBE_FAILED = "NEX-1032";
	/** 系统命令执行失败(仅日志) */
	public static final String EXEC_FAILED = "NEX-1033";
	/** 日志/文件读取失败(仅日志) */
	public static final String READ_LOG_FAILED = "NEX-1034";

	private NginxErrorCode() {
	}

	/** 按错误码取本地化文案, 返回 "[NEX-1001] 说明" */
	public static String message(String code, Object... args) {
		return prefix(code, I18n.t("error." + code, args));
	}

	/** 给已有说明加上错误码前缀 */
	public static String prefix(String code, String text) {
		return "[" + code + "] " + (text == null ? "" : text);
	}
}
