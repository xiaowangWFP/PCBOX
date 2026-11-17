package com.phoneconsole.pc.screen;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * 显示器枚举：给屏幕推流提供「选择监视哪块屏」的能力。
 * gdigrab 通过 offset_x / offset_y / video_size 在虚拟桌面上裁剪指定显示器。
 */
public final class ScreenTargets {

    public record Target(int id, String name, Rectangle bounds, boolean primary) {
    }

    private final Object lock = new Object();
    private List<Target> targets = new ArrayList<>();
    private long lastRefresh;

    public List<Target> list() {
        synchronized (lock) {
            if (System.currentTimeMillis() - lastRefresh > 10_000) {
                refreshLocked();
            }
            return new ArrayList<>(targets);
        }
    }

    public Target get(int id) {
        synchronized (lock) {
            if (targets.isEmpty()) {
                refreshLocked();
            }
            for (Target target : targets) {
                if (target.id() == id) {
                    return target;
                }
            }
            return targets.isEmpty() ? null : targets.get(0);
        }
    }

    public Target primary() {
        return get(Integer.MIN_VALUE);
    }

    private void refreshLocked() {
        List<Target> result = new ArrayList<>();
        GraphicsDevice primary = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        int index = 0;
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle bounds = device.getDefaultConfiguration().getBounds();
            boolean isPrimary = device == primary;
            String name = "显示器 " + (index + 1) + (isPrimary ? "（主屏）" : "");
            result.add(new Target(index, name, bounds, isPrimary));
            index++;
        }
        if (!result.isEmpty()) {
            targets = result;
        }
        lastRefresh = System.currentTimeMillis();
    }
}
