package com.phoneconsole.pc.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * 可选显示器列表（PC -> 手机）。
 * id 传给 screen_control 的 monitor 字段即可切换推流源。
 */
public class ScreenDevicesMessage {
    public String type = "screen_devices";
    public int current;
    public List<Device> devices = new ArrayList<>();

    public static class Device {
        public int id;
        public String name;
        public int width;
        public int height;
        public int x;
        public int y;
        public boolean primary;
    }
}
