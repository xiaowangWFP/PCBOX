package com.phoneconsole.pc.protocol;

/**
 * 单个按钮状态更新（PC -> 手机）。
 */
public class ButtonStateMessage {
    public String type = "button_state";
    public String buttonId;
    public String state;
    public String label;
    public String icon;
    public String bgColor;
}
