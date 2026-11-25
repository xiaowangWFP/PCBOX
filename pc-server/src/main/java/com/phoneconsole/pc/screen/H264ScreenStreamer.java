package com.phoneconsole.pc.screen;

import com.phoneconsole.pc.config.AppConfig;
import com.phoneconsole.pc.runtime.LogBuffer;
import org.java_websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * PC 桌面 H.264 推流：帧率、输出分辨率上限、缩放算法、x264 预设与码率全部来自 config.json。
 * 输出尺寸按采集源宽高比等比缩放到像素预算内（不加黑边）；采集源可选整块桌面或单块显示器，
 * 单块屏统一从 GDI 合成桌面按物理坐标裁剪，避免 ddagrab 的 output_idx 与界面屏序号不一致导致抓错屏。
 * 采集分辨率与输出分辨率一致时跳过 scale 滤镜，省掉一次 CPU 缩放。
 * libx264 编码后输出 Annex-B 裸流，按访问单元（AUD 分隔）通过 WebSocket 二进制帧下发。
 * <p>
 * 多个订阅者共享同一个编码进程；每个订阅者从下一个 IDR 关键帧开始发送，
 * 发送队列积压时丢弃当前帧并等待下一个关键帧重新同步。
 * 每次启停使用独立的会话号，避免旧编码进程的残留帧或退出逻辑影响新会话。
 */
public final class H264ScreenStreamer {

    private static final Logger log = LoggerFactory.getLogger(H264ScreenStreamer.class);

    public static final int DEFAULT_FPS = 30;
    /** 配置缺失时的兜底参数（与 AppConfig.Screen 默认值保持一致） */
    private static final AppConfig.Screen FALLBACK = new AppConfig.Screen();
    private static final String FALLBACK_SCALE = "fast_bilinear";
    private static final String FALLBACK_PRESET = "ultrafast";

    /** 每秒统计：实际编码帧率与码率 */
    public record Stats(double actualFps, int kbps) {
    }

    /** 订阅者出口；onAccessUnit 返回 false 表示本帧未发送，需要等下一个关键帧 */
    public interface Sink {
        boolean onAccessUnit(byte[] data, boolean keyframe);

        void onStats(Stats stats);

        /** 编码器不可用，reason 回传给客户端 */
        void onClosed(String reason);
    }

    private static final class Subscriber {
        final Sink sink;
        volatile boolean synced;

        Subscriber(Sink sink) {
            this.sink = sink;
        }
    }

    /** 推流参数快照来源：每次拉起编码器时读取，配置热重载后重启编码器即可生效 */
    private final Supplier<AppConfig.Screen> screenConfig;
    /** 采集目标：-1 = 整块桌面，>=0 = 显示器序号 */
    private final IntSupplier monitor;
    private final ScreenTargets targets;
    /** 编码帧率：USB / LAN 各自的上限由调用方按 config.json 决定 */
    private final int fps;
    private final Map<WebSocket, Subscriber> subscribers = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    private volatile boolean running;
    private volatile int activeSession = -1;
    private int sessionCounter;
    private volatile Process process;
    private Thread pumpThread;
    /** 采集通道降级：0 = gdigrab 按屏裁剪，1 = gdigrab 整块桌面 */
    private volatile int captureMode;
    /** 最近一次请求的显示器序号，切换后清除降级状态 */
    private volatile int lastRequestedMonitor = Integer.MIN_VALUE;

    private long statsStart = System.nanoTime();
    private int statsFrames;
    private long statsBytes;
    /** 当前编码输出尺寸，按采集源的宽高比等比缩放，避免左右/上下黑边 */
    private volatile int outputWidth = FALLBACK.maxWidth;
    private volatile int outputHeight = FALLBACK.maxHeight;

    public H264ScreenStreamer(Supplier<AppConfig.Screen> screenConfig,
                              IntSupplier monitor, ScreenTargets targets, int fps) {
        this.screenConfig = screenConfig;
        this.monitor = monitor;
        this.targets = targets;
        this.fps = fps <= 0 ? DEFAULT_FPS : fps;
    }

    /** 当前编码宽度（已按采集源比例换算，可能小于 maxWidth） */
    public int width() {
        return outputWidth;
    }

    /** 当前编码高度 */
    public int height() {
        return outputHeight;
    }

    public int fps() {
        return fps;
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    /** 订阅；首个订阅者会拉起编码进程。返回是否成功开始 */
    public boolean subscribe(WebSocket conn, Sink sink) {
        synchronized (lock) {
            subscribers.put(conn, new Subscriber(sink));
            if (running) {
                return true;
            }
            if (executable().isBlank()) {
                subscribers.remove(conn);
                return false;
            }
            // 先同步算出目标分辨率，保证调用方紧接着下发的 screen_state 尺寸准确
            int target = monitorIndex();
            int[] initialSize = outputSize(target);
            outputWidth = initialSize[0];
            outputHeight = initialSize[1];

            statsStart = System.nanoTime();
            statsFrames = 0;
            statsBytes = 0;
            running = true;
            activeSession = ++sessionCounter;
            int session = activeSession;
            pumpThread = new Thread(() -> pumpLoop(session), "screen-h264-pump");
            pumpThread.setDaemon(true);
            pumpThread.start();
            return true;
        }
    }

    public void unsubscribe(WebSocket conn) {
        synchronized (lock) {
            subscribers.remove(conn);
            if (subscribers.isEmpty()) {
                stopLocked();
            }
        }
    }

    public void stop() {
        synchronized (lock) {
            subscribers.clear();
            stopLocked();
        }
    }

    /** 只销毁当前编码进程，保持 running 状态，pumpLoop 会按最新 monitor 重启 */
    public void restartEncoder() {
        Process current = process;
        if (current != null) {
            current.destroyForcibly();
        }
    }

    private void stopLocked() {
        running = false;
        activeSession = -1;
        Process current = process;
        process = null;
        if (current != null) {
            current.destroyForcibly();
        }
        Thread thread = pumpThread;
        pumpThread = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private boolean isActive(int session) {
        return running && session == activeSession;
    }

    /** 当前推流参数快照；配置缺失或读取失败时退回内置默认值 */
    private AppConfig.Screen screen() {
        try {
            AppConfig.Screen value = screenConfig == null ? null : screenConfig.get();
            return value == null ? FALLBACK : value;
        } catch (Exception e) {
            return FALLBACK;
        }
    }

    private String executable() {
        String value = screen().ffmpegExecutable;
        return value == null ? "" : value.trim();
    }

    private List<String> command(int target, int mode, int width, int height) {
        AppConfig.Screen screen = screen();
        List<String> cmd = new ArrayList<>();
        cmd.add(executable());
        cmd.addAll(List.of("-hide_banner", "-loglevel", "error"));
        Rectangle area = target >= 0 ? physicalBounds(target) : null;
        boolean localCapture = target >= 0 && mode == 0 && area != null;
        if (localCapture) {
            // 单块屏：从 GDI 合成桌面里按物理坐标裁剪。
            // 不用 ddagrab：它的 output_idx 只按 DXGI 输出顺序编号，和 AWT 的屏序号对不上，
            // 多屏/虚拟屏时会把「显示器 2」抓成主屏。
            cmd.addAll(List.of("-f", "gdigrab", "-framerate", String.valueOf(fps),
                    "-offset_x", String.valueOf(area.x),
                    "-offset_y", String.valueOf(area.y),
                    "-video_size", area.width + "x" + area.height,
                    "-i", "desktop"));
        } else {
            // 镜像：抓整块虚拟桌面（所有显示器）
            cmd.addAll(List.of("-f", "gdigrab", "-framerate", String.valueOf(fps), "-i", "desktop"));
        }
        // 采集尺寸与输出尺寸一致时直接跳过 scale，省掉一次 CPU 缩放
        Rectangle source = localCapture ? area : desktopBounds();
        boolean needScale = source == null || source.width != width || source.height != height;
        if (needScale) {
            cmd.add("-vf");
            cmd.add(outputFilter(width, height, screen.scaleFlags));
        }
        int bitrate = Math.max(200, screen.maxBitrateKbps);
        String preset = screen.x264Preset == null || screen.x264Preset.isBlank()
                ? FALLBACK_PRESET : screen.x264Preset;
        cmd.addAll(List.of(
                "-c:v", "libx264",
                "-preset", preset,
                "-tune", "zerolatency",
                "-profile:v", "baseline",
                "-level", "4.2",
                // 限制峰值码率，避免关键帧在无线链路上把带宽打满造成卡顿
                "-maxrate", bitrate + "k",
                "-bufsize", (bitrate * 2) + "k",
                "-pix_fmt", "yuv420p",
                "-x264-params", "aud=1:repeat_headers=1:bframes=0",
                "-g", String.valueOf(fps * 2)));
        if (screen.x264Threads > 0) {
            // 限制软编线程数，避免多屏/高帧率时把前台应用挤掉
            cmd.addAll(List.of("-threads", String.valueOf(screen.x264Threads)));
        }
        cmd.addAll(List.of("-f", "h264", "pipe:1"));
        return cmd;
    }

    /** 按采集源宽高比直接缩放到目标尺寸，不补黑边，客户端才能铺满屏幕 */
    private static String outputFilter(int width, int height, String flags) {
        String value = flags == null || flags.isBlank() ? FALLBACK_SCALE : flags;
        return "scale=" + width + ":" + height + ":flags=" + value + ",setsar=1";
    }

    /** 按采集源比例算出输出尺寸：限制在 maxWidth x maxHeight 的像素预算内，宽高取偶数（x264 要求） */
    private int[] outputSize(int target) {
        AppConfig.Screen screen = screen();
        int maxWidth = Math.max(2, screen.maxWidth & ~1);
        int maxHeight = Math.max(2, screen.maxHeight & ~1);
        Rectangle source = target >= 0 ? physicalBounds(target) : desktopBounds();
        if (source == null || source.width <= 1 || source.height <= 1) {
            return new int[]{maxWidth, maxHeight};
        }
        double scale = Math.min(1.0,
                Math.min((double) maxWidth / source.width, (double) maxHeight / source.height));
        int width = Math.max(2, ((int) Math.round(source.width * scale)) & ~1);
        int height = Math.max(2, ((int) Math.round(source.height * scale)) & ~1);
        return new int[]{width, height};
    }

    /** 整块桌面 = 所有显示器物理区域的并集 */
    private Rectangle desktopBounds() {
        if (targets == null) {
            return null;
        }
        Rectangle union = null;
        for (ScreenTargets.Target target : targets.list()) {
            Rectangle area = target.physical();
            if (area == null || area.width <= 0 || area.height <= 0) {
                continue;
            }
            union = union == null ? new Rectangle(area) : union.union(area);
        }
        return union;
    }

    private int monitorIndex() {
        try {
            int value = monitor.getAsInt();
            return Math.max(-1, value);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 显示器物理像素区域；枚举失败时返回 null，调用方退回整块桌面 */
    private Rectangle physicalBounds(int target) {
        ScreenTargets.Target screen = targets == null ? null : targets.get(target);
        return screen == null ? null : screen.physical();
    }

    private String describe(int target, int mode) {
        if (target < 0 || mode >= 1 || physicalBounds(target) == null) {
            return "整块桌面（gdigrab）";
        }
        return "显示器 " + target + "（gdigrab 裁剪）";
    }

    private void pumpLoop(int session) {
        int rapidFailures = 0;
        while (isActive(session)) {
            Process current = null;
            long startedAt = System.currentTimeMillis();
            try {
                int target = monitorIndex();
                if (target != lastRequestedMonitor) {
                    lastRequestedMonitor = target;
                    captureMode = 0;
                }
                int mode = captureMode;
                int[] size = outputSize(target);
                outputWidth = size[0];
                outputHeight = size[1];
                List<String> command = command(target, mode, size[0], size[1]);
                AppConfig.Screen settings = screen();
                log.info("启动 H.264 屏幕编码：{}x{}@{}，采集={}，缩放={}，预设={}，码率={}k，ffmpeg={}",
                        size[0], size[1], fps, describe(target, mode),
                        settings.scaleFlags, settings.x264Preset, settings.maxBitrateKbps, command.get(0));
                Process started = new ProcessBuilder(command)
                        .redirectErrorStream(false)
                        .start();
                if (!isActive(session)) {
                    started.destroyForcibly();
                    break;
                }
                current = started;
                process = started;

                Thread logThread = new Thread(() -> drainErrors(started), "screen-h264-log");
                logThread.setDaemon(true);
                logThread.start();

                LogBuffer.get().info("SCREEN", "H.264 编码器已启动（" + size[0] + "x" + size[1] + "@" + fps + "）");
                readLoop(started.getInputStream(), session);
                int exitCode = started.waitFor();
                if (!isActive(session)) {
                    break;
                }
                long lived = System.currentTimeMillis() - startedAt;
                // 单屏裁剪失败（坐标越界等）时退回整块桌面，避免整个推流不可用
                if (lived < 2000 && target >= 0 && mode < 1) {
                    captureMode = 1;
                    rapidFailures = 0;
                    log.warn("显示器 {} 裁剪采集失败，改用整块桌面", target);
                    LogBuffer.get().warn("SCREEN", "显示器 " + target + " 裁剪采集失败，改用整块桌面");
                    markAllUnsynced();
                    Thread.sleep(1000);
                    continue;
                }
                rapidFailures = lived < 2000 ? rapidFailures + 1 : 0;
                log.warn("H.264 编码器退出（code={}），1 秒后重启", exitCode);
                LogBuffer.get().warn("SCREEN", "H.264 编码器退出（code=" + exitCode + "），1 秒后重启");
                markAllUnsynced();
                if (rapidFailures >= 3) {
                    failAll("encoder_error", session);
                    break;
                }
                Thread.sleep(1000);
            } catch (IOException e) {
                if (!isActive(session)) {
                    break;
                }
                String message = "无法启动 H.264 编码器（" + executable() + "）：" + e.getMessage();
                log.warn(message);
                LogBuffer.get().error("SCREEN", message);
                failAll("encoder_unavailable", session);
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } finally {
                if (process == current) {
                    process = null;
                }
                if (current != null && current.isAlive()) {
                    current.destroyForcibly();
                }
            }
        }
    }

    private void readLoop(InputStream input, int session) {
        AnnexBParser parser = new AnnexBParser(unit -> onAccessUnit(unit, session));
        byte[] buffer = new byte[64 * 1024];
        try {
            int read;
            while (isActive(session) && (read = input.read(buffer)) > 0) {
                parser.feed(buffer, 0, read);
            }
        } catch (IOException e) {
            if (isActive(session)) {
                log.debug("读取编码流结束：{}", e.getMessage());
            }
        }
    }

    private void onAccessUnit(AccessUnit unit, int session) {
        if (!isActive(session)) {
            return;
        }
        if (unit.keyframe() && (!unit.hasSps() || !unit.hasPps())) {
            log.debug("关键帧缺少 SPS/PPS，客户端可能无法解码首帧");
        }
        for (Map.Entry<WebSocket, Subscriber> entry : subscribers.entrySet()) {
            Subscriber subscriber = entry.getValue();
            if (!subscriber.synced && !unit.keyframe()) {
                continue;
            }
            boolean sent;
            try {
                sent = subscriber.sink.onAccessUnit(unit.data(), unit.keyframe());
            } catch (Exception e) {
                sent = false;
            }
            subscriber.synced = sent;
        }

        long now = System.nanoTime();
        statsFrames++;
        statsBytes += unit.data().length;
        long elapsed = now - statsStart;
        if (elapsed >= 1_000_000_000L) {
            double seconds = elapsed / 1_000_000_000.0;
            Stats stats = new Stats(
                    Math.round(statsFrames / seconds * 10.0) / 10.0,
                    (int) Math.round(statsBytes / 1024.0 / seconds));
            for (Subscriber subscriber : subscribers.values()) {
                try {
                    subscriber.sink.onStats(stats);
                } catch (Exception e) {
                    log.debug("推流统计回调失败：{}", e.getMessage());
                }
            }
            statsFrames = 0;
            statsBytes = 0;
            statsStart = now;
        }
    }

    private void markAllUnsynced() {
        for (Subscriber subscriber : subscribers.values()) {
            subscriber.synced = false;
        }
    }

    private void failAll(String reason, int session) {
        List<Sink> sinks = new ArrayList<>();
        synchronized (lock) {
            if (session != activeSession) {
                return;
            }
            running = false;
            activeSession = -1;
            process = null;
            for (Subscriber subscriber : subscribers.values()) {
                sinks.add(subscriber.sink);
            }
            subscribers.clear();
        }
        for (Sink sink : sinks) {
            try {
                sink.onClosed(reason);
            } catch (Exception ignored) {
                // 客户端可能已断开
            }
        }
    }

    private void drainErrors(Process current) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(current.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    log.warn("H.264 编码器：{}", line);
                    // 同步到运行日志，网页控制台才能看到真实失败原因
                    LogBuffer.get().warn("SCREEN", "ffmpeg：" + line);
                }
            }
        } catch (IOException ignored) {
            // 进程结束时流关闭属正常情况
        }
    }

    private record AccessUnit(byte[] data, boolean keyframe, boolean hasSps, boolean hasPps) {
    }

    /**
     * Annex-B 增量拆包：按 AUD（NAL type 9）切分访问单元。
     * 每个 AU 以 AUD 开头，包含 SPS/PPS（x264 repeat_headers）与若干 slice。
     */
    private static final class AnnexBParser {

        private final Consumer<AccessUnit> consumer;
        private byte[] buffer = new byte[256 * 1024];
        private int length;
        private int nalStart;
        private int zeros;
        private boolean inHeader;
        private boolean hasIdr;
        private boolean hasSps;
        private boolean hasPps;

        AnnexBParser(Consumer<AccessUnit> consumer) {
            this.consumer = consumer;
        }

        void feed(byte[] data, int offset, int count) {
            for (int i = 0; i < count; i++) {
                byte value = data[offset + i];
                append(value);

                if (value == 0) {
                    zeros++;
                    continue;
                }
                if (value == 1 && zeros >= 2) {
                    nalStart = length - zeros - 1;
                    inHeader = true;
                    zeros = 0;
                    continue;
                }
                if (inHeader) {
                    inHeader = false;
                    int type = value & 0x1f;
                    if (type == 9 && nalStart > 0) {
                        emit(0, nalStart);
                        System.arraycopy(buffer, nalStart, buffer, 0, length - nalStart);
                        length -= nalStart;
                        nalStart = 0;
                    }
                    switch (type) {
                        case 5 -> hasIdr = true;
                        case 7 -> hasSps = true;
                        case 8 -> hasPps = true;
                        default -> {
                        }
                    }
                }
                zeros = 0;
            }
        }

        private void append(byte value) {
            if (length == buffer.length) {
                buffer = Arrays.copyOf(buffer, buffer.length * 2);
            }
            buffer[length++] = value;
        }

        private void emit(int from, int to) {
            int size = to - from;
            if (size > 0) {
                consumer.accept(new AccessUnit(
                        Arrays.copyOfRange(buffer, from, to), hasIdr, hasSps, hasPps));
            }
            hasIdr = false;
            hasSps = false;
            hasPps = false;
        }
    }
}
