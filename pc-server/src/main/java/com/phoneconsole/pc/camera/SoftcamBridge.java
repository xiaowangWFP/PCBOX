package com.phoneconsole.pc.camera;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * DirectShow 虚拟摄像头驱动（softcam.dll）JNA 桥接层。
 * <p>
 * 只负责加载 64 位 softcam.dll、探测系统 COM 注册状态、创建/销毁虚拟摄像头句柄以及写入 BGR24 原始帧；
 * 不包含拉流、解码或信令状态机逻辑。
 */
public final class SoftcamBridge {

    private static final Logger log = LoggerFactory.getLogger(SoftcamBridge.class);

    /** DirectShow Softcam COM CLSID，与 softcam_installer.exe / regsvr32 注册位置一致 */
    private static final String CLSID_KEY = "CLSID\\{AEF3B972-5FA5-4647-9571-358EB472BC9E}\\InprocServer32";
    private static final String CLASSES_CLSID_KEY = "SOFTWARE\\Classes\\" + CLSID_KEY;

    public interface SoftcamApi extends Library {
        Pointer scCreateCamera(int width, int height, float framerate);

        void scDeleteCamera(Pointer camera);

        void scSendFrame(Pointer camera, byte[] imageBits);

        boolean scIsConnected(Pointer camera);
    }

    private final Object lock = new Object();
    private SoftcamApi api;
    private Path dllPath;
    private Pointer cameraHandle;
    private int width;
    private int height;

    /** 判断系统注册表中是否已注册 DirectShow Softcam Filter（未注册时微信/会议软件看不到设备） */
    public static boolean isFilterRegistered() {
        return readRegisteredDllPath() != null;
    }

    /** 读取注册表中已登记的 softcam.dll 路径；未注册返回 null */
    public static String readRegisteredDllPath() {
        try {
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_LOCAL_MACHINE, CLASSES_CLSID_KEY)) {
                String path = Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, CLASSES_CLSID_KEY, null);
                if (path != null && !path.isBlank()) {
                    return path;
                }
            }
        } catch (Throwable ignored) {
            // 忽略读取异常，尝试 HKCR
        }
        try {
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_CLASSES_ROOT, CLSID_KEY)) {
                String path = Advapi32Util.registryGetStringValue(WinReg.HKEY_CLASSES_ROOT, CLSID_KEY, null);
                if (path != null && !path.isBlank()) {
                    return path;
                }
            }
        } catch (Throwable ignored) {
            // 未注册
        }
        return null;
    }

    /** 查找可用的 softcam.dll 文件路径（优先使用系统已注册路径） */
    public static Path resolveDllPath() {
        List<Path> candidates = new ArrayList<>();
        String registered = readRegisteredDllPath();
        if (registered != null && !registered.isBlank()) {
            candidates.add(Path.of(registered));
        }
        candidates.add(Path.of("C:\\PhoneConsoleSoftcam\\softcam.dll"));
        candidates.add(Path.of("drivers\\softcam\\softcam.dll"));
        candidates.add(Path.of("..\\drivers\\softcam\\softcam.dll"));
        candidates.add(Path.of("tools\\vcamdroid\\softcam.dll"));
        candidates.add(Path.of("..\\tools\\vcamdroid\\softcam.dll"));

        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    /** 加载 softcam.dll 并打开指定分辨率的虚拟摄像头句柄（framerate=0 表示来帧即推送，不额外限速） */
    public boolean open(int targetWidth, int targetHeight) {
        int alignedW = Math.max(4, targetWidth & ~3);
        int alignedH = Math.max(4, targetHeight & ~3);

        synchronized (lock) {
            if (cameraHandle != null && width == alignedW && height == alignedH) {
                return true;
            }
            closeLocked();

            Path resolved = resolveDllPath();
            if (resolved == null) {
                log.warn("未找到 softcam.dll，虚拟摄像头功能不可用");
                return false;
            }
            try {
                if (api == null || !resolved.equals(dllPath)) {
                    api = Native.load(resolved.toString(), SoftcamApi.class);
                    dllPath = resolved;
                }
                Pointer handle = api.scCreateCamera(alignedW, alignedH, 0.0f);
                if (handle == null) {
                    log.warn("scCreateCamera({}x{}) 返回 null（可能已有其他虚拟摄像头实例占用）", alignedW, alignedH);
                    return false;
                }
                cameraHandle = handle;
                width = alignedW;
                height = alignedH;
                log.info("虚拟摄像头句柄已就绪：{}x{}（{}）", alignedW, alignedH, resolved);
                return true;
            } catch (Throwable t) {
                log.error("加载或创建 softcam 失败（{}）：{}", resolved, t.getMessage());
                cameraHandle = null;
                return false;
            }
        }
    }

    public boolean isOpen() {
        synchronized (lock) {
            return cameraHandle != null;
        }
    }

    /** 是否有外部程序（如微信、腾讯会议、OBS）正在读取虚拟摄像头画面 */
    public boolean isConnected() {
        synchronized (lock) {
            if (cameraHandle == null || api == null) {
                return false;
            }
            try {
                return api.scIsConnected(cameraHandle);
            } catch (Throwable t) {
                return false;
            }
        }
    }

    /** 向虚拟摄像头推送一帧 BGR24 原始像素数据（长度必须等于 width * height * 3） */
    public boolean sendFrame(byte[] bgr24) {
        if (bgr24 == null) {
            return false;
        }
        synchronized (lock) {
            if (cameraHandle == null || api == null) {
                return false;
            }
            int expected = width * height * 3;
            if (bgr24.length != expected) {
                return false;
            }
            try {
                api.scSendFrame(cameraHandle, bgr24);
                return true;
            } catch (Throwable t) {
                log.warn("scSendFrame 异常：{}", t.getMessage());
                return false;
            }
        }
    }

    public void close() {
        synchronized (lock) {
            closeLocked();
        }
    }

    public int width() {
        synchronized (lock) {
            return width;
        }
    }

    public int height() {
        synchronized (lock) {
            return height;
        }
    }

    public String loadedDllPath() {
        synchronized (lock) {
            return dllPath == null ? "" : dllPath.toString();
        }
    }

    public long cameraHandlePeer() {
        synchronized (lock) {
            return cameraHandle == null ? 0L : Pointer.nativeValue(cameraHandle);
        }
    }

    private void closeLocked() {
        if (cameraHandle != null && api != null) {
            try {
                api.scDeleteCamera(cameraHandle);
            } catch (Throwable ignored) {
                // 清理失败不阻塞退出
            }
        }
        cameraHandle = null;
        width = 0;
        height = 0;
    }
}

