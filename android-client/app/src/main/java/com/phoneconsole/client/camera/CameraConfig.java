package com.phoneconsole.client.camera;

/**
 * 手机端与 PC 端同步的虚拟摄像头配置与实时状态快照。
 */
public final class CameraConfig {

    public boolean available = true;
    public boolean permissionGranted = true;
    public boolean streaming = false;
    public boolean previewing = false;
    public boolean manual = false;
    public boolean autoDemand = true;
    public boolean consumerConnected = false;

    public int rtspPort = 8554;
    public int width = 1280;
    public int height = 720;
    public int fps = 30;
    public int bitrateKbps = 4000;

    /** "front" | "back" */
    public String facing = "front";
    /** 0 | 90 | 180 | 270 */
    public int rotation = 0;
    public boolean mirrorHorizontal = false;
    public boolean mirrorVertical = false;
    public boolean flash = false;
    public String error = "";

    public CameraConfig copy() {
        CameraConfig c = new CameraConfig();
        c.available = available;
        c.permissionGranted = permissionGranted;
        c.streaming = streaming;
        c.previewing = previewing;
        c.manual = manual;
        c.autoDemand = autoDemand;
        c.consumerConnected = consumerConnected;
        c.rtspPort = rtspPort;
        c.width = width;
        c.height = height;
        c.fps = fps;
        c.bitrateKbps = bitrateKbps;
        c.facing = facing;
        c.rotation = rotation;
        c.mirrorHorizontal = mirrorHorizontal;
        c.mirrorVertical = mirrorVertical;
        c.flash = flash;
        c.error = error;
        return c;
    }

    public boolean isFrontFacing() {
        return !"back".equalsIgnoreCase(facing);
    }

    public static int normalizeRotation(int deg) {
        int r = ((deg % 360) + 360) % 360;
        return (r / 90) * 90;
    }
}

