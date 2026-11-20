package com.phoneconsole.pc.net;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.phoneconsole.pc.config.AppConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 静态资源服务：向手机端提供 PWA 页面、图标与调试接口。
 * 使用 JDK 内置 HttpServer，不引入额外 Web 框架。
 * <p>
 * /api/* 统一转交 {@link AdminApi}（网页控制台使用），/api/layout 为兼容旧客户端的只读配置接口。
 */
public class StaticHttpServer {

    private static final Logger log = LoggerFactory.getLogger(StaticHttpServer.class);

    private final HttpServer server;
    private final Supplier<AppConfig> config;
    private final AdminApi adminApi;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final ExecutorService executor;

    public StaticHttpServer(int port, Supplier<AppConfig> config, AdminApi adminApi) throws IOException {
        this.config = config;
        this.adminApi = adminApi;
        this.executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "http-static");
            t.setDaemon(true);
            return t;
        });
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.createContext("/", this::handle);
        this.server.setExecutor(executor);
    }

    public void start() {
        server.start();
        log.info("静态资源服务已启动，端口 {}", server.getAddress().getPort());
    }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) {
                path = "/index.html";
            }
            // 服务端管理页放在独立路径，根路径保留给手机控制端
            if ("/admin".equals(path) || "/admin/".equals(path)) {
                path = "/admin.html";
            }
            if ("/api/layout".equals(path)) {
                writeJson(exchange, 200, gson.toJson(config.get()));
                return;
            }
            if (path.startsWith("/api/")) {
                handleApi(exchange, path);
                return;
            }

            String safePath = path.replace("..", "");
            byte[] body = readResource("/static" + safePath);
            if (body == null) {
                write(exchange, 404, "text/plain; charset=utf-8",
                        ("未找到资源：" + safePath).getBytes(StandardCharsets.UTF_8));
                return;
            }
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            write(exchange, 200, contentType(safePath), body);
        } finally {
            exchange.close();
        }
    }

    private void handleApi(HttpExchange exchange, String path) throws IOException {
        if (adminApi == null) {
            writeJson(exchange, 404, "{\"ok\":false,\"error\":\"管理接口未启用\"}");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        AdminApi.Response response = adminApi.handle(
                exchange.getRequestMethod(), path, exchange.getRequestURI().getRawQuery(), body);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        write(exchange, response.status(), "application/json; charset=utf-8",
                response.json().getBytes(StandardCharsets.UTF_8));
    }

    private void writeJson(HttpExchange exchange, int status, String json) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        write(exchange, status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] readResource(String path) throws IOException {
        try (InputStream in = StaticHttpServer.class.getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private void write(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private String contentType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (lower.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (lower.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (lower.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }
}
