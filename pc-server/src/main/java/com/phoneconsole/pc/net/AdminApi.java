package com.phoneconsole.pc.net;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.phoneconsole.pc.runtime.ConsoleRuntime;
import com.phoneconsole.pc.runtime.LogBuffer;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务端管理接口（HTTP/JSON）：
 * 服务状态、配置读写与热重载、ADB 状态与重建、运行日志、退出服务。
 */
public final class AdminApi {

    private final ConsoleRuntime runtime;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    public AdminApi(ConsoleRuntime runtime) {
        this.runtime = runtime;
    }

    public record Response(int status, String json) {
    }

    public Response handle(String method, String path, String query, String body) {
        boolean post = "POST".equalsIgnoreCase(method);
        try {
            switch (path) {
                case "/api/status":
                    return post ? methodNotAllowed(path) : json(200, runtime.status());
                case "/api/config":
                    return post ? saveConfig(body) : loadConfig();
                case "/api/adb":
                    return post ? methodNotAllowed(path) : adbStatus();
                case "/api/adb/reverse":
                    return post ? adbRebuild() : methodNotAllowed(path);
                case "/api/logs":
                    return post ? methodNotAllowed(path) : logs(query);
                case "/api/logs/clear":
                    return post ? json(200, runtime.clearLogs()) : methodNotAllowed(path);
                case "/api/shutdown":
                    return post ? shutdown() : methodNotAllowed(path);
                default:
                    return error(404, "未知接口：" + path);
            }
        } catch (Exception e) {
            LogBuffer.get().error("ADMIN", "管理接口异常 " + path + "：" + e.getMessage());
            return error(500, "服务端异常：" + e.getMessage());
        }
    }

    private Response loadConfig() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("path", runtime.configPath().toAbsolutePath().toString());
        payload.put("content", runtime.configContent());
        return json(200, payload);
    }

    private Response saveConfig(String body) {
        if (body == null || body.isBlank()) {
            return error(400, "请求体为空");
        }
        Object result = runtime.saveConfig(body);
        return json(200, result);
    }

    private Response adbStatus() {
        runtime.adb().probe();
        return json(200, adbPayload());
    }

    private Response adbRebuild() {
        runtime.adb().rebuild();
        return json(200, adbPayload());
    }

    private Map<String, Object> adbPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("adb", runtime.adb().statusMap());
        return payload;
    }

    private Response logs(String query) {
        long since = longParam(query, "since", 0);
        int limit = (int) longParam(query, "limit", 300);
        return json(200, runtime.logs(since, limit));
    }

    private Response shutdown() {
        runtime.requestShutdown();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("message", "服务端正在退出");
        return json(200, payload);
    }

    private long longParam(String query, String key, long fallback) {
        if (query == null || query.isEmpty()) {
            return fallback;
        }
        for (String pair : query.split("&")) {
            int index = pair.indexOf('=');
            if (index <= 0) {
                continue;
            }
            String name = decode(pair.substring(0, index));
            if (!key.equals(name)) {
                continue;
            }
            try {
                return Long.parseLong(decode(pair.substring(index + 1)));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private Response methodNotAllowed(String path) {
        return error(405, "接口 " + path + " 不支持该请求方法");
    }

    private Response error(int status, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("error", message);
        return json(status, payload);
    }

    private Response json(int status, Object payload) {
        return new Response(status, gson.toJson(payload));
    }
}
