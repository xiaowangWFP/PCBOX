package com.phoneconsole.pc.net;

import com.phoneconsole.pc.config.AppConfig;
import com.phoneconsole.pc.runtime.LogBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * USB 有线接入自动亮屏、解锁并打开手机端应用服务。
 * <p>
 * 在 ADB 端口映射就绪后异步触发：
 * 1. 检查亮屏状态，未亮屏时先唤醒（优先 KEYCODE_WAKEUP，受限设备降级用应用广播 WakeLock）；
 * 2. 检查锁屏状态，锁定且允许模拟输入时先按屏幕分辨率比例上滑，再输入配置的锁屏密码；
 * 3. 若需要则拉起手机控制台 App（com.phoneconsole.client/.MainActivity）。
 * 全程避免把明文密码输出到任何日志。
 */
public final class AdbAutoUnlockService {

    private static final Logger log = LoggerFactory.getLogger(AdbAutoUnlockService.class);
    private static final Pattern WM_SIZE_PATTERN = Pattern.compile("(\\d+)x(\\d+)");
    private static final String WAKE_BROADCAST_COMPONENT = "com.phoneconsole.client/.receiver.PowerConnectionReceiver";
    private static final String WAKE_BROADCAST_ACTION = "com.phoneconsole.client.ACTION_WAKE_UP";

    private final AdbPortForwarder adb;
    private final Supplier<AppConfig.AutoUnlock> configSupplier;
    private volatile Consumer<Map<String, Object>> stateListener = ignored -> {
    };

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "adb-auto-unlock");
        t.setDaemon(true);
        return t;
    });

    private final Object stateLock = new Object();
    private final Set<String> handledSerials = new HashSet<>();
    private final Set<String> inFlightSerials = new HashSet<>();
    private final AtomicBoolean startupConsumed = new AtomicBoolean(false);

    private volatile String lastResult = "等待有线设备接入";
    private volatile long lastRunAt;
    private volatile boolean needInputPermission;

    public AdbAutoUnlockService(AdbPortForwarder adb, Supplier<AppConfig.AutoUnlock> configSupplier) {
        this.adb = adb;
        this.configSupplier = configSupplier;
    }

    public void setStateListener(Consumer<Map<String, Object>> listener) {
        if (listener != null) {
            this.stateListener = listener;
        }
    }

    public void stop() {
        executor.shutdownNow();
    }

    /** 每次 ADB 守护轮询后回调：检测新上线的有线/无线设备并按配置自动触发 */
    public void onOnlineDevices(List<AdbPortForwarder.Device> online) {
        Set<String> currentSerials = new HashSet<>();
        if (online != null) {
            for (AdbPortForwarder.Device device : online) {
                if (device != null && hasText(device.serial)) {
                    currentSerials.add(device.serial);
                }
            }
        }
        synchronized (stateLock) {
            handledSerials.retainAll(currentSerials);
        }
        if (online == null || online.isEmpty()) {
            return;
        }

        AppConfig.AutoUnlock cfg = currentConfig();
        boolean startupOnly = "on_startup".equalsIgnoreCase(cfg.triggerMode);
        if (startupOnly && startupConsumed.get()) {
            return;
        }

        // 先处理有线设备再处理无线设备，确保同一台设备双通道同时在线时不重复解锁
        List<AdbPortForwarder.Device> ordered = new java.util.ArrayList<>(online);
        ordered.sort((a, b) -> Boolean.compare(a.wireless, b.wireless));

        for (AdbPortForwarder.Device device : ordered) {
            if (device == null || !hasText(device.serial)) {
                continue;
            }
            if (device.wireless && !cfg.wirelessEnabled) {
                continue;
            }
            if (!device.wireless && !cfg.enabled) {
                continue;
            }
            boolean shouldTrigger;
            synchronized (stateLock) {
                if (handledSerials.contains(device.serial) || inFlightSerials.contains(device.serial)) {
                    shouldTrigger = false;
                } else if (device.wireless && isSamePhoneAlreadyHandledViaUsb(device)) {
                    handledSerials.add(device.serial);
                    shouldTrigger = false;
                } else {
                    handledSerials.add(device.serial);
                    inFlightSerials.add(device.serial);
                    shouldTrigger = true;
                }
            }
            if (!shouldTrigger) {
                continue;
            }
            if (startupOnly) {
                startupConsumed.set(true);
            }
            executor.submit(() -> executeSafely(device, currentConfig(), false));
        }
    }

    private boolean isSamePhoneAlreadyHandledViaUsb(AdbPortForwarder.Device wirelessDevice) {
        for (String existing : handledSerials) {
            if (!AdbPortForwarder.isWirelessSerial(existing)) {
                // 查询无线设备硬件序列号与已处理的 USB 序列号比对，避免同机二次滑屏解锁
                AdbPortForwarder.Result serialRes = adb.shellOn(wirelessDevice, "getprop ro.serialno");
                if (serialRes.ok() && existing.equalsIgnoreCase(serialRes.output().trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 网页后台或手机端手动触发一次测试执行（mode = wired | wireless | all） */
    public Map<String, Object> triggerNow() {
        return triggerNow("all");
    }

    public Map<String, Object> triggerNow(String mode) {
        String normalizedMode = mode == null ? "all" : mode.trim().toLowerCase(Locale.ROOT);
        AppConfig.AutoUnlock cfg = currentConfig();

        if ("wireless".equals(normalizedMode)) {
            boolean hasWirelessOnline = false;
            for (AdbPortForwarder.Device d : adb.onlineDevicesSnapshot()) {
                if (d.wireless) {
                    hasWirelessOnline = true;
                    break;
                }
            }
            if (!hasWirelessOnline && (hasText(cfg.wirelessEndpoint) || cfg.wirelessMdnsAutoDiscover)) {
                adb.connectWireless(cfg.wirelessEndpoint, cfg.wirelessMdnsAutoDiscover);
            }
        }

        AdbPortForwarder.Status probe = adb.probe();
        List<AdbPortForwarder.Device> online = adb.onlineDevicesSnapshot();
        AdbPortForwarder.Device target = pickTargetDevice(online, normalizedMode);
        if (target == null) {
            String msg = "wireless".equals(normalizedMode)
                    ? "未检测到已授权无线调试设备（请核对 IP:端口 并确认手机已配对开启无线调试）"
                    : "未检测到已授权 ADB 设备，无法测试解锁启动";
            updateStatus(msg, false);
            return Map.of("ok", false, "error", msg, "autoUnlock", statusMap());
        }
        synchronized (stateLock) {
            inFlightSerials.add(target.serial);
        }
        Future<String> future = executor.submit(() -> executeSafely(target, cfg, true));
        try {
            String msg = future.get(15, TimeUnit.SECONDS);
            boolean ok = !needInputPermission;
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("ok", ok);
            res.put(ok ? "message" : "error", msg);
            res.put("autoUnlock", statusMap());
            res.put("checkedAt", probe != null ? probe.checkedAt : System.currentTimeMillis());
            return res;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of("ok", false, "error", "执行被中断", "autoUnlock", statusMap());
        } catch (ExecutionException | TimeoutException e) {
            return Map.of("ok", false, "error", "执行超时或异常：" + e.getMessage(), "autoUnlock", statusMap());
        }
    }

    private static AdbPortForwarder.Device pickTargetDevice(List<AdbPortForwarder.Device> online, String mode) {
        for (AdbPortForwarder.Device d : online) {
            if ("wireless".equals(mode) && d.wireless) {
                return d;
            }
            if ("wired".equals(mode) && !d.wireless) {
                return d;
            }
        }
        if ("all".equals(mode) && !online.isEmpty()) {
            for (AdbPortForwarder.Device d : online) {
                if (!d.wireless) {
                    return d;
                }
            }
            return online.get(0);
        }
        return null;
    }

    /** 对外暴露的脱敏状态快照（不含明文密码） */
    public Map<String, Object> statusMap() {
        AppConfig.AutoUnlock cfg = currentConfig();
        boolean wirelessOnline = false;
        for (AdbPortForwarder.Device d : adb.onlineDevicesSnapshot()) {
            if (d.wireless) {
                wirelessOnline = true;
                break;
            }
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", cfg.enabled);
        map.put("wirelessEnabled", cfg.wirelessEnabled);
        map.put("wirelessEndpoint", cfg.wirelessEndpoint == null ? "" : cfg.wirelessEndpoint);
        map.put("wirelessMdnsAutoDiscover", cfg.wirelessMdnsAutoDiscover);
        map.put("wirelessConnected", wirelessOnline);
        map.put("triggerMode", normalizeTriggerMode(cfg.triggerMode));
        map.put("swipeToUnlock", cfg.swipeToUnlock);
        map.put("launchApp", cfg.launchApp);
        map.put("packageName", cfg.packageName);
        map.put("activityName", cfg.activityName);
        boolean hasPwd = hasText(cfg.password);
        map.put("hasPassword", hasPwd);
        map.put("passwordLength", hasPwd ? cfg.password.length() : 0);
        map.put("lastResult", lastResult);
        map.put("lastRunAt", lastRunAt);
        map.put("needInputPermission", needInputPermission);
        return map;
    }

    public void notifyStateChanged() {
        stateListener.accept(statusMap());
    }

    private String executeSafely(AdbPortForwarder.Device device, AppConfig.AutoUnlock cfg, boolean manualTest) {
        try {
            if (!manualTest) {
                sleepQuietly(350);
            }
            return runSequence(device, cfg);
        } catch (Throwable t) {
            String modeLabel = device.wireless ? "无线" : "有线";
            String msg = modeLabel + "自动解锁异常 [" + device.serial + "]：" + t.getMessage();
            log.warn(msg, t);
            LogBuffer.get().warn("ADB", msg);
            updateStatus(msg, false);
            return msg;
        } finally {
            synchronized (stateLock) {
                inFlightSerials.remove(device.serial);
            }
        }
    }

    private String runSequence(AdbPortForwarder.Device device, AppConfig.AutoUnlock cfg) {
        ScreenState state = probeScreen(device);
        boolean injectBlocked = false;
        boolean wokeScreen = false;
        boolean unlockedNow = false;

        // 1. 若熄屏则先唤醒点亮
        if (!state.awake) {
            AdbPortForwarder.Result wakeRes = adb.shellOn(device, "input keyevent 224");
            if (wakeRes.ok()) {
                wokeScreen = true;
            } else if (isInjectPermissionError(wakeRes.output())) {
                injectBlocked = true;
                // MIUI 未开「USB 调试（安全设置）」时降级用应用自有 WAKE_LOCK 广播点亮屏幕
                AdbPortForwarder.Result bcRes = adb.shellOn(device,
                        "am broadcast -n " + WAKE_BROADCAST_COMPONENT + " -a " + WAKE_BROADCAST_ACTION);
                if (bcRes.ok()) {
                    wokeScreen = true;
                }
            }
            sleepQuietly(500);
            state = probeScreen(device);
        }

        // 2. 若处于锁屏态则尝试上滑 + 输入密码
        if (state.locked) {
            if (!injectBlocked && cfg.swipeToUnlock) {
                int[] size = queryScreenSize(device);
                int cx = Math.max(100, size[0] / 2);
                int yStart = Math.max(200, Math.round(size[1] * 0.80f));
                int yEnd = Math.max(80, Math.round(size[1] * 0.28f));
                AdbPortForwarder.Result swipeRes = adb.shellOn(device,
                        "input swipe " + cx + " " + yStart + " " + cx + " " + yEnd + " 280");
                if (!swipeRes.ok() && isInjectPermissionError(swipeRes.output())) {
                    injectBlocked = true;
                } else {
                    sleepQuietly(500);
                    state = probeScreen(device);
                }
            }

            if (!injectBlocked && state.locked && hasText(cfg.password)) {
                // 日志严格脱敏，不记录密码明文
                log.info("正在为设备 [{}] 输入锁屏密码（****）", device.serial);
                AdbPortForwarder.Result textRes = adb.shellOn(
                        device,
                        "input text " + posixQuote(formatForAdbInputText(cfg.password)));
                if (!textRes.ok() && isInjectPermissionError(textRes.output())) {
                    injectBlocked = true;
                } else {
                    sleepQuietly(450);
                    state = probeScreen(device);
                    // 若未自动识别位数解锁，再补一次回车确认；已解锁则不发回车避免误触桌面图标
                    if (state.locked) {
                        adb.shellOn(device, "input keyevent 66");
                        sleepQuietly(700);
                        state = probeScreen(device);
                    }
                    if (!state.locked) {
                        unlockedNow = true;
                    }
                }
            } else if (!injectBlocked && !state.locked) {
                unlockedNow = true;
            }
        }

        // 3. 拉起手机端控制台 App
        boolean launched = false;
        String launchError = "";
        if (cfg.launchApp) {
            String component = buildComponentName(cfg.packageName, cfg.activityName);
            AdbPortForwarder.Result amRes = adb.shellOn(device,
                    "am start -W -n " + component + " --ez from_adb_auto true");
            if (amRes.ok() && !amRes.output().contains("Error:")) {
                launched = true;
            } else {
                launchError = firstLine(amRes.output());
            }
        }

        String summary;
        if (injectBlocked && state.locked) {
            summary = "手机拦截了 ADB 模拟输入（请在手机『开发者选项』开启「USB 调试（安全设置）」后才能自动输入锁屏密码）"
                    + (launched ? "；已发送拉起应用指令" : "");
            log.warn("{} [{}]", summary, device.serial);
            LogBuffer.get().warn("ADB", "[" + device.serial + "] " + summary);
            updateStatus(summary, true);
            return summary;
        }

        if (state.locked) {
            summary = "已尝试自动解锁 [" + device.serial + "]，但手机仍处于锁屏状态（请检查锁屏密码是否正确）"
                    + (launched ? "；已拉起应用（解锁后可见）" : "");
            log.warn(summary);
            LogBuffer.get().warn("ADB", summary);
            updateStatus(summary, false);
            return summary;
        }

        String modeLabel = device.wireless ? "无线" : "有线";
        StringBuilder okMsg = new StringBuilder(modeLabel + "自动就绪 [" + device.serial + "]：");
        okMsg.append(wokeScreen ? "已亮屏" : "屏幕已亮");
        okMsg.append(unlockedNow ? " · 已解锁" : " · 无需密码");
        if (cfg.launchApp) {
            okMsg.append(launched ? " · 已打开手机应用" : " · 打开应用失败(" + launchError + ")");
        }
        summary = okMsg.toString();
        log.info(summary);
        LogBuffer.get().info("ADB", summary);
        updateStatus(summary, false);
        return summary;
    }

    private ScreenState probeScreen(AdbPortForwarder.Device device) {
        String cmd = "dumpsys power | grep -E 'mWakefulness=|Display Power:';"
                + " dumpsys window policy | grep -E 'SCREEN_STATE_|showing=|mIsShowing=';"
                + " dumpsys trust | grep -E 'deviceLocked='; true";
        AdbPortForwarder.Result res = adb.shellOn(device, cmd);
        String out = res.output() != null ? res.output() : "";

        boolean awake = out.contains("mWakefulness=Awake")
                || out.contains("Display Power: state=ON")
                || out.contains("SCREEN_STATE_ON");
        boolean locked = containsFlag(out, "showing=true")
                || containsFlag(out, "mIsShowing=true")
                || containsFlag(out, "deviceLocked=1");
        return new ScreenState(awake, locked);
    }

    private int[] queryScreenSize(AdbPortForwarder.Device device) {
        AdbPortForwarder.Result res = adb.shellOn(device, "wm size");
        if (res.ok()) {
            Matcher m = WM_SIZE_PATTERN.matcher(res.output());
            int width = 0;
            int height = 0;
            // 若存在 Override size 则取最后一行匹配，更贴近当前显示尺寸
            while (m.find()) {
                try {
                    width = Integer.parseInt(m.group(1));
                    height = Integer.parseInt(m.group(2));
                } catch (NumberFormatException ignored) {
                    // ignore
                }
            }
            if (width > 100 && height > 100) {
                return new int[] { width, height };
            }
        }
        return new int[] { 1080, 2400 };
    }

    private static boolean containsFlag(String text, String needle) {
        return text != null && text.contains(needle);
    }

    private static boolean isInjectPermissionError(String output) {
        if (output == null) {
            return false;
        }
        return output.contains("INJECT_EVENTS")
                || output.contains("SecurityException");
    }

    /** Android input text 约定空格用 %s 表示；再套一层 POSIX 单引号保证特殊符号直达 */
    private static String formatForAdbInputText(String raw) {
        return raw.replace(" ", "%s");
    }

    private static String posixQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String buildComponentName(String pkg, String act) {
        String p = hasText(pkg) ? pkg.trim() : "com.phoneconsole.client";
        String a = hasText(act) ? act.trim() : "com.phoneconsole.client.MainActivity";
        return p + "/" + a;
    }

    private static String normalizeTriggerMode(String mode) {
        return "on_startup".equalsIgnoreCase(mode) ? "on_startup" : "on_connect";
    }

    private AppConfig.AutoUnlock currentConfig() {
        AppConfig.AutoUnlock cfg = configSupplier != null ? configSupplier.get() : null;
        return cfg != null ? cfg : new AppConfig.AutoUnlock();
    }

    private void updateStatus(String msg, boolean missingPerm) {
        this.lastResult = msg;
        this.lastRunAt = System.currentTimeMillis();
        this.needInputPermission = missingPerm;
        notifyStateChanged();
    }

    private static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "未知错误";
        }
        int nl = text.indexOf('\n');
        String line = nl >= 0 ? text.substring(0, nl).trim() : text.trim();
        return line.length() > 140 ? line.substring(0, 140) + "…" : line;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record ScreenState(boolean awake, boolean locked) {
    }
}
