package com.phoneconsole.pc.core;


import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.ptr.IntByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.AWTException;
import java.awt.Robot;
import java.awt.event.InputEvent;

/**
 * 无线触控板鼠标控制器。
 * <p>
 * 相对移动走 Windows {@code SendInput}（MOUSEEVENTF_MOVE），好处是与屏幕 DPI 缩放无关：
 * AWT 的 {@code MouseInfo} 返回逻辑坐标、{@code Robot.mouseMove} 也按逻辑坐标解释，
 * 在多显示器不同缩放比例下会出现明显偏移，因此不使用 Robot 做相对移动。
 * 点击与滚轮仍走 {@link Robot}。
 */
public final class MouseController {
    private static final Logger log = LoggerFactory.getLogger(MouseController.class);
    private static final int MOUSEEVENTF_MOVE = 0x0001;
    private static volatile Robot robot;

    /** user32.mouse_event：相对位移不受显示器 DPI 缩放影响 */
    interface User32Ext extends Library {
        User32Ext INSTANCE = Native.load("user32", User32Ext.class);

        void mouse_event(int flags, int dx, int dy, int data, IntByReference extraInfo);
    }

    private MouseController() {
    }

    private static synchronized Robot getRobot() throws AWTException {
        if (robot == null) {
            robot = new Robot();
            robot.setAutoDelay(5);
        }
        return robot;
    }

    /**
     * 相对移动鼠标指针（与 DPI 无关）。
     *
     * @param dx 水平位移（像素）
     * @param dy 垂直位移（像素）
     */
    public static synchronized void moveBy(int dx, int dy) {
        if (dx == 0 && dy == 0) {
            return;
        }
        if (!Platform.isWindows()) {
            log.warn("相对移动仅在 Windows 下可用");
            return;
        }
        try {
            User32Ext.INSTANCE.mouse_event(MOUSEEVENTF_MOVE, dx, dy, 0, null);
        } catch (Throwable t) {
            log.error("鼠标相对移动失败: dx={}, dy={}", dx, dy, t);
        }
    }

    /**
     * 模拟鼠标点击。
     *
     * @param mouseButton "left" | "middle" | "right"
     */
    public static synchronized void click(String mouseButton) {
        int mask = toButtonMask(mouseButton);
        if (mask == 0) {
            log.warn("忽略未知鼠标按键: {}", mouseButton);
            return;
        }
        try {
            Robot r = getRobot();
            r.mousePress(mask);
            r.mouseRelease(mask);
        } catch (Exception e) {
            log.error("鼠标点击失败: button={}", mouseButton, e);
        }
    }

    /**
     * 模拟滚轮滚动。
     * 安卓端约定正值向上、负值向下，而 {@code Robot.mouseWheel} 负值向上、正值向下，因此取反。
     *
     * @param deltaY 安卓端传入的滚动量（正数向上，负数向下）
     */
    public static synchronized void scroll(int deltaY) {
        if (deltaY == 0) {
            return;
        }
        try {
            getRobot().mouseWheel(-deltaY);
        } catch (Exception e) {
            log.error("鼠标滚轮滚动失败: deltaY={}", deltaY, e);
        }
    }

    private static int toButtonMask(String mouseButton) {
        if (mouseButton == null) {
            return 0;
        }
        return switch (mouseButton.trim().toLowerCase()) {
            case "left" -> InputEvent.BUTTON1_DOWN_MASK;
            case "middle" -> InputEvent.BUTTON2_DOWN_MASK;
            case "right" -> InputEvent.BUTTON3_DOWN_MASK;
            default -> 0;
        };
    }
}
