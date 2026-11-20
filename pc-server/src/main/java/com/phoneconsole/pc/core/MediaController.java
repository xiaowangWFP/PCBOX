package com.phoneconsole.pc.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 系统多媒体与音量控制。
 * Windows 下通过 user32 keybd_event 发送系统媒体键，兼容主流播放器与系统混音器；
 * 音量与静音状态优先走 Windows Core Audio（{@link AudioEndpoint}）读取真实值，
 * 读取失败时才回退到基于按键次数的估算值。麦克风静音同样走 Core Audio 的采集端点。
 */
public class MediaController {

    private static final Logger log = LoggerFactory.getLogger(MediaController.class);

    private final AudioEndpoint audio = new AudioEndpoint();
    /** 默认采集设备（麦克风），dataFlow=1 */
    private final AudioEndpoint mic = new AudioEndpoint(1);

    /** Core Audio 不可用时的估算值，仅用于顶栏展示 */
    private volatile int volume = 50;
    private volatile boolean muted = false;
    private volatile boolean micMuted = false;

    public void command(String command) {
        if (command == null) {
            throw new IllegalArgumentException("media 动作缺少 command 配置");
        }
        switch (command) {
            case "volume_up" -> {
                WinKey.press(WinKey.VK_VOLUME_UP);
                volume = Math.min(100, volume + 2);
                muted = false;
            }
            case "volume_down" -> {
                WinKey.press(WinKey.VK_VOLUME_DOWN);
                volume = Math.max(0, volume - 2);
            }
            case "mute" -> {
                Boolean current = audio.muted();
                if (current != null && audio.setMuted(!current)) {
                    muted = !current;
                } else {
                    WinKey.press(WinKey.VK_VOLUME_MUTE);
                    muted = !muted;
                }
            }
            case "play_pause" -> WinKey.press(WinKey.VK_MEDIA_PLAY_PAUSE);
            case "next" -> WinKey.press(WinKey.VK_MEDIA_NEXT_TRACK);
            case "prev" -> WinKey.press(WinKey.VK_MEDIA_PREV_TRACK);
            case "mic_mute" -> toggleMicMute();
            default -> throw new IllegalArgumentException("未知媒体指令：" + command);
        }
    }

    public int volume() {
        int real = audio.volumePercent();
        if (real >= 0) {
            volume = real;
        }
        return volume;
    }

    public boolean isMuted() {
        Boolean real = audio.muted();
        if (real != null) {
            muted = real;
        }
        return muted;
    }

    public boolean isMicMuted() {
        Boolean real = mic.muted();
        if (real != null) {
            micMuted = real;
        }
        return micMuted;
    }

    /**
     * 切换默认麦克风静音：优先走 Windows Core Audio 采集端点；
     * 端点不可用时回退为内部状态取反，保证界面状态仍可切换。
     */
    public boolean toggleMicMute() {
        Boolean current = mic.muted();
        if (current != null && mic.setMuted(!current)) {
            micMuted = !current;
        } else {
            micMuted = !micMuted;
            log.warn("麦克风端点不可用，仅切换内部状态：micMuted={}", micMuted);
        }
        log.info("麦克风静音切换完成：micMuted={}", micMuted);
        return micMuted;
    }

    public void close() {
        audio.close();
        mic.close();
    }
}
