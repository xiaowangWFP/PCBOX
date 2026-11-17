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

    /** 屏幕推流参数：输出固定 1920x1080@60，采集源与 ffmpeg 路径可配置 */
    public static class Screen {
        public String ffmpegExecutable = "ffmpeg";
        /** 采集目标：-1 = 整块虚拟桌面（镜像），0 起为显示器序号（扩展屏只抓那一块） */
        public int monitor = -1;
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
