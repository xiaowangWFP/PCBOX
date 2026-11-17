package com.phoneconsole.pc.net;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.phoneconsole.pc.config.AppConfig;
import com.phoneconsole.pc.core.ActionEngine;
import com.phoneconsole.pc.protocol.ActionMessage;
import com.phoneconsole.pc.protocol.LayoutMessage;
import com.phoneconsole.pc.protocol.ScreenDevicesMessage;
import com.phoneconsole.pc.protocol.ScreenStateMessage;
import com.phoneconsole.pc.protocol.ScreenStatsMessage;
import com.phoneconsole.pc.protocol.SessionMessage;
import com.phoneconsole.pc.runtime.LogBuffer;
import com.phoneconsole.pc.screen.H264ScreenStreamer;
import com.phoneconsole.pc.screen.ScreenTargets;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 手机端长连接入口：下发页面布局、接收触控动作、广播遥测与按钮状态。
 */
public class ControlWebSocketServer extends WebSocketServer {

    private static final Logger log = LoggerFactory.getLogger(ControlWebSocketServer.class);

    private final Gson gson = new Gson();
    private volatile AppConfig config;
    private final ActionEngine engine;
    /** H.264 1080p60 订阅者（共享同一个 ffmpeg 编码进程） */
    private final Set<WebSocket> h264Connections = ConcurrentHashMap.newKeySet();
    private final ScreenTargets targets = new ScreenTargets();
    /** 当前采集的显示器序号：-1 = 整块桌面；可在客户端切换 */
    private volatile int selectedMonitor;
    private final H264ScreenStreamer h264 = new H264ScreenStreamer(
            () -> {
                AppConfig snapshot = config;
                return snapshot.screen == null ? "ffmpeg" : snapshot.screen.ffmpegExecutable;
            },
            () -> selectedMonitor);

    /** 当前手机端展示的页面，用于新连接接入时同步 */
    private volatile String currentPageId;

    public ControlWebSocketServer(InetSocketAddress address, AppConfig config, ActionEngine engine) {
        super(address);
        this.config = config;
        this.engine = engine;
        this.currentPageId = config.defaultPageId;
        this.selectedMonitor = config.screen == null ? -1 : config.screen.monitor;
        setReuseAddr(true);
        setConnectionLostTimeout(30);
        engine.setStateSink(this::broadcast);
    }

    /** 配置热重载：替换快照，并把 screen.monitor 同步到当前采集目标 */
    public void setConfig(AppConfig config) {
        if (config == null) {
            return;
        }
        this.config = config;
        int fromConfig = config.screen == null ? -1 : config.screen.monitor;
        if (fromConfig != selectedMonitor) {
            selectedMonitor = fromConfig;
            h264.restartEncoder();
            broadcastScreenDevices();
        }
    }

    private void broadcastScreenDevices() {
        for (WebSocket conn : getConnections()) {
            sendScreenDevices(conn);
        }
    }

    /** 在线连接数（手机端与网页端都计入） */
    public int clientCount() {
        return getConnections().size();
    }

    /** 配置热重载后重新校验当前页面并广播最新布局 */
    public void reloadLayout() {
        AppConfig snapshot = config;
        if (snapshot.page(currentPageId) == null) {
            currentPageId = snapshot.defaultPageId;
        }
        LayoutMessage layout = buildLayout(currentPageId);
        if (layout == null) {
            return;
        }
        currentPageId = layout.pageId;
        broadcast(gson.toJson(layout));
    }

    @Override
    public void onStart() {
        log.info("WebSocket 服务已启动，监听端口 {}", getPort());
        LogBuffer.get().info("WS", "WebSocket 服务监听 0.0.0.0:" + getPort());
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        boolean usb = isUsbChannel(conn);
        log.info("手机端接入：{}（{} 通道）", conn.getRemoteSocketAddress(), usb ? "USB" : "LAN");
        LogBuffer.get().info("WS", "客户端接入（" + (usb ? "USB" : "LAN") + "）：" + conn.getRemoteSocketAddress());

        SessionMessage session = new SessionMessage();
        session.transport = usb ? "usb" : "lan";
        // 屏幕推流不再限定通道：H.264 码率低，USB / 局域网均可使用
        session.screenStream = true;
        session.screenMaxFps = H264ScreenStreamer.FPS;
        conn.send(gson.toJson(session));

        String pageId = currentPageId != null ? currentPageId : config.defaultPageId;
        LayoutMessage layout = buildLayout(pageId);
        if (layout != null) {
            conn.send(gson.toJson(layout));
        }
        sendScreenDevices(conn);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        JsonObject json;
        try {
            json = JsonParser.parseString(message).getAsJsonObject();
        } catch (Exception e) {
            log.warn("无法解析消息：{}", message);
            return;
        }
        String type = json.has("type") ? json.get("type").getAsString() : "";
        switch (type) {
            case "action" -> {
                ActionMessage action = gson.fromJson(json, ActionMessage.class);
                if (action != null) {
                    engine.handle(conn, action);
                }
            }
            case "screen_control" -> handleScreenControl(conn, json);
            default -> log.debug("忽略消息类型：{}", type);
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        log.info("手机端断开：code={} reason={} remote={}", code, reason, remote);
        LogBuffer.get().info("WS", "客户端断开：code=" + code + (reason == null || reason.isBlank() ? "" : " " + reason));
        engine.stopAllRepeats(conn);
        stopStreamer(conn);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        log.error("WebSocket 异常：{}", ex.getMessage());
        if (conn != null) {
            engine.stopAllRepeats(conn);
            stopStreamer(conn);
        }
    }

    /** 向所有在线设备推送文本帧 */
    public void broadcast(String payload) {
        Collection<WebSocket> connections = getConnections();
        for (WebSocket conn : connections) {
            try {
                if (conn.isOpen()) {
                    conn.send(payload);
                }
            } catch (Exception e) {
                log.debug("推送失败：{}", e.getMessage());
            }
        }
    }

    /** 切换页面并广播页面配置 */
    public void broadcastLayout(String pageId) {
        LayoutMessage layout = buildLayout(pageId);
        if (layout == null) {
            log.warn("页面不存在：{}", pageId);
            return;
        }
        currentPageId = pageId;
        log.info("切换页面 -> {}（{}）", layout.pageTitle, pageId);
        LogBuffer.get().info("PAGE", "切换页面 -> " + layout.pageTitle + "（" + pageId + "）");
        broadcast(gson.toJson(layout));
    }

    public String currentPageId() {
        return currentPageId;
    }

    /** adb reverse 下手机以回环地址接入，即 USB 高速通道 */
    private boolean isUsbChannel(WebSocket conn) {
        try {
            return conn.getRemoteSocketAddress().getAddress().isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }

    private void handleScreenControl(WebSocket conn, JsonObject json) {
        String action = json.has("action") ? json.get("action").getAsString() : "";
        if ("list".equals(action)) {
            sendScreenDevices(conn);
            return;
        }
        if ("stop".equals(action)) {
            stopStreamer(conn);
            sendScreenState(conn, false, 0, 0, 0, null);
            LogBuffer.get().info("SCREEN", "停止推流：" + conn.getRemoteSocketAddress());
            return;
        }
        if (!"start".equals(action)) {
            return;
        }
        int monitor = intOf(json, "monitor", selectedMonitor);
        if (monitor != selectedMonitor) {
            selectedMonitor = monitor;
            h264.restartEncoder();
            sendScreenDevices(conn);
            LogBuffer.get().info("SCREEN", "切换采集显示器 -> " + monitor);
        }

        stopStreamer(conn);
        startH264(conn);
    }

    /** 下发可选显示器列表；id 与 ddagrab 的 output_idx 一致 */
    private void sendScreenDevices(WebSocket conn) {
        ScreenDevicesMessage message = new ScreenDevicesMessage();
        message.current = selectedMonitor;
        for (ScreenTargets.Target target : targets.list()) {
            ScreenDevicesMessage.Device device = new ScreenDevicesMessage.Device();
            device.id = target.id();
            device.name = target.name();
            device.width = target.bounds().width;
            device.height = target.bounds().height;
            device.x = target.bounds().x;
            device.y = target.bounds().y;
            device.primary = target.primary();
            message.devices.add(device);
        }
        try {
            if (conn.isOpen()) {
                conn.send(gson.toJson(message));
            }
        } catch (Exception e) {
            log.debug("显示器列表推送失败：{}", e.getMessage());
        }
    }

    /** H.264 固定 1920x1080@60，多个订阅者共享同一编码进程 */
    private void startH264(WebSocket conn) {
        H264ScreenStreamer.Sink sink = new H264ScreenStreamer.Sink() {
            @Override
            public boolean onAccessUnit(byte[] data, boolean keyframe) {
                if (!conn.isOpen() || conn.hasBufferedData()) {
                    return false;
                }
                try {
                    conn.send(ByteBuffer.wrap(data));
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }

            @Override
            public void onStats(H264ScreenStreamer.Stats stats) {
                sendScreenStats(conn, H264ScreenStreamer.FPS, H264ScreenStreamer.WIDTH,
                        H264ScreenStreamer.HEIGHT, stats.actualFps(), stats.kbps());
            }

            @Override
            public void onClosed(String reason) {
                h264Connections.remove(conn);
                sendScreenState(conn, false, 0, 0, 0, reason);
            }
        };

        if (!h264.subscribe(conn, sink)) {
            sendScreenState(conn, false, 0, 0, 0, "encoder_unavailable");
            LogBuffer.get().error("SCREEN", "H.264 编码器不可用，检查 config.json 的 screen.ffmpegExecutable");
            return;
        }
        h264Connections.add(conn);
        sendScreenState(conn, true, H264ScreenStreamer.FPS, H264ScreenStreamer.WIDTH,
                H264ScreenStreamer.HEIGHT, null);
        LogBuffer.get().info("SCREEN", "开始推流：H.264 1920x1080@60（" + conn.getRemoteSocketAddress() + "）");
    }

    private void sendScreenStats(WebSocket conn, int fps, int width, int height,
                                 double actualFps, int kbps) {
        if (!conn.isOpen()) {
            return;
        }
        ScreenStatsMessage message = new ScreenStatsMessage();
        message.fps = fps;
        message.actualFps = actualFps;
        message.width = width;
        message.height = height;
        message.kbps = kbps;
        try {
            conn.send(gson.toJson(message));
        } catch (Exception e) {
            log.debug("帧率统计推送失败：{}", e.getMessage());
        }
    }

    private void stopStreamer(WebSocket conn) {
        if (h264Connections.remove(conn)) {
            h264.unsubscribe(conn);
        }
    }

    private void sendScreenState(WebSocket conn, boolean streaming, int fps, int width,
                                 int height, String reason) {
        ScreenStateMessage state = new ScreenStateMessage();
        state.streaming = streaming;
        state.fps = fps;
        state.width = width;
        state.height = height;
        state.reason = reason;
        try {
            if (conn.isOpen()) {
                conn.send(gson.toJson(state));
            }
        } catch (Exception e) {
            log.debug("屏幕状态回报失败：{}", e.getMessage());
        }
    }

    private int intOf(JsonObject json, String key, int fallback) {
        try {
            return json.has(key) ? json.get(key).getAsInt() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    @Override
    public void stop() throws InterruptedException {
        shutdownScreen();
        super.stop();
    }

    @Override
    public void stop(int timeout) throws InterruptedException {
        shutdownScreen();
        super.stop(timeout);
    }

    private void shutdownScreen() {
        h264.stop();
        h264Connections.clear();
    }

    private LayoutMessage buildLayout(String pageId) {
        AppConfig.PageConfig page = config.page(pageId);
        if (page == null) {
            page = config.defaultPage();
        }
        if (page == null) {
            return null;
        }
        LayoutMessage message = new LayoutMessage();
        message.pageId = page.id;
        message.pageTitle = page.title;
        message.grid.rows = page.grid.rows;
        message.grid.cols = page.grid.cols;
        for (AppConfig.ButtonConfig button : page.buttons) {
            LayoutMessage.Button item = new LayoutMessage.Button();
            item.id = button.id;
            item.label = button.label;
            item.icon = button.icon;
            item.bgColor = button.bgColor;
            item.state = button.state;
            message.buttons.add(item);
        }
        return message;
    }
}
