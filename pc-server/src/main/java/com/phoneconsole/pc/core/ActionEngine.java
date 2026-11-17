package com.phoneconsole.pc.core;

import com.google.gson.Gson;
import com.phoneconsole.pc.config.AppConfig;
import com.phoneconsole.pc.protocol.ActionMessage;
import com.phoneconsole.pc.protocol.ButtonStateMessage;
import com.phoneconsole.pc.runtime.LogBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 动作执行引擎：把手机端上报的触控事件转换为系统操作。
 */
public class ActionEngine {

    private static final Logger log = LoggerFactory.getLogger(ActionEngine.class);
    private static final long REPEAT_INTERVAL_MS = 120L;
    /** 单次长按最长持续时长：即使 long_press_end 丢失也不会永久运行 */
    private static final long MAX_REPEAT_MS = 10_000L;

    private volatile AppConfig config;
    private final MediaController media = new MediaController();
    private final ProcessLauncher launcher = new ProcessLauncher();
    private final Gson gson = new Gson();

    private final ScheduledExecutorService repeater = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "action-repeat");
        t.setDaemon(true);
        return t;
    });
    /** 长按连续触发的任务：外层按连接隔离，内层按按钮 id 区分 */
    private final Map<Object, Map<String, ScheduledFuture<?>>> repeating = new ConcurrentHashMap<>();

    /** 按钮状态变化时的下行出口，由 WebSocket 服务注入 */
    private volatile Consumer<String> stateSink = payload -> { };

    public ActionEngine(AppConfig config) {
        this.config = config;
    }

    public void setStateSink(Consumer<String> sink) {
        if (sink != null) {
            this.stateSink = sink;
        }
    }

    /** 配置热重载：替换配置快照，正在执行的动作不受影响 */
    public void setConfig(AppConfig config) {
        if (config != null) {
            this.config = config;
        }
    }

    public MediaController media() {
        return media;
    }

    public void handle(ActionMessage message) {
        handle(null, message);
    }

    /**
     * 带连接标识的动作入口：长按重复任务与连接绑定，
     * 连接断开时可通过 stopAllRepeats 精确清理。
     */
    public void handle(Object owner, ActionMessage message) {
        if (message == null || message.buttonId == null) {
            return;
        }
        AppConfig snapshot = config;
        AppConfig.PageConfig page = snapshot.page(message.pageId);
        if (page == null) {
            page = snapshot.defaultPage();
        }
        if (page == null) {
            log.warn("配置中没有任何页面，忽略动作");
            return;
        }
        AppConfig.ButtonConfig button = page.button(message.buttonId);
        if (button == null) {
            log.warn("未知按钮：{}/{}", page.id, message.buttonId);
            return;
        }

        String event = message.event == null ? "click" : message.event.toLowerCase(Locale.ROOT);
        switch (event) {
            case "click" -> execute(button);
            case "long_press_start" -> startRepeat(owner, button);
            case "long_press_end", "long_press_cancel" -> stopRepeat(owner, button.id);
            default -> log.debug("忽略未知触控事件：{}", event);
        }
    }

    private void execute(AppConfig.ButtonConfig button) {
        AppConfig.ActionConfig action = button.action;
        if (action == null || action.type == null) {
            log.warn("按钮 {} 未配置动作", button.id);
            return;
        }
        try {
            switch (action.type.toLowerCase(Locale.ROOT)) {
                case "hotkey" -> KeySimulator.press(action.keys);
                case "media" -> media.command(action.command);
                case "launch" -> launcher.launch(action.target);
                case "url" -> launcher.openUrl(action.target);
                case "script" -> launcher.runScript(action.target);
                default -> {
                    log.warn("未知动作类型：{}", action.type);
                    return;
                }
            }
        } catch (Exception e) {
            log.error("执行动作失败 [{}]：{}", button.id, e.getMessage(), e);
            LogBuffer.get().error("ACTION", "执行「" + button.label + "」失败：" + e.getMessage());
            return;
        }
        LogBuffer.get().info("ACTION", "执行「" + button.label + "」：" + describe(action));
        if (action.toggle) {
            publishToggleState(button);
        }
    }

    /** 日志里展示动作的可读描述 */
    private String describe(AppConfig.ActionConfig action) {
        return switch (action.type.toLowerCase(Locale.ROOT)) {
            case "hotkey" -> "快捷键 " + String.join("+", action.keys == null ? List.of() : action.keys);
            case "media" -> "媒体键 " + action.command;
            case "launch" -> "启动 " + action.target;
            case "url" -> "打开 " + action.target;
            case "script" -> "执行脚本 " + action.target;
            default -> action.type;
        };
    }

    /**
     * 长按连续触发（音量加减等），松手即停。
     * 三重防护：按连接隔离、同连接重复 start 覆盖旧任务、超过 10 秒自动停止。
     */
    private void startRepeat(Object owner, AppConfig.ButtonConfig button) {
        if (button.action == null || button.action.toggle) {
            return;
        }
        String type = button.action.type == null ? "" : button.action.type.toLowerCase(Locale.ROOT);
        if (!"media".equals(type) && !"hotkey".equals(type)) {
            return;
        }

        final Object key = owner == null ? this : owner;
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MAX_REPEAT_MS);
        Runnable task = () -> {
            if (System.nanoTime() >= deadline) {
                stopRepeat(key, button.id);
                log.warn("长按任务超时自动停止：{}（超过 {} ms）", button.id, MAX_REPEAT_MS);
                LogBuffer.get().warn("ACTION", "长按「" + button.label + "」超时自动停止");
                return;
            }
            execute(button);
        };
        ScheduledFuture<?> future = repeater.scheduleWithFixedDelay(
                task, 0, REPEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);

        Map<String, ScheduledFuture<?>> tasks =
                repeating.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        ScheduledFuture<?> previous = tasks.put(button.id, future);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    /** 停止指定连接上某个按钮的长按任务 */
    private void stopRepeat(Object owner, String buttonId) {
        Object key = owner == null ? this : owner;
        Map<String, ScheduledFuture<?>> tasks = repeating.get(key);
        if (tasks == null) {
            return;
        }
        ScheduledFuture<?> future = tasks.remove(buttonId);
        if (future != null) {
            future.cancel(false);
        }
        if (tasks.isEmpty()) {
            repeating.remove(key, tasks);
        }
    }

    /** 连接断开时清理该连接遗留的所有长按任务，避免任务永久运行反复修改音量 */
    public void stopAllRepeats(Object owner) {
        Object key = owner == null ? this : owner;
        Map<String, ScheduledFuture<?>> tasks = repeating.remove(key);
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        tasks.values().forEach(f -> f.cancel(false));
        log.info("已清理断开连接的 {} 个长按任务", tasks.size());
        LogBuffer.get().info("ACTION", "连接断开，清理 " + tasks.size() + " 个长按任务");
    }

    /** 开关型按钮执行后回推外观变化 */
    private void publishToggleState(AppConfig.ButtonConfig button) {
        boolean active = !"active".equals(button.state) && !"muted".equals(button.state);

        ButtonStateMessage message = new ButtonStateMessage();
        message.buttonId = button.id;
        if (active) {
            AppConfig.ActionConfig action = button.action;
            message.state = action.activeState != null ? action.activeState : "active";
            message.label = action.activeLabel != null ? action.activeLabel : button.label;
            message.icon = action.activeIcon != null ? action.activeIcon : button.icon;
            message.bgColor = action.activeBgColor != null ? action.activeBgColor : button.bgColor;
        } else {
            message.state = "normal";
            message.label = button.label;
            message.icon = button.icon;
            message.bgColor = button.bgColor;
        }
        button.state = message.state;
        stateSink.accept(gson.toJson(message));
    }

    public void shutdown() {
        repeating.values().forEach(tasks -> tasks.values().forEach(f -> f.cancel(false)));
        repeating.clear();
        repeater.shutdownNow();
        media.close();
    }
}
