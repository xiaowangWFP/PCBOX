package com.phoneconsole.pc.camera;

import com.google.gson.JsonObject;
import com.phoneconsole.pc.config.AppConfig;
import com.phoneconsole.pc.runtime.LogBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 手机虚拟摄像头桥接服务：
 * 1. 维护 Windows DirectShow 虚拟摄像头（SoftcamBridge）；
 * 2. 轮询 scIsConnected()，支持「微信/会议打开摄像头时按需自动唤醒手机推流」与手动强制开关；
 * 3. 通过 ffmpeg 拉取手机端 RTSP 流并转为 BGR24 原始帧喂给 SoftcamBridge。
 */
public final class CameraBridgeService {

    private static final Logger log = LoggerFactory.getLogger(CameraBridgeService.class);
    /** 消费端（微信/会议预览切通话）瞬断容忍时间，避免频繁重启手机相机 */
    private static final long DISCONNECT_GRACE_MS = 3000L;
    /** 手动停止推流后，消费端需持续完全断开超过该时长才重新允许按需自动唤醒 */
    private static final long STOP_SUPPRESS_CLEAR_MS = 4000L;
    private static final long WATCHDOG_PERIOD_MS = 300L;
    private static final long RETRY_COOLDOWN_MS = 1000L;

    public interface Sink {
        void sendControlToPhones(JsonObject payload);

        void broadcastCameraStatus(Map<String, Object> status);
    }

    private final Supplier<AppConfig> configSupplier;
    private final Sink sink;
    private final SoftcamBridge softcam = new SoftcamBridge();
    private final Object stateLock = new Object();

    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "camera-watchdog");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean started;
    private volatile boolean stopped;

    // 当前运行时偏好（初始值来自 config.camera，可在运行期通过网页/手机端实时切换）
    private volatile String facing = "front";
    private volatile int rotation = 0;
    private volatile boolean mirrorHorizontal = false;
    private volatile boolean mirrorVertical = false;
    private volatile boolean flash = false;
    private volatile boolean autoDemand = true;
    private volatile boolean manualActive = false;
    /** 用户手动点停止后在消费端断开前保持抑制，避免按需模式立刻重连 */
    private volatile boolean suppressedUntilDisconnect = false;

    // 虚拟摄像头与外部消费端状态
    private volatile boolean softcamRegistered;
    private volatile boolean consumerConnected;
    private volatile long lastConsumerSeenAt;
    private volatile long consumerOfflineSince;
    private volatile boolean commandedActive;
    private volatile long lastCommandSentAt;

    // 手机端接入与推流回报状态
    private volatile boolean phoneConnected;
    private volatile String phoneHost = "127.0.0.1";
    private volatile boolean phoneUsb;
    private volatile boolean phoneAvailable;
    private volatile boolean phonePermissionGranted = true;
    private volatile boolean phoneStreaming;
    private volatile int phoneRtspPort = 8554;
    private volatile int phoneWidth;
    private volatile int phoneHeight;
    private volatile String phoneError = "";

    // FFmpeg 解码子进程状态（优先走原生 8MB pipe_feeder.dll，不可用时回退 Java 管道）
    private final PipeFeeder pipeFeeder = new PipeFeeder();
    private volatile Process ffmpegProcess;
    private volatile Thread ffmpegReaderThread;
    private volatile long lastFfmpegAttemptAt;
    private volatile boolean receiverRunning;
    private volatile long framesSent;
    private volatile double actualFps;
    private volatile String lastError = "";

    public CameraBridgeService(Supplier<AppConfig> configSupplier, Sink sink) {
        this.configSupplier = configSupplier;
        this.sink = sink;
        syncPrefsFromConfig(cameraConfig());
    }

    public void start() {
        if (started) {
            return;
        }
        started = true;
        stopped = false;
        refreshSoftcamHandle();
        watchdog.scheduleWithFixedDelay(this::tick, 200, WATCHDOG_PERIOD_MS, TimeUnit.MILLISECONDS);
        log.info("虚拟摄像头桥接服务已启动（DirectShow 注册={}）", softcamRegistered);
        LogBuffer.get().info("CAMERA", "虚拟摄像头服务已启动（驱动注册="
                + (softcamRegistered ? "已注册 DirectShow Softcam" : "未注册，需运行工具箱 [12] 注册") + "）");
    }

    public void onConfigReloaded(AppConfig config) {
        AppConfig.Camera cam = config != null && config.camera != null ? config.camera : new AppConfig.Camera();
        synchronized (stateLock) {
            syncPrefsFromConfig(cam);
        }
        refreshSoftcamHandle();
        if (!cam.enabled) {
            manualActive = false;
            stopReceiverAndSendBlank();
            sendControl("stop", false);
        } else if (commandedActive || phoneStreaming) {
            // 配置热改了分辨率/帧率/码率/角度时同步给手机并重启拉流适应新尺寸
            sendControl("start", manualActive);
            stopReceiver();
        } else {
            sendControl("sync", false);
        }
        notifyStatus();
    }

    public void onPhoneConnected(boolean usb, String remoteHost) {
        String resolvedHost = (usb || remoteHost == null || remoteHost.isBlank()) ? "127.0.0.1" : remoteHost;
        boolean newlyConnected;
        synchronized (stateLock) {
            boolean sameEndpoint = this.phoneConnected
                    && this.phoneUsb == usb
                    && resolvedHost.equals(this.phoneHost);
            this.phoneConnected = true;
            this.phoneUsb = usb;
            this.phoneHost = resolvedHost;
            if (sameEndpoint) {
                return;
            }
            newlyConnected = true;
            this.phoneStreaming = false;
            this.phoneError = "";
            this.commandedActive = false;
            this.suppressedUntilDisconnect = false;
        }
        if (newlyConnected) {
            sendControl("sync", false);
        }
        notifyStatus();
    }

    public void onPhoneDisconnected(boolean stillHasOtherPhone) {
        if (stillHasOtherPhone) {
            return;
        }
        synchronized (stateLock) {
            this.phoneConnected = false;
            this.phoneStreaming = false;
            this.phoneAvailable = false;
            this.commandedActive = false;
        }
        stopReceiverAndSendBlank();
        notifyStatus();
    }

    /** 收到手机端上报的 camera_state（仅更新设备与 RTSP 状态，不覆盖 PC 端 manualActive） */
    public void onPhoneCameraState(boolean available, boolean permissionGranted, boolean streaming,
            int rtspPort, int width, int height,
            String reportedFacing, int reportedRotation,
            boolean reportedMirrorH, boolean reportedMirrorV,
            boolean reportedFlash, String error) {
        boolean shouldRestartReceiver = false;
        synchronized (stateLock) {
            this.phoneAvailable = available;
            this.phonePermissionGranted = permissionGranted;
            boolean wasStreaming = this.phoneStreaming;
            this.phoneStreaming = streaming;
            if (rtspPort > 0) {
                this.phoneRtspPort = rtspPort;
            }
            if (width > 0 && height > 0) {
                this.phoneWidth = width;
                this.phoneHeight = height;
            }
            if (reportedFacing != null && !reportedFacing.isBlank()) {
                this.facing = "back".equalsIgnoreCase(reportedFacing) ? "back" : "front";
            }
            this.rotation = normalizeRotation(reportedRotation);
            this.mirrorHorizontal = reportedMirrorH;
            this.mirrorVertical = reportedMirrorV;
            this.flash = reportedFlash;
            this.phoneError = error == null ? "" : error;
            if (wasStreaming && !streaming) {
                shouldRestartReceiver = true;
            }
        }
        if (!streaming || shouldRestartReceiver) {
            stopReceiverAndSendBlank();
        }
        notifyStatus();
    }

    /** 来自网页控制台或手机端的摄像头动作 */
    public Map<String, Object> handleWebCommand(String action, JsonObject params) {
        String act = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        AppConfig.Camera cfg = cameraConfig();
        synchronized (stateLock) {
            switch (act) {
                case "start" -> {
                    manualActive = true;
                    suppressedUntilDisconnect = false;
                    lastError = "";
                }
                case "stop" -> {
                    manualActive = false;
                    commandedActive = false;
                    lastConsumerSeenAt = 0L;
                    consumerOfflineSince = 0L;
                    suppressedUntilDisconnect = true;
                    lastError = "";
                }
                case "auto_demand" -> {
                    if (params != null && params.has("enabled")) {
                        autoDemand = params.get("enabled").getAsBoolean();
                    } else {
                        autoDemand = !autoDemand;
                    }
                    if (!autoDemand && !manualActive) {
                        commandedActive = false;
                        lastConsumerSeenAt = 0L;
                        suppressedUntilDisconnect = false;
                    }
                }
                case "switch_camera" -> facing = "front".equals(facing) ? "back" : "front";
                case "rotate" -> {
                    int step = params != null && params.has("delta") ? params.get("delta").getAsInt() : 90;
                    rotation = normalizeRotation(rotation + step);
                }
                case "mirror_h", "flip_h" -> mirrorHorizontal = !mirrorHorizontal;
                case "mirror_v", "flip_v" -> mirrorVertical = !mirrorVertical;
                case "flash" -> flash = params != null && params.has("enabled")
                        ? params.get("enabled").getAsBoolean()
                        : !flash;
                case "configure", "sync" -> {
                    if (params != null) {
                        if (params.has("width") && params.has("height")) {
                            cfg.width = Math.max(320, params.get("width").getAsInt() & ~3);
                            cfg.height = Math.max(240, params.get("height").getAsInt() & ~3);
                        }
                        if (params.has("fps")) {
                            cfg.fps = Math.max(5, Math.min(60, params.get("fps").getAsInt()));
                        }
                        if (params.has("facing")) {
                            facing = "back".equalsIgnoreCase(params.get("facing").getAsString()) ? "back" : "front";
                        }
                        if (params.has("rotation")) {
                            rotation = normalizeRotation(params.get("rotation").getAsInt());
                        }
                        if (params.has("mirrorHorizontal")) {
                            mirrorHorizontal = params.get("mirrorHorizontal").getAsBoolean();
                        }
                        if (params.has("mirrorVertical")) {
                            mirrorVertical = params.get("mirrorVertical").getAsBoolean();
                        }
                        if (params.has("flash")) {
                            flash = params.get("flash").getAsBoolean();
                        }
                    }
                }
                default -> {
                    return Map.of("ok", false, "error", "未知摄像头指令：" + act);
                }
            }
        }

        boolean fromPhone = params != null && params.has("fromPhone")
                && !params.get("fromPhone").isJsonNull() && params.get("fromPhone").getAsBoolean();
        if ("configure".equals(act)) {
            refreshSoftcamHandle();
            stopReceiver();
        }
        if ("stop".equals(act)) {
            stopReceiverAndSendBlank();
            sendControl("stop", false);
        } else if ("start".equals(act)) {
            sendControl("start", true);
        } else if ("auto_demand".equals(act)) {
            if (!autoDemand && !manualActive && (phoneStreaming || receiverRunning)) {
                stopReceiverAndSendBlank();
                sendControl("stop", false);
            } else if (!fromPhone) {
                sendControl("sync", false);
            }
        } else if ("configure".equals(act) && fromPhone) {
            // 手机端发起画质变更时自身已完成本地重启，PC 侧不回发冗余 start 避免二次重建冲突
            log.info("手机端更新画质参数：{}x{}@{}fps", cfg.width, cfg.height, cfg.fps);
        } else if (!"sync".equals(act) && (commandedActive || manualActive || phoneStreaming)) {
            sendControl("start", manualActive);
        } else if (!fromPhone) {
            sendControl("sync", false);
        }
        notifyStatus();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("camera", statusMap());
        return result;
    }

    public Map<String, Object> statusMap() {
        AppConfig.Camera cfg = cameraConfig();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", cfg.enabled);
        map.put("filterRegistered", softcamRegistered);
        map.put("dllPath", softcam.loadedDllPath());
        map.put("softcamOpen", softcam.isOpen());
        map.put("consumerConnected", consumerConnected);
        map.put("suppressed", suppressedUntilDisconnect);
        map.put("autoDemand", autoDemand);
        map.put("manualActive", manualActive);
        map.put("desiredActive", computeDesiredActive());
        map.put("phoneConnected", phoneConnected);
        map.put("phoneUsb", phoneUsb);
        map.put("phoneAvailable", phoneAvailable);
        map.put("phonePermissionGranted", phonePermissionGranted);
        map.put("phoneStreaming", phoneStreaming);
        map.put("receiverRunning", receiverRunning);
        map.put("width", cfg.width);
        map.put("height", cfg.height);
        map.put("fps", cfg.fps);
        map.put("actualFps", Math.round(actualFps * 10.0) / 10.0);
        map.put("framesSent", framesSent);
        map.put("rtspPort", cfg.rtspPort);
        map.put("facing", facing);
        map.put("rotation", rotation);
        map.put("mirrorHorizontal", mirrorHorizontal);
        map.put("mirrorVertical", mirrorVertical);
        map.put("flash", flash);
        map.put("error", !lastError.isBlank() ? lastError : phoneError);
        return map;
    }

    public void shutdown() {
        stopped = true;
        watchdog.shutdownNow();
        stopReceiverAndSendBlank();
        if (phoneConnected && commandedActive) {
            sendControl("stop", false);
        }
        softcam.close();
        log.info("虚拟摄像头桥接服务已停止");
    }

    private void tick() {
        if (stopped) {
            return;
        }
        try {
            AppConfig.Camera cfg = cameraConfig();
            softcamRegistered = SoftcamBridge.isFilterRegistered();
            if (!cfg.enabled) {
                return;
            }
            if (!softcam.isOpen()) {
                refreshSoftcamHandle();
            }

            boolean prevConsumer = consumerConnected;
            boolean nowConsumer = softcam.isConnected();
            long now = System.currentTimeMillis();
            consumerConnected = nowConsumer;
            if (nowConsumer) {
                lastConsumerSeenAt = now;
                consumerOfflineSince = 0L;
            } else {
                if (consumerOfflineSince == 0L) {
                    consumerOfflineSince = now;
                }
                // 仅当消费端持续完全断开超过 4 秒后才解除手动停止抑制，避免软件重开/探测闪断又强制唤醒
                if (suppressedUntilDisconnect && now - consumerOfflineSince >= STOP_SUPPRESS_CLEAR_MS) {
                    suppressedUntilDisconnect = false;
                }
            }
            if (prevConsumer != nowConsumer) {
                log.info("虚拟摄像头消费端状态变化：{}", nowConsumer ? "已被微信/会议软件打开" : "已关闭");
                LogBuffer.get().info("CAMERA", nowConsumer ? "检测到应用打开虚拟摄像头，准备就绪" : "应用已释放虚拟摄像头");
                sendControl("sync", false);
                notifyStatus();
            }

            if (receiverRunning && ffmpegProcess == null) {
                // 同步原生 8MB pipe_feeder 的实时帧数与帧率
                if (pipeFeeder.isRunning()) {
                    long delta = pipeFeeder.pollNewFrames();
                    if (delta > 0) {
                        framesSent += delta;
                    }
                    double f = pipeFeeder.fps();
                    if (f > 0) {
                        actualFps = f;
                        if (!lastError.isBlank()) {
                            lastError = "";
                        }
                    } else {
                        String err = pipeFeeder.lastError();
                        if (!err.isBlank()) {
                            lastError = err;
                        }
                    }
                    notifyStatus();
                } else {
                    String err = pipeFeeder.lastError();
                    if (!err.isBlank()) {
                        lastError = err;
                    }
                    pipeFeeder.stop();
                    receiverRunning = false;
                    actualFps = 0;
                    notifyStatus();
                }
            }

            boolean desired = computeDesiredActive();
            if (!phoneConnected) {
                return;
            }

            if (desired) {
                // 需要推流：确保手机端启动 RTSP 服务，并在手机就绪后拉起 ffmpeg 喂帧
                if (!commandedActive || (!phoneStreaming && now - lastCommandSentAt > 2500L)) {
                    commandedActive = true;
                    lastCommandSentAt = now;
                    sendControl("start", manualActive);
                }
                if (phoneStreaming && !receiverRunning
                        && phoneWidth == cfg.width && phoneHeight == cfg.height
                        && now - lastFfmpegAttemptAt >= RETRY_COOLDOWN_MS) {
                    startReceiver(cfg);
                } else if (phoneStreaming && !receiverRunning && now - lastFfmpegAttemptAt >= RETRY_COOLDOWN_MS + 1000L) {
                    // 兜底：若手机分辨率未能切到期望尺寸仍允许按原分辨率拉流
                    startReceiver(cfg);
                }
            } else {
                // 不再需要推流：停掉 ffmpeg 与手机端相机以省电降温
                if (commandedActive || phoneStreaming || receiverRunning) {
                    commandedActive = false;
                    stopReceiverAndSendBlank();
                    sendControl("stop", false);
                    notifyStatus();
                }
            }
        } catch (Throwable t) {
            log.debug("camera-watchdog 异常：{}", t.getMessage());
        }
    }

    private boolean computeDesiredActive() {
        AppConfig.Camera cfg = cameraConfig();
        if (!cfg.enabled) {
            return false;
        }
        if (manualActive) {
            return true;
        }
        if (!autoDemand || suppressedUntilDisconnect) {
            return false;
        }
        return consumerConnected || (lastConsumerSeenAt > 0
                && System.currentTimeMillis() - lastConsumerSeenAt < DISCONNECT_GRACE_MS);
    }

    private void refreshSoftcamHandle() {
        softcamRegistered = SoftcamBridge.isFilterRegistered();
        AppConfig.Camera cfg = cameraConfig();
        if (!cfg.enabled) {
            softcam.close();
            return;
        }
        // DirectShow 客户端（微信/会议软件）已连接时不可销毁重建句柄，否则会强行断开输出 Pin；
        // 保持当前画布并通过 ffmpeg scale/pad 自动适配不同推流分辨率即可无缝切换画质。
        if (softcam.isOpen() && softcam.isConnected()) {
            return;
        }
        if (!softcam.open(cfg.width, cfg.height)) {
            lastError = SoftcamBridge.resolveDllPath() == null
                    ? "未找到 softcam.dll（请检查 drivers/softcam）"
                    : "无法创建 Softcam 实例（可能已被其他程序占用）";
        } else if (lastError.toLowerCase(Locale.ROOT).contains("softcam")) {
            lastError = "";
        }
    }

    private void startReceiver(AppConfig.Camera cfg) {
        if (receiverRunning || stopped) {
            return;
        }
        lastFfmpegAttemptAt = System.currentTimeMillis();
        String ffmpeg = resolveFfmpegExecutable();
        if (ffmpeg == null) {
            lastError = "未找到 ffmpeg 可执行文件";
            return;
        }
        refreshSoftcamHandle();
        if (!softcam.isOpen()) {
            return;
        }

        int targetW = softcam.width();
        int targetH = softcam.height();
        int port = phoneRtspPort > 0 ? phoneRtspPort : cfg.rtspPort;
        String host = (phoneHost == null || phoneHost.isBlank()) ? "127.0.0.1" : phoneHost;
        String rtspUrl = "rtsp://" + host + ":" + port + "/live";

        String scalePad = String.format(Locale.ROOT,
                "scale=w=%d:h=%d:force_original_aspect_ratio=decrease:flags=fast_bilinear,pad=%d:%d:(ow-iw)/2:(oh-ih)/2:black",
                targetW, targetH, targetW, targetH);

        // rtsp_transport=tcp 走 ADB 转发；allowed_media_types=video 跳过空音频轨探测；
        // probesize 设为 256KB 避免 1080p 关键帧超出 32KB 探测限制；
        // fps_mode=passthrough 禁止 rawvideo 默认按流时基(90k tbr)倍增复制首帧导致画面卡死
        List<String> cmd = List.of(
                ffmpeg,
                "-hide_banner",
                "-loglevel", "error",
                "-rtsp_transport", "tcp",
                "-allowed_media_types", "video",
                "-fflags", "nobuffer",
                "-flags", "low_delay",
                "-probesize", "262144",
                "-analyzeduration", "800000",
                "-i", rtspUrl,
                "-an",
                "-vf", scalePad,
                "-fps_mode", "passthrough",
                "-pix_fmt", "bgr24",
                "-f", "rawvideo",
                "pipe:1");

        long peer = softcam.cameraHandlePeer();
        if (peer != 0 && PipeFeeder.isAvailable()
                && pipeFeeder.start(cmd, targetW, targetH, peer, softcam.loadedDllPath())) {
            receiverRunning = true;
            lastError = "";
            log.info("启动摄像头原生 8MB 管道拉流：{} -> {}x{}", rtspUrl, targetW, targetH);
            LogBuffer.get().info("CAMERA", "开始拉取手机摄像头流：" + rtspUrl + " -> " + targetW + "x" + targetH);
            notifyStatus();
            return;
        }

        try {
            Process proc = new ProcessBuilder(cmd).start();
            ffmpegProcess = proc;
            receiverRunning = true;
            lastError = "";
            log.info("启动摄像头解码拉流：{} -> {}x{}", rtspUrl, targetW, targetH);
            LogBuffer.get().info("CAMERA", "开始拉取手机摄像头流：" + rtspUrl + " -> " + targetW + "x" + targetH);

            Thread errThread = new Thread(() -> drainStderr(proc.getErrorStream()), "camera-ffmpeg-err");
            errThread.setDaemon(true);
            errThread.start();

            Thread reader = new Thread(() -> readFramesLoop(proc, targetW, targetH), "camera-ffmpeg-read");
            reader.setDaemon(true);
            ffmpegReaderThread = reader;
            reader.start();
            notifyStatus();
        } catch (IOException e) {
            receiverRunning = false;
            lastError = "启动 ffmpeg 失败：" + e.getMessage();
            log.warn(lastError);
        }
    }

    private void readFramesLoop(Process proc, int width, int height) {
        int frameBytes = width * height * 3;
        byte[] buffer = new byte[frameBytes];
        long statStartNs = System.nanoTime();
        int statFrames = 0;

        try (InputStream in = proc.getInputStream()) {
            while (!stopped && proc == ffmpegProcess) {
                if (!readFully(in, buffer, frameBytes)) {
                    break;
                }
                softcam.sendFrame(buffer);
                framesSent++;
                if (!lastError.isBlank()) {
                    lastError = "";
                }
                statFrames++;

                long nowNs = System.nanoTime();
                long elapsedNs = nowNs - statStartNs;
                if (elapsedNs >= 1_000_000_000L) {
                    actualFps = statFrames * 1_000_000_000.0 / elapsedNs;
                    statFrames = 0;
                    statStartNs = nowNs;
                    notifyStatus();
                }
            }
        } catch (IOException ignored) {
            // 停止或断流时退出循环
        } finally {
            if (proc == ffmpegProcess) {
                receiverRunning = false;
                ffmpegProcess = null;
                actualFps = 0;
            }
            proc.destroyForcibly();
            notifyStatus();
        }
    }

    private static boolean readFully(InputStream in, byte[] dst, int len) throws IOException {
        int offset = 0;
        while (offset < len) {
            int n = in.read(dst, offset, len - offset);
            if (n < 0) {
                return false;
            }
            offset += n;
        }
        return true;
    }

    private void drainStderr(InputStream err) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(err, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    lastError = trimmed;
                    log.debug("[camera-ffmpeg] {}", trimmed);
                }
            }
        } catch (IOException ignored) {
            // ignore
        }
    }

    private void stopReceiver() {
        pipeFeeder.stop();
        Process proc = ffmpegProcess;
        ffmpegProcess = null;
        receiverRunning = false;
        actualFps = 0;
        if (proc != null) {
            proc.destroyForcibly();
        }
        Thread reader = ffmpegReaderThread;
        ffmpegReaderThread = null;
        if (reader != null && reader != Thread.currentThread()) {
            try {
                reader.join(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 停止推流后发送一帧纯黑画面，避免微信/会议界面定格在挂断前最后一帧 */
    private void stopReceiverAndSendBlank() {
        stopReceiver();
        if (softcam.isOpen()) {
            int w = softcam.width();
            int h = softcam.height();
            if (w > 0 && h > 0) {
                softcam.sendFrame(new byte[w * h * 3]);
            }
        }
    }

    private void sendControl(String action, boolean force) {
        AppConfig.Camera cfg = cameraConfig();
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "camera_control");
        msg.addProperty("action", action);
        msg.addProperty("force", force);
        msg.addProperty("autoDemand", autoDemand);
        msg.addProperty("consumerConnected", consumerConnected);
        msg.addProperty("width", cfg.width);
        msg.addProperty("height", cfg.height);
        msg.addProperty("fps", cfg.fps);
        msg.addProperty("bitrateKbps", cfg.bitrateKbps);
        msg.addProperty("rtspPort", cfg.rtspPort);
        msg.addProperty("facing", facing);
        msg.addProperty("rotation", rotation);
        msg.addProperty("mirrorHorizontal", mirrorHorizontal);
        msg.addProperty("mirrorVertical", mirrorVertical);
        msg.addProperty("flash", flash);
        sink.sendControlToPhones(msg);
    }

    private void notifyStatus() {
        sink.broadcastCameraStatus(statusMap());
    }

    private AppConfig.Camera cameraConfig() {
        AppConfig app = configSupplier.get();
        return app != null && app.camera != null ? app.camera : new AppConfig.Camera();
    }

    private void syncPrefsFromConfig(AppConfig.Camera cam) {
        this.facing = "back".equalsIgnoreCase(cam.facing) ? "back" : "front";
        this.rotation = normalizeRotation(cam.rotation);
        this.mirrorHorizontal = cam.mirrorHorizontal;
        this.mirrorVertical = cam.mirrorVertical;
        this.autoDemand = cam.autoDemand;
    }

    private static int normalizeRotation(int deg) {
        int r = ((deg % 360) + 360) % 360;
        return (r / 90) * 90;
    }

    private String resolveFfmpegExecutable() {
        AppConfig app = configSupplier.get();
        String configured = app != null && app.screen != null ? app.screen.ffmpegExecutable : null;
        if (configured != null && !configured.isBlank() && !"ffmpeg".equalsIgnoreCase(configured.trim())) {
            return configured.trim();
        }
        for (Path p : List.of(
                Path.of("deps\\ffmpeg\\bin\\ffmpeg.exe"),
                Path.of("..\\deps\\ffmpeg\\bin\\ffmpeg.exe"))) {
            if (Files.isRegularFile(p)) {
                return p.toAbsolutePath().normalize().toString();
            }
        }
        return "ffmpeg";
    }
}
