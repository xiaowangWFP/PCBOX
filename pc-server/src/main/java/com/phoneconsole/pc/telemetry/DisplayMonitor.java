package com.phoneconsole.pc.telemetry;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.win32.W32APIOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * Windows 显示器刷新率采集：EnumDisplayDevices + EnumDisplaySettings。
 * <p>
 * 返回的是当前显示模式设置里的刷新率（如 165Hz），不是游戏实际渲染 FPS；
 * 开启 VRR / 动态刷新率时可能保持标称值。
 */
public final class DisplayMonitor {

    private static final int ENUM_CURRENT_SETTINGS = -1;
    private static final int DISPLAY_DEVICE_ATTACHED_TO_DESKTOP = 0x1;
    private static final int DISPLAY_DEVICE_MIRRORING_DRIVER = 0x8;

    public static final class DisplayInfo {
        public String id;
        public String name;
        public int width;
        public int height;
        public int hz;
        public boolean primary;
    }

    private final Object lock = new Object();

    public List<DisplayInfo> list() {
        List<DisplayInfo> result = new ArrayList<>();
        if (!Platform.isWindows()) {
            return result;
        }
        synchronized (lock) {
            for (int i = 0; ; i++) {
                DISPLAY_DEVICE device = new DISPLAY_DEVICE();
                device.cb = device.size();
                if (!User32.INSTANCE.EnumDisplayDevicesW(null, i, device, 0)) {
                    break;
                }
                if ((device.StateFlags & DISPLAY_DEVICE_ATTACHED_TO_DESKTOP) == 0
                        || (device.StateFlags & DISPLAY_DEVICE_MIRRORING_DRIVER) != 0) {
                    continue;
                }

                DEVMODE mode = new DEVMODE();
                mode.dmSize = (short) mode.size();
                if (!User32.INSTANCE.EnumDisplaySettingsW(
                        new WString(str(device.DeviceName)), ENUM_CURRENT_SETTINGS, mode)) {
                    continue;
                }

                DisplayInfo info = new DisplayInfo();
                info.id = str(device.DeviceName);
                info.name = str(device.DeviceString);
                if (info.name.isEmpty()) {
                    info.name = info.id;
                }
                info.width = mode.dmPelsWidth;
                info.height = mode.dmPelsHeight;
                info.hz = mode.dmDisplayFrequency;
                info.primary = mode.dmPositionX == 0 && mode.dmPositionY == 0;
                result.add(info);
            }
        }
        return result;
    }

    /** 按 id 查找；找不到时回退主屏，再回退第一块屏 */
    public DisplayInfo find(String id, List<DisplayInfo> displays) {
        if (displays == null || displays.isEmpty()) {
            return null;
        }
        if (id != null && !id.isEmpty()) {
            for (DisplayInfo display : displays) {
                if (id.equals(display.id)) {
                    return display;
                }
            }
        }
        for (DisplayInfo display : displays) {
            if (display.primary) {
                return display;
            }
        }
        return displays.get(0);
    }

    private static String str(char[] chars) {
        if (chars == null) {
            return "";
        }
        int length = 0;
        while (length < chars.length && chars[length] != 0) {
            length++;
        }
        return new String(chars, 0, length);
    }

    @Structure.FieldOrder({"cb", "DeviceName", "DeviceString", "StateFlags", "DeviceID", "DeviceKey"})
    public static class DISPLAY_DEVICE extends Structure {
        public int cb;
        public char[] DeviceName = new char[32];
        public char[] DeviceString = new char[128];
        public int StateFlags;
        public char[] DeviceID = new char[128];
        public char[] DeviceKey = new char[128];
    }

    @Structure.FieldOrder({
            "dmDeviceName", "dmSpecVersion", "dmDriverVersion", "dmSize", "dmDriverExtra", "dmFields",
            "dmPositionX", "dmPositionY", "dmDisplayOrientation", "dmDisplayFixedOutput",
            "dmColor", "dmDuplex", "dmYResolution", "dmTTOption", "dmCollate",
            "dmFormName", "dmLogPixels", "dmBitsPerPel", "dmPelsWidth", "dmPelsHeight",
            "dmDisplayFlags", "dmDisplayFrequency"
    })
    public static class DEVMODE extends Structure {
        public char[] dmDeviceName = new char[32];
        public short dmSpecVersion;
        public short dmDriverVersion;
        public short dmSize;
        public short dmDriverExtra;
        public int dmFields;
        public int dmPositionX;
        public int dmPositionY;
        public int dmDisplayOrientation;
        public int dmDisplayFixedOutput;
        public short dmColor;
        public short dmDuplex;
        public short dmYResolution;
        public short dmTTOption;
        public short dmCollate;
        public char[] dmFormName = new char[32];
        public short dmLogPixels;
        public int dmBitsPerPel;
        public int dmPelsWidth;
        public int dmPelsHeight;
        public int dmDisplayFlags;
        public int dmDisplayFrequency;
    }

    interface User32 extends Library {
        User32 INSTANCE = Native.load("user32", User32.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean EnumDisplayDevicesW(WString device, int devNum, DISPLAY_DEVICE lpDisplayDevice, int flags);

        boolean EnumDisplaySettingsW(WString deviceName, int modeNum, DEVMODE devMode);
    }
}
