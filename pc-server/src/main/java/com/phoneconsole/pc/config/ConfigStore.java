package com.phoneconsole.pc.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * config.json 的加载、校验与保存。
 * 网页控制台保存配置时也走同一套校验，保证热重载后的内存配置一定可用。
 */
public final class ConfigStore {

    private static final Logger log = LoggerFactory.getLogger(ConfigStore.class);
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private static final Set<String> ACTION_TYPES = Set.of("hotkey", "media", "launch", "url", "script");
    private static final Set<String> MEDIA_COMMANDS = Set.of(
            "volume_up", "volume_down", "mute", "play_pause", "next", "prev", "mic_mute");

    private ConfigStore() {
    }

    public static AppConfig loadOrCreate(Path path) throws IOException {
        if (Files.notExists(path)) {
            Files.writeString(path, readTemplate(), StandardCharsets.UTF_8);
            log.info("未找到配置文件，已生成默认配置：{}", path.toAbsolutePath());
        }
        return parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    /** 解析并校验配置文本（网页控制台保存前调用） */
    public static AppConfig parse(String json) throws IOException {
        AppConfig config;
        try {
            config = GSON.fromJson(json, AppConfig.class);
        } catch (JsonSyntaxException e) {
            throw new IOException("JSON 格式错误：" + e.getMessage(), e);
        }
        if (config == null) {
            throw new IOException("配置内容为空");
        }
        normalize(config);
        validate(config);
        return config;
    }

    /** 写回配置文件（保持格式化输出，便于手工查看） */
    public static void save(Path path, AppConfig config) throws IOException {
        Files.writeString(path, GSON.toJson(config), StandardCharsets.UTF_8);
    }

    public static String toJson(AppConfig config) {
        return GSON.toJson(config);
    }

    private static void normalize(AppConfig config) {
        if (config.network == null) {
            config.network = new AppConfig.Network();
        }
        if (config.screen == null) {
            config.screen = new AppConfig.Screen();
        }
        if (config.pages == null) {
            config.pages = new ArrayList<>();
        }
        if (config.appBindings == null) {
            config.appBindings = new ArrayList<>();
        }
        for (AppConfig.PageConfig page : config.pages) {
            if (page.grid == null) {
                page.grid = new AppConfig.Grid();
            }
            if (page.buttons == null) {
                page.buttons = new ArrayList<>();
            }
        }
    }

    private static void validate(AppConfig config) throws IOException {
        List<String> errors = new ArrayList<>();
        Set<String> pageIds = new HashSet<>();

        if (config.pages.isEmpty()) {
            errors.add("pages 不能为空，至少需要一个页面");
        }
        for (AppConfig.PageConfig page : config.pages) {
            if (page.id == null || page.id.isBlank()) {
                errors.add("存在 id 为空的页面");
                continue;
            }
            if (!pageIds.add(page.id)) {
                errors.add("页面 id 重复：" + page.id);
            }
            if (page.title == null || page.title.isBlank()) {
                page.title = page.id;
            }
            if (page.grid.rows < 1 || page.grid.rows > 20) {
                errors.add("页面 " + page.id + " 的 grid.rows 需在 1~20");
            }
            if (page.grid.cols < 1 || page.grid.cols > 12) {
                errors.add("页面 " + page.id + " 的 grid.cols 需在 1~12");
            }
            Set<String> buttonIds = new HashSet<>();
            for (AppConfig.ButtonConfig button : page.buttons) {
                if (button.id == null || button.id.isBlank()) {
                    errors.add("页面 " + page.id + " 存在 id 为空的按钮");
                    continue;
                }
                if (!buttonIds.add(button.id)) {
                    errors.add("页面 " + page.id + " 的按钮 id 重复：" + button.id);
                }
                if (button.label == null || button.label.isBlank()) {
                    button.label = button.id;
                }
                if (button.bgColor == null || button.bgColor.isBlank()) {
                    button.bgColor = "#1E293B";
                }
                if (button.state == null || button.state.isBlank()) {
                    button.state = "normal";
                }
                validateAction(page.id, button, errors);
            }
        }

        if (config.defaultPageId == null || !pageIds.contains(config.defaultPageId)) {
            if (!config.pages.isEmpty() && config.pages.get(0).id != null) {
                config.defaultPageId = config.pages.get(0).id;
            }
        }

        AppConfig.Network network = config.network;
        if (network.wsPort < 1 || network.wsPort > 65535) {
            errors.add("network.wsPort 需在 1~65535");
        }
        if (network.httpPort < 1 || network.httpPort > 65535) {
            errors.add("network.httpPort 需在 1~65535");
        }
        if (network.adbExecutable == null || network.adbExecutable.isBlank()) {
            network.adbExecutable = "adb";
        }
        if (network.reversePollMs < 1000) {
            network.reversePollMs = 1000;
        }
        if (config.screen.ffmpegExecutable == null || config.screen.ffmpegExecutable.isBlank()) {
            config.screen.ffmpegExecutable = "ffmpeg";
        }
        if (config.screen.monitor < -1 || config.screen.monitor > 15) {
            errors.add("screen.monitor 需在 -1~15（-1 表示整块桌面，0 起为显示器序号）");
        }

        List<AppConfig.AppBinding> validBindings = new ArrayList<>();
        for (AppConfig.AppBinding binding : config.appBindings) {
            if (binding.process == null || binding.process.isBlank()
                    || binding.pageId == null || !pageIds.contains(binding.pageId)) {
                log.warn("忽略无效的应用绑定：{} -> {}", binding.process, binding.pageId);
                continue;
            }
            validBindings.add(binding);
        }
        config.appBindings = validBindings;

        if (!errors.isEmpty()) {
            throw new IOException("配置校验失败：" + String.join("；", errors));
        }
    }

    private static void validateAction(String pageId, AppConfig.ButtonConfig button, List<String> errors) {
        String where = "按钮 " + pageId + "/" + button.id;
        AppConfig.ActionConfig action = button.action;
        if (action == null || action.type == null || action.type.isBlank()) {
            errors.add(where + " 缺少 action.type");
            return;
        }
        String type = action.type.toLowerCase(Locale.ROOT);
        if (!ACTION_TYPES.contains(type)) {
            errors.add(where + " 的 action.type 无效：" + action.type);
            return;
        }
        switch (type) {
            case "hotkey" -> {
                if (action.keys == null || action.keys.isEmpty()) {
                    errors.add(where + " 的 hotkey 缺少 keys");
                }
            }
            case "media" -> {
                if (action.command == null || action.command.isBlank()) {
                    errors.add(where + " 的 media 缺少 command");
                } else if (!MEDIA_COMMANDS.contains(action.command.toLowerCase(Locale.ROOT))) {
                    errors.add(where + " 的媒体命令不受支持：" + action.command);
                }
            }
            default -> {
                if (action.target == null || action.target.isBlank()) {
                    errors.add(where + " 的 " + type + " 缺少 target");
                }
            }
        }
    }

    private static String readTemplate() throws IOException {
        try (InputStream in = ConfigStore.class.getResourceAsStream("/config.default.json")) {
            if (in == null) {
                throw new IOException("classpath 中缺少 config.default.json 模板");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
