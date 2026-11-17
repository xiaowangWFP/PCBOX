package com.phoneconsole.pc.core;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import java.util.Locale;

/**
 * Windows 虚拟键（Virtual Key）原生发送，用于 java.awt.Robot 不支持的媒体键 / 音量键。
 */
final class WinKey {

    /** Windows 虚拟键码 */
    static final int VK_VOLUME_MUTE = 0xAD;
    static final int VK_VOLUME_DOWN = 0xAE;
    static final int VK_VOLUME_UP = 0xAF;
    static final int VK_MEDIA_NEXT_TRACK = 0xB0;
    static final int VK_MEDIA_PREV_TRACK = 0xB1;
    static final int VK_MEDIA_PLAY_PAUSE = 0xB3;

    private static final int KEYEVENTF_EXTENDEDKEY = 0x0001;
    private static final int KEYEVENTF_KEYUP = 0x0002;

    private WinKey() {
    }

    static boolean supported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    static void press(int virtualKey) {
        if (!supported()) {
            throw new UnsupportedOperationException("媒体键仅在 Windows 上可用");
        }
        User32Ex.INSTANCE.keybd_event((byte) virtualKey, (byte) 0, KEYEVENTF_EXTENDEDKEY, Pointer.NULL);
        User32Ex.INSTANCE.keybd_event((byte) virtualKey, (byte) 0,
                KEYEVENTF_EXTENDEDKEY | KEYEVENTF_KEYUP, Pointer.NULL);
    }

    private interface User32Ex extends Library {
        User32Ex INSTANCE = Native.load("user32", User32Ex.class);

        void keybd_event(byte virtualKey, byte scanCode, int flags, Pointer extraInfo);
    }
}
