package com.phoneconsole.client.model;

import java.util.List;

/**
 * 页面配置下发报文。
 */
public class LayoutMessage {
    public String type;
    public String pageId;
    public String pageTitle;
    public Grid grid;
    public List<ButtonItem> buttons;
    /** 服务端全部可用面板，用于切换控制面板 */
    public List<PageRef> pages;

    public static class PageRef {
        public String id;
        public String title;
    }

    public static class Grid {
        public int rows;
        public int cols;
    }
}
