package com.phoneconsole.pc.protocol;

/**
 * 屏幕监视状态回报（PC -> 手机）。
 */
public class ScreenStateMessage {
    public String type = "screen_state";
    public boolean streaming;
    public int fps;
    public int width;
    public int height;
    /** 拒绝或异常原因，如 usb_required / encoder_unavailable / encoder_error */
    public String reason;
}
