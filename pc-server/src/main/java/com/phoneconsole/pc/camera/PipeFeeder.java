package com.phoneconsole.pc.camera;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 原生 8MB 管道 + 直写 softcam.dll 桥接器（pipe_feeder.dll）。
 * 解决 Java ProcessBuilder 默认 4KB 管道导致 1080p BGR24（每帧 6.2MB）跌到 9fps 的瓶颈。
 */
public final class PipeFeeder {

    private static final Logger log = LoggerFactory.getLogger(PipeFeeder.class);

    public interface Api extends Library {
        Pointer feeder_start(WString cmdLine, int width, int height, long softcamHandlePeer, WString softcamDllPath);

        int feeder_is_running(Pointer handle);

        long feeder_get_frames(Pointer handle);

        double feeder_get_fps(Pointer handle);

        int feeder_get_error(Pointer handle, byte[] outBuf, int bufCap);

        void feeder_stop(Pointer handle);
    }

    private static Api api;
    private static boolean loadAttempted;

    private Pointer session;
    private long lastFrames;

    public static synchronized boolean isAvailable() {
        return ensureLoaded() != null;
    }

    private static synchronized Api ensureLoaded() {
        if (loadAttempted) {
            return api;
        }
        loadAttempted = true;
        Path dll = resolveDllPath();
        if (dll == null) {
            log.info("未找到 pipe_feeder.dll，摄像头拉流将回退到 Java 管道模式");
            return null;
        }
        try {
            api = Native.load(dll.toString(), Api.class);
            log.info("已加载原生 8MB 摄像头管道加速库：{}", dll);
            return api;
        } catch (Throwable t) {
            log.warn("加载 pipe_feeder.dll 失败：{}", t.getMessage());
            api = null;
            return null;
        }
    }

    private static Path resolveDllPath() {
        for (Path p : List.of(
                Path.of("native\\pipe_feeder.dll"),
                Path.of("pc-server\\native\\pipe_feeder.dll"),
                Path.of("drivers\\softcam\\pipe_feeder.dll"),
                Path.of("..\\drivers\\softcam\\pipe_feeder.dll"))) {
            if (Files.isRegularFile(p)) {
                return p.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    public boolean start(List<String> cmdArgs, int width, int height, long softcamPeer, String softcamDllPath) {
        stop();
        Api nativeApi = ensureLoaded();
        if (nativeApi == null || softcamPeer == 0 || softcamDllPath == null || softcamDllPath.isBlank()) {
            return false;
        }
        String cmdLine = buildWindowsCommandLine(cmdArgs);
        Pointer h = nativeApi.feeder_start(
                new WString(cmdLine),
                width,
                height,
                softcamPeer,
                new WString(softcamDllPath));
        if (h == null) {
            return false;
        }
        session = h;
        lastFrames = 0L;
        return true;
    }

    public boolean isRunning() {
        Pointer h = session;
        Api nativeApi = api;
        if (h == null || nativeApi == null) {
            return false;
        }
        return nativeApi.feeder_is_running(h) != 0;
    }

    public long pollNewFrames() {
        Pointer h = session;
        Api nativeApi = api;
        if (h == null || nativeApi == null) {
            return 0L;
        }
        long total = nativeApi.feeder_get_frames(h);
        long delta = Math.max(0L, total - lastFrames);
        lastFrames = total;
        return delta;
    }

    public double fps() {
        Pointer h = session;
        Api nativeApi = api;
        if (h == null || nativeApi == null) {
            return 0.0;
        }
        return nativeApi.feeder_get_fps(h);
    }

    public String lastError() {
        Pointer h = session;
        Api nativeApi = api;
        if (h == null || nativeApi == null) {
            return "";
        }
        byte[] buf = new byte[1024];
        int n = nativeApi.feeder_get_error(h, buf, buf.length);
        return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8).trim() : "";
    }

    public void stop() {
        Pointer h = session;
        session = null;
        lastFrames = 0L;
        Api nativeApi = api;
        if (h != null && nativeApi != null) {
            nativeApi.feeder_stop(h);
        }
    }

    private static String buildWindowsCommandLine(List<String> args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(quoteArg(args.get(i)));
        }
        return sb.toString();
    }

    private static String quoteArg(String arg) {
        if (arg == null || arg.isEmpty()) {
            return "\"\"";
        }
        boolean needsQuote = false;
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (Character.isWhitespace(c) || c == '"') {
                needsQuote = true;
                break;
            }
        }
        if (!needsQuote) {
            return arg;
        }
        StringBuilder sb = new StringBuilder("\"");
        int backslashes = 0;
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (c == '\\') {
                backslashes++;
            } else if (c == '"') {
                for (int b = 0; b < backslashes * 2 + 1; b++) {
                    sb.append('\\');
                }
                sb.append('"');
                backslashes = 0;
            } else {
                for (int b = 0; b < backslashes; b++) {
                    sb.append('\\');
                }
                sb.append(c);
                backslashes = 0;
            }
        }
        for (int b = 0; b < backslashes * 2; b++) {
            sb.append('\\');
        }
        sb.append('"');
        return sb.toString();
    }
}

