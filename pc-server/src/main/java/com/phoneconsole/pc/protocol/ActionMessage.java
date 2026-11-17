package com.phoneconsole.pc.protocol;

/**
 * 触控动作上报（手机 -> PC）。
 * {"type":"action","pageId":"...","buttonId":"...","event":"click","timestamp":0}
 */
public class ActionMessage {
    public String type;
    public String pageId;
    public String buttonId;
    /** click | long_press_start | long_press_end */
    public String event;
    public long timestamp;
}
