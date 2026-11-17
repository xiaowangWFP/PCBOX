package com.phoneconsole.pc.screen;

import com.phoneconsole.pc.runtime.LogBuffer;
import org.java_websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * PC 桌面 H.264 推流：固定输出 1920x1080@60，采集源可选整块桌面（镜像）或单块显示器（扩展屏），
 * ffmpeg ddagrab / gdigrab 采集 + libx264 编码，输出 Annex-B 裸流，
 * 按访问单元（AUD 分隔）通过 WebSocket 二进制帧下发。
 * <p>
 * 多个订阅者共享同一个编码进程；每个订阅者从下一个 IDR 关键帧开始发送，
 * 发送队列积压时丢弃当前帧并等待下一个关键帧重新同步。
 * 每次启停使用独立的会话号，避免旧编码进程的残留帧或退出逻辑影响新会话。
 */
public final class H264ScreenStreamer {

    private static final Logger log = LoggerFactory.getLogger(H264ScreenStreamer.class);

    public static final int WIDTH = 1920;
    public static final int HEIGHT = 1080;
    public static final int FPS = 60;

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

    private final Supplier<String> ffmpegExecutable;
    /** 采集目标：-1 = 整块桌面，>=0 = 显示器序号 */
    private final IntSupplier monitor;
    private final Map<WebSocket, Subscriber> subscribers = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    private volatile boolean running;
    private volatile int activeSession = -1;
    private int sessionCounter;
    private volatile Process process;
    private Thread pumpThread;

    private long statsStart = System.nanoTime();
    private int statsFrames;
    private long statsBytes;

    public H264ScreenStreamer(Supplier<String> ffmpegExecutable, IntSupplier monitor) {
        this.ffmpegExecutable = ffmpegExecutable;
        this.monitor = monitor;
    }

    public boolean isRunning() {
        return running;
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

    private String executable() {
        String value = ffmpegExecutable.get();
        return value == null ? "" : value.trim();
    }

    private List<String> command() {
        int target = monitorIndex();
        List<String> cmd = new ArrayList<>();
        cmd.add(executable());
        cmd.addAll(List.of("-hide_banner", "-loglevel", "error"));
        if (target >= 0) {
            // 扩展屏：只抓指定显示器，ddagrab 输出 D3D11 硬件帧，软件编码前需下载回内存
            cmd.addAll(List.of("-f", "lavfi", "-i",
                    "ddagrab=output_idx=" + target + ":framerate=" + FPS + ":draw_mouse=1"));
            cmd.addAll(List.of("-vf", "hwdownload,format=bgra," + outputFilter()));
        } else {
            // 镜像：抓整块虚拟桌面（所有显示器）
            cmd.addAll(List.of("-f", "gdigrab", "-framerate", String.valueOf(FPS), "-i", "desktop"));
            cmd.add("-vf");
            cmd.add(outputFilter());
        }
        cmd.addAll(List.of(
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-tune", "zerolatency",
                "-profile:v", "baseline",
                "-level", "4.2",
                "-pix_fmt", "yuv420p",
                "-x264-params", "aud=1:repeat_headers=1:bframes=0",
                "-g", String.valueOf(FPS * 2),
                "-f", "h264",
                "pipe:1"));
        return cmd;
    }

    /** 等比缩放并用黑边补齐到 1920x1080，保证客户端始终按 16:9 解码 */
    private static String outputFilter() {
        return "scale=" + WIDTH + ":" + HEIGHT + ":force_original_aspect_ratio=decrease,"
                + "pad=" + WIDTH + ":" + HEIGHT + ":(ow-iw)/2:(oh-ih)/2:black,setsar=1";
    }

    private int monitorIndex() {
        try {
            int value = monitor.getAsInt();
            return Math.max(-1, value);
        } catch (Exception e) {
            return -1;
        }
    }

    private void pumpLoop(int session) {
        int rapidFailures = 0;
        while (isActive(session)) {
            Process current = null;
            long startedAt = System.currentTimeMillis();
            try {
                List<String> command = command();
                int target = monitorIndex();
                log.info("启动 H.264 屏幕编码：{}x{}@{}，采集={}，ffmpeg={}",
                        WIDTH, HEIGHT, FPS, target < 0 ? "整块桌面" : "显示器 " + target, command.get(0));
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

                LogBuffer.get().info("SCREEN", "H.264 编码器已启动（1920x1080@60）");
                readLoop(started.getInputStream(), session);
                int exitCode = started.waitFor();
                if (!isActive(session)) {
                    break;
                }
                long lived = System.currentTimeMillis() - startedAt;
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
