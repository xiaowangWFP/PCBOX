package com.phoneconsole.pc.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * 页面配置下发（PC -> 手机）。
 */
public class LayoutMessage {

    public String type = "layout_sync";
    public String pageId;
    public String pageTitle;
    public Grid grid = new Grid();
    public List<Button> buttons = new ArrayList<>();
    /** 全部可用面板（供客户端做面板切换） */
    public List<Page> pages = new ArrayList<>();

    public static class Page {
        public String id;
        public String title;
    }

    public static class Grid {
        public int rows;
        public int cols;
    }

    public static class Button {
        public String id;
        public String label;
        public String icon;
        public String bgColor;
        public String state;
    }
}
