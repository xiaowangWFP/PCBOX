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
}
