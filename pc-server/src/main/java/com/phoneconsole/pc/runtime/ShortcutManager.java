package com.phoneconsole.pc.runtime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Windows 启动快捷键与后台静默快捷方式管理：
 * 通过 PowerShell 调用 WScript.Shell 管理开始菜单 / 桌面 / 开机启动 .lnk，
 * 使电脑尚未启动服务端时也可按全局快捷键（如 Ctrl+Alt+P）无黑框拉起服务端。
 */
public final class ShortcutManager {

    private static final Logger log = LoggerFactory.getLogger(ShortcutManager.class);
    private static final Pattern VALID_HOTKEY = Pattern.compile(
            "^(?:Ctrl\\+Alt\\+Shift|Ctrl\\+Alt|Ctrl\\+Shift|Alt\\+Shift)\\+(?:[A-Z0-9]|F(?:[1-9]|1[0-2]))$|^F(?:9|1[0-2])$");

    @FunctionalInterface
    interface Runner {
        String run(List<String> command) throws IOException, InterruptedException;
    }

    private final Path scriptPath;
    private final Runner runner;
    private volatile Map<String, Object> lastStatus;

    public ShortcutManager() {
        this(resolveScriptPath("bind-shortcut.ps1"), null);
    }

    ShortcutManager(Path scriptPath, Runner runner) {
        this.scriptPath = scriptPath;
        this.runner = runner != null ? runner : ShortcutManager::executePowerShell;
        this.lastStatus = emptyStatus("Windows 快捷启动初始化中");
    }

    public static Path resolveScriptPath(String fileName) {
        Path direct = Path.of(fileName);
        if (Files.exists(direct)) {
            return direct.toAbsolutePath().normalize();
        }
        Path sub = Path.of("pc-server", fileName);
        if (Files.exists(sub)) {
            return sub.toAbsolutePath().normalize();
        }
        return direct.toAbsolutePath().normalize();
    }

    /**
     * 以无黑框后台方式拉起新的服务端实例，等待当前 PID 释放端口后启动。
     */
    public static boolean spawnSilentRestart() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return false;
        }
        Path vbs = resolveScriptPath("launch-silent.vbs");
        if (!Files.exists(vbs)) {
            log.warn("未找到静默启动脚本：{}", vbs);
            return false;
        }
        try {
            String wscript = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                    "System32", "wscript.exe").toString();
            ProcessBuilder pb = new ProcessBuilder(
                    wscript,
                    vbs.toAbsolutePath().toString(),
                    "-WaitPid",
                    String.valueOf(ProcessHandle.current().pid())
            );
            Path parent = vbs.getParent();
            if (parent != null) {
                pb.directory(parent.toFile());
            }
            pb.start();
            return true;
        } catch (Exception e) {
            log.warn("拉起静默重启失败：{}", e.getMessage());
            return false;
        }
    }

    public static String normalizeHotkey(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        boolean ctrl = false;
        boolean alt = false;
        boolean shift = false;
        String key = "";
        for (String token : raw.split("\\+")) {
            String upper = token.trim().toUpperCase(Locale.ROOT);
            if (upper.isEmpty()) {
                continue;
            }
            if ("CTRL".equals(upper) || "CONTROL".equals(upper)) {
                ctrl = true;
            } else if ("ALT".equals(upper)) {
                alt = true;
            } else if ("SHIFT".equals(upper)) {
                shift = true;
            } else {
                key = upper;
            }
        }
        if (key.isEmpty()) {
            return "";
        }
        List<String> out = new ArrayList<>();
        if (ctrl) {
            out.add("Ctrl");
        }
        if (alt) {
            out.add("Alt");
        }
        if (shift) {
            out.add("Shift");
        }
        out.add(key);
        return String.join("+", out);
    }

    public static boolean isValidHotkey(String hotkey) {
        return hotkey != null && VALID_HOTKEY.matcher(hotkey).matches();
    }

    public Map<String, Object> status() {
        return refresh();
    }

    public Map<String, Object> cachedStatus() {
        return lastStatus;
    }

    public synchronized Map<String, Object> refresh() {
        Map<String, Object> res = invokeScript("status", "", false, false, "keep");
        if (Boolean.TRUE.equals(res.get("ok"))) {
            lastStatus = res;
        }
        return res;
    }

    public synchronized Map<String, Object> bind(String rawHotkey, boolean desktopShortcut, boolean autoStart) {
        String normalized = normalizeHotkey(rawHotkey);
        if (!isValidHotkey(normalized)) {
            return failure("快捷键格式无效，请使用双修饰键组合（如 Ctrl+Alt+P / Ctrl+Shift+K）或 F9~F12");
        }
        String autoMode = autoStart ? "enable" : "disable";
        Map<String, Object> res = invokeScript(
                "bind", normalized, desktopShortcut, !desktopShortcut, autoMode);
        if (Boolean.TRUE.equals(res.get("ok"))) {
            lastStatus = res;
            String msg = "已绑定全局启动快捷键：" + normalized
                    + (desktopShortcut ? "（含桌面快捷方式）" : "")
                    + (autoStart ? " · 已开启开机静默自启" : "");
            res = new LinkedHashMap<>(res);
            res.put("message", msg);
            LogBuffer.get().info("CORE", msg);
            log.info(msg);
        }
        return res;
    }

    public synchronized Map<String, Object> unbind(boolean removeDesktop, boolean disableAutoStart) {
        Map<String, Object> res = invokeScript(
                "unbind", "", false, removeDesktop, disableAutoStart ? "disable" : "keep");
        if (Boolean.TRUE.equals(res.get("ok"))) {
            lastStatus = res;
            res = new LinkedHashMap<>(res);
            res.put("message", "已解除全局启动快捷键");
            LogBuffer.get().info("CORE", "已解除全局启动快捷键");
            log.info("已解除全局启动快捷键");
        }
        return res;
    }

    private Map<String, Object> invokeScript(String action, String hotkey, boolean desktop,
                                             boolean removeDesktop, String autoStartMode) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return failure("全局快捷键绑定仅支持 Windows 系统");
        }
        if (scriptPath == null || !Files.exists(scriptPath)) {
            return failure("未找到快捷方式脚本：" + scriptPath);
        }
        List<String> cmd = new ArrayList<>();
        cmd.add("powershell.exe");
        cmd.add("-NoProfile");
        cmd.add("-ExecutionPolicy");
        cmd.add("Bypass");
        cmd.add("-File");
        cmd.add(scriptPath.toAbsolutePath().toString());
        cmd.add("-Action");
        cmd.add(action);
        if (hotkey != null && !hotkey.isEmpty()) {
            cmd.add("-Hotkey");
            cmd.add(hotkey);
        }
        if (desktop) {
            cmd.add("-Desktop");
        }
        if (removeDesktop) {
            cmd.add("-RemoveDesktop");
        }
        if (autoStartMode != null && !"keep".equals(autoStartMode)) {
            cmd.add("-AutoStartMode");
            cmd.add(autoStartMode);
        }

        try {
            String raw = runner.run(cmd);
            JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
            Map<String, Object> map = new LinkedHashMap<>();
            boolean ok = obj.has("ok") && obj.get("ok").getAsBoolean();
            map.put("ok", ok);
            if (!ok) {
                map.put("error", obj.has("error") ? obj.get("error").getAsString() : "快捷方式操作失败");
                return map;
            }
            map.put("bound", obj.has("bound") && obj.get("bound").getAsBoolean());
            map.put("hotkey", obj.has("hotkey") ? obj.get("hotkey").getAsString() : "");
            map.put("desktopShortcut", obj.has("desktopShortcut") && obj.get("desktopShortcut").getAsBoolean());
            map.put("autoStart", obj.has("autoStart") && obj.get("autoStart").getAsBoolean());
            map.put("startMenuPath", obj.has("startMenuPath") ? obj.get("startMenuPath").getAsString() : "");
            map.put("desktopPath", obj.has("desktopPath") ? obj.get("desktopPath").getAsString() : "");
            map.put("startupPath", obj.has("startupPath") ? obj.get("startupPath").getAsString() : "");
            return map;
        } catch (Exception e) {
            log.warn("执行快捷方式管理失败：{}", e.getMessage());
            return failure("操作失败：" + e.getMessage());
        }
    }

    private static String executePowerShell(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Thread drainer = new Thread(() -> {
            try {
                process.getInputStream().transferTo(buf);
            } catch (IOException ignored) {
            }
        }, "shortcut-ps");
        drainer.setDaemon(true);
        drainer.start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            drainer.join(200);
            throw new IOException("PowerShell 执行超时");
        }
        drainer.join(500);
        String out = buf.toString(StandardCharsets.UTF_8).trim();
        int jsonStart = out.lastIndexOf('{');
        if (jsonStart > 0) {
            out = out.substring(jsonStart);
        }
        if (process.exitValue() != 0 && !out.startsWith("{")) {
            throw new IOException(out.isEmpty() ? "退出码 " + process.exitValue() : out);
        }
        return out;
    }

    private static Map<String, Object> emptyStatus(String msg) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", true);
        map.put("bound", false);
        map.put("hotkey", "");
        map.put("desktopShortcut", false);
        map.put("autoStart", false);
        map.put("message", msg);
        return map;
    }

    private static Map<String, Object> failure(String error) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", false);
        map.put("error", error);
        return map;
    }
}
