package com.phoneconsole.pc.tray;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.awt.Desktop;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.net.URI;

/**
 * 系统托盘：常驻后台运行，提供调试页面入口与退出。
 * TODO: 可在此扩展“局域网二维码弹窗”。
 */
public final class TrayManager {

    private static final Logger log = LoggerFactory.getLogger(TrayManager.class);

    private final TrayIcon trayIcon;

    private TrayManager(TrayIcon trayIcon) {
        this.trayIcon = trayIcon;
    }

    public static TrayManager install(String debugUrl) {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            log.info("当前环境不支持系统托盘，跳过托盘图标");
            return null;
        }
        try {
            PopupMenu menu = new PopupMenu();

            MenuItem open = new MenuItem("打开调试页面");
            open.addActionListener(e -> openBrowser(debugUrl));

            MenuItem exit = new MenuItem("退出");
            exit.addActionListener(e -> System.exit(0));

            menu.add(open);
            menu.addSeparator();
            menu.add(exit);

            TrayIcon icon = new TrayIcon(createIcon(), "手机控制台 PC 服务端", menu);
            icon.setImageAutoSize(true);
            icon.addActionListener(e -> openBrowser(debugUrl));
            icon.setToolTip("手机控制台 PC 服务端\n" + debugUrl);

            SystemTray.getSystemTray().add(icon);
            log.info("托盘图标已就绪");
            return new TrayManager(icon);
        } catch (Exception e) {
            log.warn("创建托盘图标失败：{}", e.getMessage());
            return null;
        }
    }

    public void remove() {
        try {
            SystemTray.getSystemTray().remove(trayIcon);
        } catch (Exception ignored) {
            // 退出流程中忽略异常
        }
    }

    private static void openBrowser(String url) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            } catch (Exception e) {
                log.warn("Desktop.browse 打开失败，改用 rundll32：{}", e.getMessage());
            }
        }
        try {
            new ProcessBuilder("rundll32.exe", "url.dll,FileProtocolHandler", url).start();
        } catch (Exception e) {
            log.warn("打开浏览器失败：{}", e.getMessage());
        }
    }

    private static Image createIcon() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x2563EB));
        g.fillRoundRect(0, 0, 16, 16, 6, 6);
        g.setColor(Color.WHITE);
        g.fillRoundRect(3, 2, 10, 12, 3, 3);
        g.setColor(new Color(0x2563EB));
        g.fillRoundRect(5, 4, 6, 7, 1, 1);
        g.dispose();
        return image;
    }
}
