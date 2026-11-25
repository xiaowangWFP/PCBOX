package com.phoneconsole.pc.net;

import com.phoneconsole.pc.config.AppConfig;
import com.phoneconsole.pc.runtime.LogBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * ADB 守护线程：轮询 USB 设备状态，发现设备接入/变化时自动补发端口映射。
 * 命令：adb reverse tcp:8500 tcp:8500
 * <p>
 * 设备寻址做了冗余：默认用稳定序列号（-s），失败时自动改用 transport_id（-t）重试；
 * 序列号在多传输下重复时优先用 transport_id。最近一次成功的寻址方式会按设备记住。
 */
public class AdbPortForwarder {

    private static final Logger log = LoggerFactory.getLogger(AdbPortForwarder.class);
    private static final long COMMAND_TIMEOUT_SECONDS = 5;
    private static final String SELECTOR_SERIAL = "serial";
    private static final String SELECTOR_TRANSPORT = "transport";

    private volatile AppConfig.Network network;
    private final int port;
    private volatile int forwardPort;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "adb-watchdog");
        t.setDaemon(true);
        return t;
    });

    /** adb 命令串行执行，避免守护线程与手动重建同时操作设备 */
    private final Object adbLock = new Object();
    /** 每个序列号最近一次成功的寻址方式，用于下次优先选择 */
    private final Map<String, String> selectorBySerial = new ConcurrentHashMap<>();

    /** 上一次成功映射的设备组合（序列号 + transport_id），用于去重 */
    private String lastMapped = "";
    private boolean adbMissing;

    private volatile Status status;

    /** 网页控制台展示的 ADB 状态快照 */
    public static final class Status {
        public boolean adbAvailable;
        public String executable = "adb";
        public List<String> devices = List.of();
        public List<Device> deviceDetails = List.of();
        public List<String> mappings = List.of();
        public long checkedAt;
        public String message = "";

        Status(String executable) {
            this.executable = executable;
        }
    }

    /** 单个 USB 设备：序列号与 transport_id 互为冗余地址 */
    public static final class Device {
        public String serial = "";
        public String transportId = "";
        public String state = "";
        public String model = "";
        /** serial | transport，最近一次成功使用的寻址方式 */
        public String selector = SELECTOR_SERIAL;
    }

    public AdbPortForwarder(AppConfig.Network network, int wsPort) {
        this(network, wsPort, 0);
    }

    public AdbPortForwarder(AppConfig.Network network, int wsPort, int forwardPort) {
        this.network = network;
        this.port = wsPort;
        this.forwardPort = Math.max(0, forwardPort);
        this.status = new Status(network != null ? network.adbExecutable : "adb");
    }

    /** 配置热重载后更新 adb 路径等参数（轮询间隔需重启生效） */
    public void setNetwork(AppConfig.Network network) {
        if (network != null) {
            this.network = network;
        }
    }

    /** 设置或更新需要自动维持的 adb forward 端口（用于虚拟摄像头 RTSP，<=0 表示禁用） */
    public void setForwardPort(int forwardPort) {
        int normalized = Math.max(0, forwardPort);
        if (this.forwardPort != normalized) {
            this.forwardPort = normalized;
            this.lastMapped = "";
        }
    }

    private AppConfig.Network net() {
        AppConfig.Network current = network;
        return current != null ? current : new AppConfig.Network();
    }

    public void start() {
        long interval = Math.max(1000L, net().reversePollMs);
        scheduler.scheduleWithFixedDelay(this::tick, 500, interval, TimeUnit.MILLISECONDS);
        log.info("ADB 守护线程已启动，轮询间隔 {} ms", interval);
        LogBuffer.get().info("ADB", "守护线程已启动，轮询间隔 " + interval + " ms");
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    /** 转成接口返回用的 Map，字段名与前端约定一致 */
    public Map<String, Object> statusMap() {
        Status snapshot = status;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("available", snapshot.adbAvailable);
        map.put("executable", snapshot.executable);
        map.put("devices", snapshot.devices);
        map.put("deviceDetails", snapshot.deviceDetails);
        map.put("mappings", snapshot.mappings);
        map.put("checkedAt", snapshot.checkedAt);
        map.put("message", snapshot.message);
        map.put("port", port);
        map.put("forwardPort", forwardPort);
        map.put("redundancy", "serial+transport_id");
        return map;
    }

    private void tick() {
        try {
            synchronized (adbLock) {
                Result devicesResult = run(net().adbExecutable, "devices", "-l");
                if (!devicesResult.ok()) {
                    publishStatus(List.of(), List.of(), false, firstLine(devicesResult.output, "adb 不可用"));
                    lastMapped = "";
                    return;
                }

                List<Device> details = parseDeviceDetails(devicesResult.output);
                List<Device> online = onlineDevices(details);
                if (online.isEmpty()) {
                    publishStatus(details, List.of(), true, "未检测到已授权设备");
                    lastMapped = "";
                    return;
                }

                String joined = mappingKey(online);
                boolean previouslyMapped = joined.equals(lastMapped);
                List<String> mappings = new ArrayList<>();
                boolean allOk = true;
                int fwd = forwardPort;

                for (Device device : online) {
                    if (!ensureMapping(device, "reverse", port, previouslyMapped, mappings)) {
                        allOk = false;
                    }
                    if (fwd > 0 && !ensureMapping(device, "forward", fwd, previouslyMapped, mappings)) {
                        allOk = false;
                    }
                }

                lastMapped = allOk ? joined : "";
                publishStatus(details, mappings, true, allOk ? "" : "端口映射存在失败，下个周期重试");
            }
        } catch (Throwable t) {
            log.debug("ADB 轮询异常：{}", t.getMessage());
        }
    }

    private boolean ensureMapping(Device device, String mode, int targetPort,
                                  boolean previouslyMapped, List<String> mappings) {
        boolean isForward = "forward".equals(mode);
        String verbLabel = isForward ? "正向端口映射" : "有线端口映射";
        Result list = runOn(device, mode, "--list");
        if (list.ok() && hasPortMapping(list.output(), targetPort, isForward ? device.serial : null)) {
            appendMappingLines(mappings, device.serial, mode, list.output());
            return true;
        }

        Result applied = runOn(device, mode, "tcp:" + targetPort, "tcp:" + targetPort);
        if (applied.ok()) {
            String verb = previouslyMapped ? "检测到" + verbLabel + "丢失，已自动重建" : "已建立" + verbLabel;
            log.info("{} [{}]：{} tcp:{}（{} 寻址）", verb, device.serial, mode, targetPort, selectorLabel(device));
            LogBuffer.get().info("ADB", verb + " [" + device.serial + "] " + mode + " tcp:" + targetPort
                    + "（" + selectorLabel(device) + "）");
            Result refreshed = runOn(device, mode, "--list");
            if (refreshed.ok()) {
                appendMappingLines(mappings, device.serial, mode, refreshed.output());
            } else {
                mappings.add(device.serial + " " + mode + " tcp:" + targetPort + " tcp:" + targetPort);
            }
            return true;
        }

        log.warn("{}失败 [{}]：{} tcp:{}，{}", verbLabel, device.serial, mode, targetPort,
                firstLine(applied.output, "未知错误"));
        LogBuffer.get().warn("ADB", verbLabel + "失败 [" + device.serial + "] " + mode + " tcp:" + targetPort
                + "：" + firstLine(applied.output, "未知错误"));
        return false;
    }

    private void appendMappingLines(List<String> out, String serial, String mode, String rawOutput) {
        boolean isForward = "forward".equals(mode);
        for (String line : parseLines(rawOutput)) {
            if (isForward) {
                // adb forward --list 返回包含全部设备的映射，过滤出当前设备并去重序列号前缀
                if (!line.startsWith(serial + " ")) {
                    continue;
                }
                out.add(serial + " forward " + line.substring(serial.length()).trim());
            } else {
                out.add(serial + " " + line);
            }
        }
    }

    private static boolean hasPortMapping(String output, int port, String requiredSerialPrefix) {
        if (output == null || output.isBlank()) {
            return false;
        }
        String needle = "tcp:" + port;
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (requiredSerialPrefix != null && !trimmed.startsWith(requiredSerialPrefix + " ")) {
                continue;
            }
            for (String token : trimmed.split("\\s+")) {
                if (needle.equals(token)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 手动检测：读取设备列表（含 transport_id）与每个设备的已有 reverse 映射 */
    public Status probe() {
        synchronized (adbLock) {
            AppConfig.Network net = net();
            Status snapshot = new Status(net.adbExecutable);
            snapshot.checkedAt = System.currentTimeMillis();

            Result devices = run(net.adbExecutable, "devices", "-l");
            if (!devices.ok()) {
                adbMissing = devices.unavailable();
                snapshot.message = firstLine(devices.output, "adb 不可用");
                status = snapshot;
                LogBuffer.get().warn("ADB", "设备检测失败：" + snapshot.message);
                return snapshot;
            }

            adbMissing = false;
            List<Device> details = parseDeviceDetails(devices.output);
            snapshot.adbAvailable = true;
            snapshot.deviceDetails = List.copyOf(details);
            snapshot.devices = List.copyOf(onlineSerials(details));
            List<String> mappingNotes = new ArrayList<>();
            snapshot.mappings = List.copyOf(readMappings(details, mappingNotes));
            if (snapshot.devices.isEmpty()) {
                mappingNotes.add("未检测到已授权设备");
            }
            snapshot.message = String.join("；", mappingNotes);
            status = snapshot;
            LogBuffer.get().info("ADB", "检测完成：设备 " + snapshot.devices.size()
                    + " 台，映射 " + snapshot.mappings.size() + " 条");
            return snapshot;
        }
    }

    /** 手动重建：对在线设备重新执行 adb reverse（serial / transport_id 双寻址），并回读映射 */
    public Status rebuild() {
        synchronized (adbLock) {
            AppConfig.Network net = net();
            Status snapshot = new Status(net.adbExecutable);
            snapshot.checkedAt = System.currentTimeMillis();

            Result devices = run(net.adbExecutable, "devices", "-l");
            if (!devices.ok()) {
                adbMissing = devices.unavailable();
                snapshot.message = firstLine(devices.output, "adb 不可用");
                status = snapshot;
                LogBuffer.get().warn("ADB", "手动重建失败：" + snapshot.message);
                return snapshot;
            }

            adbMissing = false;
            List<Device> details = parseDeviceDetails(devices.output);
            List<Device> online = onlineDevices(details);
            snapshot.adbAvailable = true;
            snapshot.deviceDetails = List.copyOf(details);
            snapshot.devices = List.copyOf(onlineSerials(details));
            if (online.isEmpty()) {
                snapshot.message = "未检测到已授权设备，请检查数据线与 USB 调试开关";
                status = snapshot;
                LogBuffer.get().warn("ADB", "手动重建跳过：" + snapshot.message);
                return snapshot;
            }

            List<String> notes = new ArrayList<>();
            boolean allOk = true;
            int fwd = forwardPort;
            for (Device device : online) {
                Result reverse = runOn(device, "reverse", "tcp:" + port, "tcp:" + port);
                if (reverse.ok()) {
                    notes.add(device.serial + " 反向映射成功（" + selectorLabel(device) + "）");
                } else {
                    allOk = false;
                    notes.add(device.serial + " 反向映射失败：" + firstLine(reverse.output, "未知错误"));
                }
                if (fwd > 0) {
                    Result forward = runOn(device, "forward", "tcp:" + fwd, "tcp:" + fwd);
                    if (forward.ok()) {
                        notes.add(device.serial + " 摄像头端口转发成功（tcp:" + fwd + "）");
                    } else {
                        allOk = false;
                        notes.add(device.serial + " 摄像头端口转发失败：" + firstLine(forward.output, "未知错误"));
                    }
                }
            }
            if (allOk) {
                lastMapped = mappingKey(online);
            }
            List<String> mappingNotes = new ArrayList<>();
            snapshot.mappings = List.copyOf(readMappings(online, mappingNotes));
            List<String> messages = new ArrayList<>(notes);
            messages.addAll(mappingNotes);
            snapshot.message = String.join("；", messages);
            status = snapshot;

            if (allOk) {
                log.info("手动重建端口映射完成：{}", snapshot.message);
                LogBuffer.get().info("ADB", "手动重建完成：" + snapshot.message);
            } else {
                log.warn("手动重建端口映射存在失败：{}", snapshot.message);
                LogBuffer.get().warn("ADB", "手动重建存在失败：" + snapshot.message);
            }
            return snapshot;
        }
    }

    /** 读取每个在线设备的 reverse 与 forward 列表，前缀序列号便于多设备区分 */
    private List<String> readMappings(List<Device> devices, List<String> notes) {
        List<String> mappings = new ArrayList<>();
        int fwd = forwardPort;
        for (Device device : onlineDevices(devices)) {
            Result list = runOn(device, "reverse", "--list");
            if (!list.ok()) {
                notes.add(device.serial + " 读取映射失败：" + firstLine(list.output, "未知错误"));
                continue;
            }
            appendMappingLines(mappings, device.serial, "reverse", list.output());
            if (fwd > 0) {
                Result fwdList = runOn(device, "forward", "--list");
                if (fwdList.ok()) {
                    appendMappingLines(mappings, device.serial, "forward", fwdList.output());
                }
            }
        }
        return mappings;
    }

    private void publishStatus(List<Device> details, List<String> mappings, boolean available, String message) {
        Status snapshot = new Status(net().adbExecutable);
        snapshot.adbAvailable = available;
        snapshot.deviceDetails = List.copyOf(details);
        snapshot.devices = List.copyOf(onlineSerials(details));
        snapshot.mappings = snapshot.devices.isEmpty() ? List.of() : List.copyOf(mappings);
        snapshot.checkedAt = System.currentTimeMillis();
        snapshot.message = message != null ? message
                : (available ? (snapshot.devices.isEmpty() ? "未检测到已授权设备" : "") : "未找到 adb 可执行文件");
        status = snapshot;
    }

    /**
     * 冗余寻址：优先上一次成功的寻址方式，失败自动换另一种重试。
     * 序列号重复（同一设备同时存在 USB / TCP 传输）时默认用 transport_id。
     */
    private Result runOn(Device device, String... args) {
        boolean preferTransport = SELECTOR_TRANSPORT.equals(device.selector) && hasText(device.transportId);
        String primary = preferTransport ? SELECTOR_TRANSPORT : SELECTOR_SERIAL;
        Result first = executeOn(device, preferTransport, args);
        if (first.ok()) {
            rememberSelector(device, primary);
            return first;
        }

        Result second = executeOn(device, !preferTransport, args);
        if (second.ok()) {
            rememberSelector(device, preferTransport ? SELECTOR_SERIAL : SELECTOR_TRANSPORT);
            String label = SELECTOR_TRANSPORT.equals(device.selector)
                    ? "transport_id=" + device.transportId : "serial";
            log.warn("设备 {} 的{}寻址失败，已冗余切换到 {}", device.serial,
                    SELECTOR_TRANSPORT.equals(primary) ? " transport_id " : " serial ", label);
            LogBuffer.get().warn("ADB", "设备 " + device.serial + " 的主寻址失败，已切换到 " + label);
            return second;
        }

        return new Result(second.exitCode(),
                "serial / transport_id 两种寻址均失败：" + firstLine(first.output, "无输出")
                        + " / " + firstLine(second.output, "无输出"),
                first.unavailable() && second.unavailable());
    }

    private Result executeOn(Device device, boolean transport, String... args) {
        if (transport) {
            if (!hasText(device.transportId)) {
                return new Result(-1, "缺少 transport_id", false);
            }
            return execOn("-t", device.transportId, args);
        }
        if (!hasText(device.serial)) {
            return new Result(-1, "缺少设备序列号", false);
        }
        return execOn("-s", device.serial, args);
    }

    private Result execOn(String option, String target, String... args) {
        List<String> command = new ArrayList<>();
        command.add(net().adbExecutable);
        command.add(option);
        command.add(target);
        command.addAll(Arrays.asList(args));
        return run(command.toArray(new String[0]));
    }

    private void rememberSelector(Device device, String selector) {
        device.selector = selector;
        if (hasText(device.serial)) {
            selectorBySerial.put(device.serial, selector);
        }
    }

    private String selectorLabel(Device device) {
        return SELECTOR_TRANSPORT.equals(device.selector)
                ? "transport_id=" + device.transportId
                : "serial";
    }

    private String mappingKey(List<Device> devices) {
        StringBuilder builder = new StringBuilder();
        for (Device device : devices) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(device.serial).append('#').append(device.transportId);
        }
        builder.append("#fwd=").append(forwardPort);
        return builder.toString();
    }

    private List<Device> parseDeviceDetails(String output) {
        List<Device> devices = new ArrayList<>();
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("List of devices") || trimmed.startsWith("*")) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            Device device = new Device();
            device.serial = parts[0];
            device.state = parts[1];
            for (int i = 2; i < parts.length; i++) {
                String token = parts[i];
                if (token.startsWith("transport_id:")) {
                    device.transportId = token.substring("transport_id:".length());
                } else if (token.startsWith("model:")) {
                    device.model = token.substring("model:".length());
                }
            }
            devices.add(device);
        }
        applySelectors(devices);
        return devices;
    }

    /** 恢复上次成功的寻址方式；序列号重复时优先 transport_id */
    private void applySelectors(List<Device> devices) {
        Map<String, Integer> serialCounts = new HashMap<>();
        for (Device device : devices) {
            serialCounts.merge(device.serial, 1, Integer::sum);
        }
        for (Device device : devices) {
            if (serialCounts.getOrDefault(device.serial, 0) > 1 && hasText(device.transportId)) {
                device.selector = SELECTOR_TRANSPORT;
                continue;
            }
            String cached = selectorBySerial.get(device.serial);
            if (SELECTOR_TRANSPORT.equals(cached) && hasText(device.transportId)) {
                device.selector = SELECTOR_TRANSPORT;
            } else if (SELECTOR_SERIAL.equals(cached)) {
                device.selector = SELECTOR_SERIAL;
            }
        }
    }

    private List<Device> onlineDevices(List<Device> devices) {
        List<Device> online = new ArrayList<>();
        for (Device device : devices) {
            if ("device".equals(device.state)) {
                online.add(device);
            }
        }
        return online;
    }

    private List<String> onlineSerials(List<Device> devices) {
        List<String> serials = new ArrayList<>();
        for (Device device : devices) {
            if ("device".equals(device.state)) {
                serials.add(device.serial);
            }
        }
        return serials;
    }

    private List<String> parseLines(String output) {
        List<String> lines = new ArrayList<>();
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    private String firstLine(String output, String fallback) {
        if (output == null || output.isBlank()) {
            return fallback;
        }
        String trimmed = output.trim();
        int index = trimmed.indexOf('\n');
        String line = index >= 0 ? trimmed.substring(0, index).trim() : trimmed;
        return line.length() > 200 ? line.substring(0, 200) + "…" : line;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 执行命令并返回退出码与输出，超时立即强杀；所有 adb 调用都走这里 */
    private Result run(String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(-1, "命令超时（" + COMMAND_TIMEOUT_SECONDS + "s）", false);
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (adbMissing) {
                adbMissing = false;
                log.info("ADB 已可用");
                LogBuffer.get().info("ADB", "adb 已恢复可用");
            }
            return new Result(process.exitValue(), output, false);
        } catch (IOException e) {
            if (!adbMissing) {
                adbMissing = true;
                log.warn("未检测到 adb（{}），有线模式将不可用；仅影响 USB 直连，无线模式不受影响",
                        net().adbExecutable);
                LogBuffer.get().warn("ADB", "未检测到 adb（" + net().adbExecutable + "），有线模式不可用");
            }
            return new Result(-1, "无法执行 " + command[0] + "：" + e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "命令被中断", false);
        }
    }

    private record Result(int exitCode, String output, boolean unavailable) {
        boolean ok() {
            return exitCode == 0;
        }
    }
}
