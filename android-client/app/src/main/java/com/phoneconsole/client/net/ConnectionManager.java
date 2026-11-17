package com.phoneconsole.client.net;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.phoneconsole.client.model.ButtonStateMessage;
import com.phoneconsole.client.model.LayoutMessage;
import com.phoneconsole.client.model.ScreenDevicesMessage;
import com.phoneconsole.client.model.SessionMessage;
import com.phoneconsole.client.model.TelemetryMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * 双模连接管理：
 * 1. 先探测 ws://127.0.0.1:8500（USB + adb reverse），超时 800ms；
 * 2. 失败后回退到已保存的局域网地址；
 * 3. 全部失败按固定间隔重试，断开后自动重连。
 */
public class ConnectionManager {

    public interface Listener {
        void onLayout(LayoutMessage layout);

        void onTelemetry(TelemetryMessage telemetry);

        void onButtonState(ButtonStateMessage state);

        void onConnectionChanged(boolean connected, String mode, String endpoint);

        /** 会话能力：通道类型与屏幕推流是否可用 */
        void onSession(SessionMessage session);

        /** 屏幕推流状态回报 */
        void onScreenState(boolean streaming, String reason);

        /** 屏幕推流实时统计（每秒一次） */
        void onScreenStats(double actualFps, int kbps);

        /** 可选显示器列表 */
        void onScreenDevices(ScreenDevicesMessage devices);

        /** H.264 访问单元（OkHttp 工作线程回调，交由解码线程处理） */
        void onScreenFrame(byte[] data);
    }

    private static final String TAG = "ConnectionManager";
    private static final String PREFS = "phone_console";
    private static final String KEY_LAN = "lan_endpoint";
    private static final int DEFAULT_PORT = 8500;
    private static final long CONNECT_TIMEOUT_MS = 800L;
    private static final long RECONNECT_DELAY_MS = 3000L;

    private final Context context;
    private final Listener listener;
    private final Gson gson = new Gson();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String> candidates = new ArrayList<>();

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build();

    private volatile WebSocket socket;
    private volatile boolean stopped = true;
    private int candidateIndex;
    private int generation;

    public ConnectionManager(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        stopped = false;
        candidateIndex = 0;
        buildCandidates();
        connectNext();
    }

    public void stop() {
        stopped = true;
        generation++;
        WebSocket ws = socket;
        socket = null;
        if (ws != null) {
            ws.close(1000, "client stop");
        }
        main.removeCallbacksAndMessages(null);
    }

    /** 保存局域网地址，例如 ws://192.168.1.100:8500（后续可改为扫码写入） */
    public void setLanEndpoint(String wsUrl) {
        prefs().edit().putString(KEY_LAN, wsUrl).apply();
    }

    /** 读取已保存的局域网地址，未设置时返回空串 */
    public String getLanEndpoint() {
        String lan = prefs().getString(KEY_LAN, null);
        return lan == null ? "" : lan;
    }

    /** 重新探测全部候选地址（地址变更后调用） */
    public void reconnect() {
        stop();
        start();
    }

    public void sendAction(String pageId, String buttonId, String event) {
        WebSocket ws = socket;
        if (ws == null) {
            Log.d(TAG, "未连接，忽略动作 " + buttonId);
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "action");
        payload.addProperty("pageId", pageId);
        payload.addProperty("buttonId", buttonId);
        payload.addProperty("event", event);
        payload.addProperty("timestamp", System.currentTimeMillis());
        ws.send(payload.toString());
    }

    /** 屏幕推流控制：start=true 开启，否则停止；画质固定 1080p60 H.264 */
    public void sendScreenControl(boolean start) {
        sendScreenControl(start, -1);
    }

    /** 屏幕推流控制（monitor=-1 表示整块桌面，>=0 为显示器序号） */
    public void sendScreenControl(boolean start, int monitor) {
        WebSocket ws = socket;
        if (ws == null) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "screen_control");
        payload.addProperty("action", start ? "start" : "stop");
        if (start) {
            // 过渡期兼容旧服务端：新服务端忽略该字段，固定使用 H.264
            payload.addProperty("codec", "h264");
            payload.addProperty("monitor", monitor);
        }
        ws.send(payload.toString());
    }

    /** 请求 PC 下发可选显示器列表 */
    public void requestScreenDevices() {
        WebSocket ws = socket;
        if (ws == null) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "screen_control");
        payload.addProperty("action", "list");
        ws.send(payload.toString());
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void buildCandidates() {
        candidates.clear();
        candidates.add("ws://127.0.0.1:" + DEFAULT_PORT);
        String lan = prefs().getString(KEY_LAN, null);
        if (lan != null && !lan.trim().isEmpty()) {
            candidates.add(lan.trim());
        }
    }

    private void connectNext() {
        if (stopped) {
            return;
        }
        if (candidateIndex >= candidates.size()) {
            main.post(() -> listener.onConnectionChanged(false, "-", "未连接"));
            main.postDelayed(this::restart, RECONNECT_DELAY_MS);
            return;
        }
        attempt(candidates.get(candidateIndex++));
    }

    private void restart() {
        if (!stopped) {
            start();
        }
    }

    private void attempt(String url) {
        final int gen = ++generation;
        Request request = new Request.Builder().url(url).build();
        client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                if (gen != generation) {
                    webSocket.close(1000, "stale");
                    return;
                }
                socket = webSocket;
                Log.i(TAG, "已连接 " + url);
                main.post(() -> listener.onConnectionChanged(true, modeOf(url), url));
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                dispatch(text);
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull ByteString bytes) {
                if (gen != generation) {
                    return;
                }
                listener.onScreenFrame(bytes.toByteArray());
            }

            @Override
            public void onClosing(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                webSocket.close(1000, null);
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t,
                                  @Nullable Response response) {
                if (gen != generation) {
                    return;
                }
                Log.d(TAG, "连接失败 " + url + "：" + t.getMessage());
                if (socket == webSocket) {
                    socket = null;
                }
                main.post(ConnectionManager.this::connectNext);
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                if (gen != generation) {
                    return;
                }
                socket = null;
                main.post(() -> {
                    listener.onConnectionChanged(false, "-", "连接断开");
                    restart();
                });
            }
        });
    }

    private String modeOf(String url) {
        return url.contains("127.0.0.1") || url.contains("localhost") ? "USB" : "Wi-Fi";
    }

    private void dispatch(String text) {
        try {
            JsonObject obj = JsonParser.parseString(text).getAsJsonObject();
            String type = obj.has("type") ? obj.get("type").getAsString() : "";
            switch (type) {
                case "layout_sync": {
                    LayoutMessage layout = gson.fromJson(obj, LayoutMessage.class);
                    main.post(() -> listener.onLayout(layout));
                    break;
                }
                case "telemetry": {
                    TelemetryMessage telemetry = gson.fromJson(obj, TelemetryMessage.class);
                    main.post(() -> listener.onTelemetry(telemetry));
                    break;
                }
                case "button_state": {
                    ButtonStateMessage state = gson.fromJson(obj, ButtonStateMessage.class);
                    main.post(() -> listener.onButtonState(state));
                    break;
                }
                case "session": {
                    SessionMessage session = gson.fromJson(obj, SessionMessage.class);
                    main.post(() -> listener.onSession(session));
                    break;
                }
                case "screen_state": {
                    boolean streaming = obj.has("streaming") && obj.get("streaming").getAsBoolean();
                    String reason = obj.has("reason") && !obj.get("reason").isJsonNull()
                            ? obj.get("reason").getAsString() : null;
                    main.post(() -> listener.onScreenState(streaming, reason));
                    break;
                }
                case "screen_stats": {
                    double actualFps = obj.has("actualFps") ? obj.get("actualFps").getAsDouble() : 0;
                    int kbps = obj.has("kbps") ? obj.get("kbps").getAsInt() : 0;
                    main.post(() -> listener.onScreenStats(actualFps, kbps));
                    break;
                }
                case "screen_devices": {
                    ScreenDevicesMessage devices = gson.fromJson(obj, ScreenDevicesMessage.class);
                    main.post(() -> listener.onScreenDevices(devices));
                    break;
                }
                default:
                    Log.d(TAG, "忽略消息类型：" + type);
                    break;
            }
        } catch (Exception e) {
            Log.w(TAG, "报文解析失败：" + e.getMessage());
        }
    }
}
