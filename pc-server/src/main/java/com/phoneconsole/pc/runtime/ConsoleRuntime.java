package com.phoneconsole.pc.runtime;

import com.phoneconsole.pc.config.AppConfig;
import com.phoneconsole.pc.config.ConfigStore;
import com.phoneconsole.pc.core.ActionEngine;
import com.phoneconsole.pc.net.AdbPortForwarder;
import com.phoneconsole.pc.net.ControlWebSocketServer;
import com.phoneconsole.pc.telemetry.WindowWatcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网页控制台的运行时入口：状态查询、配置热重载、页面切换、日志与退出。
 * <p>
 * 配置对象整体替换（volatile），各组件通过 setConfig 拿到新快照，
 * 避免运行中修改集合导致并发问题。
 */
public final class ConsoleRuntime {

    private final Path configPath;
    private final ActionEngine engine;
    private final ControlWebSocketServer wsServer;
    private final AdbPortForwarder adb;
    private final WindowWatcher watcher;
    private final long startedAt = System.currentTimeMillis();
    /** 实际监听的端口，配置里改端口需重启才会变化 */
    private final int boundWsPort;
    private final int boundHttpPort;

    private volatile AppConfig config;
    private volatile boolean shuttingDown;

    public ConsoleRuntime(Path configPath,
                          AppConfig config,
                          ActionEngine engine,
                          ControlWebSocketServer wsServer,
                          AdbPortForwarder adb,
                          WindowWatcher watcher) {
        this.configPath = configPath;
        this.config = config;
        this.engine = engine;
        this.wsServer = wsServer;
        this.adb = adb;
        this.watcher = watcher;
        this.boundWsPort = config.network.wsPort;
        this.boundHttpPort = config.network.httpPort;
    }

    public AppConfig config() {
        return config;
    }

    public Path configPath() {
        return configPath;
    }

    public AdbPortForwarder adb() {
        return adb;
    }

    /** 服务状态：端口、页面、在线数、配置路径、ADB 快照 */
    public Map<String, Object> status() {
        AppConfig snapshot = config;
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("ok", true);
        status.put("startedAt", startedAt);
        status.put("uptimeMs", System.currentTimeMillis() - startedAt);
        status.put("configPath", configPath.toAbsolutePath().toString());
        status.put("configModified", lastModified());
        status.put("wsPort", boundWsPort);
        status.put("httpPort", boundHttpPort);
        status.put("configWsPort", snapshot.network.wsPort);
        status.put("configHttpPort", snapshot.network.httpPort);
        status.put("adbExecutable", snapshot.network.adbExecutable);
        status.put("reversePollMs", snapshot.network.reversePollMs);
        status.put("pageId", wsServer.currentPageId());
        status.put("clients", wsServer.clientCount());
        status.put("pages", pages(snapshot));
        status.put("adb", adb.statusMap());
        status.put("logCount", LogBuffer.get().size());
        status.put("shuttingDown", shuttingDown);
        return status;
    }

    public String configContent() {
        return ConfigStore.toJson(config);
    }

    /** 校验 -> 落盘 -> 热重载；失败时保持运行中的旧配置不变 */
    public synchronized Map<String, Object> saveConfig(String rawJson) {
        AppConfig parsed;
        try {
            parsed = ConfigStore.parse(rawJson);
        } catch (IOException e) {
            LogBuffer.get().warn("ADMIN", "配置校验未通过：" + e.getMessage());
            return failure(e.getMessage());
        }

        AppConfig previous = config;
        try {
            ConfigStore.save(configPath, parsed);
        } catch (IOException e) {
            LogBuffer.get().error("ADMIN", "写入 config.json 失败：" + e.getMessage());
            return failure("写入 config.json 失败：" + e.getMessage());
        }

        config = parsed;
        engine.setConfig(parsed);
        wsServer.setConfig(parsed);
        watcher.setConfig(parsed);
        adb.setNetwork(parsed.network);
        wsServer.reloadLayout();

        List<String> warnings = new ArrayList<>();
        if (networkChanged(previous.network, parsed.network)) {
            warnings.add("network 中的端口 / adb 路径 / 轮询间隔需重启服务后生效");
        }
        LogBuffer.get().info("ADMIN", "配置已保存并热重载：" + configPath.toAbsolutePath());

        Map<String, Object> result = success("配置已保存并热重载");
        result.put("warnings", warnings);
        result.put("configModified", lastModified());
        return result;
    }

    public Map<String, Object> switchPage(String pageId) {
        AppConfig.PageConfig page = config.page(pageId);
        if (page == null) {
            return failure("页面不存在：" + pageId);
        }
        wsServer.broadcastLayout(pageId);
        Map<String, Object> result = success("已切换到「" + page.title + "」");
        result.put("pageId", pageId);
        return result;
    }

    public Map<String, Object> logs(long since, int limit) {
        List<LogBuffer.Entry> entries = LogBuffer.get().since(since, limit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("entries", entries);
        result.put("lastSeq", LogBuffer.get().lastSeq());
        return result;
    }

    public Map<String, Object> clearLogs() {
        LogBuffer.get().clear();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("lastSeq", LogBuffer.get().lastSeq());
        return result;
    }

    /** 先返回 HTTP 响应，再由独立线程触发 JVM 退出（走既有的关闭钩子） */
    public void requestShutdown() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        LogBuffer.get().warn("ADMIN", "网页端请求退出，服务将在 0.3 秒后停止");
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.exit(0);
        }, "web-shutdown");
        thread.setDaemon(true);
        thread.start();
    }

    private List<Map<String, Object>> pages(AppConfig snapshot) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AppConfig.PageConfig page : snapshot.pages) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", page.id);
            item.put("title", page.title);
            item.put("rows", page.grid == null ? 0 : page.grid.rows);
            item.put("cols", page.grid == null ? 0 : page.grid.cols);
            item.put("buttons", page.buttons == null ? 0 : page.buttons.size());
            list.add(item);
        }
        return list;
    }

    private boolean networkChanged(AppConfig.Network before, AppConfig.Network after) {
        return before.wsPort != after.wsPort
                || before.httpPort != after.httpPort
                || !String.valueOf(before.adbExecutable).equals(String.valueOf(after.adbExecutable))
                || before.reversePollMs != after.reversePollMs;
    }

    private long lastModified() {
        try {
            return Files.getLastModifiedTime(configPath).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private Map<String, Object> success(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("message", message);
        return result;
    }

    private Map<String, Object> failure(String error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        result.put("error", error);
        return result;
    }
}
