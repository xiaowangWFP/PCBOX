package com.phoneconsole.pc.core;

import com.sun.jna.Function;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.ptr.FloatByReference;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Windows Core Audio（IAudioEndpointVolume）封装：读取/设置系统主音量与静音状态。
 * <p>
 * 直接按 vtable 索引调用 COM 方法，避免引入额外原生库：
 * IMMDeviceEnumerator#GetDefaultAudioEndpoint(4) → IMMDevice#Activate(3) → IAudioEndpointVolume。
 * 任何一步失败都会退化为不可用状态（{@link #isAvailable()} = false），调用方回退到估算值。
 */
public class AudioEndpoint {

    private static final Logger log = LoggerFactory.getLogger(AudioEndpoint.class);

    private static final String CLSID_MM_DEVICE_ENUMERATOR = "{BCDE0395-E52F-467C-8E3D-C4579291692E}";
    private static final String IID_IMM_DEVICE_ENUMERATOR = "{A95664D2-9614-4F35-A746-DE8DB63617E6}";
    private static final String IID_IAUDIO_ENDPOINT_VOLUME = "{5CDF2C82-841E-4546-9722-0CF74078229A}";

    /** 数据流向：0 = 播放（扬声器），1 = 采集（麦克风） */
    private static final int E_CONSOLE = 0;
    private static final int CLSCTX_ALL = 0x17;

    private final int dataFlow;

    private static final int VTABLE_RELEASE = 2;
    private static final int VTABLE_GET_DEFAULT_ENDPOINT = 4;
    private static final int VTABLE_ACTIVATE = 3;
    private static final int VTABLE_SET_MASTER_SCALAR = 7;
    private static final int VTABLE_GET_MASTER_SCALAR = 9;
    private static final int VTABLE_SET_MUTE = 14;
    private static final int VTABLE_GET_MUTE = 15;

    private static final long RETRY_COOLDOWN_MS = 5000;

    private Pointer enumerator;
    private Pointer device;
    private Pointer endpoint;
    private long lastAttemptAt;
    private boolean warnLogged;

    public AudioEndpoint() {
        this(0);
    }

    /**
     * @param dataFlow 0 = 默认播放设备，1 = 默认采集设备（麦克风）
     */
    public AudioEndpoint(int dataFlow) {
        this.dataFlow = dataFlow;
    }

    /** 系统默认播放端点是否可用 */
    public synchronized boolean isAvailable() {
        if (!Platform.isWindows()) {
            return false;
        }
        if (endpoint == null) {
            long now = System.currentTimeMillis();
            if (lastAttemptAt == 0 || now - lastAttemptAt >= RETRY_COOLDOWN_MS) {
                lastAttemptAt = now;
                initialize();
            }
        }
        return endpoint != null;
    }

    /** @return 主音量 0~100；不可用时返回 -1 */
    public synchronized int volumePercent() {
        if (!isAvailable()) {
            return -1;
        }
        try {
            comInit();
            FloatByReference level = new FloatByReference();
            int hr = invoke(endpoint, VTABLE_GET_MASTER_SCALAR, level);
            if (hr != 0) {
                // 默认音频设备可能已切换或失效，释放句柄以便下次重连
                close();
                return -1;
            }
            return Math.round(Math.max(0, Math.min(1, level.getValue())) * 100);
        } catch (Throwable t) {
            log.debug("读取系统音量失败：{}", t.getMessage());
            close();
            return -1;
        }
    }

    /** @return 是否静音；不可用时返回 null */
    public synchronized Boolean muted() {
        if (!isAvailable()) {
            return null;
        }
        try {
            comInit();
            IntByReference mute = new IntByReference();
            int hr = invoke(endpoint, VTABLE_GET_MUTE, mute);
            if (hr != 0) {
                close();
                return null;
            }
            return mute.getValue() != 0;
        } catch (Throwable t) {
            log.debug("读取静音状态失败：{}", t.getMessage());
            close();
            return null;
        }
    }

    /** 设置静音，返回是否成功 */
    public synchronized boolean setMuted(boolean value) {
        if (!isAvailable()) {
            return false;
        }
        try {
            comInit();
            int hr = invoke(endpoint, VTABLE_SET_MUTE, value ? 1 : 0, Pointer.NULL);
            if (hr != 0) {
                close();
                return false;
            }
            return true;
        } catch (Throwable t) {
            log.debug("设置静音失败：{}", t.getMessage());
            close();
            return false;
        }
    }

    /** 设置主音量（0~100），返回是否成功 */
    public synchronized boolean setVolumePercent(int percent) {
        if (!isAvailable()) {
            return false;
        }
        try {
            comInit();
            float scalar = Math.max(0, Math.min(100, percent)) / 100f;
            int hr = invoke(endpoint, VTABLE_SET_MASTER_SCALAR, scalar, Pointer.NULL);
            if (hr != 0) {
                close();
                return false;
            }
            return true;
        } catch (Throwable t) {
            log.debug("设置音量失败：{}", t.getMessage());
            close();
            return false;
        }
    }

    public synchronized void close() {
        release(endpoint);
        release(device);
        release(enumerator);
        endpoint = null;
        device = null;
        enumerator = null;
    }

    private void initialize() {
        try {
            comInit();
            PointerByReference enumeratorRef = new PointerByReference();
            Ole32.INSTANCE.CoCreateInstance(new Guid.CLSID(CLSID_MM_DEVICE_ENUMERATOR), null,
                    CLSCTX_ALL, new Guid.IID(IID_IMM_DEVICE_ENUMERATOR), enumeratorRef);
            enumerator = enumeratorRef.getValue();
            if (enumerator == null) {
                return;
            }
            PointerByReference deviceRef = new PointerByReference();
            if (invoke(enumerator, VTABLE_GET_DEFAULT_ENDPOINT, dataFlow, E_CONSOLE, deviceRef) != 0) {
                close();
                return;
            }
            device = deviceRef.getValue();
            if (device == null) {
                close();
                return;
            }

            Guid.IID iid = new Guid.IID(IID_IAUDIO_ENDPOINT_VOLUME);
            iid.write();
            PointerByReference endpointRef = new PointerByReference();
            if (invoke(device, VTABLE_ACTIVATE, iid.getPointer(), CLSCTX_ALL, Pointer.NULL, endpointRef) != 0) {
                close();
                return;
            }
            endpoint = endpointRef.getValue();
            if (endpoint == null) {
                close();
                return;
            }
            warnLogged = false;
            log.info(dataFlow == 0
                    ? "系统音量读取已启用（Windows Core Audio）"
                    : "麦克风音量/静音控制已启用（Windows Core Audio）");
        } catch (Throwable t) {
            close();
            if (!warnLogged) {
                warnLogged = true;
                log.warn("Core Audio 初始化失败，音量回退为估算值：{}", t.getMessage());
            }
        }
    }

    private void comInit() {
        // 已初始化的线程返回 S_FALSE，可安全重复调用
        Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED);
    }

    private void release(Pointer object) {
        if (object != null) {
            try {
                invoke(object, VTABLE_RELEASE);
            } catch (Throwable ignored) {
                // 释放失败不影响退出
            }
        }
    }

    /** 按 vtable 索引调用 COM 方法，隐式把 this 作为第一个参数 */
    private static int invoke(Pointer object, int vtableIndex, Object... args) {
        Pointer vtable = object.getPointer(0);
        Pointer functionPointer = vtable.getPointer((long) vtableIndex * Native.POINTER_SIZE);
        Function function = Function.getFunction(functionPointer, Function.ALT_CONVENTION);
        Object[] full = new Object[args.length + 1];
        full[0] = object;
        System.arraycopy(args, 0, full, 1, args.length);
        return function.invokeInt(full);
    }
}
