package com.phoneconsole.pc;

import com.phoneconsole.pc.config.AppConfig;
import com.phoneconsole.pc.config.ConfigStore;
import com.phoneconsole.pc.core.ActionEngine;
import com.phoneconsole.pc.net.AdbPortForwarder;
import com.phoneconsole.pc.net.AdminApi;
import com.phoneconsole.pc.net.ControlWebSocketServer;
import com.phoneconsole.pc.net.StaticHttpServer;
import com.phoneconsole.pc.runtime.ConsoleRuntime;
import com.phoneconsole.pc.runtime.LogBuffer;
import com.phoneconsole.pc.telemetry.TelemetryService;
import com.phoneconsole.pc.telemetry.WindowWatcher;
import com.phoneconsole.pc.tray.TrayManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.Timer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * PC 服务端启动入口。
 * <p>
 * 启动顺序：加载配置 -> WebSocket 服务 -> 网页控制台 -> 遥测/窗口感知 -> ADB 守护 -> 托盘。
 * 启动后可在 http://127.0.0.1:8501/ 的「系统管理」页完成配置热重载、页面切换、ADB 映射与退出，
 * 日常使用不再需要回到命令行窗口。
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            fatal("服务端启动失败：" + t);
        }
    }

    private static void run(String[] args) throws Exception {
        Path configPath = Path.of(System.getProperty("config", "config.json"));
        AppConfig config = ConfigStore.loadOrCreate(configPath);
        ensurePortAvailable(config.network.wsPort, "WebSocket");
        ensurePortAvailable(config.network.httpPort, "网页控制台");
        log.info("配置已就绪：{}（{} 个页面）", configPath.toAbsolutePath(), config.pages.size());
        LogBuffer.get().info("CORE", "配置文件：" + configPath.toAbsolutePath()
                + "（" + config.pages.size() + " 个页面）");

        ActionEngine engine = new ActionEngine(config);

        ControlWebSocketServer wsServer = new ControlWebSocketServer(
                new InetSocketAddress("0.0.0.0", config.network.wsPort), config, engine);
        wsServer.start();
        log.info("手机端连接地址：ws://<PC-IP>:{}", config.network.wsPort);

        AdbPortForwarder adb = new AdbPortForwarder(config.network, config.network.wsPort);
        WindowWatcher watcher = new WindowWatcher(config, wsServer);
        ConsoleRuntime runtime = new ConsoleRuntime(configPath, config, engine, wsServer, adb, watcher);

        StaticHttpServer httpServer = new StaticHttpServer(
                config.network.httpPort, runtime::config, new AdminApi(runtime));
        httpServer.start();

        TelemetryService telemetry = new TelemetryService(1000, wsServer::broadcast, engine.media());
        telemetry.start();

        adb.start();
        watcher.start();

        String debugUrl = "http://127.0.0.1:" + config.network.httpPort + "/";
        TrayManager tray = TrayManager.install(debugUrl);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LogBuffer.get().warn("CORE", "收到退出信号，正在停止服务");
            if (tray != null) {
                tray.remove();
            }
            shutdown(wsServer, httpServer, telemetry, adb, watcher, engine);
        }, "shutdown-hook"));

        log.info("PC 服务端已就绪（调试页面：{}），按 Ctrl+C 退出", debugUrl);
        LogBuffer.get().info("CORE", "PC 服务端已就绪，网页控制台：" + debugUrl);
        new CountDownLatch(1).await();
    }

    private static void shutdown(ControlWebSocketServer wsServer,
                                 StaticHttpServer httpServer,
                                 TelemetryService telemetry,
                                 AdbPortForwarder adb,
                                 WindowWatcher watcher,
                                 ActionEngine engine) {
        stopQuietly("WebSocket 服务", () -> wsServer.stop(1000));
        stopQuietly("静态资源服务", httpServer::stop);
        stopQuietly("遥测线程", telemetry::stop);
        stopQuietly("ADB 线程", adb::stop);
        stopQuietly("窗口感知", watcher::stop);
        stopQuietly("动作引擎", engine::shutdown);
        log.info("PC 服务端已退出");
        LogBuffer.get().info("CORE", "PC 服务端已退出");
    }

    private static void stopQuietly(String name, ThrowingRunnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.debug("关闭 {} 失败：{}", name, e.getMessage());
        }
    }

    /**
     * 启动前探测端口占用：避免重复启动时旧实例占着端口，
     * 导致新实例在 httpServer 处抛异常、main 提前退出，
     * 但 WebSocket 非守护线程仍把 JVM 吊住，形成“端口在听却没有遥测”的僵尸进程。
     */
    private static void ensurePortAvailable(int port, String label) {
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(true);
            probe.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            fatal(label + " 端口 " + port + " 已被占用，可能已有服务端实例在运行。\n"
                    + "请先关闭旧实例（或结束残留的 java 进程）后再启动。");
        }
    }

    /** 启动失败：写日志、控制台输出并弹窗提示后退出，确保不留残余线程 */
    private static void fatal(String message) {
        log.error(message);
        LogBuffer.get().warn("CORE", message.replace("\n", " "));
        System.err.println(message);
        try {
            JOptionPane pane = new JOptionPane(message, JOptionPane.ERROR_MESSAGE);
            JDialog dialog = pane.createDialog("手机控制台 - 启动失败");
            // 5 秒后自动关闭，避免无人值守时窗口一直挂着
            new Timer(5000, e -> dialog.dispose()).start();
            dialog.setVisible(true);
        } catch (Throwable ignored) {
            // 无图形环境时忽略
        }
        System.exit(2);
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
