package com.phoneconsole.pc.protocol;

/**
 * 连接建立后下发的会话能力（PC -> 手机）。
 * 屏幕推流只允许走 USB（adb reverse 回环）通道，服务端按对端地址判定。
 */
public class SessionMessage {
    public String type = "session";
    /** usb | wireless_adb | lan */
    public String transport;
    /** 是否允许开启屏幕监视推流 */
    public boolean screenStream;
    /** 推流帧率上限 */
    public int screenMaxFps;
}
