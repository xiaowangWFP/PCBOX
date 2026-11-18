package com.phoneconsole.pc.screen;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.List;

/**
 * 显示器枚举：给屏幕推流提供「选择监视哪块屏」的能力。
 * <p>
 * AWT 给出的是 DPI 缩放后的逻辑坐标，而 ddagrab / gdigrab 需要物理像素，
 * 因此每块屏同时记录逻辑区域（界面展示）与物理区域（gdigrab 的 offset/size）。
 */
public final class ScreenTargets {

    public record Target(int id, String name, Rectangle bounds, Rectangle physical, boolean primary) {
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

    /** 按序号取显示器；序号不存在（比如上次使用了两块屏、这次只接了一块）时返回 null */
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
            return null;
        }
    }

    public Target primary() {
        synchronized (lock) {
            if (targets.isEmpty()) {
                refreshLocked();
            }
            for (Target target : targets) {
                if (target.primary()) {
                    return target;
                }
            }
            return targets.isEmpty() ? null : targets.get(0);
        }
    }

    private void refreshLocked() {
        List<Target> result = new ArrayList<>();
        GraphicsDevice primary = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        int index = 0;
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            GraphicsConfiguration configuration = device.getDefaultConfiguration();
            Rectangle bounds = configuration.getBounds();
            boolean isPrimary = device == primary;
            String name = "显示器 " + (index + 1) + (isPrimary ? "（主屏）" : "");
            result.add(new Target(index, name, bounds, physicalBounds(configuration), isPrimary));
            index++;
        }
        if (!result.isEmpty()) {
            targets = result;
        }
        lastRefresh = System.currentTimeMillis();
    }

    /** 逻辑坐标 × 该屏 DPI 缩放 = 物理像素；虚拟屏同样适用，gdigrab 可据此裁剪 */
    private static Rectangle physicalBounds(GraphicsConfiguration configuration) {
        Rectangle bounds = configuration.getBounds();
        AffineTransform transform = configuration.getDefaultTransform();
        double scaleX = transform == null ? 1.0 : transform.getScaleX();
        double scaleY = transform == null ? 1.0 : transform.getScaleY();
        int width = (int) Math.round(bounds.width * scaleX);
        int height = (int) Math.round(bounds.height * scaleY);
        return new Rectangle(bounds.x, bounds.y, width, height);
    }
}
