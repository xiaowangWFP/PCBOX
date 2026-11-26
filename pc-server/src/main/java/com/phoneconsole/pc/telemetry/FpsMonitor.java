package com.phoneconsole.pc.telemetry;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.ptr.DoubleByReference;
import com.sun.jna.ptr.IntByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 基于 Windows ETW（DXGI / D3D9 / DxgKrnl）的实时屏幕与前台应用帧率采集器。
 * <p>
 * 加载原生 fps_probe.dll，同时追踪：
 * 1. 前台窗口所属进程的真实渲染帧率（appFps）；
 * 2. 系统屏幕合成/扫描输出帧率（screenFps）。
 */
public final class FpsMonitor {

    private static final Logger log = LoggerFactory.getLogger(FpsMonitor.class);

    public static final class Sample {
        public final double fps;
        public final String source;

        public Sample(double fps, String source) {
            this.fps = fps;
            this.source = source;
        }
    }

    public interface NativeApi extends Library {
        int fps_start();

        void fps_set_target_pid(int pid);

        int fps_get(DoubleByReference outAppFps, DoubleByReference outScreenFps);

        void fps_stop();
    }

    private NativeApi api;
    private volatile boolean started;

    public synchronized boolean start() {
        if (started) {
            return true;
        }
        if (!Platform.isWindows()) {
            return false;
        }
        Path dll = resolveDllPath();
        if (dll == null) {
            log.info("未找到 fps_probe.dll，实时屏幕帧率采集未启用");
            return false;
        }
        try {
            api = Native.load(dll.toString(), NativeApi.class);
            if (api.fps_start() == 1) {
                started = true;
                log.info("实时屏幕帧率 ETW 采集已启动（{}）", dll);
                return true;
            }
            log.warn("fps_probe.dll 启动 ETW 会话失败（可能需要加入 Performance Log Users 或管理员权限）");
        } catch (Throwable t) {
            log.warn("加载 fps_probe.dll 失败：{}", t.getMessage());
        }
        api = null;
        return false;
    }

    public Sample sample() {
        if (!started || api == null) {
            return new Sample(-1, "");
        }
        try {
            int fgPid = foregroundPid();
            api.fps_set_target_pid(fgPid);
            DoubleByReference appRef = new DoubleByReference(-1.0);
            DoubleByReference scrRef = new DoubleByReference(-1.0);
            if (api.fps_get(appRef, scrRef) != 1) {
                return new Sample(-1, "");
            }
            double appFps = appRef.getValue();
            double scrFps = scrRef.getValue();
            if (appFps >= 1.0) {
                return new Sample(Math.round(appFps * 10.0) / 10.0, "foreground");
            }
            if (scrFps >= 0.0) {
                return new Sample(Math.round(scrFps * 10.0) / 10.0, "desktop");
            }
        } catch (Throwable t) {
            log.debug("读取实时 FPS 异常：{}", t.getMessage());
        }
        return new Sample(-1, "");
    }

    public synchronized void stop() {
        if (!started || api == null) {
            return;
        }
        try {
            api.fps_stop();
        } catch (Throwable ignored) {
            // ignore
        } finally {
            started = false;
            api = null;
        }
    }

    private static int foregroundPid() {
        try {
            WinDef.HWND hwnd = User32.INSTANCE.GetForegroundWindow();
            if (hwnd == null) {
                return 0;
            }
            IntByReference pidRef = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef);
            return Math.max(0, pidRef.getValue());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static Path resolveDllPath() {
        for (Path p : List.of(
                Path.of("native\\fps_probe.dll"),
                Path.of("pc-server\\native\\fps_probe.dll"),
                Path.of("drivers\\fps\\fps_probe.dll"),
                Path.of("..\\drivers\\fps\\fps_probe.dll"))) {
            if (Files.isRegularFile(p)) {
                return p.toAbsolutePath().normalize();
            }
        }
        return null;
    }
}

