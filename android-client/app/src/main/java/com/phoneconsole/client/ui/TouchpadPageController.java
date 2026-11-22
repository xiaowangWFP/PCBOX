package com.phoneconsole.client.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import com.phoneconsole.client.R;

/**
 * 触控板独立页：整页触摸区 + 可拖动的悬浮键组 + 右上角设置面板。
 * 所有开关、布局、大小与悬浮位置都持久化，旋转后自动恢复。
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
    private static final String KEY_PAD_LAYOUT = "pad_layout";
    private static final String KEY_PAD_SIZE = "pad_size";
    private static final String KEY_PAD_X = "pad_x";
    private static final String KEY_PAD_Y = "pad_y";

    private static final int LAYOUT_COLUMN = 0;
    private static final int LAYOUT_GRID = 1;
    private static final int LAYOUT_ROW = 2;

    private final Context context;
    private final ActionSink sink;
    private final SharedPreferences prefs;

    private final TouchpadView touchpad;
    private final DraggablePadLayout pad;
    private final View settingsPanel;
    private final SwitchCompat padVisibleSwitch;
    private final SwitchCompat volumeScrollSwitch;
    private final Spinner sensitivitySpinner;
    private final TextView[] layoutButtons;
    private final TextView[] sizeButtons;

    private int layoutMode;
    private int sizeMode;
    private boolean volumeScrollEnabled;

    public TouchpadPageController(@NonNull Context context, @NonNull View root, @NonNull ActionSink sink) {
        this.context = context;
        this.sink = sink;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        touchpad = root.findViewById(R.id.tpTouchpadView);
        pad = root.findViewById(R.id.tpPadContainer);
        settingsPanel = root.findViewById(R.id.tpSettingsPanel);
        padVisibleSwitch = root.findViewById(R.id.tpPadVisibleSwitch);
        volumeScrollSwitch = root.findViewById(R.id.tpVolumeScrollSwitch);
        sensitivitySpinner = root.findViewById(R.id.tpSensitivity);
        layoutButtons = new TextView[]{
                root.findViewById(R.id.tpLayoutColumn),
                root.findViewById(R.id.tpLayoutGrid),
                root.findViewById(R.id.tpLayoutRow)
        };
        sizeButtons = new TextView[]{
                root.findViewById(R.id.tpSizeSmall),
                root.findViewById(R.id.tpSizeNormal),
                root.findViewById(R.id.tpSizeLarge)
        };

        layoutMode = clamp(prefs.getInt(KEY_PAD_LAYOUT, LAYOUT_GRID), 0, 2);
        sizeMode = clamp(prefs.getInt(KEY_PAD_SIZE, 1), 0, 2);
        volumeScrollEnabled = prefs.getBoolean(KEY_VOLUME_SCROLL, false);

        bindTouchpad();
        bindSettingsButton(root);
        bindSettingsPanel(root);
        bindPadDrag();
        rebuildPad();
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
                pad.setVisibility(checked ? View.VISIBLE : View.GONE);
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

        for (int i = 0; i < layoutButtons.length; i++) {
            final int mode = i;
            if (layoutButtons[i] != null) {
                layoutButtons[i].setOnClickListener(v -> {
                    layoutMode = mode;
                    prefs.edit().putInt(KEY_PAD_LAYOUT, mode).apply();
                    rebuildPad();
                });
            }
        }

        for (int i = 0; i < sizeButtons.length; i++) {
            final int mode = i;
            if (sizeButtons[i] != null) {
                sizeButtons[i].setOnClickListener(v -> {
                    sizeMode = mode;
                    prefs.edit().putInt(KEY_PAD_SIZE, mode).apply();
                    rebuildPad();
                });
            }
        }

        View reset = root.findViewById(R.id.tpPadReset);
        if (reset != null) {
            reset.setOnClickListener(v -> {
                prefs.edit().remove(KEY_PAD_X).remove(KEY_PAD_Y).apply();
                pad.applyPositionRatio(1f, 0.3f);
            });
        }
    }

    private void bindPadDrag() {
        pad.setOnPositionChangedListener((xRatio, yRatio) ->
                prefs.edit()
                        .putFloat(KEY_PAD_X, xRatio)
                        .putFloat(KEY_PAD_Y, yRatio)
                        .apply());
    }

    private boolean restorePadVisible() {
        return prefs.getBoolean(KEY_PAD_VISIBLE, true);
    }

    private void restorePadVisibility() {
        boolean visible = restorePadVisible();
        pad.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    // ---------------- 悬浮键组 ----------------

    private void rebuildPad() {
        float xRatio = pad.isPositionInitialized() ? pad.currentXRatio() : prefs.getFloat(KEY_PAD_X, 1f);
        float yRatio = pad.isPositionInitialized() ? pad.currentYRatio() : prefs.getFloat(KEY_PAD_Y, 0.3f);

        pad.removeAllViews();
        if (layoutMode == LAYOUT_GRID) {
            pad.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout leftColumn = column();
            leftColumn.addView(button("左键", "left", true));
            leftColumn.addView(button("中键", "middle", false));
            leftColumn.addView(button("右键", "right", false));

            LinearLayout rightColumn = column();
            rightColumn.addView(button("上滚", "scroll_up", false));
            rightColumn.addView(button("下滚", "scroll_down", false));

            pad.addView(leftColumn);
            pad.addView(rightColumn);
        } else {
            pad.setOrientation(layoutMode == LAYOUT_ROW ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            pad.addView(button("左键", "left", true));
            pad.addView(button("中键", "middle", false));
            pad.addView(button("右键", "right", false));
            pad.addView(button("上滚", "scroll_up", false));
            pad.addView(button("下滚", "scroll_down", false));
        }

        updateOptionStyles();
        restorePadPosition(xRatio, yRatio);
    }

    /** 尺寸测量完成后再按比例定位，避免首次进入时边界为 0 导致贴左上角 */
    private void restorePadPosition(float xRatio, float yRatio) {
        final int[] attempts = {0};
        final Runnable apply = new Runnable() {
            @Override
            public void run() {
                if (pad.getWidth() <= 0 || pad.getHeight() <= 0) {
                    if (++attempts[0] < 30) {
                        pad.postDelayed(this, 16);
                    }
                    return;
                }
                pad.applyPositionRatio(xRatio, yRatio);
            }
        };
        pad.post(apply);
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return column;
    }

    private TextView button(String label, String action, boolean primary) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setGravity(Gravity.CENTER);
        view.setTextSize(sizeMode == 0 ? 12f : (sizeMode == 2 ? 15f : 13.5f));
        view.setTextColor(ContextCompat.getColor(context,
                primary ? R.color.text_on_accent : R.color.text_primary));
        view.setBackgroundResource(primary ? R.drawable.bg_primary_button : R.drawable.bg_ghost_button);
        view.setClickable(true);

        int size = buttonSizePx();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        int margin = dp(3);
        lp.setMargins(margin, margin, margin, margin);
        view.setLayoutParams(lp);

        view.setOnClickListener(v -> {
            if ("scroll_up".equals(action)) {
                sink.onScroll(3);
            } else if ("scroll_down".equals(action)) {
                sink.onScroll(-3);
            } else {
                sink.onClick(action);
            }
        });
        return view;
    }

    private void updateOptionStyles() {
        styleOptions(layoutButtons, layoutMode);
        styleOptions(sizeButtons, sizeMode);
    }

    private void styleOptions(TextView[] buttons, int selected) {
        for (int i = 0; i < buttons.length; i++) {
            TextView button = buttons[i];
            if (button == null) {
                continue;
            }
            boolean active = i == selected;
            button.setBackgroundResource(active ? R.drawable.bg_primary_button : R.drawable.bg_ghost_button);
            button.setTextColor(ContextCompat.getColor(context,
                    active ? R.color.text_on_accent : R.color.text_primary));
        }
    }

    private int buttonSizePx() {
        int dp = sizeMode == 0 ? 48 : (sizeMode == 2 ? 64 : 56);
        return dp(dp);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
