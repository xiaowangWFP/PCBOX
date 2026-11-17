package com.phoneconsole.client.model;

/**
 * 会话能力报文：PC 在连接建立后下发，标识当前通道与屏幕推流是否可用。
 */
public class SessionMessage {
    public String type;
    /** usb | lan */
    public String transport;
    public boolean screenStream;
    public int screenMaxFps;
}
