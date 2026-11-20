package com.phoneconsole.pc.core;


import com.sun.jna.Library;
import com.sun.jna.Native;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 基于 java.awt.Robot 的系统级按键模拟。
 * 支持修饰键组合（如 ctrl+shift+esc）与单键，以及远程文本粘贴输入。
 */
public final class KeySimulator {

    private static final Logger log = LoggerFactory.getLogger(KeySimulator.class);

    private static Robot robot;

    private KeySimulator() {
    }

    public static synchronized void press(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("hotkey 动作缺少 keys 配置");
        }
        int[] codes = new int[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            codes[i] = vkOf(keys.get(i));
        }
        Robot r = robot();
        for (int code : codes) {
            r.keyPress(code);
        }
        for (int i = codes.length - 1; i >= 0; i--) {
            r.keyRelease(codes[i]);
        }
    }

    /**
     * 执行形如 "win+shift+s"、"ctrl+c"、"win+l" 的快捷组合键。
     * 未知键位只记日志，不向上抛出，避免打断 WebSocket 消息处理线程。
     */
    public static synchronized void pressCombo(String combo) {
        if (combo == null || combo.isBlank()) {
            return;
        }
        String normalized = combo.trim().toLowerCase(Locale.ROOT);
        if ("win+l".equals(normalized)) {
            lockWorkstation();
            return;
        }
        if ("sleep".equals(normalized)) {
            sleepSystem();
            return;
        }
        List<String> keys = Arrays.stream(normalized.split("\\+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (keys.isEmpty()) {
            return;
        }
        try {
            press(keys);
        } catch (RuntimeException e) {
            log.warn("快捷组合键执行失败（{}）：{}", combo, e.getMessage());
        }
    }

    /**
     * 进入睡眠（S3）。
     * <p>
     * 不使用 {@code rundll32 powrprof.dll,SetSuspendState 0,1,0}：这条命令行对参数的传递
     * 并不可靠，在开启休眠的机器上经常变成休眠（S4）甚至行为异常。
     * 这里直接调用 powrprof 的 SetSuspendState(false, false, false)，
     * 明确要求“不休眠、不强制、允许唤醒事件”。
     */
    private static void sleepSystem() {
        if (isDryRun()) {
            log.info("[沙箱] 命中睡眠分支：应调用 SetSuspendState(hibernate=false, forceCritical=false, disableWakeEvent=false)，已跳过实际挂起");
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                boolean ok = PowrProfLib.INSTANCE.SetSuspendState(false, false, false);
                if (ok) {
                    log.info("已进入睡眠");
                } else {
                    log.warn("SetSuspendState 调用失败（可能缺少睡眠状态或权限不足）");
                }
            } catch (Throwable t) {
                log.error("触发系统睡眠失败", t);
            }
        }, "system-sleep");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 沙箱开关：启动参数 -Dphoneconsole.dryRun=true 或环境变量 PHONE_CONSOLE_DRY_RUN=true。
     * 开启后睡眠只写日志，不会真正挂起系统，用于验证「按钮 → 协议 → 系统调用」这条链路。
     */
    static boolean isDryRun() {
        String property = System.getProperty("phoneconsole.dryRun");
        if (property != null) {
            return Boolean.parseBoolean(property);
        }
        return Boolean.parseBoolean(System.getenv("PHONE_CONSOLE_DRY_RUN"));
    }

    /** powrprof.dll：SetSuspendState(BOOLEAN hibernate, BOOLEAN force, BOOLEAN disableWakeEvent) */
    interface PowrProfLib extends Library {
        PowrProfLib INSTANCE = Native.load("powrprof", PowrProfLib.class);

        boolean SetSuspendState(boolean hibernate, boolean forceCritical, boolean disableWakeEvent);
    }

    private static void lockWorkstation() {
        try {
            new ProcessBuilder("rundll32.exe", "user32.dll,LockWorkStation").start();
            log.info("已调用系统锁屏");
        } catch (Exception e) {
            log.error("锁定屏幕失败", e);
        }
    }

    /**
     * 将远程文本输入到当前活动窗口（经剪贴板粘贴，兼容中文与 Unicode）。
     */
    public static synchronized void typeText(String text, boolean pressEnter) {
        if (text == null || text.isEmpty()) {
            return;
        }
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            String previousText = null;
            try {
                Transferable prev = clipboard.getContents(null);
                if (prev != null && prev.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                    previousText = (String) prev.getTransferData(DataFlavor.stringFlavor);
                }
            } catch (Exception ignored) {
                // 读取原剪贴板失败不影响本次输入
            }

            clipboard.setContents(new StringSelection(text), null);
            press(List.of("ctrl", "v"));

            if (pressEnter) {
                Thread.sleep(30);
                press(List.of("enter"));
            }

            if (previousText != null) {
                Thread.sleep(80);
                clipboard.setContents(new StringSelection(previousText), null);
            }
        } catch (Exception e) {
            log.error("远程文本输入失败", e);
        }
    }

    private static synchronized Robot robot() {
        if (robot == null) {
            try {
                robot = new Robot();
                robot.setAutoDelay(20);
                robot.setAutoWaitForIdle(false);
            } catch (Exception e) {
                throw new IllegalStateException("无法初始化按键模拟（当前可能处于无图形环境）", e);
            }
        }
        return robot;
    }

    private static int vkOf(String key) {
        String k = key == null ? "" : key.trim().toLowerCase();
        return switch (k) {
            case "ctrl", "control" -> KeyEvent.VK_CONTROL;
            case "shift" -> KeyEvent.VK_SHIFT;
            case "alt" -> KeyEvent.VK_ALT;
            case "win", "meta", "cmd" -> KeyEvent.VK_WINDOWS;
            case "esc", "escape" -> KeyEvent.VK_ESCAPE;
            case "tab" -> KeyEvent.VK_TAB;
            case "enter", "return" -> KeyEvent.VK_ENTER;
            case "space" -> KeyEvent.VK_SPACE;
            case "backspace" -> KeyEvent.VK_BACK_SPACE;
            case "delete", "del" -> KeyEvent.VK_DELETE;
            case "insert" -> KeyEvent.VK_INSERT;
            case "up" -> KeyEvent.VK_UP;
            case "down" -> KeyEvent.VK_DOWN;
            case "left" -> KeyEvent.VK_LEFT;
            case "right" -> KeyEvent.VK_RIGHT;
            case "home" -> KeyEvent.VK_HOME;
            case "end" -> KeyEvent.VK_END;
            case "pageup" -> KeyEvent.VK_PAGE_UP;
            case "pagedown" -> KeyEvent.VK_PAGE_DOWN;
            default -> functionKey(k);
        };
    }

    private static int functionKey(String k) {
        if (k.matches("f([1-9]|1[0-2])")) {
            return KeyEvent.VK_F1 + (Integer.parseInt(k.substring(1)) - 1);
        }
        if (k.length() == 1) {
            return KeyEvent.getExtendedKeyCodeForChar(Character.toUpperCase(k.charAt(0)));
        }
        throw new IllegalArgumentException("无法识别的按键：" + k);
    }
}
