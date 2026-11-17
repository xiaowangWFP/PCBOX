package com.phoneconsole.pc.telemetry;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 通过 Win32 API 读取当前前台窗口所属进程的可执行文件名（如 idea64.exe）。
 * 仅 Windows 有效，其他系统返回 null。
 */
final class ForegroundWindow {

    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
    private static final int MAX_PATH_CHARS = 1024;

    private ForegroundWindow() {
    }

    static String exeName() {
        try {
            Pointer hwnd = User32Ex.INSTANCE.GetForegroundWindow();
            if (hwnd == null) {
                return null;
            }
            IntByReference pid = new IntByReference();
            User32Ex.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
            if (pid.getValue() <= 0) {
                return null;
            }
            Pointer process = Kernel32Ex.INSTANCE.OpenProcess(
                    PROCESS_QUERY_LIMITED_INFORMATION, false, pid.getValue());
            if (process == null) {
                return null;
            }
            try {
                byte[] buffer = new byte[MAX_PATH_CHARS * 2];
                IntByReference size = new IntByReference(MAX_PATH_CHARS);
                if (!Kernel32Ex.INSTANCE.QueryFullProcessImageNameW(process, 0, buffer, size)) {
                    return null;
                }
                String path = new String(buffer, 0, size.getValue() * 2, StandardCharsets.UTF_16LE);
                int index = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
                String name = index >= 0 ? path.substring(index + 1) : path;
                return name.toLowerCase(Locale.ROOT);
            } finally {
                Kernel32Ex.INSTANCE.CloseHandle(process);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private interface User32Ex extends Library {
        User32Ex INSTANCE = Native.load("user32", User32Ex.class);

        Pointer GetForegroundWindow();

        int GetWindowThreadProcessId(Pointer hWnd, IntByReference pid);
    }

    private interface Kernel32Ex extends Library {
        Kernel32Ex INSTANCE = Native.load("kernel32", Kernel32Ex.class);

        Pointer OpenProcess(int desiredAccess, boolean inheritHandle, int processId);

        boolean QueryFullProcessImageNameW(Pointer process, int flags, byte[] buffer, IntByReference size);

        boolean CloseHandle(Pointer handle);
    }
}
