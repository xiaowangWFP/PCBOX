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

    public static class Grid {
        public int rows;
        public int cols;
    }
}
