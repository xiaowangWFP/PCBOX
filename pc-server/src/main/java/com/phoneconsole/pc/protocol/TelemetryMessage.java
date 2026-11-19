package com.phoneconsole.pc.protocol;

/**
 * 硬件监控推送（PC -> 手机，默认每秒一次）。
 */
public class TelemetryMessage {
    public String type = "telemetry";
    public double cpu;
    public double mem;
    /** GPU 占用百分比；-1 表示当前系统无法采集 */
    public double gpu = -1;
    /** 已用显存（MB）；-1 表示当前系统无法采集 */
    public double vram = -1;
    public String netUp;
    public String netDown;
    public int volume;
    public boolean isMuted;
}
