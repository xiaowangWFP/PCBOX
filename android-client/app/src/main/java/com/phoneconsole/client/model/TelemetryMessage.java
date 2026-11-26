package com.phoneconsole.client.model;

/**
 * 硬件监控推送报文。
 */
public class TelemetryMessage {
    public String type;
    public double cpu;
    public double mem;
    /** GPU 占用百分比；-1 表示 PC 端无法采集 */
    public double gpu = -1;
    /** 已用显存（MB）；-1 表示 PC 端无法采集 */
    public double vram = -1;
    /** 显存总量（MB）；-1 表示无法采集 */
    public double vramTotal = -1;
    public String netUp;
    public String netDown;
    public int volume;
    public boolean isMuted;
    /** 麦克风是否静音 */
    public boolean micMuted;
    /** 当前选中显示器的刷新率；-1 表示无法采集 */
    public double displayHz = -1;
    /** 实时屏幕/前台应用帧率（FPS，ETW）；-1 表示不可用 */
    public double fps = -1;
    /** 帧率来源（foreground | desktop） */
    public String fpsSource = "";
    public String displayId;
    public String displayName;
    /** 所有活动显示器，供选择 */
    public java.util.List<DisplayInfo> displays;

    public static class DisplayInfo {
        public String id;
        public String name;
        public int width;
        public int height;
        public int hz;
        public boolean primary;
    }
}
