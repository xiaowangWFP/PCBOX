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
        scheduler.scheduleAtFixedRate(this::tick, 0, intervalMs, TimeUnit.MILLISECONDS);
        log.info("硬件状态采集已启动，间隔 {} ms", intervalMs);
    }

    public void stop() {
        scheduler.shutdownNow();
        gpu.close();
    }

    private void tick() {
        try {
            sink.accept(gson.toJson(snapshot()));
        } catch (Exception e) {
            log.debug("遥测采集失败：{}", e.getMessage());
        }
    }

    public TelemetryMessage snapshot() {
        TelemetryMessage message = new TelemetryMessage();
        message.cpu = cpuUsage();
        message.mem = memoryUsage();
        message.gpu = gpu.usage();
        message.vram = gpu.vramUsedMb();
        message.vramTotal = vramTotalMb;
        double[] speed = networkSpeed();
        message.netUp = formatSpeed(speed[0]);
        message.netDown = formatSpeed(speed[1]);
        message.volume = media.volume();
        message.isMuted = media.isMuted();
        return message;
    }

    private double cpuUsage() {
        if (osBean == null) {
            return 0;
        }
        double load = osBean.getCpuLoad();
        if (load < 0) {
            return 0;
        }
        return Math.round(load * 1000.0) / 10.0;
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
