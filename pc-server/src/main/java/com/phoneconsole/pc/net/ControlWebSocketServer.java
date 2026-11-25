package com.phoneconsole.pc.net;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.phoneconsole.pc.camera.CameraBridgeService;
import com.phoneconsole.pc.config.AppConfig;
import com.phoneconsole.pc.config.ConfigStore;
import com.phoneconsole.pc.core.ActionEngine;
import com.phoneconsole.pc.core.KeySimulator;
import com.phoneconsole.pc.core.MouseController;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 手机端长连接入口：下发页面布局、接收触控动作、广播遥测与按钮状态。
 */
public class ControlWebSocketServer extends WebSocketServer {

    private static final Logger log = LoggerFactory.getLogger(ControlWebSocketServer.class);

    private final Gson gson = new Gson();
    private volatile AppConfig config;
    private final ActionEngine engine;
    /** 自定义按键增删后的配置落盘回调，由 Main 注入 */
    private volatile Consumer<AppConfig> configPersister;

    private static final String CLIENT_WEB = "web";
    private static final String CLIENT_PHONE = "phone";
    /** 每条连接的客户端类型：默认手机端，收到 client_hello 后按声明改写 */
    private final Map<WebSocket, String> clientKinds = new ConcurrentHashMap<>();
    /** 遥测快照提供方：连接建立时立即给新客户端一帧 */
    private volatile java.util.function.Supplier<String> telemetrySource;
    private volatile CameraBridgeService cameraBridge;

    public void setTelemetrySource(java.util.function.Supplier<String> source) {
        this.telemetrySource = source;
    }

    public void setCameraBridge(CameraBridgeService cameraBridge) {
        this.cameraBridge = cameraBridge;
    }

    public AppConfig currentConfig() {
        return config;
    }

    /** 通道帧率上限来自 config.json（screen.usbFps / screen.lanFps），改动后对后续订阅生效 */
    private static final int USB_FPS_FALLBACK = 30;
    private static final int LAN_FPS_FALLBACK = 24;

    /** 每个客户端各自订阅的（显示器, 帧率），互不影响 */
    private record Subscription(int monitor, int fps) {
        String key() {
            return monitor + "@" + fps;
        }
    }

    private final Map<WebSocket, Subscription> connMonitors = new ConcurrentHashMap<>();
    /** 同一（屏 + 帧率）的多个客户端共享一个 ffmpeg 进程 */
    private final Map<String, H264ScreenStreamer> streamers = new ConcurrentHashMap<>();
    private final ScreenTargets targets = new ScreenTargets();
    private final Object configLock = new Object();
    /** 新客户端的默认采集目标：-1 = 整块桌面 */
    private volatile int selectedMonitor;

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

    /** 注入配置落盘回调：自定义按键增删后由 Main 负责写回 config.json */
    public void setConfigPersister(Consumer<AppConfig> persister) {
        this.configPersister = persister;
    }

    /** 配置热重载：替换快照，并更新后续新客户端使用的默认采集目标 */
    public void setConfig(AppConfig config) {
        if (config == null) {
            return;
        }
        AppConfig.Screen previousScreen;
        synchronized (configLock) {
            previousScreen = this.config == null ? null : this.config.screen;
            this.config = config;
        }
        if (screenChanged(previousScreen, config.screen)) {
            // 分辨率/缩放/预设/码率变更：重启编码进程让新参数立即生效
            for (H264ScreenStreamer streamer : streamers.values()) {
                streamer.restartEncoder();
            }
            LogBuffer.get().info("SCREEN", "推流参数已变更，正在重启编码器应用新参数");
        }
        int fromConfig = config.screen == null ? -1 : config.screen.monitor;
        if (fromConfig != selectedMonitor) {
            selectedMonitor = fromConfig;
            // 只影响之后发起的订阅，已在观看的客户端保持自己选的那块屏
            broadcastScreenDevices();
        }
    }

    /** 只比较影响单个编码进程的参数；帧率在订阅时确定，重新开始推流后生效 */
    private static boolean screenChanged(AppConfig.Screen before, AppConfig.Screen after) {
        if (before == null || after == null) {
            return before != after;
        }
        return before.maxWidth != after.maxWidth
                || before.maxHeight != after.maxHeight
                || before.maxBitrateKbps != after.maxBitrateKbps
                || before.x264Threads != after.x264Threads
                || !Objects.equals(before.scaleFlags, after.scaleFlags)
                || !Objects.equals(before.x264Preset, after.x264Preset)
                || !Objects.equals(before.ffmpegExecutable, after.ffmpegExecutable);
    }

    private AppConfig.Screen screenConfig() {
        AppConfig snapshot = config;
        return snapshot == null ? null : snapshot.screen;
    }

    private int usbFps() {
        AppConfig.Screen screen = screenConfig();
        int value = screen == null ? 0 : screen.usbFps;
        return value <= 0 ? USB_FPS_FALLBACK : Math.min(120, value);
    }

    private int lanFps() {
        AppConfig.Screen screen = screenConfig();
        int value = screen == null ? 0 : screen.lanFps;
        return value <= 0 ? LAN_FPS_FALLBACK : Math.min(120, value);
    }

    private void broadcastScreenDevices() {
        for (WebSocket conn : getConnections()) {
            sendScreenDevices(conn);
        }
    }

    /**
     * 在线手机端数量：连接后主动声明 client=web 的是浏览器控制端，
     * 不计入「在线设备」，避免把 PC 上的网页端当成一台手机。
     */
    public int clientCount() {
        int count = 0;
        for (String kind : clientKinds.values()) {
            if (!CLIENT_WEB.equals(kind)) {
                count++;
            }
        }
        return count;
    }

    /** 网页控制端（浏览器）连接数 */
    public int webClientCount() {
        int count = 0;
        for (String kind : clientKinds.values()) {
            if (CLIENT_WEB.equals(kind)) {
                count++;
            }
        }
        return count;
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
        clientKinds.put(conn, CLIENT_PHONE);
        boolean usb = isUsbChannel(conn);
        log.info("手机端接入：{}（{} 通道）", conn.getRemoteSocketAddress(), usb ? "USB" : "LAN");
        LogBuffer.get().info("WS", "客户端接入（" + (usb ? "USB" : "LAN") + "）：" + conn.getRemoteSocketAddress());

        SessionMessage session = new SessionMessage();
        session.transport = usb ? "usb" : "lan";
        // 屏幕推流不再限定通道：H.264 码率低，USB / 局域网均可使用
        session.screenStream = true;
        session.screenMaxFps = usb ? usbFps() : lanFps();
        conn.send(gson.toJson(session));

        java.util.function.Supplier<String> telemetry = telemetrySource;
        if (telemetry != null) {
            try {
                conn.send(telemetry.get());
            } catch (Exception e) {
                log.debug("首帧遥测下发失败：{}", e.getMessage());
            }
        }

        AppConfig snapshot = config;
        String pageId = currentPageId != null ? currentPageId : (snapshot == null ? null : snapshot.defaultPageId);
        LayoutMessage layout = buildLayout(pageId);
        if (layout != null) {
            conn.send(gson.toJson(layout));
        }
        sendScreenDevices(conn);
        syncPhoneEndpointToCamera();
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
        String type = strOf(json, "type", "");
        switch (type) {
            case "action" -> {
                ActionMessage action = gson.fromJson(json, ActionMessage.class);
                if (action != null) {
                    engine.handle(conn, action);
                }
            }
            case "screen_control" -> handleScreenControl(conn, json);
            case "client_hello" -> {
                String kind = strOf(json, "client", "");
                if (CLIENT_WEB.equalsIgnoreCase(kind)) {
                    clientKinds.put(conn, CLIENT_WEB);
                    log.info("网页控制端接入：{}", conn.getRemoteSocketAddress());
                    syncPhoneEndpointToCamera();
                }
            }
            case "camera_state" -> handleCameraState(conn, json);
            case "camera_request" -> handleCameraRequest(json);
            case "layout_request" -> {
                String pageId = strOf(json, "pageId", null);
                if (pageId != null && !pageId.isBlank()) {
                    broadcastLayout(pageId);
                }
            }
            case "hotkey" -> KeySimulator.pressCombo(strOf(json, "combo", ""));
            case "remote_text" -> KeySimulator.typeText(strOf(json, "text", ""), boolOf(json, "pressEnter", false));
            case "touchpad_move" -> MouseController.moveBy(intOf(json, "dx", 0), intOf(json, "dy", 0));
            case "touchpad_click" -> MouseController.click(strOf(json, "button", "left"));
            case "touchpad_scroll" -> MouseController.scroll(intOf(json, "deltaY", 0));
            case "mic_mute" -> engine.toggleMicMute();
            case "deck_key_add" -> handleDeckKeyAdd(json);
            case "deck_key_delete" -> handleDeckKeyDelete(json);
            default -> log.debug("忽略消息类型：{}", type);
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        clientKinds.remove(conn);
        log.info("手机端断开：code={} reason={} remote={}", code, reason, remote);
        LogBuffer.get().info("WS", "客户端断开：code=" + code + (reason == null || reason.isBlank() ? "" : " " + reason));
        engine.stopAllRepeats(conn);
        stopStreamer(conn);
        syncPhoneEndpointToCamera();
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        log.error("WebSocket 异常：{}", ex.getMessage());
        if (conn != null) {
            engine.stopAllRepeats(conn);
            stopStreamer(conn);
            syncPhoneEndpointToCamera();
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

    /** 向所有在线手机端发送控制文本帧（过滤掉网页控制端） */
    public void sendToPhones(JsonObject payload) {
        if (payload == null) {
            return;
        }
        String text = gson.toJson(payload);
        for (WebSocket conn : getConnections()) {
            if (CLIENT_WEB.equals(clientKinds.get(conn))) {
                continue;
            }
            try {
                if (conn.isOpen()) {
                    conn.send(text);
                }
            } catch (Exception e) {
                log.debug("下发手机端指令失败：{}", e.getMessage());
            }
        }
    }

    public void broadcastCameraStatus(Map<String, Object> status) {
        if (status == null) {
            return;
        }
        JsonObject msg = gson.toJsonTree(status).getAsJsonObject();
        msg.addProperty("type", "camera_status");
        String payload = gson.toJson(msg);
        for (WebSocket conn : getConnections()) {
            if (!CLIENT_WEB.equals(clientKinds.get(conn))) {
                continue;
            }
            try {
                if (conn.isOpen()) {
                    conn.send(payload);
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private void handleCameraState(WebSocket conn, JsonObject json) {
        clientKinds.put(conn, CLIENT_PHONE);
        CameraBridgeService bridge = cameraBridge;
        if (bridge == null) {
            return;
        }
        syncPhoneEndpointToCamera();
        bridge.onPhoneCameraState(
                boolOf(json, "available", true),
                boolOf(json, "permissionGranted", true),
                boolOf(json, "streaming", false),
                intOf(json, "rtspPort", 8554),
                intOf(json, "width", 0),
                intOf(json, "height", 0),
                strOf(json, "facing", "front"),
                intOf(json, "rotation", 0),
                boolOf(json, "mirrorHorizontal", false),
                boolOf(json, "mirrorVertical", false),
                boolOf(json, "flash", false),
                strOf(json, "error", ""));
    }

    private void handleCameraRequest(JsonObject json) {
        CameraBridgeService bridge = cameraBridge;
        if (bridge == null) {
            return;
        }
        bridge.handleWebCommand(strOf(json, "action", ""), json);
    }

    private void syncPhoneEndpointToCamera() {
        CameraBridgeService bridge = cameraBridge;
        if (bridge == null) {
            return;
        }
        WebSocket selected = null;
        for (WebSocket conn : getConnections()) {
            if (!conn.isOpen() || CLIENT_WEB.equals(clientKinds.get(conn))) {
                continue;
            }
            if (isUsbChannel(conn)) {
                selected = conn;
                break;
            }
            if (selected == null) {
                selected = conn;
            }
        }
        if (selected == null) {
            bridge.onPhoneDisconnected(false);
            return;
        }
        boolean usb = isUsbChannel(selected);
        String host = "127.0.0.1";
        if (!usb) {
            try {
                host = selected.getRemoteSocketAddress().getAddress().getHostAddress();
            } catch (Exception ignored) {
                host = "127.0.0.1";
            }
        }
        bridge.onPhoneConnected(usb, host);
    }

    /** 切换页面并广播页面配置 */
    public void broadcastLayout(String pageId) {
        LayoutMessage layout = buildLayout(pageId);
        if (layout == null) {
            log.warn("页面不存在：{}", pageId);
            return;
        }
        currentPageId = layout.pageId;
        log.info("切换页面 -> {}（{}）", layout.pageTitle, layout.pageId);
        LogBuffer.get().info("PAGE", "下发面板 -> " + layout.pageTitle + "（" + layout.pageId + "）");
        broadcast(gson.toJson(layout));
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
        String action = strOf(json, "action", "");
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
        if (monitor >= 0 && targets.get(monitor) == null) {
            // 显示器数量变了（拔掉外接屏、虚拟屏未激活等）：序号已不存在，直接退回整块桌面
            LogBuffer.get().warn("SCREEN", "显示器 " + monitor + " 不存在，改用整块桌面");
            monitor = -1;
        }
        // 采集目标按连接独立，不再改动全局状态，避免一台设备切屏把其他设备的画面一起切走
        stopStreamer(conn);
        startH264(conn, monitor);
    }

    /** 下发可选显示器列表；id 就是 AWT/Win32 的屏序号，采集时按该屏物理坐标裁剪 */
    private void sendScreenDevices(WebSocket conn) {
        ScreenDevicesMessage message = new ScreenDevicesMessage();
        Subscription subscription = connMonitors.get(conn);
        message.current = subscription == null ? selectedMonitor : subscription.monitor();
        for (ScreenTargets.Target target : targets.list()) {
            ScreenDevicesMessage.Device device = new ScreenDevicesMessage.Device();
            device.id = target.id();
            device.name = target.name();
            // 用物理像素上报，和实际采集分辨率一致（AWT 给的是 DPI 缩放后的逻辑值）
            device.width = target.physical().width;
            device.height = target.physical().height;
            device.x = target.physical().x;
            device.y = target.physical().y;
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

    /** 同一块屏 + 同一帧率的客户端共享一个编码器；不同屏/不同帧率互不影响 */
    private H264ScreenStreamer streamerFor(int monitor, int fps) {
        String key = monitor + "@" + fps;
        return streamers.computeIfAbsent(key, ignored -> new H264ScreenStreamer(
                this::screenConfig, () -> monitor, targets, fps));
    }

    private void startH264(WebSocket conn, int monitor) {
        int fps = isUsbChannel(conn) ? usbFps() : lanFps();
        final Subscription subscription = new Subscription(monitor, fps);
        final H264ScreenStreamer streamer = streamerFor(monitor, fps);
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
                sendScreenStats(conn, streamer.fps(), streamer.width(),
                        streamer.height(), stats.actualFps(), stats.kbps());
            }

            @Override
            public void onClosed(String reason) {
                connMonitors.remove(conn);
                if (streamer.subscriberCount() == 0) {
                    streamers.remove(subscription.key(), streamer);
                }
                sendScreenState(conn, false, 0, 0, 0, reason);
            }
        };

        connMonitors.put(conn, subscription);
        if (!streamer.subscribe(conn, sink)) {
            connMonitors.remove(conn);
            if (streamer.subscriberCount() == 0) {
                streamers.remove(subscription.key(), streamer);
            }
            sendScreenState(conn, false, 0, 0, 0, "encoder_unavailable");
            LogBuffer.get().error("SCREEN", "H.264 编码器不可用，检查 config.json 的 screen.ffmpegExecutable");
            return;
        }
        sendScreenState(conn, true, streamer.fps(), streamer.width(), streamer.height(), null);
        LogBuffer.get().info("SCREEN", "开始推流：" + streamer.width() + "x" + streamer.height()
                + "@" + fps + "，采集=" + (monitor < 0 ? "整块桌面" : "显示器 " + monitor)
                + "（" + conn.getRemoteSocketAddress() + "）");
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
        Subscription subscription = connMonitors.remove(conn);
        if (subscription == null) {
            return;
        }
        H264ScreenStreamer streamer = streamers.get(subscription.key());
        if (streamer == null) {
            return;
        }
        streamer.unsubscribe(conn);
        if (streamer.subscriberCount() == 0) {
            // 最后一个人走了才回收编码器，其他人还在看就继续复用
            streamers.remove(subscription.key(), streamer);
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
            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsInt() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private String strOf(JsonObject json, String key, String fallback) {
        try {
            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private boolean boolOf(JsonObject json, String key, boolean fallback) {
        try {
            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsBoolean() : fallback;
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
        for (H264ScreenStreamer streamer : streamers.values()) {
            streamer.stop();
        }
        streamers.clear();
        connMonitors.clear();
    }

    /** 新增自定义按键：写入配置并广播最新布局 */
    private void handleDeckKeyAdd(JsonObject json) {
        String label = strOf(json, "label", "").trim();
        String actionType = strOf(json, "actionType", "").trim().toLowerCase();
        String target = strOf(json, "target", "").trim();
        String bgColorHex = strOf(json, "bgColorHex", "#1E293B").trim();

        if (label.isEmpty() || actionType.isEmpty() || target.isEmpty()) {
            log.warn("忽略新增按键：参数不完整（label={} type={} target={}）", label, actionType, target);
            return;
        }

        AppConfig.ActionConfig action = new AppConfig.ActionConfig();
        action.type = actionType;
        switch (actionType) {
            case "hotkey" -> {
                var keys = Arrays.stream(target.toLowerCase().split("\\+"))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
                if (keys.isEmpty()) {
                    log.warn("忽略新增按键：hotkey 缺少有效按键 {}", target);
                    return;
                }
                action.keys = keys;
            }
            case "media" -> {
                String cmd = target.toLowerCase();
                if (!ConfigStore.isSupportedMediaCommand(cmd)) {
                    log.warn("忽略新增按键：不支持的媒体指令 {}", target);
                    return;
                }
                action.command = cmd;
            }
            case "launch", "url", "script" -> action.target = target;
            default -> {
                log.warn("忽略新增按键：不支持的动作类型 {}", actionType);
                return;
            }
        }

        String updatedPageId;
        synchronized (configLock) {
            AppConfig snapshot = config;
            String pageId = strOf(json, "pageId", snapshot == null ? null : snapshot.defaultPageId);
            AppConfig.PageConfig page = findPage(snapshot, pageId);
            if (page == null) {
                log.warn("新增按键失败：页面不存在 {}", pageId);
                return;
            }
            AppConfig.ButtonConfig button = new AppConfig.ButtonConfig();
            button.id = "custom_" + UUID.randomUUID().toString().substring(0, 8);
            button.label = label;
            button.bgColor = bgColorHex.isEmpty() ? "#1E293B" : bgColorHex;
            button.state = "normal";
            button.action = action;

            List<AppConfig.ButtonConfig> nextButtons = new ArrayList<>(page.buttons);
            nextButtons.add(button);
            page.buttons = nextButtons;
            persistConfig(snapshot);
            updatedPageId = page.id;
        }
        log.info("新增自定义按键：{}（{} -> {}）", label, actionType, target);
        LogBuffer.get().info("DECK", "新增自定义按键：" + label);
        broadcastLayout(updatedPageId);
    }

    /** 删除自定义按键：写入配置并广播最新布局 */
    private void handleDeckKeyDelete(JsonObject json) {
        String buttonId = strOf(json, "buttonId", "").trim();
        if (buttonId.isEmpty()) {
            return;
        }
        String updatedPageId = null;
        boolean removed;
        synchronized (configLock) {
            AppConfig snapshot = config;
            String pageId = strOf(json, "pageId", snapshot == null ? null : snapshot.defaultPageId);
            AppConfig.PageConfig page = findPage(snapshot, pageId);
            if (page == null) {
                return;
            }
            List<AppConfig.ButtonConfig> nextButtons = new ArrayList<>(page.buttons);
            removed = nextButtons.removeIf(b -> buttonId.equals(b.id));
            if (removed) {
                page.buttons = nextButtons;
                persistConfig(snapshot);
                updatedPageId = page.id;
            }
        }
        if (removed) {
            log.info("删除自定义按键：{}", buttonId);
            LogBuffer.get().info("DECK", "删除自定义按键：" + buttonId);
            broadcastLayout(updatedPageId);
        }
    }

    private AppConfig.PageConfig findPage(AppConfig snapshot, String pageId) {
        if (snapshot == null || snapshot.pages == null || snapshot.pages.isEmpty()) {
            return null;
        }
        if (pageId == null || pageId.isBlank()) {
            return snapshot.defaultPage();
        }
        return snapshot.page(pageId);
    }

    private void persistConfig(AppConfig snapshot) {
        Consumer<AppConfig> persister = configPersister;
        if (persister != null && snapshot != null) {
            persister.accept(snapshot);
        }
    }

    private LayoutMessage buildLayout(String pageId) {
        synchronized (configLock) {
            AppConfig snapshot = config;
            if (snapshot == null) {
                return null;
            }
            AppConfig.PageConfig page = snapshot.page(pageId);
            if (page == null) {
                page = snapshot.defaultPage();
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
                item.state = engine.buttonState(page.id, button);
                message.buttons.add(item);
            }
            for (AppConfig.PageConfig each : snapshot.pages) {
                LayoutMessage.Page ref = new LayoutMessage.Page();
                ref.id = each.id;
                ref.title = each.title;
                message.pages.add(ref);
            }
            return message;
        }
    }
}
