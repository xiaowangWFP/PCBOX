package com.phoneconsole.client.model;

import java.util.List;

/**
 * 可选显示器列表：PC 下发，客户端据此选择要监视的屏幕。
 */
public class ScreenDevicesMessage {
    public String type;
    public int current;
    public List<Device> devices;

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
