package com.phoneconsole.pc.protocol;

/**
 * 屏幕推流实时统计（PC -> 手机，每秒一次）。
 * 目标帧率是上限，actualFps 才是当前机器真正跑出来的帧率。
 */
public class ScreenStatsMessage {
    public String type = "screen_stats";
    public int fps;
    public double actualFps;
    /** 实际编码宽度（源屏幕较窄时不会放大） */
    public int width;
    public int height;
    public int kbps;
}
