package com.hedgeguard.web;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.common.Config;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 内置 HTTP 服务（{@code ARCHITECTURE.md} §2.1 L4「JDK com.sun.net.httpserver」/ §3.7）。
 *
 * <p>零依赖：仅用 JDK 内置 {@code com.sun.net.httpserver}（{@code §7.2.2}「HTTP Server / JSON 之外的 Web 层零依赖」）。
 *
 * <p>安全与运行约束：
 * <ul>
 *   <li><b>只绑定本地回环</b>（{@code NFR-SEC-3} / 附录 B 红线 9）——非回环配置在启动期直接失败；</li>
 *   <li>请求体上限 {@value #MAX_BODY_BYTES} 字节，超限返回 {@code 10001}；</li>
 *   <li>响应头回写 {@code X-HG-TraceId}，便于前端报障时直接提供追踪号。</li>
 * </ul>
 */
public final class HttpServerLauncher {

    private static final Logger LOG = Logger.getLogger(HttpServerLauncher.class.getName());

    /** 请求体上限（16 MiB）。 */
    public static final int MAX_BODY_BYTES = 16 * 1024 * 1024;

    /** HTTP 工作线程数默认值（`CFG-33 hg.web.threads` = 16，取 `T-003` 实测实现值；`ARCHITECTURE.md` §11.2 已登记）。 */
    public static final int DEFAULT_HTTP_THREADS = 16;

    private final HttpServer server;
    private final RequestDispatcher dispatcher;
    private final ExecutorService executor;
    private final int port;

    /**
     * 构造并绑定端口（不启动）。
     *
     * @param config     配置
     * @param dispatcher 分发器
     */
    public HttpServerLauncher(Config config, RequestDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        String bind = config.loopbackOnly(Config.KEY_HTTP_BIND);
        this.port = config.getInt(Config.KEY_HTTP_PORT, 27402);
        int threads = config.getInt(Config.KEY_HTTP_THREADS, DEFAULT_HTTP_THREADS);
        this.executor = Executors.newFixedThreadPool(threads, new DaemonThreadFactory());
        try {
            this.server = HttpServer.create(new InetSocketAddress(bind, port), 128);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "HTTP 服务绑定失败：bind=" + bind + " port=" + port + "；原因=" + e.getMessage()
                            + "；复现=确认端口未被占用（ss -lntp | grep " + port + "）", e);
        }
        this.server.setExecutor(executor);
        this.server.createContext("/", this::handle);
    }

    /** 实际绑定端口（端口配 0 时为系统分配）。 */
    public int boundPort() {
        return server.getAddress().getPort();
    }

    /** 启动服务。 */
    public void start() {
        server.start();
        LOG.info("HTTP 服务已启动：http://" + server.getAddress().getHostString() + ":"
                + server.getAddress().getPort() + "（静态托管前缀 "
                + StaticHandler.URL_PREFIX + "/**，REST 前缀 /api/v1）");
    }

    /** 停止服务（先停接收，再停线程池）。 */
    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    /**
     * 适配 {@link HttpExchange} → {@link RequestContext} → {@link HttpResult}。
     *
     * @param exchange JDK 提供的交换对象
     */
    private void handle(HttpExchange exchange) {
        HttpResult result;
        RequestContext ctx = null;
        try {
            ctx = toContext(exchange);
            result = dispatcher.dispatch(ctx);
        } catch (IOException e) {
            String traceId = ctx == null ? TraceIds.next() : ctx.traceId();
            LOG.log(Level.WARNING, "读取请求失败（连接中断或报文异常）traceId=" + traceId, e);
            result = HttpResult.json(ErrorCode.INTERNAL_ERROR.httpStatus(), ErrorMapper.map(
                    BizException.of(ErrorCode.INTERNAL_ERROR, "请求读取失败：连接中断或报文异常", e), traceId));
        } catch (RuntimeException e) {
            String traceId = ctx == null ? TraceIds.next() : ctx.traceId();
            result = HttpResult.json(ErrorMapper.httpStatus(e), ErrorMapper.map(e, traceId));
        }
        try {
            write(exchange, result, ctx);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "响应写出失败（客户端可能已断开）：" + e.getMessage(), e);
        } finally {
            exchange.close();
        }
    }

    private static RequestContext toContext(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        String rawPath = exchange.getRequestURI().getRawPath();
        String path = QueryStrings.decode(rawPath);
        String rawQuery = exchange.getRequestURI().getRawQuery();
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, java.util.List<String>> e : exchange.getRequestHeaders().entrySet()) {
            if (e.getValue() != null && !e.getValue().isEmpty()) {
                headers.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().get(0));
            }
        }
        String body = readBody(exchange);
        String traceId = TraceIds.resolve(exchange.getRequestHeaders().getFirst("X-HG-TraceId"));
        String ip = exchange.getRemoteAddress() == null ? null
                : exchange.getRemoteAddress().getAddress().getHostAddress();
        return new RequestContext(method, path, rawQuery, headers, body, traceId, ip);
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength != null) {
            try {
                long len = Long.parseLong(contentLength.trim());
                if (len > MAX_BODY_BYTES) {
                    throw BizException.of(ErrorCode.PARAM_INVALID,
                            "请求体超限：" + len + " 字节 > 上限 " + MAX_BODY_BYTES + " 字节；复现=拆分或改用导入接口分批提交");
                }
            } catch (NumberFormatException e) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "Content-Length 非法：" + contentLength);
            }
        }
        try (InputStream in = exchange.getRequestBody()) {
            byte[] buf = readFully(in);
            return new String(buf, StandardCharsets.UTF_8);
        }
    }

    private static byte[] readFully(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            if (out.size() + n > MAX_BODY_BYTES) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "请求体超限（流式读入超过 " + MAX_BODY_BYTES + " 字节）；复现=拆分提交");
            }
            out.write(chunk, 0, n);
        }
        return out.toByteArray();
    }

    private static void write(HttpExchange exchange, HttpResult result, RequestContext ctx)
            throws IOException {
        if (result.contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", result.contentType);
        }
        for (Map.Entry<String, String> h : result.headers().entrySet()) {
            exchange.getResponseHeaders().set(h.getKey(), h.getValue());
        }
        String traceId = ctx == null ? TraceIds.next() : ctx.traceId();
        exchange.getResponseHeaders().set("X-HG-TraceId", traceId);
        boolean head = "HEAD".equalsIgnoreCase(exchange.getRequestMethod());
        int len = head ? -1 : result.body.length;
        exchange.sendResponseHeaders(result.status, len);
        if (!head && result.body.length > 0) {
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(result.body);
            }
        }
    }

    /** 守护线程工厂：线程名含 {@code hg-http-}，便于线上定位（{@code ARCHITECTURE.md} §2.4.4 J1）。 */
    private static final class DaemonThreadFactory implements ThreadFactory {

        private final AtomicInteger seq = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "hg-http-" + seq.getAndIncrement());
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, ex) ->
                    LOG.log(Level.SEVERE, "HTTP 工作线程未捕获异常：" + Clocks.epochMilli(), ex));
            return t;
        }
    }
}
