package com.phoneconsole.pc.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * config.json 的内存映射。字段全部 public，便于 Gson 直接读写。
 */
public class AppConfig {

    public Network network = new Network();
    public Screen screen = new Screen();
    public Camera camera = new Camera();
    public AutoUnlock autoUnlock = new AutoUnlock();
    public String defaultPageId = "default_page";
    public List<PageConfig> pages = new ArrayList<>();
    public List<AppBinding> appBindings = new ArrayList<>();

    /** 网络与 ADB 参数 */
    public static class Network {
        public int wsPort = 8500;
        public int httpPort = 8501;
        public String adbExecutable = "adb";
        public long reversePollMs = 3000;
    }

    /**
     * 屏幕推流参数（P0 全部外置到 config.json，可按需回滚到 1080p60）：
     * 输出分辨率上限、USB/LAN 帧率、缩放算法、x264 预设与码率预算都可配。
     */
    public static class Screen {
        public String ffmpegExecutable = "ffmpeg";
        /** 采集目标：-1 = 整块虚拟桌面（镜像），0 起为显示器序号（扩展屏只抓那一块） */
        public int monitor = -1;
        /** 输出分辨率上限（像素预算），按采集源比例等比缩放、不补黑边；1080p 档改回 1920x1080 */
        public int maxWidth = 1280;
        public int maxHeight = 720;
        /** USB 通道（adb reverse）帧率上限；需要高刷可改回 60 */
        public int usbFps = 30;
        /** 局域网通道帧率上限 */
        public int lanFps = 24;
        /** 峰值码率（kbps）；bufsize 自动取其 2 倍 */
        public int maxBitrateKbps = 3000;
        /** 缩放算法：fast_bilinear 最省 CPU；追求画质可改回 bicubic */
        public String scaleFlags = "fast_bilinear";
        /** libx264 预设：ultrafast 最省 CPU；画质优先可改回 veryfast */
        public String x264Preset = "ultrafast";
        /** 编码线程数；0 = 交给 ffmpeg 自动分配，建议 2~4 防止抢占前台 */
        public int x264Threads = 0;
    }

    /** 手机虚拟摄像头参数（输出到 Windows DirectShow Softcam，供微信 / 会议软件调用） */
    public static class Camera {
        public boolean enabled = true;
        public int rtspPort = 8554;
        /** 输出分辨率，softcam 要求宽高为 4 的倍数；默认 1280x720 适配微信与会议软件 */
        public int width = 1280;
        public int height = 720;
        public int fps = 30;
        public int bitrateKbps = 4000;
        /** 按需自动启停：开启时仅在微信/会议软件打开摄像头时唤醒手机推流 */
        public boolean autoDemand = true;
        /** 默认镜头：front | back */
        public String facing = "front";
        /** 画面旋转角度：0 | 90 | 180 | 270 */
        public int rotation = 0;
        /** 水平镜像 / 垂直翻转 */
        public boolean mirrorHorizontal = false;
        public boolean mirrorVertical = false;
    }

    /** USB 有线 / 无线调试接入自动亮屏、输入密码解锁并打开手机端 App */
    public static class AutoUnlock {
        public boolean enabled = true;
        /** 是否启用无线调试（ADB over Wi-Fi）自动连接与亮屏解锁 */
        public boolean wirelessEnabled = false;
        /** 无线调试地址 IP:端口，例如 10.26.3.135:33007 */
        public String wirelessEndpoint = "";
        /** 当固定端口连接失败时，自动通过 adb mdns services 发现 _adb-tls-connect._tcp 端口 */
        public boolean wirelessMdnsAutoDiscover = true;
        /** on_connect（每次连接触发）| on_startup（仅服务启动首次上线触发） */
        public String triggerMode = "on_connect";
        /** 手机锁屏密码（留空则仅亮屏上滑，不输入密码） */
        public String password = "";
        /** 解锁前上滑调出密码界面 */
        public boolean swipeToUnlock = true;
        /** 解锁后自动拉起手机端应用 */
        public boolean launchApp = true;
        public String packageName = "com.phoneconsole.client";
        public String activityName = "com.phoneconsole.client.MainActivity";
    }

    /** 一个手机端页面（一屏按钮） */
    public static class PageConfig {
        public String id;
        public String title;
        public Grid grid = new Grid();
        public List<ButtonConfig> buttons = new ArrayList<>();

        public ButtonConfig button(String buttonId) {
            if (buttonId == null) {
                return null;
            }
            for (ButtonConfig b : buttons) {
                if (buttonId.equals(b.id)) {
                    return b;
                }
            }
            return null;
        }
    }

    public static class Grid {
        public int rows = 3;
        public int cols = 3;
    }

    /** 单个触控按钮 */
    public static class ButtonConfig {
        public String id;
        public String label;
        public String icon = "";
        public String bgColor = "#1E293B";
        public String state = "normal";
        public ActionConfig action;
    }

    /** 按钮动作定义 */
    public static class ActionConfig {
        /** hotkey | media | launch | url | script */
        public String type;
        /** hotkey 生效：如 ["ctrl", "shift", "esc"] */
        public List<String> keys;
        /** media 生效：volume_up / volume_down / mute / play_pause / next / prev */
        public String command;
        /** launch / url / script 生效 */
        public String target;
        /** 是否为开关型动作（执行后切换按钮外观） */
        public boolean toggle;
        public String activeState;
        public String activeLabel;
        public String activeIcon;
        public String activeBgColor;
    }

    /** 前台进程与页面的绑定关系 */
    public static class AppBinding {
        public String process;
        public String pageId;
    }

    public PageConfig page(String pageId) {
        if (pageId == null) {
            return null;
        }
        for (PageConfig p : pages) {
            if (pageId.equals(p.id)) {
                return p;
            }
        }
        return null;
    }

    public AppBinding bindingFor(String processName) {
        if (processName == null || appBindings == null) {
            return null;
        }
        String target = processName.toLowerCase(Locale.ROOT);
        for (AppBinding binding : appBindings) {
            if (binding.process != null && binding.process.toLowerCase(Locale.ROOT).equals(target)) {
                return binding;
            }
        }
        return null;
    }

    /** 默认页面（配置缺失时的兜底） */
    public PageConfig defaultPage() {
        PageConfig page = page(defaultPageId);
        return page != null ? page : (pages.isEmpty() ? null : pages.get(0));
    }
}
