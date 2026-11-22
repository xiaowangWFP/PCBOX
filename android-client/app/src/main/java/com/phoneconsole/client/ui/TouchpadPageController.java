package com.phoneconsole.client.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.phoneconsole.client.R;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 触控板独立页：整页触摸区 + 每个按键独立定位/大小的悬浮层 + 右上角设置面板。
 * <p>
 * 支持默认 5 个按键与用户自定义快捷键；位置、大小、名称、震动、DPI 全部持久化。
 */
public final class TouchpadPageController {

    /** 页面只依赖这些动作，便于与连接层解耦 */
    public interface ActionSink {
        void onMove(int dx, int dy);

        void onClick(@NonNull String mouseButton);

        void onScroll(int deltaY);

        void onHotkey(@NonNull String combo);

        void onSendText(@NonNull String text);
    }

    /** 录音权限请求码，MainActivity 转发结果 */
    public static final int REQ_RECORD_AUDIO = 2002;

    private static final String PREFS = "touchpad_prefs";
    private static final String KEY_VOLUME_SCROLL = "volume_scroll";
    private static final String KEY_DPI = "dpi";
    private static final String KEY_LEGACY_SENSITIVITY = "sensitivity";
    private static final String KEY_PAD_VISIBLE = "pad_visible";
    private static final String KEY_PAD_KEYS = "pad_keys";
    private static final String KEY_HAPTICS = "haptics";
    private static final String KEY_GESTURES = "gestures_enabled";

    private static final int MIN_SIZE = 30;
    private static final int MAX_SIZE = 200;
    private static final int MIN_DPI = 400;
    private static final int MAX_DPI = 2400;
    private static final int DEFAULT_DPI = 1200;

    private static final String[] DEFAULT_IDS = {"left", "middle", "right", "scroll_up", "scroll_down"};
    private static final String[] DEFAULT_LABELS = {"左键", "中键", "右键", "上滚", "下滚"};
    private static final String[] DEFAULT_ACTIONS = {"click:left", "click:middle", "click:right", "scroll:3", "scroll:-3"};
    private static final float[] DEFAULT_X = {0.70f, 0.70f, 0.70f, 0.90f, 0.90f};
    private static final float[] DEFAULT_Y = {0.10f, 0.24f, 0.38f, 0.10f, 0.24f};

    /** 全键键盘：成对存放「显示名, 发送值」，只列 PC 端 KeySimulator 支持的键 */
    private static final String[][] PICKER_ROWS = {
            {"Ctrl", "ctrl", "Shift", "shift", "Alt", "alt", "Win", "win"},
            {"Esc", "esc", "Tab", "tab", "Enter", "enter", "Space", "space",
                    "Backspace", "backspace"},
            {"Insert", "insert", "Home", "home", "End", "end",
                    "PageUp", "pageup", "PageDown", "pagedown", "Delete", "delete"},
            {"↑", "up", "↓", "down", "←", "left", "→", "right"},
            {"F1", "f1", "F2", "f2", "F3", "f3", "F4", "f4", "F5", "f5", "F6", "f6"},
            {"F7", "f7", "F8", "f8", "F9", "f9", "F10", "f10", "F11", "f11", "F12", "f12"},
            {"`", "`", "1", "1", "2", "2", "3", "3", "4", "4", "5", "5", "6", "6",
                    "7", "7", "8", "8", "9", "9", "0", "0", "-", "-", "=", "="},
            {"Q", "q", "W", "w", "E", "e", "R", "r", "T", "t", "Y", "y",
                    "U", "u", "I", "i", "O", "o", "P", "p"},
            {"A", "a", "S", "s", "D", "d", "F", "f", "G", "g", "H", "h",
                    "J", "j", "K", "k", "L", "l", ";", ";", "'", "'"},
            {"Z", "z", "X", "x", "C", "c", "V", "v", "B", "b", "N", "n",
                    "M", "m", ",", ",", ".", ".", "/", "/"},
            {"[", "[", "]", "]", "\\", "\\"}
    };


    private final Context context;
    private final Activity activity;
    private final ActionSink sink;
    private final SharedPreferences prefs;
    private final int touchSlop;

    private final TouchpadView touchpad;
    private final FrameLayout padLayer;
    private final View adjustHint;
    private final View settingsPanel;
    private final SwitchCompat padVisibleSwitch;
    private final SwitchCompat volumeScrollSwitch;
    private final SwitchCompat hapticsSwitch;
    private final SwitchCompat gestureSwitch;
    private final SeekBar sizeSeek;
    private final TextView sizeValue;
    private final SeekBar dpiSeek;
    private final TextView dpiValue;
    private final TextView adjustButton;
    private final TextView currentKeyView;
    private final TextView renameButton;
    private final TextView deleteButton;
    private final VoiceInputController voiceInput;

    private final List<KeyEntry> keys = new ArrayList<>();
    private boolean volumeScrollEnabled;
    private boolean hapticsEnabled;
    private boolean gestureEnabled;
    private boolean adjustMode;
    private String selectedKeyId;

    public TouchpadPageController(@NonNull Context context, @NonNull Activity activity,
                                  @NonNull View root, @NonNull ActionSink sink) {
        this.context = context;
        this.activity = activity;
        this.sink = sink;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        this.gestureEnabled = prefs.getBoolean(KEY_GESTURES, true);

        touchpad = root.findViewById(R.id.tpTouchpadView);
        padLayer = root.findViewById(R.id.tpPadLayer);
        adjustHint = root.findViewById(R.id.tpAdjustHint);
        settingsPanel = root.findViewById(R.id.tpSettingsPanel);
        padVisibleSwitch = root.findViewById(R.id.tpPadVisibleSwitch);
        volumeScrollSwitch = root.findViewById(R.id.tpVolumeScrollSwitch);
        hapticsSwitch = root.findViewById(R.id.tpHapticsSwitch);
        gestureSwitch = root.findViewById(R.id.tpGestureSwitch);
        sizeSeek = root.findViewById(R.id.tpSizeSeek);
        sizeValue = root.findViewById(R.id.tpSizeValue);
        dpiSeek = root.findViewById(R.id.tpDpiSeek);
        dpiValue = root.findViewById(R.id.tpDpiValue);
        adjustButton = root.findViewById(R.id.tpAdjustButton);
        currentKeyView = root.findViewById(R.id.tpCurrentKey);
        renameButton = root.findViewById(R.id.tpRenameKey);
        deleteButton = root.findViewById(R.id.tpDeleteKey);

        loadKeys();
        bindTouchpad();
        bindSettingsButton(root);
        bindSettingsPanel(root);
        bindSeekBars();
        initDpi();
        buildKeys();
        restorePadVisibility();

        voiceInput = new VoiceInputController(context, root, new VoiceInputController.Host() {
            @Override
            public void onVoiceText(@NonNull String text) {
                sink.onSendText(text);
            }

            @Override
            public void onRequestRecordPermission() {
                ActivityCompat.requestPermissions(activity,
                        new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
            }
        });
    }

    /** 录音权限结果转发给语音输入控制器 */
    public void onRecordPermissionResult(boolean granted) {
        voiceInput.onRecordPermissionResult(granted);
    }

    public boolean isVolumeScrollEnabled() {
        return volumeScrollEnabled;
    }

    // ---------------- 触摸区 ----------------

    private void bindTouchpad() {
        touchpad.setGestureEnabled(gestureEnabled);
        touchpad.setOnTouchpadEventListener(new TouchpadView.OnTouchpadEventListener() {
            @Override
            public void onMove(int dx, int dy) {
                sink.onMove(dx, dy);
            }

            @Override
            public void onTapClick() {
                sink.onClick("left");
            }
        });
        touchpad.setOnGestureListener(new TouchpadView.OnGestureListener() {
            @Override
            public void onTwoFingerScroll(int deltaY) {
                sink.onScroll(deltaY);
            }

            @Override
            public void onMultiFingerSwipe(int fingers, int direction) {
                String combo = comboForGesture(fingers, direction);
                if (combo == null) {
                    return;
                }
                if (hapticsEnabled) {
                    Haptics.tap(context);
                }
                sink.onHotkey(combo);
            }
        });
    }

    /** 三指上下/左右、四指左右映射为 Windows 快捷键 */
    private String comboForGesture(int fingers, int direction) {
        if (fingers == 3) {
            switch (direction) {
                case TouchpadView.SWIPE_UP:
                    return "win+tab";
                case TouchpadView.SWIPE_DOWN:
                    return "win+d";
                case TouchpadView.SWIPE_LEFT:
                    return "alt+shift+tab";
                case TouchpadView.SWIPE_RIGHT:
                    return "alt+tab";
                default:
                    return null;
            }
        }
        if (fingers == 4) {
            switch (direction) {
                case TouchpadView.SWIPE_LEFT:
                    return "ctrl+win+left";
                case TouchpadView.SWIPE_RIGHT:
                    return "ctrl+win+right";
                case TouchpadView.SWIPE_UP:
                    return "win+tab";
                case TouchpadView.SWIPE_DOWN:
                    return "win+d";
                default:
                    return null;
            }
        }
        return null;
    }

    // ---------------- 设置面板 ----------------

    private void bindSettingsButton(View root) {
        View button = root.findViewById(R.id.tpSettingsButton);
        if (button != null) {
            button.setOnClickListener(v -> settingsPanel.setVisibility(
                    settingsPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        }
    }

    private void bindSettingsPanel(View root) {
        if (padVisibleSwitch != null) {
            padVisibleSwitch.setChecked(restorePadVisible());
            padVisibleSwitch.setOnCheckedChangeListener((view, checked) -> {
                padLayer.setVisibility(checked ? View.VISIBLE : View.GONE);
                prefs.edit().putBoolean(KEY_PAD_VISIBLE, checked).apply();
            });
        }

        if (volumeScrollSwitch != null) {
            volumeScrollSwitch.setChecked(volumeScrollEnabled);
            volumeScrollSwitch.setOnCheckedChangeListener((view, checked) -> {
                volumeScrollEnabled = checked;
                prefs.edit().putBoolean(KEY_VOLUME_SCROLL, checked).apply();
            });
        }

        if (hapticsSwitch != null) {
            hapticsEnabled = prefs.getBoolean(KEY_HAPTICS, true);
            hapticsSwitch.setChecked(hapticsEnabled);
            hapticsSwitch.setOnCheckedChangeListener((view, checked) -> {
                hapticsEnabled = checked;
                prefs.edit().putBoolean(KEY_HAPTICS, checked).apply();
            });
        }

        if (gestureSwitch != null) {
            gestureSwitch.setChecked(gestureEnabled);
            gestureSwitch.setOnCheckedChangeListener((view, checked) -> {
                gestureEnabled = checked;
                touchpad.setGestureEnabled(checked);
                prefs.edit().putBoolean(KEY_GESTURES, checked).apply();
            });
        }

        if (adjustButton != null) {
            adjustButton.setOnClickListener(v -> setAdjustMode(!adjustMode));
        }
        if (renameButton != null) {
            renameButton.setOnClickListener(v -> renameSelectedKey());
        }
        if (deleteButton != null) {
            deleteButton.setOnClickListener(v -> deleteSelectedKey());
        }
        View addButton = root.findViewById(R.id.tpAddKey);
        if (addButton != null) {
            addButton.setOnClickListener(v -> showKeyPickerDialog());
        }

        View gestureHelp = root.findViewById(R.id.tpGestureHelp);
        if (gestureHelp != null) {
            gestureHelp.setOnClickListener(v -> new AlertDialog.Builder(context)
                    .setTitle(R.string.touchpad_gesture_help)
                    .setMessage(R.string.touchpad_gesture_detail)
                    .setPositiveButton(R.string.touchpad_gesture_help_ok, null)
                    .show());
        }

        View reset = root.findViewById(R.id.tpPadReset);
        if (reset != null) {
            reset.setOnClickListener(v -> resetAllKeys());
        }
    }

    private void bindSeekBars() {
        if (sizeSeek != null) {
            sizeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser) {
                        return;
                    }
                    applySize(MIN_SIZE + progress);
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    if (findKey(selectedKeyId) != null) {
                        saveKeys();
                    }
                }
            });
        }

        if (dpiSeek != null) {
            dpiSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser) {
                        return;
                    }
                    int dpi = MIN_DPI + progress;
                    touchpad.setSensitivity(dpi / 1000f);
                    if (dpiValue != null) {
                        dpiValue.setText(dpi + " DPI");
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    prefs.edit().putInt(KEY_DPI, MIN_DPI + seekBar.getProgress()).apply();
                }
            });
        }
    }

    private void initDpi() {
        int dpi = prefs.getInt(KEY_DPI, 0);
        if (dpi == 0) {
            int legacy = clamp(prefs.getInt(KEY_LEGACY_SENSITIVITY, 1), 0, 2);
            dpi = legacy == 0 ? 750 : (legacy == 2 ? 1850 : DEFAULT_DPI);
        }
        dpi = clamp(dpi, MIN_DPI, MAX_DPI);
        if (dpiSeek != null) {
            dpiSeek.setProgress(dpi - MIN_DPI);
        }
        if (dpiValue != null) {
            dpiValue.setText(dpi + " DPI");
        }
        touchpad.setSensitivity(dpi / 1000f);
    }

    // ---------------- 调整模式 ----------------

    private void setAdjustMode(boolean enabled) {
        adjustMode = enabled;
        if (adjustButton != null) {
            adjustButton.setText(enabled ? R.string.touchpad_adjust_done : R.string.touchpad_adjust_keys);
        }
        if (adjustHint != null) {
            adjustHint.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }
        if (enabled) {
            if (settingsPanel != null) {
                settingsPanel.setVisibility(View.VISIBLE);
            }
            if (selectedKeyId == null && !keys.isEmpty()) {
                selectKey(keys.get(0).id);
            } else {
                refreshSelectionUi();
            }
        } else {
            selectedKeyId = null;
            refreshSelectionUi();
        }
    }

    private void selectKey(String keyId) {
        selectedKeyId = keyId;
        refreshSelectionUi();
        if (settingsPanel != null) {
            settingsPanel.setVisibility(View.VISIBLE);
        }
    }

    private void refreshSelectionUi() {
        for (KeyEntry key : keys) {
            if (key.view == null) {
                continue;
            }
            boolean active = adjustMode && key.id.equals(selectedKeyId);
            key.view.setScaleX(active ? 1.12f : 1f);
            key.view.setScaleY(active ? 1.12f : 1f);
            key.view.setAlpha(active ? 1f : (adjustMode ? 0.92f : 1f));
        }

        KeyEntry selected = findKey(selectedKeyId);
        if (currentKeyView != null) {
            currentKeyView.setText(selected == null
                    ? context.getString(R.string.touchpad_key_none) : selected.label);
        }
        if (sizeSeek != null) {
            sizeSeek.setEnabled(selected != null);
        }
        if (sizeValue != null) {
            sizeValue.setText(selected == null ? "--" : selected.sizePercent + "%");
        }
        if (selected != null && sizeSeek != null) {
            sizeSeek.setProgress(clamp(selected.sizePercent - MIN_SIZE, 0, MAX_SIZE - MIN_SIZE));
        }
        if (renameButton != null) {
            renameButton.setAlpha(selected == null ? 0.4f : 1f);
        }
        if (deleteButton != null) {
            deleteButton.setAlpha(selected == null ? 0.4f : 1f);
        }
    }

    private void applySize(int percent) {
        KeyEntry key = findKey(selectedKeyId);
        if (key == null || key.view == null) {
            return;
        }
        key.sizePercent = clamp(percent, MIN_SIZE, MAX_SIZE);
        applyKeyStyle(key);
        if (sizeValue != null) {
            sizeValue.setText(key.sizePercent + "%");
        }
        key.view.post(() -> clampPosition(key));
    }

    // ---------------- 按键管理 ----------------

    private void loadKeys() {
        keys.clear();
        boolean loaded = false;
        String raw = prefs.getString(KEY_PAD_KEYS, null);
        if (raw != null && !raw.isEmpty()) {
            try {
                Object parsed = new JSONTokener(raw).nextValue();
                if (parsed instanceof JSONArray) {
                    JSONArray array = (JSONArray) parsed;
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject item = array.optJSONObject(i);
                        if (item == null) {
                            continue;
                        }
                        KeyEntry key = new KeyEntry();
                        key.id = item.optString("id", "key_" + i);
                        key.label = item.optString("label", key.id);
                        key.action = item.optString("action", "hotkey:" + key.label);
                        key.xRatio = (float) item.optDouble("x", 0.7);
                        key.yRatio = (float) item.optDouble("y", 0.3);
                        key.sizePercent = clamp(item.optInt("size", 100), MIN_SIZE, MAX_SIZE);
                        keys.add(key);
                    }
                    loaded = !keys.isEmpty();
                } else if (parsed instanceof JSONObject) {
                    JSONObject legacy = (JSONObject) parsed;
                    for (int i = 0; i < DEFAULT_IDS.length; i++) {
                        JSONObject item = legacy.optJSONObject(DEFAULT_IDS[i]);
                        KeyEntry key = new KeyEntry();
                        key.id = DEFAULT_IDS[i];
                        key.label = DEFAULT_LABELS[i];
                        key.action = DEFAULT_ACTIONS[i];
                        key.xRatio = item == null ? DEFAULT_X[i] : (float) item.optDouble("x", DEFAULT_X[i]);
                        key.yRatio = item == null ? DEFAULT_Y[i] : (float) item.optDouble("y", DEFAULT_Y[i]);
                        int legacySize = item == null ? 1 : item.optInt("s", 1);
                        key.sizePercent = legacySize == 0 ? 80 : (legacySize == 2 ? 130 : 100);
                        keys.add(key);
                    }
                    loaded = true;
                }
            } catch (Exception ignored) {
                // 配置损坏时回落默认
            }
        }
        if (!loaded) {
            for (int i = 0; i < DEFAULT_IDS.length; i++) {
                KeyEntry key = new KeyEntry();
                key.id = DEFAULT_IDS[i];
                key.label = DEFAULT_LABELS[i];
                key.action = DEFAULT_ACTIONS[i];
                key.xRatio = DEFAULT_X[i];
                key.yRatio = DEFAULT_Y[i];
                key.sizePercent = 100;
                keys.add(key);
            }
        }
    }

    private void saveKeys() {
        JSONArray array = new JSONArray();
        try {
            for (KeyEntry key : keys) {
                JSONObject item = new JSONObject();
                item.put("id", key.id);
                item.put("label", key.label);
                item.put("action", key.action);
                item.put("x", round(key.xRatio));
                item.put("y", round(key.yRatio));
                item.put("size", key.sizePercent);
                array.put(item);
            }
        } catch (Exception ignored) {
            return;
        }
        prefs.edit().putString(KEY_PAD_KEYS, array.toString()).apply();
    }

    private void buildKeys() {
        padLayer.removeAllViews();
        for (KeyEntry key : keys) {
            TextView view = new TextView(context);
            view.setText(key.label);
            view.setGravity(Gravity.CENTER);
            view.setClickable(true);
            view.setOnClickListener(v -> dispatchKeyAction(key));

            int size = buttonSizePx(key.sizePercent);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
            padLayer.addView(view, lp);
            key.view = view;
            applyKeyStyle(key);
            bindKeyTouch(key);
        }
        refreshSelectionUi();
        applyPositionsWhenReady();
    }

    /** 等按键层完成测量后再按比例落位，避免首次进入时全部堆在左上角 */
    private void applyPositionsWhenReady() {
        final int[] attempts = {0};
        final Runnable apply = new Runnable() {
            @Override
            public void run() {
                if (padLayer.getWidth() <= 0 || padLayer.getHeight() <= 0) {
                    if (++attempts[0] < 30) {
                        padLayer.postDelayed(this, 16);
                    }
                    return;
                }
                for (KeyEntry key : keys) {
                    applyKeyPosition(key);
                }
            }
        };
        padLayer.post(apply);
    }

    private void bindKeyTouch(KeyEntry key) {
        key.view.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX;
            private float downRawY;
            private float startX;
            private float startY;
            private boolean dragging;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (!adjustMode) {
                    return false;
                }
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startX = view.getX();
                        startY = view.getY();
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (!dragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                            dragging = true;
                        }
                        if (dragging) {
                            float maxX = Math.max(0, padLayer.getWidth() - view.getWidth());
                            float maxY = Math.max(0, padLayer.getHeight() - view.getHeight());
                            view.setX(Math.max(0, Math.min(maxX, startX + dx)));
                            view.setY(Math.max(0, Math.min(maxY, startY + dy)));
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (dragging) {
                            saveKeyPosition(key);
                        } else {
                            selectKey(key.id);
                        }
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging) {
                            saveKeyPosition(key);
                        }
                        dragging = false;
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    private void dispatchKeyAction(KeyEntry key) {
        if (hapticsEnabled) {
            Haptics.tap(context);
        }
        String action = key.action == null ? "" : key.action;
        try {
            if (action.startsWith("hotkey:")) {
                sink.onHotkey(action.substring(7));
            } else if (action.startsWith("click:")) {
                sink.onClick(action.substring(6));
            } else if (action.startsWith("scroll:")) {
                sink.onScroll(Integer.parseInt(action.substring(7)));
            }
        } catch (RuntimeException e) {
            Toast.makeText(context, "按键配置无效：" + action, Toast.LENGTH_SHORT).show();
        }
    }

    private void showKeyPickerDialog() {
        View content = LayoutInflater.from(context).inflate(R.layout.dialog_key_picker, null);
        EditText labelInput = content.findViewById(R.id.etDialogKeyLabel);
        TextView preview = content.findViewById(R.id.tvDialogComboPreview);
        LinearLayout rows = content.findViewById(R.id.dialogKeyRows);
        android.widget.ScrollView keyScroll = content.findViewById(R.id.dialogKeyScroll);

        labelInput.setText("快捷键 " + (keys.size() + 1));
        labelInput.setSelectAllOnFocus(true);

        LinkedHashSet<String> selected = new LinkedHashSet<>();
        Map<String, String> displayOf = new HashMap<>();
        Map<String, TextView> pickers = new HashMap<>();

        for (String[] row : PICKER_ROWS) {
            LinearLayout line = new LinearLayout(context);
            line.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lineLp.topMargin = dp(3);
            line.setLayoutParams(lineLp);

            for (int i = 0; i + 1 < row.length; i += 2) {
                String display = row[i];
                String value = row[i + 1];
                displayOf.put(value, display);

                TextView picker = new TextView(context);
                picker.setText(display);
                picker.setGravity(Gravity.CENTER);
                picker.setTextSize(11f);
                picker.setBackgroundResource(R.drawable.bg_ghost_button);
                picker.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1f);
                lp.setMargins(dp(2), 0, dp(2), 0);
                picker.setLayoutParams(lp);
                picker.setOnClickListener(v -> {
                    if (selected.contains(value)) {
                        selected.remove(value);
                    } else {
                        selected.add(value);
                    }
                    updatePickerStyles(selected, pickers);
                    preview.setText(buildComboPreview(selected, displayOf));
                });
                pickers.put(value, picker);
                line.addView(picker);
            }
            rows.addView(line);
        }

        updatePickerStyles(selected, pickers);
        preview.setText(buildComboPreview(selected, displayOf));

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.touchpad_add_key)
                .setView(content)
                .setNegativeButton("取消", null)
                .setPositiveButton("确认", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setLayout((int) (context.getResources().getDisplayMetrics().widthPixels * 0.92f),
                        WindowManager.LayoutParams.WRAP_CONTENT);
            }
            if (keyScroll != null) {
                keyScroll.post(() -> keyScroll.scrollTo(0, 0));
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (selected.isEmpty()) {
                    Toast.makeText(context, R.string.touchpad_key_picker_empty_toast, Toast.LENGTH_SHORT).show();
                    return;
                }
                String combo = buildComboValue(selected);
                String label = labelInput.getText().toString().trim();
                if (label.isEmpty()) {
                    label = buildComboPreview(selected, displayOf).replace(" + ", "+");
                }
                KeyEntry key = new KeyEntry();
                key.id = "custom_" + System.currentTimeMillis();
                key.label = label;
                key.action = "hotkey:" + combo;
                key.xRatio = 0.55f;
                key.yRatio = 0.55f;
                key.sizePercent = 100;
                keys.add(key);
                selectedKeyId = key.id;
                saveKeys();
                buildKeys();
                selectKey(key.id);
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private void updatePickerStyles(Set<String> selected, Map<String, TextView> pickers) {
        for (Map.Entry<String, TextView> entry : pickers.entrySet()) {
            boolean active = selected.contains(entry.getKey());
            entry.getValue().setBackgroundResource(active
                    ? R.drawable.bg_primary_button : R.drawable.bg_ghost_button);
            entry.getValue().setTextColor(ContextCompat.getColor(context,
                    active ? R.color.text_on_accent : R.color.text_primary));
        }
    }

    private String buildComboPreview(Set<String> selected, Map<String, String> displayOf) {
        if (selected.isEmpty()) {
            return context.getString(R.string.touchpad_key_picker_empty);
        }
        List<String> names = new ArrayList<>();
        for (String value : orderKeys(selected)) {
            names.add(displayOf.getOrDefault(value, value));
        }
        return String.join(" + ", names);
    }

    private String buildComboValue(Set<String> selected) {
        return String.join("+", orderKeys(selected));
    }

    private List<String> orderKeys(Set<String> selected) {
        List<String> ordered = new ArrayList<>();
        for (String modifier : new String[]{"ctrl", "shift", "alt", "win"}) {
            if (selected.contains(modifier)) {
                ordered.add(modifier);
            }
        }
        for (String value : selected) {
            if (!value.equals("ctrl") && !value.equals("shift")
                    && !value.equals("alt") && !value.equals("win")) {
                ordered.add(value);
            }
        }
        return ordered;
    }

    private void renameSelectedKey() {
        KeyEntry key = findKey(selectedKeyId);
        if (key == null) {
            Toast.makeText(context, "请先点按一个按键", Toast.LENGTH_SHORT).show();
            return;
        }
        EditText input = new EditText(context);
        input.setHint(R.string.touchpad_key_name);
        input.setText(key.label);
        input.setSelectAllOnFocus(true);
        int pad = dp(16);
        input.setPadding(pad, dp(8), pad, dp(8));

        new AlertDialog.Builder(context)
                .setTitle(R.string.touchpad_rename_key)
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    String label = input.getText().toString().trim();
                    if (label.isEmpty()) {
                        return;
                    }
                    key.label = label;
                    if (key.view != null) {
                        key.view.setText(label);
                    }
                    saveKeys();
                    refreshSelectionUi();
                })
                .show();
    }

    private void deleteSelectedKey() {
        KeyEntry key = findKey(selectedKeyId);
        if (key == null) {
            Toast.makeText(context, "请先点按一个按键", Toast.LENGTH_SHORT).show();
            return;
        }
        keys.remove(key);
        selectedKeyId = null;
        saveKeys();
        buildKeys();
    }

    private void resetAllKeys() {
        prefs.edit().remove(KEY_PAD_KEYS).apply();
        keys.clear();
        selectedKeyId = null;
        for (int i = 0; i < DEFAULT_IDS.length; i++) {
            KeyEntry key = new KeyEntry();
            key.id = DEFAULT_IDS[i];
            key.label = DEFAULT_LABELS[i];
            key.action = DEFAULT_ACTIONS[i];
            key.xRatio = DEFAULT_X[i];
            key.yRatio = DEFAULT_Y[i];
            key.sizePercent = 100;
            keys.add(key);
        }
        saveKeys();
        buildKeys();
        Toast.makeText(context, "已恢复默认按键", Toast.LENGTH_SHORT).show();
    }

    // ---------------- 位置与样式 ----------------

    private void applyKeyStyle(KeyEntry key) {
        if (key.view == null) {
            return;
        }
        int size = buttonSizePx(key.sizePercent);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) key.view.getLayoutParams();
        lp.width = size;
        lp.height = size;
        key.view.setLayoutParams(lp);
        key.view.setTextSize(textSizeFor(key.sizePercent));
        boolean primary = "left".equals(key.id);
        key.view.setBackgroundResource(primary ? R.drawable.bg_primary_button : R.drawable.bg_ghost_button);
        key.view.setTextColor(ContextCompat.getColor(context,
                primary ? R.color.text_on_accent : R.color.text_primary));
    }

    private void applyKeyPosition(KeyEntry key) {
        if (key.view == null) {
            return;
        }
        float maxX = Math.max(0, padLayer.getWidth() - key.view.getWidth());
        float maxY = Math.max(0, padLayer.getHeight() - key.view.getHeight());
        key.view.setX(Math.max(0, Math.min(1, key.xRatio)) * maxX);
        key.view.setY(Math.max(0, Math.min(1, key.yRatio)) * maxY);
    }

    private void clampPosition(KeyEntry key) {
        if (key.view == null) {
            return;
        }
        float maxX = Math.max(0, padLayer.getWidth() - key.view.getWidth());
        float maxY = Math.max(0, padLayer.getHeight() - key.view.getHeight());
        if (maxX <= 0 || maxY <= 0) {
            return;
        }
        key.view.setX(Math.min(key.view.getX(), maxX));
        key.view.setY(Math.min(key.view.getY(), maxY));
        key.xRatio = key.view.getX() / maxX;
        key.yRatio = key.view.getY() / maxY;
    }

    private void saveKeyPosition(KeyEntry key) {
        if (key.view == null) {
            return;
        }
        float maxX = Math.max(1, padLayer.getWidth() - key.view.getWidth());
        float maxY = Math.max(1, padLayer.getHeight() - key.view.getHeight());
        key.xRatio = key.view.getX() / maxX;
        key.yRatio = key.view.getY() / maxY;
        saveKeys();
    }

    private boolean restorePadVisible() {
        return prefs.getBoolean(KEY_PAD_VISIBLE, true);
    }

    private void restorePadVisibility() {
        padLayer.setVisibility(restorePadVisible() ? View.VISIBLE : View.GONE);
    }

    // ---------------- 工具 ----------------

    private KeyEntry findKey(String keyId) {
        if (keyId == null) {
            return null;
        }
        for (KeyEntry key : keys) {
            if (keyId.equals(key.id)) {
                return key;
            }
        }
        return null;
    }

    private int buttonSizePx(int percent) {
        float size = 56f * percent / 100f;
        return dp(Math.max(32, Math.round(size)));
    }

    private float textSizeFor(int percent) {
        return 11.5f + (percent - MIN_SIZE) * 3.5f / (MAX_SIZE - MIN_SIZE);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static double round(float value) {
        return Math.round(value * 1000d) / 1000d;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class KeyEntry {
        String id;
        String label;
        String action;
        float xRatio;
        float yRatio;
        int sizePercent;
        TextView view;
    }
}
