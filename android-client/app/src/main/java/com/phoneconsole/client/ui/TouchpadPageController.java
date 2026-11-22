package com.phoneconsole.client.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import com.phoneconsole.client.R;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 触控板独立页：整页触摸区 + 每个按键独立定位/大小的悬浮层 + 右上角设置面板。
 * <p>
 * 按键位置、每个按键的大小、显示开关、音量键滚动与灵敏度全部持久化；
 * 「调整按键」模式下可逐个拖动，点按选中后单独改大小。
 */
public final class TouchpadPageController {

    /** 页面只依赖这三个动作，便于与连接层解耦 */
    public interface ActionSink {
        void onMove(int dx, int dy);

        void onClick(@NonNull String mouseButton);

        void onScroll(int deltaY);
    }

    private static final String PREFS = "touchpad_prefs";
    private static final String KEY_VOLUME_SCROLL = "volume_scroll";
    private static final String KEY_SENSITIVITY = "sensitivity";
    private static final String KEY_PAD_VISIBLE = "pad_visible";
    private static final String KEY_PAD_KEYS = "pad_keys";

    private static final String[] KEY_IDS = {"left", "middle", "right", "scroll_up", "scroll_down"};
    private static final String[] KEY_LABELS = {"左键", "中键", "右键", "上滚", "下滚"};
    /** 默认位置（可移动范围的比例，1 表示贴右/贴底） */
    private static final float[] DEFAULT_X = {0.70f, 0.70f, 0.70f, 0.90f, 0.90f};
    private static final float[] DEFAULT_Y = {0.10f, 0.24f, 0.38f, 0.10f, 0.24f};

    private static final int SIZE_SMALL = 0;
    private static final int SIZE_NORMAL = 1;
    private static final int SIZE_LARGE = 2;

    private final Context context;
    private final ActionSink sink;
    private final SharedPreferences prefs;
    private final int touchSlop;

    private final TouchpadView touchpad;
    private final FrameLayout padLayer;
    private final View adjustHint;
    private final View settingsPanel;
    private final SwitchCompat padVisibleSwitch;
    private final SwitchCompat volumeScrollSwitch;
    private final Spinner sensitivitySpinner;
    private final TextView adjustButton;
    private final TextView currentKeyView;
    private final TextView[] sizeButtons;

    private final Map<String, KeyView> keys = new LinkedHashMap<>();
    private boolean volumeScrollEnabled;
    private boolean adjustMode;
    private String selectedKey;

    public TouchpadPageController(@NonNull Context context, @NonNull View root, @NonNull ActionSink sink) {
        this.context = context;
        this.sink = sink;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        touchpad = root.findViewById(R.id.tpTouchpadView);
        padLayer = root.findViewById(R.id.tpPadLayer);
        adjustHint = root.findViewById(R.id.tpAdjustHint);
        settingsPanel = root.findViewById(R.id.tpSettingsPanel);
        padVisibleSwitch = root.findViewById(R.id.tpPadVisibleSwitch);
        volumeScrollSwitch = root.findViewById(R.id.tpVolumeScrollSwitch);
        sensitivitySpinner = root.findViewById(R.id.tpSensitivity);
        adjustButton = root.findViewById(R.id.tpAdjustButton);
        currentKeyView = root.findViewById(R.id.tpCurrentKey);
        sizeButtons = new TextView[]{
                root.findViewById(R.id.tpSizeSmall),
                root.findViewById(R.id.tpSizeNormal),
                root.findViewById(R.id.tpSizeLarge)
        };

        volumeScrollEnabled = prefs.getBoolean(KEY_VOLUME_SCROLL, false);

        bindTouchpad();
        bindSettingsButton(root);
        bindSettingsPanel(root);
        buildKeys();
        restorePadVisibility();
    }

    public boolean isVolumeScrollEnabled() {
        return volumeScrollEnabled;
    }

    // ---------------- 触摸区 ----------------

    private void bindTouchpad() {
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

        if (sensitivitySpinner != null) {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
                    android.R.layout.simple_spinner_item, new String[]{"慢速", "标准", "快速"});
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            sensitivitySpinner.setAdapter(adapter);
            int saved = clamp(prefs.getInt(KEY_SENSITIVITY, 1), 0, 2);
            sensitivitySpinner.setSelection(saved);
            sensitivitySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    touchpad.setSensitivity(position == 0 ? 0.75f : (position == 2 ? 1.85f : 1.2f));
                    prefs.edit().putInt(KEY_SENSITIVITY, position).apply();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
        }

        if (adjustButton != null) {
            adjustButton.setOnClickListener(v -> setAdjustMode(!adjustMode));
        }

        for (int i = 0; i < sizeButtons.length; i++) {
            final int sizeMode = i;
            if (sizeButtons[i] != null) {
                sizeButtons[i].setOnClickListener(v -> applySizeToSelected(sizeMode));
            }
        }

        View reset = root.findViewById(R.id.tpPadReset);
        if (reset != null) {
            reset.setOnClickListener(v -> resetAllKeys());
        }
    }

    private boolean restorePadVisible() {
        return prefs.getBoolean(KEY_PAD_VISIBLE, true);
    }

    private void restorePadVisibility() {
        boolean visible = restorePadVisible();
        padLayer.setVisibility(visible ? View.VISIBLE : View.GONE);
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
            if (selectedKey == null) {
                selectKey(KEY_IDS[0]);
            } else {
                refreshSelectionUi();
            }
        } else {
            selectedKey = null;
            refreshSelectionUi();
        }
    }

    private void selectKey(String keyId) {
        selectedKey = keyId;
        refreshSelectionUi();
        if (settingsPanel != null) {
            settingsPanel.setVisibility(View.VISIBLE);
        }
    }

    private void refreshSelectionUi() {
        for (KeyView key : keys.values()) {
            boolean active = adjustMode && key.id.equals(selectedKey);
            key.view.setScaleX(active ? 1.12f : 1f);
            key.view.setScaleY(active ? 1.12f : 1f);
            key.view.setAlpha(active ? 1f : (adjustMode ? 0.92f : 1f));
        }

        if (currentKeyView != null) {
            int index = indexOfKey(selectedKey);
            currentKeyView.setText(index >= 0 ? KEY_LABELS[index] : context.getString(R.string.touchpad_key_none));
        }

        KeyView key = keys.get(selectedKey);
        int sizeMode = key == null ? -1 : key.sizeMode;
        for (int i = 0; i < sizeButtons.length; i++) {
            TextView button = sizeButtons[i];
            if (button == null) {
                continue;
            }
            boolean active = i == sizeMode;
            button.setBackgroundResource(active ? R.drawable.bg_primary_button : R.drawable.bg_ghost_button);
            button.setTextColor(ContextCompat.getColor(context,
                    active ? R.color.text_on_accent : R.color.text_primary));
        }
    }

    private void applySizeToSelected(int sizeMode) {
        KeyView key = keys.get(selectedKey);
        if (key == null) {
            Toast.makeText(context, "请先点按一个按键", Toast.LENGTH_SHORT).show();
            return;
        }
        key.sizeMode = sizeMode;
        int size = buttonSizePx(sizeMode);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) key.view.getLayoutParams();
        lp.width = size;
        lp.height = size;
        key.view.setLayoutParams(lp);
        key.view.setTextSize(sizeMode == SIZE_SMALL ? 12f : (sizeMode == SIZE_LARGE ? 15f : 13.5f));
        key.view.post(() -> clampPosition(key));
        saveKeys();
        refreshSelectionUi();
    }

    private void resetAllKeys() {
        prefs.edit().remove(KEY_PAD_KEYS).apply();
        selectedKey = null;
        for (int i = 0; i < KEY_IDS.length; i++) {
            KeyView key = keys.get(KEY_IDS[i]);
            if (key == null) {
                continue;
            }
            key.xRatio = DEFAULT_X[i];
            key.yRatio = DEFAULT_Y[i];
            key.sizeMode = SIZE_NORMAL;
            applyKeySize(key);
        }
        padLayer.post(() -> {
            for (KeyView key : keys.values()) {
                applyKeyPosition(key);
            }
        });
        refreshSelectionUi();
        Toast.makeText(context, "已恢复默认位置与大小", Toast.LENGTH_SHORT).show();
    }

    // ---------------- 按键构建与持久化 ----------------

    private void buildKeys() {
        padLayer.removeAllViews();
        keys.clear();
        JSONObject saved = loadKeysJson();

        for (int i = 0; i < KEY_IDS.length; i++) {
            String id = KEY_IDS[i];
            JSONObject item = saved == null ? null : saved.optJSONObject(id);

            TextView view = new TextView(context);
            view.setText(KEY_LABELS[i]);
            view.setGravity(Gravity.CENTER);
            view.setClickable(true);
            view.setOnClickListener(v -> dispatchKeyAction(id));

            KeyView key = new KeyView(id, view, i);
            key.xRatio = item == null ? DEFAULT_X[i] : (float) item.optDouble("x", DEFAULT_X[i]);
            key.yRatio = item == null ? DEFAULT_Y[i] : (float) item.optDouble("y", DEFAULT_Y[i]);
            key.sizeMode = item == null ? SIZE_NORMAL : clamp(item.optInt("s", SIZE_NORMAL), 0, 2);

            int size = buttonSizePx(key.sizeMode);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
            padLayer.addView(view, lp);
            applyKeySize(key);
            bindKeyTouch(key);
            keys.put(id, key);
        }

        padLayer.post(() -> {
            for (KeyView key : keys.values()) {
                applyKeyPosition(key);
            }
        });
        refreshSelectionUi();
    }

    private void bindKeyTouch(KeyView key) {
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

    private void dispatchKeyAction(String keyId) {
        switch (keyId) {
            case "scroll_up":
                sink.onScroll(3);
                break;
            case "scroll_down":
                sink.onScroll(-3);
                break;
            default:
                sink.onClick(keyId);
                break;
        }
    }

    private void applyKeySize(KeyView key) {
        int size = buttonSizePx(key.sizeMode);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) key.view.getLayoutParams();
        lp.width = size;
        lp.height = size;
        key.view.setLayoutParams(lp);
        key.view.setTextSize(key.sizeMode == SIZE_SMALL ? 12f : (key.sizeMode == SIZE_LARGE ? 15f : 13.5f));
        boolean primary = "left".equals(key.id);
        key.view.setBackgroundResource(primary ? R.drawable.bg_primary_button : R.drawable.bg_ghost_button);
        key.view.setTextColor(ContextCompat.getColor(context,
                primary ? R.color.text_on_accent : R.color.text_primary));
    }

    private void applyKeyPosition(KeyView key) {
        float maxX = Math.max(0, padLayer.getWidth() - key.view.getWidth());
        float maxY = Math.max(0, padLayer.getHeight() - key.view.getHeight());
        key.view.setX(Math.max(0, Math.min(1, key.xRatio)) * maxX);
        key.view.setY(Math.max(0, Math.min(1, key.yRatio)) * maxY);
    }

    private void clampPosition(KeyView key) {
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

    private void saveKeyPosition(KeyView key) {
        float maxX = Math.max(1, padLayer.getWidth() - key.view.getWidth());
        float maxY = Math.max(1, padLayer.getHeight() - key.view.getHeight());
        key.xRatio = key.view.getX() / maxX;
        key.yRatio = key.view.getY() / maxY;
        saveKeys();
    }

    private void saveKeys() {
        JSONObject root = new JSONObject();
        try {
            for (KeyView key : keys.values()) {
                JSONObject item = new JSONObject();
                item.put("x", round(key.xRatio));
                item.put("y", round(key.yRatio));
                item.put("s", key.sizeMode);
                root.put(key.id, item);
            }
        } catch (Exception ignored) {
            return;
        }
        prefs.edit().putString(KEY_PAD_KEYS, root.toString()).apply();
    }

    private JSONObject loadKeysJson() {
        String raw = prefs.getString(KEY_PAD_KEYS, null);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(raw);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- 工具 ----------------

    private int indexOfKey(String keyId) {
        if (keyId == null) {
            return -1;
        }
        for (int i = 0; i < KEY_IDS.length; i++) {
            if (KEY_IDS[i].equals(keyId)) {
                return i;
            }
        }
        return -1;
    }

    private int buttonSizePx(int sizeMode) {
        int dp = sizeMode == SIZE_SMALL ? 48 : (sizeMode == SIZE_LARGE ? 64 : 56);
        return dp(dp);
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

    private static final class KeyView {
        final String id;
        final TextView view;
        int sizeMode;
        float xRatio;
        float yRatio;

        KeyView(String id, TextView view, int sizeMode) {
            this.id = id;
            this.view = view;
            this.sizeMode = sizeMode;
        }
    }
}
