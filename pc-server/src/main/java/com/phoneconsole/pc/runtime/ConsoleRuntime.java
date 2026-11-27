package com.phoneconsole.pc.runtime;

import com.google.gson.JsonObject;
import com.phoneconsole.pc.camera.CameraBridgeService;
import com.phoneconsole.pc.config.AppConfig;
import com.phoneconsole.pc.config.ConfigStore;
import com.phoneconsole.pc.core.ActionEngine;
import com.phoneconsole.pc.net.AdbAutoUnlockService;
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
 * 服务端管理页的运行时入口：状态查询、配置热重载、日志与退出。
 * <p>
 * 配置对象整体替换（volatile），各组件通过 setConfig 拿到新快照，
 * 避免运行中修改集合导致并发问题。
 */
public final class ConsoleRuntime {

    private final Path configPath;
    private final ActionEngine engine;
    private final ControlWebSocketServer wsServer;
    private final AdbPortForwarder adb;
    private final AdbAutoUnlockService autoUnlock;
    private final WindowWatcher watcher;
    private final CameraBridgeService cameraBridge;
    private final ShortcutManager shortcutManager = new ShortcutManager();
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
            AdbAutoUnlockService autoUnlock,
            WindowWatcher watcher,
            CameraBridgeService cameraBridge) {
        this.configPath = configPath;
        this.config = config;
        this.engine = engine;
        this.wsServer = wsServer;
        this.adb = adb;
        this.autoUnlock = autoUnlock;
        this.watcher = watcher;
        this.cameraBridge = cameraBridge;
        this.boundWsPort = config.network.wsPort;
        this.boundHttpPort = config.network.httpPort;
        Thread initShortcut = new Thread(shortcutManager::refresh, "shortcut-status-init");
        initShortcut.setDaemon(true);
        initShortcut.start();
    }

    public AppConfig config() {
        return config;
    }

    public Path configPath() {
        return configPath;
    }

    /** 实际监听的 WebSocket 端口（供扫码连接生成地址） */
    public int wsPort() {
        return boundWsPort;
    }

    public AdbPortForwarder adb() {
        return adb;
    }

    /** 服务状态：端口、在线设备数、配置路径、ADB 快照 */
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
        status.put("clients", wsServer.clientCount());
        status.put("webClients", wsServer.webClientCount());
        status.put("adb", adb.statusMap());
        status.put("autoUnlock", autoUnlock != null ? autoUnlock.statusMap() : Map.of("enabled", false));
        status.put("camera", cameraBridge != null ? cameraBridge.statusMap() : Map.of("enabled", false));
        status.put("shortcut", shortcutManager.cachedStatus());
        status.put("logCount", LogBuffer.get().size());
        status.put("shuttingDown", shuttingDown);
        return status;
    }

    public Map<String, Object> shortcutStatus() {
        return shortcutManager.status();
    }

    public Map<String, Object> updateShortcut(JsonObject body) {
        String action = body.has("action") && !body.get("action").isJsonNull()
                ? body.get("action").getAsString().trim()
                : "bind";
        if ("unbind".equalsIgnoreCase(action)) {
            boolean removeDesktop = !body.has("removeDesktop") || body.get("removeDesktop").getAsBoolean();
            boolean disableAutoStart = !body.has("disableAutoStart") || body.get("disableAutoStart").getAsBoolean();
            return shortcutManager.unbind(removeDesktop, disableAutoStart);
        }
        String hotkey = body.has("hotkey") && !body.get("hotkey").isJsonNull()
                ? body.get("hotkey").getAsString()
                : "";
        boolean desktopShortcut = body.has("desktopShortcut") && body.get("desktopShortcut").getAsBoolean();
        boolean autoStart = body.has("autoStart") && body.get("autoStart").getAsBoolean();
        return shortcutManager.bind(hotkey, desktopShortcut, autoStart);
    }

    /** 触发静默重启：先拉起等待当前进程退出的后台启动器，再正常退出当前 JVM */
    public Map<String, Object> requestRestart() {
        if (shuttingDown) {
            return failure("服务端已在退出流程中");
        }
        if (!ShortcutManager.spawnSilentRestart()) {
            return failure("未能启动后台重启脚本，请检查 pc-server/launch-silent.vbs");
        }
        LogBuffer.get().warn("ADMIN", "网页端请求重启，服务将在 0.3 秒后静默重启");
        requestShutdown();
        return success("已触发静默重启，请稍候数秒");
    }

    public Map<String, Object> cameraStatus() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("camera", cameraBridge != null ? cameraBridge.statusMap() : Map.of("enabled", false));
        return payload;
    }

    public Map<String, Object> cameraControl(String action, JsonObject params) {
        if (cameraBridge == null) {
            return failure("虚拟摄像头服务未初始化");
        }
        return cameraBridge.handleWebCommand(action, params);
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
        return saveParsedConfig(parsed, "配置已保存并热重载");
    }

    /** 来自管理网页或手机端的 USB 自动亮屏解锁设置变更 */
    public synchronized Map<String, Object> updateUsbUnlock(JsonObject params) {
        AppConfig parsed;
        try {
            parsed = ConfigStore.parse(ConfigStore.toJson(config));
        } catch (IOException e) {
            return failure("解析当前配置失败：" + e.getMessage());
        }
        if (parsed.autoUnlock == null) {
            parsed.autoUnlock = new AppConfig.AutoUnlock();
        }
        if (params != null) {
            if (params.has("enabled") && !params.get("enabled").isJsonNull()) {
                parsed.autoUnlock.enabled = params.get("enabled").getAsBoolean();
            }
            if (params.has("triggerMode") && !params.get("triggerMode").isJsonNull()) {
                String mode = params.get("triggerMode").getAsString().trim();
                parsed.autoUnlock.triggerMode = "on_startup".equalsIgnoreCase(mode) ? "on_startup" : "on_connect";
            }
            if (params.has("swipeToUnlock") && !params.get("swipeToUnlock").isJsonNull()) {
                parsed.autoUnlock.swipeToUnlock = params.get("swipeToUnlock").getAsBoolean();
            }
            if (params.has("launchApp") && !params.get("launchApp").isJsonNull()) {
                parsed.autoUnlock.launchApp = params.get("launchApp").getAsBoolean();
            }
            if (params.has("packageName") && !params.get("packageName").isJsonNull()) {
                String pkg = params.get("packageName").getAsString().trim();
                if (!pkg.isEmpty()) {
                    parsed.autoUnlock.packageName = pkg;
                }
            }
            if (params.has("activityName") && !params.get("activityName").isJsonNull()) {
                String act = params.get("activityName").getAsString().trim();
                if (!act.isEmpty()) {
                    parsed.autoUnlock.activityName = act;
                }
            }
            boolean clearPwd = params.has("clearPassword")
                    && !params.get("clearPassword").isJsonNull()
                    && params.get("clearPassword").getAsBoolean();
            if (clearPwd) {
                parsed.autoUnlock.password = "";
            } else if (params.has("password") && !params.get("password").isJsonNull()) {
                String pwd = params.get("password").getAsString();
                if (!pwd.isEmpty()) {
                    parsed.autoUnlock.password = pwd;
                }
            }
            if (params.has("wirelessEnabled") && !params.get("wirelessEnabled").isJsonNull()) {
                parsed.autoUnlock.wirelessEnabled = params.get("wirelessEnabled").getAsBoolean();
            }
            if (params.has("wirelessEndpoint") && !params.get("wirelessEndpoint").isJsonNull()) {
                String endpoint = params.get("wirelessEndpoint").getAsString().trim();
                if (!endpoint.isEmpty() && !ConfigStore.isValidHostPort(endpoint)) {
                    return failure("无线调试地址格式需为 IP:端口（如 10.26.3.135:33007）");
                }
                parsed.autoUnlock.wirelessEndpoint = endpoint;
            }
            if (params.has("wirelessMdnsAutoDiscover") && !params.get("wirelessMdnsAutoDiscover").isJsonNull()) {
                parsed.autoUnlock.wirelessMdnsAutoDiscover = params.get("wirelessMdnsAutoDiscover").getAsBoolean();
            }
        }
        adb.resetWirelessBackoff();
        return saveParsedConfig(parsed, "自动唤醒与无线调试设置已保存");
    }

    public Map<String, Object> triggerUsbUnlockNow() {
        return triggerUsbUnlockNow("all");
    }

    public Map<String, Object> triggerUsbUnlockNow(String mode) {
        if (autoUnlock == null) {
            return failure("自动解锁服务未初始化");
        }
        return autoUnlock.triggerNow(mode);
    }

    public Map<String, Object> pairWireless(JsonObject params) {
        String endpoint = strParam(params, "endpoint", "");
        String code = strParam(params, "pairCode", strParam(params, "code", ""));
        Map<String, Object> raw = adb.pairWireless(endpoint, code);
        if (autoUnlock != null) {
            autoUnlock.notifyStateChanged();
        }
        return appendAutoUnlock(raw);
    }

    public Map<String, Object> connectWireless(JsonObject params) {
        String endpoint = strParam(params, "endpoint", "");
        if (endpoint.isEmpty() && config.autoUnlock != null && config.autoUnlock.wirelessEndpoint != null) {
            endpoint = config.autoUnlock.wirelessEndpoint.trim();
        }
        boolean allowMdns = params != null && params.has("allowMdns") && !params.get("allowMdns").isJsonNull()
                ? params.get("allowMdns").getAsBoolean()
                : (config.autoUnlock == null || config.autoUnlock.wirelessMdnsAutoDiscover);
        Map<String, Object> raw = adb.connectWireless(endpoint, allowMdns);
        if (autoUnlock != null) {
            autoUnlock.notifyStateChanged();
        }
        return appendAutoUnlock(raw);
    }

    public Map<String, Object> disconnectWireless(JsonObject params) {
        String endpoint = strParam(params, "endpoint", "");
        if (endpoint.isEmpty() && config.autoUnlock != null && config.autoUnlock.wirelessEndpoint != null) {
            endpoint = config.autoUnlock.wirelessEndpoint.trim();
        }
        Map<String, Object> raw = adb.disconnectWireless(endpoint);
        if (autoUnlock != null) {
            autoUnlock.notifyStateChanged();
        }
        return appendAutoUnlock(raw);
    }

    private Map<String, Object> appendAutoUnlock(Map<String, Object> raw) {
        Map<String, Object> result = new LinkedHashMap<>(raw);
        if (autoUnlock != null) {
            result.put("autoUnlock", autoUnlock.statusMap());
        }
        return result;
    }

    private static String strParam(JsonObject params, String key, String fallback) {
        if (params != null && params.has(key) && !params.get(key).isJsonNull()) {
            return params.get(key).getAsString().trim();
        }
        return fallback;
    }

    private Map<String, Object> saveParsedConfig(AppConfig parsed, String message) {
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
        adb.setForwardPort(parsed.camera != null && parsed.camera.enabled ? parsed.camera.rtspPort : 0);
        if (cameraBridge != null) {
            cameraBridge.onConfigReloaded(parsed);
        }
        if (autoUnlock != null) {
            autoUnlock.notifyStateChanged();
        }
        wsServer.reloadLayout();

        List<String> warnings = new ArrayList<>();
        if (networkChanged(previous.network, parsed.network)) {
            warnings.add("network 中的端口 / adb 路径 / 轮询间隔需重启服务后生效");
        }
        LogBuffer.get().info("ADMIN", message + "：" + configPath.toAbsolutePath());

        Map<String, Object> result = success(message);
        result.put("warnings", warnings);
        result.put("configModified", lastModified());
        result.put("autoUnlock", autoUnlock != null ? autoUnlock.statusMap() : Map.of());
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
