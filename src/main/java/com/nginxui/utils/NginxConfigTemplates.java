package com.nginxui.utils;

import java.util.ArrayList;
import java.util.List;

/**
 * Nginx 配置示例库: 按类别组织常用代码片段, 每个片段都是 self-contained 的 server/upstream/stream 块,
 * 用户可一键插入到当前编辑器光标位置或复制到 conf.d/ 独立文件.
 *
 * 示例分两类:
 *   - 完整示例: 可直接落盘的独立 conf 片段, 通常含 server 块;
 *   - 基础片段: 仅指令级别的通用配置, 可粘贴到 http/server/location 任意层级.
 */
public final class NginxConfigTemplates {

	private NginxConfigTemplates() {
	}

	// ==================== 分类 ====================

	public static class Category {
		public final String id;
		public final String name;

		public Category(String id, String name) {
			this.id = id;
			this.name = name;
		}
	}

	public static final Category CAT_CLUSTER = new Category("cluster", "集群代理");
	public static final Category CAT_STREAM = new Category("stream", "TCP/Socket");
	public static final Category CAT_SITE = new Category("site", "站点模板");
	public static final Category CAT_COMMON = new Category("common", "通用配置");
	public static final Category CAT_SECURITY = new Category("security", "安全与优化");

	public static final List<Category> ALL_CATEGORIES = List.of(
			CAT_CLUSTER, CAT_STREAM, CAT_SITE, CAT_COMMON, CAT_SECURITY);

	// ==================== 条目 ====================

	public static class Entry {
		public final String id;
		public final Category category;
		public final String title;
		public final String description;
		/** 提示用户需要改的点位 */
		public final String placeholders;
		public final String content;

		public Entry(String id, Category category, String title, String description,
				String placeholders, String content) {
			this.id = id;
			this.category = category;
			this.title = title;
			this.description = description;
			this.placeholders = placeholders;
			this.content = content;
		}
	}

	private static final List<Entry> ALL = new ArrayList<Entry>();

	static {
		// ==================== 集群代理 ====================

		// --- ecology http ---
		ALL.add(new Entry("eco-http", CAT_CLUSTER,
				"生态集群 HTTP 代理",
				"使用 sticky 会话保持的 HTTP upstream 负载均衡, proxy_pass 走集群, 常见内网服务入口。",
				"需修改: upstream 中的后端 IP:Port; server_name",
				ecologyHttp()));

		// --- ecology https ---
		ALL.add(new Entry("eco-https", CAT_CLUSTER,
				"生态集群 HTTPS 代理",
				"在 ecologycluster upstream 上走 HTTPS, 含 TLS 1.2、主流加密套件、HSTS(注释)。",
				"需修改: upstream 后端地址; ssl_certificate/ssl_certificate_key; server_name",
				ecologyHttps()));

		// --- emobile http ---
		ALL.add(new Entry("em-http", CAT_CLUSTER,
				"手机端 HTTP 代理",
				"emobilecluster upstream 的 HTTP 入口, 含长连接、proxy_http_version 1.1、Connection 清空。",
				"需修改: upstream 后端地址; server_name",
				emobileHttp()));

		// --- emobile https ---
		ALL.add(new Entry("em-https", CAT_CLUSTER,
				"手机端 HTTPS 代理",
				"emobilecluster upstream 的 HTTPS 入口, 监听 9443, TLS 1.2, 兼容手机端旧客户端。",
				"需修改: ssl_certificate/ssl_certificate_key; upstream 后端地址",
				emobileHttps()));

		// --- pc-message https (带 WebSocket 升级) ---
		ALL.add(new Entry("pc-msg-https", CAT_CLUSTER,
				"PC 客户端消息 HTTPS (WebSocket)",
				"带 WebSocket Upgrade 头的 HTTPS 代理, 走 message7070 upstream, 适合实时消息服务。",
				"需修改: ssl_certificate/ssl_certificate_key; upstream 后端地址",
				pcMessageHttps()));

		// --- 通用 least_conn 负载均衡 ---
		ALL.add(new Entry("lb-least-conn", CAT_CLUSTER,
				"least_conn 负载均衡 + HTTP/2",
				"最少连接负载均衡, 开 HTTP/2, 适合后端连接耗时差异较大的场景。",
				"需修改: server_name; upstream 后端地址; ssl_certificate",
				lbLeastConn()));

		// --- 通用 sticky session + ip_hash 混合 ---
		ALL.add(new Entry("lb-sticky", CAT_CLUSTER,
				"sticky 会话保持负载均衡",
				"sticky cookie 会话保持 + keepalive 长连接, 适合需要会话粘性的 Java/.NET 应用。",
				"需修改: server_name; upstream 后端地址",
				lbSticky()));

		// ==================== TCP / Socket ====================

		ALL.add(new Entry("stream-tcp", CAT_STREAM,
				"TCP 四层代理 (stream)",
				"Nginx stream 块转发原始 TCP, 适合数据库、Redis、原生 Socket 协议代理。",
				"需修改: listen 端口; proxy_pass 后端 IP:Port (stream upstream)",
				streamTcp()));

		ALL.add(new Entry("stream-tls", CAT_STREAM,
				"TLS 四层代理 (stream ssl)",
				"stream + listen ssl + proxy_pass, 对外 TLS 加密, 对内裸 TCP。适合消息系统 5443 场景。",
				"需修改: listen 端口; ssl_certificate/ssl_certificate_key; proxy_pass 上游",
				streamTls()));

		ALL.add(new Entry("stream-upstream", CAT_STREAM,
				"stream upstream + TCP 负载均衡",
				"stream 块内 upstream 组做 TCP 层 round-robin / hash 负载均衡。",
				"需修改: upstream 后端地址; listen 端口; proxy_pass",
				streamUpstream()));

		// ==================== 站点模板 ====================

		ALL.add(new Entry("site-spa", CAT_SITE,
				"SPA 前端站点 (try_files 回退)",
				"单页应用静态资源托管 + try_files 回退 index.html, 适合 Vue/React/Angular 生产部署。",
				"需修改: root 路径; server_name; 可选 ssl_certificate",
				siteSpa()));

		ALL.add(new Entry("site-static", CAT_SITE,
				"纯静态文件站点",
				"静态目录托管 + gzip + expires 缓存 + autoindex 目录列表 (默认注释)。",
				"需修改: root 路径; server_name",
				siteStatic()));

		ALL.add(new Entry("site-single-reverse", CAT_SITE,
				"单站点反向代理 (后端单体)",
				"最精简的 HTTPS 反向代理 server 块, proxy_pass 到单个后端应用。",
				"需修改: server_name; ssl_certificate; proxy_pass 后端地址",
				siteSingleReverse()));

		ALL.add(new Entry("site-path-rewrite", CAT_SITE,
				"按路径分流 + 重写",
				"不同 location 转发到不同后端, /api/ 路径重写掉前缀, /static/ 走本地文件。",
				"需修改: server_name; proxy_pass 地址; root 路径; rewrite 规则",
				sitePathRewrite()));

		ALL.add(new Entry("site-80-to-443", CAT_SITE,
				"HTTP → HTTPS 强制跳转",
				"listen 80 只做 301 跳转到 https://$host$request_uri, 配合 HTTPS server 使用。",
				"需修改: server_name",
				siteRedirectToHttps()));

		// ==================== 通用配置 ====================

		ALL.add(new Entry("gzip", CAT_COMMON,
				"gzip 压缩配置片段",
				"放 http 块内, 压缩 HTML/JS/CSS/JSON/XML, threshold 1KB, 兼容 IE6。",
				"直接粘贴到 http {} 内",
				gzipBlock()));

		ALL.add(new Entry("upstream-basic", CAT_COMMON,
				"upstream 块骨架",
				"最基础的 upstream server 组, 可改 least_conn / ip_hash / sticky。",
				"需修改: 组名; 后端 server; 负载策略",
				upstreamBasic()));

		ALL.add(new Entry("basic-proxy-headers", CAT_COMMON,
				"反向代理通用头 (location 内)",
				"放 location 块内的 proxy_pass + X-Forwarded-* 标准头, 保留客户端真实 IP。",
				"直接粘贴到 location / {} 内, 按需加 proxy_pass",
				proxyStandardHeaders()));

		ALL.add(new Entry("websocket-proxy", CAT_COMMON,
				"WebSocket 代理片段",
				"location 块内的 WebSocket Upgrade 配置, 配合 proxy_http_version 1.1。",
				"直接粘贴到 location /ws/ {} 内",
				proxyWebSocket()));

		// ==================== 安全与优化 ====================

		ALL.add(new Entry("limit-req", CAT_SECURITY,
				"访问限流 limit_req",
				"在 http 块定义 zone, 在 server/location 内引用 limit_req zone=api burst=20 nodelay。",
				"放 http {} 内定义 zone, 放 server/location 内引用",
				limitReq()));

		ALL.add(new Entry("cors", CAT_SECURITY,
				"CORS 跨域 (add_header)",
				"允许跨域 + 预检 204 处理, 配合 OPTIONS 请求返回。",
				"放 server 块内, 或 location /api/ 内",
				cors()));

		ALL.add(new Entry("hsts-csp", CAT_SECURITY,
				"HSTS / CSP / XSS-Protection 头",
				"HTTPS 安全响应头组合: HSTS 一年 + XSS-Protection + CSP default-src。",
				"放 server 块内",
				securityHeaders()));

		ALL.add(new Entry("file-upload", CAT_SECURITY,
				"文件上传大小限制",
				"client_max_body_size + 413 自定义错误页, 放 server 或 http 块。",
				"按需修改大小, 放 server 或 http {}",
				fileUpload()));
	}

	// ==================== 查询 ====================

	public static List<Entry> all() {
		return ALL;
	}

	public static List<Entry> byCategory(Category cat) {
		List<Entry> out = new ArrayList<Entry>();
		for (Entry e : ALL) {
			if (e.category == cat) {
				out.add(e);
			}
		}
		return out;
	}

	public static Entry find(String id) {
		for (Entry e : ALL) {
			if (e.id.equals(id)) {
				return e;
			}
		}
		return null;
	}

	// ==================== 示例内容 ====================

	private static String ecologyHttp() {
		return "## ecology HTTP 代理 (放 http {} 块内)\n"
				+ "upstream ecologycluster {\n"
				+ "    sticky name=ecologycluster;\n"
				+ "    server 127.0.0.1:80;\n"
				+ "    keepalive 1000;\n"
				+ "}\n"
				+ "\n"
				+ "server {\n"
				+ "    listen       80;\n"
				+ "    server_name  your-domain.com;\n"
				+ "\n"
				+ "    location / {\n"
				+ "        proxy_pass          http://ecologycluster;\n"
				+ "        proxy_http_version  1.1;\n"
				+ "        proxy_set_header    Connection \"\";\n"
				+ "        proxy_read_timeout  3600;\n"
				+ "        proxy_send_timeout  3600;\n"
				+ "        proxy_buffer_size   128k;\n"
				+ "        proxy_buffers       32 32k;\n"
				+ "        proxy_busy_buffers_size 128k;\n"
				+ "        proxy_set_header    Host              $http_host;\n"
				+ "        proxy_set_header    X-Real-IP         $remote_addr;\n"
				+ "        proxy_set_header    X-Forwarded-For   $proxy_add_x_forwarded_for;\n"
				+ "        proxy_set_header    X-Forwarded-Proto $scheme;\n"
				+ "    }\n"
				+ "}\n";
	}

	private static String ecologyHttps() {
		return "## ecology HTTPS 代理 (放 http {} 块内)\n"
				+ "upstream ecologycluster {\n"
				+ "    sticky name=ecologycluster;\n"
				+ "    server 127.0.0.1:80;\n"
				+ "    keepalive 1000;\n"
				+ "}\n"
				+ "\n"
				+ "server {\n"
				+ "    listen       443 ssl;\n"
				+ "    server_name  your-domain.com;\n"
				+ "\n"
				+ "    ssl_certificate      server.pem;\n"
				+ "    ssl_certificate_key  server.key;\n"
				+ "    ssl_protocols        TLSv1.2 TLSv1.3;\n"
				+ "    ssl_ciphers          HIGH:!aNULL:!MD5:!RC4;\n"
				+ "    ssl_prefer_server_ciphers on;\n"
				+ "\n"
				+ "    # add_header Strict-Transport-Security \"max-age=31536000; includeSubDomains\" always;\n"
				+ "\n"
				+ "    location / {\n"
				+ "        proxy_pass          http://ecologycluster;\n"
				+ "        proxy_http_version  1.1;\n"
				+ "        proxy_set_header    Connection \"\";\n"
				+ "        proxy_read_timeout  3600;\n"
				+ "        proxy_send_timeout  3600;\n"
				+ "        proxy_set_header    Host              $http_host;\n"
				+ "        proxy_set_header    X-Real-IP         $remote_addr;\n"
				+ "        proxy_set_header    X-Forwarded-For   $proxy_add_x_forwarded_for;\n"
				+ "        proxy_set_header    X-Forwarded-Proto $scheme;\n"
				+ "        proxy_redirect      off;\n"
				+ "    }\n"
				+ "}\n";
	}

	private static String emobileHttp() {
		return "## emobile HTTP 代理 (放 http {} 块内)\n"
				+ "upstream emobilecluster {\n"
				+ "    sticky name=emobilecluster;\n"
				+ "    server 127.0.0.1:8999;\n"
				+ "    keepalive 1000;\n"
				+ "}\n"
				+ "\n"
				+ "server {\n"
				+ "    listen       8999;\n"
				+ "    server_name  localhost;\n"
				+ "\n"
				+ "    location / {\n"
				+ "        proxy_pass          http://emobilecluster;\n"
				+ "        proxy_http_version  1.1;\n"
				+ "        proxy_set_header    Connection \"\";\n"
				+ "        proxy_read_timeout  3600;\n"
				+ "        proxy_send_timeout  3600;\n"
				+ "        proxy_set_header    Host              $http_host;\n"
				+ "        proxy_set_header    X-Real-IP         $remote_addr;\n"
				+ "        proxy_set_header    X-Forwarded-For   $proxy_add_x_forwarded_for;\n"
				+ "        proxy_set_header    X-Forwarded-Proto $scheme;\n"
				+ "        proxy_intercept_errors on;\n"
				+ "    }\n"
				+ "}\n";
	}

	private static String emobileHttps() {
		return "## emobile HTTPS 代理 (放 http {} 块内)\n"
				+ "upstream emobilecluster {\n"
				+ "    sticky name=emobilecluster;\n"
				+ "    server 127.0.0.1:8999;\n"
				+ "    keepalive 1000;\n"
				+ "}\n"
				+ "\n"
				+ "server {\n"
				+ "    listen       9443 ssl;\n"
				+ "    server_name  your-domain.com;\n"
				+ "\n"
				+ "    ssl_certificate      server.pem;\n"
				+ "    ssl_certificate_key  server.key;\n"
				+ "    ssl_protocols        TLSv1.2;\n"
				+ "    ssl_ciphers          HIGH:!aNULL:!MD5:!RC4;\n"
				+ "    ssl_prefer_server_ciphers on;\n"
				+ "\n"
				+ "    location / {\n"
				+ "        proxy_pass          http://emobilecluster;\n"
				+ "        proxy_http_version  1.1;\n"
				+ "        proxy_set_header    Connection \"\";\n"
				+ "        proxy_read_timeout  3600;\n"
				+ "        proxy_send_timeout  3600;\n"
				+ "        proxy_set_header    Host              $http_host;\n"
				+ "        proxy_set_header    X-Real-IP         $remote_addr;\n"
				+ "        proxy_set_header    X-Forwarded-For   $proxy_add_x_forwarded_for;\n"
				+ "        proxy_set_header    X-Forwarded-Proto $scheme;\n"
				+ "        proxy_intercept_errors on;\n"
				+ "    }\n"
				+ "}\n";
	}

	private static String pcMessageHttps() {
		return "## PC 客户端消息 HTTPS (WebSocket, 放 http {} 块内)\n"
				+ "upstream message7070 {\n"
				+ "    ip_hash;\n"
				+ "    server 127.0.0.1:7070;\n"
				+ "    keepalive 64;\n"
				+ "}\n"
				+ "\n"
				+ "server {\n"
				+ "    listen       7443 ssl;\n"
				+ "    server_name  your-domain.com;\n"
				+ "\n"
				+ "    ssl_certificate      server.pem;\n"
				+ "    ssl_certificate_key  server.key;\n"
				+ "    ssl_protocols        TLSv1.2 TLSv1.3;\n"
				+ "    ssl_ciphers          HIGH:!aNULL:!MD5:!RC4;\n"
				+ "    ssl_prefer_server_ciphers on;\n"
				+ "\n"
				+ "    location / {\n"
				+ "        proxy_pass          http://message7070;\n"
				+ "        proxy_http_version  1.1;\n"
				+ "        # WebSocket 升级头\n"
				+ "        proxy_set_header    Upgrade    $http_upgrade;\n"
				+ "        proxy_set_header    Connection \"upgrade\";\n"
				+ "        proxy_read_timeout  3600s;   # WebSocket 长连接需要足够大的超时\n"
				+ "        proxy_send_timeout  3600s;\n"
				+ "        proxy_set_header    Host              $http_host;\n"
				+ "        proxy_set_header    X-Real-IP         $remote_addr;\n"
				+ "        proxy_set_header    X-Forwarded-For   $proxy_add_x_forwarded_for;\n"
				+ "        proxy_set_header    X-Forwarded-Proto $scheme;\n"
				+ "    }\n"
				+ "}\n";
	}

	private static String lbLeastConn() {
		return "## least_conn + HTTP/2 负载均衡 (放 http {} 块内)\n"
				+ "upstream backend {\n"
				+ "    least_conn;\n"
				+ "    server 10.0.0.11:8080 max_fails=3 fail_timeout=30s;\n"
				+ "    server 10.0.0.12:8080 max_fails=3 fail_timeout=30s;\n"
				+ "    server 10.0.0.13:8080 backup;\n"
				+ "    keepalive 32;\n"
				+ "}\n"
				+ "\n"
				+ "server {\n"
				+ "    listen       443 ssl http2;\n"
				+ "    server_name  your-domain.com;\n"
				+ "\n"
				+ "    ssl_certificate      server.pem;\n"
				+ "    ssl_certificate_key  server.key;\n"
				+ "    ssl_protocols        TLSv1.2 TLSv1.3;\n"
				+ "    ssl_ciphers          ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384;\n"
				+ "    ssl_prefer_server_ciphers on;\n"
				+ "\n"
				+ "    location / {\n"
				+ "        proxy_pass         http://backend;\n"
				+ "        proxy_http_version 1.1;\n"
				+ "        proxy_set_header   Connection \"\";\n"
				+ "        proxy_set_header   Host              $http_host;\n"
				+ "        proxy_set_header   X-Real-IP         $remote_addr;\n"
				+ "        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;\n"
				+ "        proxy_set_header   X-Forwarded-Proto $scheme;\n"
				+ "    }\n"
				+ "}\n";
	}

	private static String lbSticky() {
		return "## sticky 会话保持负载均衡 (放 http {} 块内)\n"
				+ "upstream backend {\n"
				+ "    sticky cookie srv_id expires=1h domain=.your-domain.com path=/;\n"
				+ "    server 10.0.0.11:8080;\n"
				+ "    server 10.0.0.12:8080;\n"
				+ "    keepalive 64;\n"
				+ "}\n"
				+ "\n"
				+ "server {\n"
				+ "    listen       80;\n"
				+ "    server_name  your-domain.com;\n"
				+ "\n"
				+ "    location / {\n"
				+ "        proxy_pass          http://backend;\n"
				+ "        proxy_http_version  1.1;\n"
				+ "        proxy_set_header    Connection \"\";\n"
				+ "        proxy_set_header    Host              $http_host;\n"
				+ "        proxy_set_header    X-Real-IP         $remote_addr;\n"
				+ "        proxy_set_header    X-Forwarded-For   $proxy_add_x_forwarded_for;\n"
				+ "        proxy_set_header    X-Forwarded-Proto $scheme;\n"
				+ "    }\n"
				+ "}\n";
	}

	private static String streamTcp() {
		return "## TCP 四层代理 (放配置顶层, 与 http {} 同级)\n"
				+ "stream {\n"
				+ "    # 原生 TCP 消息代理\n"
				+ "    server {\n"
				+ "        listen       5222;\n"
				+ "        proxy_pass   127.0.0.1:5222;\n"
				+ "        proxy_connect_timeout 2s;\n"
				+ "        proxy_timeout 360s;\n"
				+ "    }\n"
				+ "}\n";
	}

	private static String streamTls() {
		return "## TLS 四层代理 (放配置顶层, 与 http {} 同级)\n"
				+ "stream {\n"
				+ "    # 对外 TLS, 对内裸 TCP\n"
				+ "    server {\n"
				+ "        listen       5443 ssl;\n"
				+ "        ssl_certificate      server.pem;\n"
				+ "        ssl_certificate_key  server.key;\n"
				+ "        ssl_protocols        TLSv1.2 TLSv1.3;\n"
				+ "        ssl_ciphers          HIGH:!aNULL:!MD5;\n"
				+ "        ssl_prefer_server_ciphers on;\n"
				+ "\n"
				+ "        proxy_pass   127.0.0.1:5222;\n"
				+ "    }\n"
				+ "}\n";
	}

	private static String streamUpstream() {
		return "## stream upstream + TCP 负载均衡 (放配置顶层, 与 http {} 同级)\n"
				+ "stream {\n"
				+ "    upstream msg_backend {\n"
				+ "        # hash $remote_addr consistent;   # 按源 IP 哈希, 去掉则 round-robin\n"
				+ "        server 127.0.0.1:5222 max_fails=3 fail_timeout=10s;\n"
				+ "        server 127.0.0.1:5223 max_fails=3 fail_timeout=10s;\n"
				+ "    }\n"
				+ "\n"
				+ "    server {\n"
				+ "        listen       5222;\n"
				+ "        proxy_pass   msg_backend;\n"
				+ "        proxy_connect_timeout 2s;\n"
				+ "        proxy_timeout 360s;\n"
				+ "    }\n"
				+ "}\n";
	}

	private static String siteSpa() {
		return "## SPA 前端站点 (Vue/React 部署, 放 http {} 块内)\n"
				+ "server {\n"
				+ "    listen       80;\n"
				+ "    server_name  spa.your-domain.com;\n"
				+ "\n"
				+ "    root   D:/nginx/html/dist;   # Windows 示例, 按实际改\n"
				+ "    index  index.html;\n"
				+ "\n"
				+ "    # 静态资源强缓存 (文件名已带 hash)\n"
				+ "    location ~* \\.(?:js|css|woff2?|ttf|eot|otf|png|jpg|jpeg|gif|ico|svg|webp)$ {\n"
				+ "        expires 1y;\n"
				+ "        add_header Cache-Control \"public, immutable\";\n"
				+ "    }\n"
				+ "\n"
				+ "    # 禁止访问隐藏文件\n"
				+ "    location ~ /\\. {\n"
				+ "        deny all;\n"
				+ "    }\n"
				+ "\n"
				+ "    # SPA 路由回退: 所有未匹配路径都走 index.html\n"
				+ "    location / {\n"
				+ "        try_files $uri $uri/ /index.html;\n"
				+ "    }\n"
				+ "}\n";
	}

	private static String siteStatic() {
		return "## 纯静态文件站点 (放 http {} 块内)\n"
				+ "server {\n"
				+ "    listen       80;\n"
				+ "    server_name  static.your-domain.com;\n"
				+ "\n"
				+ "    root   D:/nginx/html;\n"
				+ "    index  index.html;\n"
				+ "\n"
				+ "    # gzip 压缩\n"
				+ "    gzip on;\n"
				+ "    gzip_types text/plain text/css application/json application/javascript text/xml application/xml application/xml+rss text/javascript image/svg+xml;\n"
				+ "    gzip_min_length 1024;\n"
				+ "\n"
				+ "    # 长缓存\n"
				+ "    location ~* \\.(?:js|css|png|jpg|jpeg|gif|ico|svg|webp|woff2?)$ {\n"
				+ "        expires 30d;\n"
				+ "        add_header Cache-Control \"public\";\n"
				+ "    }\n"
				+ "\n"
				+ "    # 目录列表 (按需开启)\n"
				+ "    # autoindex on;\n"
				+ "    # autoindex_exact_size off;\n"
				+ "    # autoindex_localtime on;\n"
				+ "\n"
				+ "    location / {\n"
				+ "        try_files $uri $uri/ =404;\n"
				+ "    }\n"
				+ "}\n";
	}

	private static String siteSingleReverse() {
		return "## 单站点反向代理 (放 http {} 块内)\n"
				+ "server {\n"
				+ "    listen       443 ssl;\n"
				+ "    server_name  your-domain.com;\n"
				+ "\n"
				+ "    ssl_certificate      server.pem;\n"
				+ "    ssl_certificate_key  server.key;\n"
				+ "    ssl_protocols        TLSv1.2 TLSv1.3;\n"
				+ "\n"
				+ "    location / {\n"
				+ "        proxy_pass          http://127.0.0.1:8080;   # 后端单体应用\n"
				+ "        proxy_http_version  1.1;\n"
				+ "        proxy_set_header    Host              $http_host;\n"
				+ "        proxy_set_header    X-Real-IP         $remote_addr;\n"
				+ "        proxy_set_header    X-Forwarded-For   $proxy_add_x_forwarded_for;\n"
				+ "        proxy_set_header    X-Forwarded-Proto $scheme;\n"
				+ "    }\n"
				+ "}\n";
	}

	private static String sitePathRewrite() {
		return "## 按路径分流 + rewrite (放 http {} 块内)\n"
				+ "upstream api_backend {\n"
				+ "    server 127.0.0.1:8080;\n"
				+ "}\n"
				+ "\n"
				+ "server {\n"
				+ "    listen       80;\n"
				+ "    server_name  your-domain.com;\n"
				+ "\n"
				+ "    # API 请求: /api/v1/user → /v1/user (rewrite 掉 /api 前缀)\n"
				+ "    location /api/ {\n"
				+ "        rewrite ^/api/(.*)$ /$1 break;\n"
				+ "        proxy_pass          http://api_backend;\n"
				+ "        proxy_http_version  1.1;\n"
				+ "        proxy_set_header    Connection \"\";\n"
				+ "        proxy_set_header    Host              $http_host;\n"
				+ "        proxy_set_header    X-Real-IP         $remote_addr;\n"
				+ "        proxy_set_header    X-Forwarded-For   $proxy_add_x_forwarded_for;\n"
				+ "    }\n"
				+ "\n"
				+ "    # 静态资源: 不走 proxy, 直接读磁盘\n"
				+ "    location /static/ {\n"
				+ "        alias D:/nginx/static/;\n"
				+ "        expires 30d;\n"
				+ "        add_header Cache-Control \"public\";\n"
				+ "    }\n"
				+ "\n"
				+ "    # 其他请求\n"
				+ "    location / {\n"
				+ "        proxy_pass          http://api_backend;\n"
				+ "        proxy_set_header    Host              $http_host;\n"
				+ "        proxy_set_header    X-Real-IP         $remote_addr;\n"
				+ "    }\n"
				+ "}\n";
	}

	private static String siteRedirectToHttps() {
		return "## HTTP → HTTPS 强制跳转 (放 http {} 块内, 与 HTTPS server 配合)\n"
				+ "server {\n"
				+ "    listen       80;\n"
				+ "    server_name  your-domain.com;\n"
				+ "\n"
				+ "    # 301 永久跳转, 保留路径与查询串\n"
				+ "    return 301 https://$host$request_uri;\n"
				+ "}\n"
				+ "\n"
				+ "# HTTPS server 单独一个 server { listen 443 ssl; ... }\n";
	}

	private static String gzipBlock() {
		return "# gzip 压缩 (放 http {} 块内)\n"
				+ "gzip on;\n"
				+ "gzip_comp_level 6;\n"
				+ "gzip_min_length 1024;\n"
				+ "gzip_buffers 16 8k;\n"
				+ "gzip_http_version 1.1;\n"
				+ "gzip_types\n"
				+ "    text/plain\n"
				+ "    text/css\n"
				+ "    text/xml\n"
				+ "    text/javascript\n"
				+ "    application/json\n"
				+ "    application/javascript\n"
				+ "    application/xml\n"
				+ "    application/xml+rss\n"
				+ "    image/svg+xml;\n"
				+ "gzip_vary on;\n"
				+ "# 低版本 IE 不压缩\n"
				+ "gzip_disable \"MSIE [1-6]\\.\";\n";
	}

	private static String upstreamBasic() {
		return "# upstream 骨架 (放 http {} 块内)\n"
				+ "# 负载策略: round-robin(默认) / least_conn / ip_hash / sticky\n"
				+ "upstream backend {\n"
				+ "    # least_conn;            # 最少连接\n"
				+ "    # ip_hash;               # 源 IP 哈希\n"
				+ "    # sticky name=srv;       # sticky 会话保持\n"
				+ "\n"
				+ "    server 10.0.0.11:8080 max_fails=3 fail_timeout=10s;\n"
				+ "    server 10.0.0.12:8080 max_fails=3 fail_timeout=10s;\n"
				+ "    # server 10.0.0.13:8080 backup;   # 备用节点\n"
				+ "\n"
				+ "    keepalive 32;   # 长连接池大小\n"
				+ "}\n";
	}

	private static String proxyStandardHeaders() {
		return "# 反向代理通用头 (放 location / {} 内)\n"
				+ "proxy_pass          http://backend;   # 按需替换\n"
				+ "proxy_http_version  1.1;\n"
				+ "proxy_set_header    Connection \"\";\n"
				+ "proxy_set_header    Host              $http_host;\n"
				+ "proxy_set_header    X-Real-IP         $remote_addr;\n"
				+ "proxy_set_header    X-Forwarded-For   $proxy_add_x_forwarded_for;\n"
				+ "proxy_set_header    X-Forwarded-Proto $scheme;\n"
				+ "# 可选: 传客户端端口\n"
				+ "# proxy_set_header  X-Client-Port     $remote_port;\n";
	}

	private static String proxyWebSocket() {
		return "# WebSocket 代理 (放 location /ws/ {} 内)\n"
				+ "proxy_pass          http://ws_backend;   # 按需替换 upstream\n"
				+ "proxy_http_version  1.1;\n"
				+ "proxy_set_header    Upgrade    $http_upgrade;\n"
				+ "proxy_set_header    Connection \"upgrade\";\n"
				+ "# 长连接需要足够大的超时\n"
				+ "proxy_read_timeout  3600s;\n"
				+ "proxy_send_timeout  3600s;\n"
				+ "proxy_set_header    Host              $http_host;\n"
				+ "proxy_set_header    X-Real-IP         $remote_addr;\n"
				+ "proxy_set_header    X-Forwarded-For   $proxy_add_x_forwarded_for;\n"
				+ "proxy_set_header    X-Forwarded-Proto $scheme;\n";
	}

	private static String limitReq() {
		return "# 访问限流 limit_req\n"
				+ "# 1) 放 http {} 块内定义 zone:\n"
				+ "limit_req_zone $binary_remote_addr zone=api:10m rate=10r/s;\n"
				+ "limit_req_zone $binary_remote_addr zone=login:10m rate=1r/s;\n"
				+ "\n"
				+ "# 2) 放 server / location 内引用:\n"
				+ "# location /api/ {\n"
				+ "#     limit_req zone=api burst=20 nodelay;\n"
				+ "#     ...\n"
				+ "# }\n"
				+ "# location /login {\n"
				+ "#     limit_req zone=login burst=5 nodelay;\n"
				+ "#     ...\n"
				+ "# }\n";
	}

	private static String cors() {
		return "# CORS 跨域 (放 server 块内, 或 location /api/ 内)\n"
				+ "add_header Access-Control-Allow-Origin      $http_origin always;\n"
				+ "add_header Access-Control-Allow-Methods     \"GET, POST, PUT, DELETE, OPTIONS\" always;\n"
				+ "add_header Access-Control-Allow-Headers      \"Authorization, Content-Type, X-Requested-With\" always;\n"
				+ "add_header Access-Control-Allow-Credentials \"true\" always;\n"
				+ "add_header Access-Control-Max-Age           3600 always;\n"
				+ "\n"
				+ "# 预检请求直接返回 204, 不进入 proxy\n"
				+ "if ($request_method = OPTIONS) {\n"
				+ "    return 204;\n"
				+ "}\n";
	}

	private static String securityHeaders() {
		return "# 安全响应头 (放 HTTPS server 块内)\n"
				+ "# HSTS: 浏览器强制 HTTPS 一年 (首次访问后生效)\n"
				+ "add_header Strict-Transport-Security \"max-age=31536000; includeSubDomains\" always;\n"
				+ "\n"
				+ "# 防止 MIME 嗅探\n"
				+ "add_header X-Content-Type-Options \"nosniff\" always;\n"
				+ "\n"
				+ "# 点击劫持防护\n"
				+ "add_header X-Frame-Options \"SAMEORIGIN\" always;\n"
				+ "\n"
				+ "# XSS 过滤\n"
				+ "add_header X-XSS-Protection \"1; mode=block\" always;\n"
				+ "\n"
				+ "# 内容安全策略 (按需收紧, 当前为宽松策略)\n"
				+ "# add_header Content-Security-Policy \"default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:\" always;\n";
	}

	private static String fileUpload() {
		return "# 文件上传大小限制 (放 server 或 http 块)\n"
				+ "client_max_body_size 100M;\n"
				+ "client_body_buffer_size 128k;\n"
				+ "\n"
				+ "# 自定义 413 错误页 (可选)\n"
				+ "error_page 413 /413.html;\n"
				+ "location = /413.html {\n"
				+ "    root D:/nginx/html;\n"
				+ "}\n";
	}
}
