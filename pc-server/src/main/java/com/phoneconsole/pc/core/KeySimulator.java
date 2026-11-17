package com.phoneconsole.pc.core;

import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.util.List;

/**
 * 基于 java.awt.Robot 的系统级按键模拟。
 * 支持修饰键组合（如 ctrl+shift+esc）与单键。
 */
public final class KeySimulator {

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
