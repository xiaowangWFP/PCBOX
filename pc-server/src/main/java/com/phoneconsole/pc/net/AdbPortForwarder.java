package com.phoneconsole.pc.net;

import com.phoneconsole.pc.config.AppConfig;
import com.phoneconsole.pc.runtime.LogBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
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

    @FunctionalInterface
    public interface CommandExecutor {
        Result execute(String... command);
    }

    private static final Logger log = LoggerFactory.getLogger(AdbPortForwarder.class);
    private static final long COMMAND_TIMEOUT_SECONDS = 5;
    private static final long WIRELESS_CONNECT_TIMEOUT_SECONDS = 3;
    private static final long PAIR_TIMEOUT_SECONDS = 10;
    private static final int TCP_PROBE_TIMEOUT_MS = 1200;
    private static final long WIRELESS_RETRY_COOLDOWN_MS = 30_000L;
    private static final String SELECTOR_SERIAL = "serial";
    private static final String SELECTOR_TRANSPORT = "transport";

    private volatile AppConfig.Network network;
    private volatile java.util.function.Supplier<AppConfig.AutoUnlock> autoUnlockSupplier;
    private final int port;
    private volatile int forwardPort;
    private final CommandExecutor commandExecutor;
    private final boolean customExecutor;
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
    private long lastWirelessAttemptAt;
    private volatile String wirelessMessage = "";
    private volatile java.util.function.Consumer<List<Device>> deviceObserver;

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
        public int wiredCount;
        public int wirelessCount;
        public boolean wirelessConnected;
        public String wirelessMessage = "";

        Status(String executable) {
            this.executable = executable;
        }
    }

    /** 单个 USB/无线设备：序列号与 transport_id 互为冗余地址 */
    public static final class Device {
        public String serial = "";
        public String transportId = "";
        public String state = "";
        public String model = "";
        public boolean wireless;
        /** serial | transport，最近一次成功使用的寻址方式 */
        public String selector = SELECTOR_SERIAL;
    }

    public AdbPortForwarder(AppConfig.Network network, int wsPort) {
        this(network, wsPort, 0, null);
    }

    public AdbPortForwarder(AppConfig.Network network, int wsPort, int forwardPort) {
        this(network, wsPort, forwardPort, null);
    }

    AdbPortForwarder(AppConfig.Network network, int wsPort, int forwardPort, CommandExecutor executor) {
        this.network = network;
        this.port = wsPort;
        this.forwardPort = Math.max(0, forwardPort);
        this.customExecutor = executor != null;
        this.commandExecutor = customExecutor ? executor : this::runProcess;
        this.status = new Status(network != null ? network.adbExecutable : "adb");
    }

    public static boolean isWirelessSerial(String serial) {
        return serial != null && serial.contains(":");
    }

    public void setAutoUnlockSupplier(java.util.function.Supplier<AppConfig.AutoUnlock> supplier) {
        this.autoUnlockSupplier = supplier;
        this.lastWirelessAttemptAt = 0L;
    }

    /** 配置热重载后清零无线重试冷却，下次轮询立即按新配置尝试 */
    public void resetWirelessBackoff() {
        this.lastWirelessAttemptAt = 0L;
    }

    public boolean hasWiredOnline() {
        Status s = status;
        return s != null && s.wiredCount > 0;
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
        map.put("wiredCount", snapshot.wiredCount);
        map.put("wirelessCount", snapshot.wirelessCount);
        map.put("wirelessConnected", snapshot.wirelessConnected);
        map.put("wirelessMessage", snapshot.wirelessMessage);
        map.put("port", port);
        map.put("forwardPort", forwardPort);
        map.put("redundancy", "serial+transport_id");
        return map;
    }

    public void setDeviceObserver(java.util.function.Consumer<List<Device>> observer) {
        this.deviceObserver = observer;
    }

    /** 在同步 ADB 锁保护下执行单个设备上的 adb shell 命令 */
    public Result shellOn(Device device, String shellCommand) {
        if (device == null || !hasText(shellCommand)) {
            return new Result(-1, "无效设备或空命令", false);
        }
        synchronized (adbLock) {
            return runOn(device, "shell", shellCommand);
        }
    }

    /** 当前最近一次检测到的已授权设备快照 */
    public List<Device> onlineDevicesSnapshot() {
        Status snapshot = status;
        if (snapshot == null || snapshot.deviceDetails == null) {
            return List.of();
        }
        return List.copyOf(onlineDevices(snapshot.deviceDetails));
    }

    /** 手动触发无线调试配对（Android 11+：adb pair IP:配对端口 配对码） */
    public Map<String, Object> pairWireless(String endpoint, String pairCode) {
        String target = endpoint == null ? "" : endpoint.trim();
        String code = pairCode == null ? "" : pairCode.trim();
        if (!com.phoneconsole.pc.config.ConfigStore.isValidHostPort(target)) {
            return Map.of("ok", false, "error", "配对地址格式需为 IP:配对端口", "adb", statusMap());
        }
        if (code.isEmpty() || code.matches(".*\\s+.*")) {
            return Map.of("ok", false, "error", "请输入有效的 6 位无线配对码", "adb", statusMap());
        }
        if (!customExecutor) {
            String reachError = probeTcpUnreachableReason(target, TCP_PROBE_TIMEOUT_MS);
            if (reachError != null) {
                wirelessMessage = "无线配对失败 [" + target + "]：" + reachError;
                log.warn("ADB {}", wirelessMessage);
                LogBuffer.get().warn("ADB", wirelessMessage);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("ok", false);
                payload.put("error", wirelessMessage);
                payload.put("adb", statusMap());
                return payload;
            }
        }
        Result res;
        synchronized (adbLock) {
            res = runWithTimeout(PAIR_TIMEOUT_SECONDS, net().adbExecutable, "pair", target, code);
        }
        String out = firstLine(res.output(), res.ok() ? "配对成功" : "配对失败");
        boolean ok = res.ok() && !out.toLowerCase(java.util.Locale.ROOT).contains("failed");
        if (ok) {
            wirelessMessage = "无线配对成功：" + out;
            log.info("ADB 无线配对成功 [{}]", target);
            LogBuffer.get().info("ADB", "无线配对成功 [" + target + "]：" + out);
        } else {
            wirelessMessage = "无线配对失败：" + out;
            log.warn("ADB 无线配对失败 [{}]：{}", target, out);
            LogBuffer.get().warn("ADB", "无线配对失败 [" + target + "]：" + out);
        }
        probe();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", ok);
        payload.put(ok ? "message" : "error", wirelessMessage);
        payload.put("adb", statusMap());
        return payload;
    }

    /** 手动触发连接无线调试（adb connect IP:端口） */
    public Map<String, Object> connectWireless(String endpoint, boolean allowMdnsFallback) {
        String target = endpoint == null ? "" : endpoint.trim();
        if (!com.phoneconsole.pc.config.ConfigStore.isValidHostPort(target) && !allowMdnsFallback) {
            return Map.of("ok", false, "error", "无线调试地址格式需为 IP:端口（如 10.26.3.135:33007）", "adb", statusMap());
        }
        String connectedEndpoint = "";
        synchronized (adbLock) {
            lastWirelessAttemptAt = 0L;
            Result devRes = run(net().adbExecutable, "devices", "-l");
            List<Device> details = devRes.ok() ? parseDeviceDetails(devRes.output()) : List.of();
            connectedEndpoint = tryConnectWirelessLocked(details, target, allowMdnsFallback);
        }
        Status latest = probe();
        boolean ok = hasText(connectedEndpoint) || latest.wirelessConnected;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", ok);
        if (ok) {
            String msg = "无线调试已连接" + (hasText(connectedEndpoint) ? "：" + connectedEndpoint : "");
            payload.put("message", msg);
            payload.put("connectedEndpoint", connectedEndpoint);
        } else {
            payload.put("error", hasText(wirelessMessage) ? wirelessMessage : "连接无线调试失败");
        }
        payload.put("adb", statusMap());
        return payload;
    }

    /** 手动断开无线调试设备 */
    public Map<String, Object> disconnectWireless(String endpoint) {
        String target = endpoint == null ? "" : endpoint.trim();
        synchronized (adbLock) {
            if (hasText(target)) {
                run(net().adbExecutable, "disconnect", target);
            } else {
                Result devRes = run(net().adbExecutable, "devices", "-l");
                if (devRes.ok()) {
                    for (Device d : parseDeviceDetails(devRes.output())) {
                        if (d.wireless) {
                            run(net().adbExecutable, "disconnect", d.serial);
                        }
                    }
                }
            }
            lastWirelessAttemptAt = System.currentTimeMillis();
            wirelessMessage = "已断开无线调试";
        }
        probe();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("message", "已断开无线调试");
        payload.put("adb", statusMap());
        return payload;
    }

    void tick() {
        List<Device> onlineForNotify = List.of();
        try {
            synchronized (adbLock) {
                Result devicesResult = run(net().adbExecutable, "devices", "-l");
                if (!devicesResult.ok()) {
                    publishStatus(List.of(), List.of(), false, firstLine(devicesResult.output, "adb 不可用"));
                    lastMapped = "";
                    onlineForNotify = List.of();
                } else {
                    List<Device> details = parseDeviceDetails(devicesResult.output);
                    List<Device> online = onlineDevices(details);
                    if (!online.isEmpty()) {
                        applyOnlineMappingsLocked(details, online);
                    }
                    // 先为已接入的有线设备建立映射与就绪状态，再尝试无线调试连接，避免无线超时阻塞有线启动
                    if (ensureWirelessIfConfiguredLocked(details)) {
                        Result refreshed = run(net().adbExecutable, "devices", "-l");
                        if (refreshed.ok()) {
                            details = parseDeviceDetails(refreshed.output);
                            online = onlineDevices(details);
                        }
                    }
                    if (online.isEmpty()) {
                        publishStatus(details, List.of(), true, "未检测到已授权设备");
                        lastMapped = "";
                        onlineForNotify = List.of();
                    } else {
                        applyOnlineMappingsLocked(details, online);
                        onlineForNotify = List.copyOf(online);
                    }
                }
            }
            java.util.function.Consumer<List<Device>> observer = deviceObserver;
            if (observer != null) {
                observer.accept(onlineForNotify);
            }
        } catch (Throwable t) {
            log.debug("ADB 轮询异常：{}", t.getMessage());
        }
    }

    private void applyOnlineMappingsLocked(List<Device> details, List<Device> online) {
        String joined = mappingKey(online);
        boolean previouslyMapped = joined.equals(lastMapped);
        List<String> mappings = new ArrayList<>();
        boolean allOk = true;
        int fwd = forwardPort;
        boolean hasWired = hasWiredDevice(online);

        for (Device device : online) {
            if (!ensureMapping(device, "reverse", port, previouslyMapped, mappings)) {
                allOk = false;
            }
            if (fwd > 0 && (!hasWired || !device.wireless)) {
                if (!ensureMapping(device, "forward", fwd, previouslyMapped, mappings)) {
                    allOk = false;
                }
            }
        }

        lastMapped = allOk ? joined : "";
        publishStatus(details, mappings, true, allOk ? "" : "端口映射存在失败，下个周期重试");
    }

    private boolean ensureWirelessIfConfiguredLocked(List<Device> details) {
        AppConfig.AutoUnlock cfg = autoUnlockSupplier != null ? autoUnlockSupplier.get() : null;
        if (cfg == null || !cfg.wirelessEnabled) {
            return false;
        }
        String endpoint = cfg.wirelessEndpoint == null ? "" : cfg.wirelessEndpoint.trim();
        if (!hasText(endpoint) && !cfg.wirelessMdnsAutoDiscover) {
            return false;
        }
        for (Device d : details) {
            if (d.wireless && "device".equals(d.state)) {
                wirelessMessage = "无线已连接：" + d.serial;
                return false;
            }
        }
        long now = System.currentTimeMillis();
        if (lastWirelessAttemptAt > 0 && now - lastWirelessAttemptAt < WIRELESS_RETRY_COOLDOWN_MS) {
            return false;
        }
        lastWirelessAttemptAt = now;
        String connected = tryConnectWirelessLocked(details, endpoint, cfg.wirelessMdnsAutoDiscover);
        return hasText(connected);
    }

    private String tryConnectWirelessLocked(List<Device> details, String endpoint, boolean allowMdns) {
        if (hasText(endpoint)) {
            cleanupOfflineWirelessEntry(details, endpoint);
            String reachError = customExecutor ? null : probeTcpUnreachableReason(endpoint, TCP_PROBE_TIMEOUT_MS);
            if (reachError != null) {
                wirelessMessage = "无法连接无线调试 [" + endpoint + "]：" + reachError;
            } else {
                Result conn = runWithTimeout(WIRELESS_CONNECT_TIMEOUT_SECONDS, net().adbExecutable, "connect", endpoint);
                if (isConnectSuccess(conn)) {
                    wirelessMessage = "无线已连接：" + endpoint;
                    log.info("ADB 无线调试已连接：{}", endpoint);
                    LogBuffer.get().info("ADB", "无线调试已连接：" + endpoint);
                    return endpoint;
                }
                // 失败或超时后立即 disconnect，避免 adb devices 长期残留 offline 僵尸条目
                run(net().adbExecutable, "disconnect", endpoint);
                String errLine = firstLine(conn.output(), "连接失败");
                wirelessMessage = "无线连接失败 [" + endpoint + "]：" + errLine
                        + "（如首次使用或端口变化，请先配对或核对手机无线调试端口）";
            }
        }

        if (allowMdns) {
            Result mdns = run(net().adbExecutable, "mdns", "services");
            if (mdns.ok()) {
                String preferredHost = extractHost(endpoint);
                String discovered = parseMdnsConnectEndpoint(mdns.output(), preferredHost);
                if (hasText(discovered) && !discovered.equals(endpoint)) {
                    cleanupOfflineWirelessEntry(details, discovered);
                    String reachError = customExecutor ? null : probeTcpUnreachableReason(discovered, TCP_PROBE_TIMEOUT_MS);
                    if (reachError == null) {
                        Result connMdns = runWithTimeout(WIRELESS_CONNECT_TIMEOUT_SECONDS, net().adbExecutable, "connect", discovered);
                        if (isConnectSuccess(connMdns)) {
                            wirelessMessage = "通过 mDNS 发现并连接无线调试：" + discovered;
                            log.info("ADB 通过 mDNS 连接无线调试：{}", discovered);
                            LogBuffer.get().info("ADB", "通过 mDNS 连接无线调试：" + discovered);
                            return discovered;
                        }
                        run(net().adbExecutable, "disconnect", discovered);
                    }
                }
            }
        }
        return "";
    }

    private void cleanupOfflineWirelessEntry(List<Device> details, String endpoint) {
        for (Device d : details) {
            if (endpoint.equals(d.serial) && !"device".equals(d.state)) {
                run(net().adbExecutable, "disconnect", endpoint);
            }
        }
    }

    /** 连接/配对前做轻量 TCP 预探：若直接超时即可快速判定网络隔离/不可达，避免卡死 adbLock 或残留 offline */
    private static String probeTcpUnreachableReason(String endpoint, int timeoutMs) {
        String host = extractHost(endpoint);
        int idx = endpoint == null ? -1 : endpoint.lastIndexOf(':');
        if (host.isEmpty() || idx < 0) {
            return "地址格式无效";
        }
        int port;
        try {
            port = Integer.parseInt(endpoint.substring(idx + 1).trim());
        } catch (NumberFormatException e) {
            return "端口无效";
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return null;
        } catch (SocketTimeoutException e) {
            return "网络 TCP 超时（可能处于校园网/公共Wi-Fi终端隔离或不在同一网段，可开电脑移动热点或普通路由器后再试）";
        } catch (IOException e) {
            // Connection refused 通常表示端口未开/已更换，放行后续 mDNS 自动发现或由 adb 给出精确提示
            return null;
        }
    }

    static String parseMdnsConnectEndpoint(String output, String preferredHost) {
        if (output == null || output.isBlank()) {
            return "";
        }
        String fallback = "";
        for (String line : output.split("\\R")) {
            if (!line.contains("_adb-tls-connect._tcp")) {
                continue;
            }
            String[] parts = line.trim().split("\\s+");
            for (int i = parts.length - 1; i >= 0; i--) {
                String token = parts[i].trim();
                if (com.phoneconsole.pc.config.ConfigStore.isValidHostPort(token)) {
                    if (preferredHost != null && !preferredHost.isBlank()
                            && preferredHost.equals(extractHost(token))) {
                        return token;
                    }
                    if (fallback.isEmpty()) {
                        fallback = token;
                    }
                }
            }
        }
        return fallback;
    }

    private static String extractHost(String endpoint) {
        if (endpoint == null) {
            return "";
        }
        int idx = endpoint.lastIndexOf(':');
        return idx > 0 ? endpoint.substring(0, idx).trim() : endpoint.trim();
    }

    private static boolean isConnectSuccess(Result res) {
        if (res == null || !res.ok()) {
            return false;
        }
        String out = res.output() == null ? "" : res.output().toLowerCase(java.util.Locale.ROOT);
        return (out.contains("connected to ") || out.contains("already connected to "))
                && !out.contains("cannot connect")
                && !out.contains("failed to connect");
    }

    private static boolean hasWiredDevice(List<Device> online) {
        for (Device d : online) {
            if (!d.wireless) {
                return true;
            }
        }
        return false;
    }

    private boolean ensureMapping(Device device, String mode, int targetPort,
                                  boolean previouslyMapped, List<String> mappings) {
        boolean isForward = "forward".equals(mode);
        String verbLabel = isForward ? "正向端口映射" : (device.wireless ? "无线反向映射" : "有线反向映射");
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
            populateWirelessMeta(snapshot, details);
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
                snapshot.wirelessMessage = wirelessMessage;
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
            populateWirelessMeta(snapshot, details);
            if (online.isEmpty()) {
                snapshot.message = "未检测到已授权设备，请检查数据线与 USB / 无线调试开关";
                status = snapshot;
                LogBuffer.get().warn("ADB", "手动重建跳过：" + snapshot.message);
                return snapshot;
            }

            List<String> notes = new ArrayList<>();
            boolean allOk = true;
            int fwd = forwardPort;
            boolean hasWired = hasWiredDevice(online);
            for (Device device : online) {
                Result reverse = runOn(device, "reverse", "tcp:" + port, "tcp:" + port);
                if (reverse.ok()) {
                    notes.add(device.serial + " 反向映射成功（" + selectorLabel(device) + "）");
                } else {
                    allOk = false;
                    notes.add(device.serial + " 反向映射失败：" + firstLine(reverse.output, "未知错误"));
                }
                if (fwd > 0 && (!hasWired || !device.wireless)) {
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
        populateWirelessMeta(snapshot, details);
        snapshot.message = message != null ? message
                : (available ? (snapshot.devices.isEmpty() ? "未检测到已授权设备" : "") : "未找到 adb 可执行文件");
        status = snapshot;
    }

    private void populateWirelessMeta(Status snapshot, List<Device> details) {
        int wired = 0;
        int wireless = 0;
        if (details != null) {
            for (Device d : details) {
                if ("device".equals(d.state)) {
                    if (d.wireless) {
                        wireless++;
                    } else {
                        wired++;
                    }
                }
            }
        }
        snapshot.wiredCount = wired;
        snapshot.wirelessCount = wireless;
        snapshot.wirelessConnected = wireless > 0;
        snapshot.wirelessMessage = wirelessMessage;
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
            device.wireless = isWirelessSerial(device.serial);
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

    /** 所有 adb 调用统一走 CommandExecutor，便于生产执行与单元测试替换 */
    private Result run(String... command) {
        return commandExecutor.execute(command);
    }

    private Result runWithTimeout(long timeoutSeconds, String... command) {
        if (customExecutor) {
            return commandExecutor.execute(command);
        }
        return runProcessWithTimeout(timeoutSeconds, command);
    }

    private Result runProcess(String... command) {
        return runProcessWithTimeout(COMMAND_TIMEOUT_SECONDS, command);
    }

    /** 执行命令并返回退出码与输出，边读边等避免 >4KB 管道死锁 */
    private Result runProcessWithTimeout(long timeoutSeconds, String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thread drainer = new Thread(() -> {
                try {
                    process.getInputStream().transferTo(buf);
                } catch (IOException ignored) {
                    // 进程超时被销毁时管道关闭属正常现象
                }
            }, "adb-stdout");
            drainer.setDaemon(true);
            drainer.start();

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                drainer.join(200);
                return new Result(-1, "命令超时（" + timeoutSeconds + "s）", false);
            }
            drainer.join(500);
            String output = buf.toString(StandardCharsets.UTF_8).trim();
            if (adbMissing) {
                adbMissing = false;
                log.info("ADB 已可用");
                LogBuffer.get().info("ADB", "adb 已恢复可用");
            }
            return new Result(process.exitValue(), output, false);
        } catch (IOException e) {
            if (!adbMissing) {
                adbMissing = true;
                log.warn("未检测到 adb（{}），有线/无线 ADB 模式将不可用；仅影响 ADB 功能，局域网 WebSocket 不受影响",
                        net().adbExecutable);
                LogBuffer.get().warn("ADB", "未检测到 adb（" + net().adbExecutable + "），ADB 模式不可用");
            }
            return new Result(-1, "无法执行 " + command[0] + "：" + e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "命令被中断", false);
        }
    }

    public record Result(int exitCode, String output, boolean unavailable) {
        public boolean ok() {
            return exitCode == 0;
        }
    }
}
