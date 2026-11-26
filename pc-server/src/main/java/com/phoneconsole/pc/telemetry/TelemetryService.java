package com.phoneconsole.pc.telemetry;

import com.google.gson.Gson;
import com.phoneconsole.pc.core.MediaController;
import com.phoneconsole.pc.protocol.TelemetryMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import oshi.SystemInfo;
import oshi.hardware.GraphicsCard;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 硬件状态采集：CPU、内存、实时上下行网速、系统音量，默认每秒推送一次。
 */
public class TelemetryService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);

    private final long intervalMs;
    private final Consumer<String> sink;
    private final MediaController media;
    private final GpuMonitor gpu = new GpuMonitor();
    private final DisplayMonitor displayMonitor = new DisplayMonitor();
    private final FpsMonitor fpsMonitor = new FpsMonitor();
    private volatile String selectedDisplayId;
    private final Gson gson = new Gson();
    private final com.sun.management.OperatingSystemMXBean osBean;
    private final HardwareAbstractionLayer hardware;
    /** 显存总量（MB），-1 表示无法读取；启动时算一次 */
    private final double vramTotalMb;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "telemetry");
        t.setDaemon(true);
        return t;
    });

    private long lastRecv = -1;
    private long lastSent = -1;
    private long lastTime = 0;
    /** 睡眠/长时间停顿检测：两次快照间隔超过该值就重建基准 */
    private static final long RESUME_GAP_MS = 5000;
    private long lastTickAt = 0;
    private double lastCpu = 0;
    private double lastGpu = -1;
    private int gpuMissCount = 0;

    public TelemetryService(long intervalMs, Consumer<String> sink, MediaController media) {
        this.intervalMs = intervalMs;
        this.sink = sink;
        this.media = media;

        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        this.osBean = bean instanceof com.sun.management.OperatingSystemMXBean sun ? sun : null;
        this.hardware = new SystemInfo().getHardware();
        this.vramTotalMb = detectVramTotalMb();
    }

    /** 多显卡时取显存最大的一块，避免核显共享内存拉偏总量 */
    private double detectVramTotalMb() {
        try {
            long max = 0;
            for (GraphicsCard card : hardware.getGraphicsCards()) {
                long bytes = card.getVRam();
                if (bytes > max) {
                    max = bytes;
                }
            }
            if (max <= 0) {
                return -1;
            }
            return Math.round(max / 1048576.0 * 10.0) / 10.0;
        } catch (Throwable t) {
            log.debug("显存总量读取失败：{}", t.getMessage());
            return -1;
        }
    }

    public void start() {
        fpsMonitor.start();
        scheduler.scheduleAtFixedRate(this::tick, 0, intervalMs, TimeUnit.MILLISECONDS);
        log.info("硬件状态采集已启动，间隔 {} ms", intervalMs);
    }

    public void stop() {
        scheduler.shutdownNow();
        fpsMonitor.stop();
        gpu.close();
    }

    private void tick() {
        try {
            sink.accept(gson.toJson(snapshot()));
        } catch (Exception e) {
            log.debug("遥测采集失败：{}", e.getMessage());
        }
    }

    /** 当前快照的 JSON：新连接建立时立即下发一帧，避免页面首秒无数据 */
    public String snapshotJson() {
        return gson.toJson(snapshot());
    }

    /**
     * 同步快照：定时 tick 与新连接首帧都会调用，必须串行，
     * 否则网络基准会被并发改写，导致网速尖峰/归零。
     */
    public synchronized TelemetryMessage snapshot() {
        long now = System.currentTimeMillis();
        boolean resumed = lastTickAt > 0 && now - lastTickAt > RESUME_GAP_MS;
        lastTickAt = now;
        if (resumed) {
            // 睡眠或长时间卡顿：网络计数器、PDH GPU 查询都重新起算
            lastRecv = -1;
            lastSent = -1;
            lastTime = 0;
            gpu.reset();
            log.info("检测到休眠/长时间停顿，已重置遥测基准");
        }

        double prevCpu = lastCpu;
        double prevGpu = lastGpu;

        TelemetryMessage message = new TelemetryMessage();
        message.cpu = resumed ? prevCpu : cpuUsage();
        message.mem = memoryUsage();
        message.gpu = resumed ? prevGpu : gpuUsage();
        message.vram = gpu.vramUsedMb();
        message.vramTotal = vramTotalMb;
        double[] speed = networkSpeed();
        message.netUp = formatSpeed(speed[0]);
        message.netDown = formatSpeed(speed[1]);
        message.volume = media.volume();
        message.isMuted = media.isMuted();
        message.micMuted = media.isMicMuted();

        List<DisplayMonitor.DisplayInfo> displays = displayMonitor.list();
        message.displays = displays;
        DisplayMonitor.DisplayInfo current = displayMonitor.find(selectedDisplayId, displays);
        if (current != null) {
            message.displayId = current.id;
            message.displayName = current.name;
            // 部分虚拟显示器会返回 0/1，视为不可用
            message.displayHz = current.hz >= 20 ? current.hz : -1;
        }
        FpsMonitor.Sample fpsSample = fpsMonitor.sample();
        message.fps = fpsSample.fps;
        message.fpsSource = fpsSample.source;
        return message;
    }

    /** 手机端选择要显示的显示器；空字符串表示回到主屏 */
    public void selectDisplay(String displayId) {
        this.selectedDisplayId = displayId;
    }

    private double cpuUsage() {
        if (osBean == null) {
            return lastCpu;
        }
        double load = osBean.getCpuLoad();
        if (load < 0) {
            // 采样间隔过短或睡眠恢复时可能返回 -1，保留上次值，避免图表掉到 0
            return lastCpu;
        }
        lastCpu = Math.round(load * 1000.0) / 10.0;
        return lastCpu;
    }

    /** GPU 采样失败时保留上次值，连续 3 次失败才显示 — */
    private double gpuUsage() {
        double value = gpu.usage();
        if (value < 0) {
            gpuMissCount++;
            return gpuMissCount >= 3 ? -1 : lastGpu;
        }
        gpuMissCount = 0;
        lastGpu = value;
        return value;
    }

    private double memoryUsage() {
        if (osBean == null) {
            return 0;
        }
        long total = osBean.getTotalMemorySize();
        if (total <= 0) {
            return 0;
        }
        long used = total - osBean.getFreeMemorySize();
        return Math.round(used * 1000.0 / total) / 10.0;
    }

    /** @return [上行字节/秒, 下行字节/秒] */
    private double[] networkSpeed() {
        long recv = 0;
        long sent = 0;
        try {
            List<NetworkIF> interfaces = hardware.getNetworkIFs();
            for (NetworkIF nif : interfaces) {
                String name = nif.getName() == null ? "" : nif.getName().toLowerCase(Locale.ROOT);
                if (name.startsWith("lo")) {
                    continue;
                }
                nif.updateAttributes();
                recv += nif.getBytesRecv();
                sent += nif.getBytesSent();
            }
        } catch (Exception e) {
            log.debug("网卡采样失败：{}", e.getMessage());
            return new double[] {0, 0};
        }

        long now = System.currentTimeMillis();
        if (lastTime == 0 || recv < lastRecv || sent < lastSent) {
            lastRecv = recv;
            lastSent = sent;
            lastTime = now;
            return new double[] {0, 0};
        }
        double seconds = (now - lastTime) / 1000.0;
        double up = seconds <= 0 ? 0 : (sent - lastSent) / seconds;
        double down = seconds <= 0 ? 0 : (recv - lastRecv) / seconds;

        lastRecv = recv;
        lastSent = sent;
        lastTime = now;
        return new double[] {Math.max(0, up), Math.max(0, down)};
    }

    private String formatSpeed(double bytesPerSecond) {
        if (bytesPerSecond < 1024) {
            return Math.round(bytesPerSecond) + " B/s";
        }
        if (bytesPerSecond < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.0f KB/s", bytesPerSecond / 1024);
        }
        return String.format(Locale.ROOT, "%.1f MB/s", bytesPerSecond / 1024 / 1024);
    }
}
