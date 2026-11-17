package com.phoneconsole.pc.telemetry;

import com.phoneconsole.pc.config.AppConfig;
import com.phoneconsole.pc.net.ControlWebSocketServer;
import com.phoneconsole.pc.runtime.LogBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 前台焦点感知：检测最前端窗口进程，命中 appBindings 时自动下发对应页面。
 */
public class WindowWatcher {

    private static final Logger log = LoggerFactory.getLogger(WindowWatcher.class);

    private volatile AppConfig config;
    private final ControlWebSocketServer server;
    private final boolean supported;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "window-watcher");
        t.setDaemon(true);
        return t;
    });

    private String lastProcess = "";
    private String lastPageId = "";

    public WindowWatcher(AppConfig config, ControlWebSocketServer server) {
        this.config = config;
        this.server = server;
        this.supported = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** 配置热重载：绑定关系与默认页面可能已变化 */
    public void setConfig(AppConfig config) {
        if (config != null) {
            this.config = config;
        }
    }

    public void start() {
        if (!supported) {
            log.info("当前系统非 Windows，前台窗口感知功能已跳过");
            return;
        }
        scheduler.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
        log.info("前台窗口感知已启动");
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    private void tick() {
        try {
            String process = ForegroundWindow.exeName();
            if (process == null || process.isEmpty() || process.equals(lastProcess)) {
                return;
            }
            lastProcess = process;

            AppConfig snapshot = config;
            AppConfig.AppBinding binding = snapshot.bindingFor(process);
            String pageId = binding != null ? binding.pageId : snapshot.defaultPageId;
            if (pageId == null || pageId.equals(lastPageId)) {
                return;
            }
            lastPageId = pageId;
            log.info("前台进程切换为 {}，下发页面 {}", process, pageId);
            LogBuffer.get().info("WINDOW", "前台进程 " + process + " -> 页面 " + pageId);
            server.broadcastLayout(pageId);
        } catch (Exception e) {
            log.debug("窗口感知异常：{}", e.getMessage());
        }
    }
}
