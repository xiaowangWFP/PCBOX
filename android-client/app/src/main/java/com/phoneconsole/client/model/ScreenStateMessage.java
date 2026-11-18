package com.phoneconsole.client.model;

/**
 * 推流状态回报：streaming=false 时 reason 说明原因。
 * 分辨率与帧率由服务端按 config.json 实时下发，客户端不再写死 1080p60。
 */
public class ScreenStateMessage {
    public String type;
    public boolean streaming;
    public int fps;
    public int width;
    public int height;
    /** usb_required / encoder_unavailable / encoder_error */
    public String reason;
}
