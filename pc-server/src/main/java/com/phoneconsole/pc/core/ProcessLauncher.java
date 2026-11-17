package com.phoneconsole.pc.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;

/**
 * 应用启动、URL 打开与脚本调度。
 */
public class ProcessLauncher {

    private static final Logger log = LoggerFactory.getLogger(ProcessLauncher.class);

    public void launch(String target) throws IOException {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("launch 动作缺少 target 配置");
        }
        new ProcessBuilder(target).start();
        log.info("已启动应用：{}", target);
    }

    public void openUrl(String url) throws Exception {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url 动作缺少 target 配置");
        }
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            throw new IllegalStateException("当前系统不支持调用默认浏览器");
        }
        Desktop.getDesktop().browse(URI.create(url));
        log.info("已打开链接：{}", url);
    }

    public void runScript(String script) throws IOException {
        if (script == null || script.isBlank()) {
            throw new IllegalArgumentException("script 动作缺少 target 配置");
        }
        new ProcessBuilder("cmd", "/c", script)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        log.info("已静默执行脚本：{}", script);
    }
}
