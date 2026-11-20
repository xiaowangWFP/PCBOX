package com.phoneconsole.client.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.phoneconsole.client.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/**
 * 功能控制工作台控制器：
 * 同时驱动竖屏 (res/layout/page_control.xml) 与横屏双栏 (res/layout-land/page_control.xml)，
 * 所有网络指令通过 ControlActionDispatcher 回调给原有 MainActivity / ConnectionManager，
 * 做到零侵入增量接入。
 */
public class ControlWorkspaceController {

    public static final class DeckPageMeta {
        public final String id;
        public final String title;

        public DeckPageMeta(@NonNull String id, @NonNull String title) {
            this.id = id;
            this.title = title;
        }
    }

    public static final class CustomKeyDraft {
        public final String label;
        public final String actionType;
        public final String target;
        public final String bgColorHex;

        public CustomKeyDraft(
                @NonNull String label,
                @NonNull String actionType,
                @NonNull String target,
                @NonNull String bgColorHex
        ) {
            this.label = label;
            this.actionType = actionType;
            this.target = target;
            this.bgColorHex = bgColorHex;
        }
    }

    public interface ControlActionDispatcher {
        void onSwitchDeckPage(@NonNull String pageId);

        void onDeckKeyClick(@NonNull String buttonId);

        void onDeckKeyRepeat(@NonNull String buttonId);

        void onDeckKeyDelete(@NonNull String buttonId, @NonNull String label);

        void onAddCustomKey(@NonNull CustomKeyDraft draft);

        void onSetVolume(int volumePercent);

        void onToggleSpeakerMute();

        void onToggleMicMute();

        void onQuickMediaCommand(@NonNull String command);

        void onQuickHotkey(@NonNull String combo);

        void onSendRemoteText(@NonNull String text, boolean pressEnter);

        void onTouchpadMove(int dx, int dy);

        void onTouchpadClick(@NonNull String mouseButton);

        void onTouchpadScroll(int deltaY);
    }

    private static final class HistoryEntry {
        final String title;
        final String subtitle;
        final Runnable replayAction;

        HistoryEntry(String title, String subtitle, Runnable replayAction) {
            this.title = title;
            this.subtitle = subtitle;
            this.replayAction = replayAction;
        }
    }

    private static final int MAX_HISTORY = 16;

    private final Context context;
    private final View root;
    private final ControlActionDispatcher dispatcher;
    private final TactileDeckAdapter deckAdapter = new TactileDeckAdapter();
    private final LinkedList<HistoryEntry> historyEntries = new LinkedList<>();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private TextView tvDeckTitle;
    private TextView tvDeckMeta;
    private LinearLayout deckTabContainer;
    private View deckTabThumb;
    private TextView btnToggleEditDeck;
    private TextView btnSpeakerMute;
    private TextView btnMicMute;
    private SeekBar seekVolume;
    private TextView tvVolumeValue;
    private RecyclerView rvDeckButtons;

    private TextView tabInput;
    private TextView tabTouchpad;
    private TextView tabHistory;
    private View companionTabThumb;
    private View paneQuickInput;
    private View paneTouchpad;
    private View paneHistory;
    private EditText etRemoteText;
    private TouchpadView touchpadView;
    private LinearLayout historyContainer;

    private boolean userDraggingVolume = false;
    private String activeDeckId = "";
    private int activeCompanionIndex = 0;

    public ControlWorkspaceController(
            @NonNull View pageControlRoot,
            @NonNull ControlActionDispatcher dispatcher
    ) {
        this.context = pageControlRoot.getContext();
        this.root = pageControlRoot;
        this.dispatcher = dispatcher;
        bindViews();
        wireInteractions();
        renderHistory();
    }

    private void bindViews() {
        tvDeckTitle = root.findViewById(R.id.tvDeckTitle);
        tvDeckMeta = root.findViewById(R.id.tvDeckMeta);
        deckTabContainer = root.findViewById(R.id.deckTabContainer);
        deckTabThumb = root.findViewById(R.id.deckTabThumb);
        btnToggleEditDeck = root.findViewById(R.id.btnToggleEditDeck);
        btnSpeakerMute = root.findViewById(R.id.btnSpeakerMute);
        btnMicMute = root.findViewById(R.id.btnMicMute);
        seekVolume = root.findViewById(R.id.seekVolume);
        tvVolumeValue = root.findViewById(R.id.tvVolumeValue);
        rvDeckButtons = root.findViewById(R.id.rvDeckButtons);

        tabInput = root.findViewById(R.id.companionTabInput);
        tabTouchpad = root.findViewById(R.id.companionTabTouchpad);
        tabHistory = root.findViewById(R.id.companionTabHistory);
        companionTabThumb = root.findViewById(R.id.companionTabThumb);
        paneQuickInput = root.findViewById(R.id.paneQuickInput);
        paneTouchpad = root.findViewById(R.id.paneTouchpad);
        paneHistory = root.findViewById(R.id.paneHistory);
        etRemoteText = root.findViewById(R.id.etRemoteText);
        touchpadView = root.findViewById(R.id.touchpadView);
        historyContainer = root.findViewById(R.id.historyContainer);

        int defaultCols = context.getResources().getInteger(R.integer.deck_default_columns);
        rvDeckButtons.setLayoutManager(new GridLayoutManager(context, defaultCols));
        rvDeckButtons.setAdapter(deckAdapter);
    }

    private void wireInteractions() {
        deckAdapter.setOnKeyInteractionListener(new TactileDeckAdapter.OnKeyInteractionListener() {
            @Override
            public void onKeyClick(@NonNull TactileDeckAdapter.DeckKeyItem item) {
                dispatcher.onDeckKeyClick(item.id);
                recordHistory(item.label, item.hint, () -> dispatcher.onDeckKeyClick(item.id));
            }

            @Override
            public void onKeyRepeatTick(@NonNull TactileDeckAdapter.DeckKeyItem item) {
                dispatcher.onDeckKeyRepeat(item.id);
            }

            @Override
            public void onKeyDelete(@NonNull TactileDeckAdapter.DeckKeyItem item) {
                dispatcher.onDeckKeyDelete(item.id, item.label);
            }
        });

        btnToggleEditDeck.setOnClickListener(v -> {
            boolean next = !deckAdapter.isEditMode();
            deckAdapter.setEditMode(next);
            btnToggleEditDeck.setText(next ? R.string.ctrl_finish_manage : R.string.ctrl_manage_keys);
            btnToggleEditDeck.setBackgroundResource(
                    next ? R.drawable.bg_primary_button : R.drawable.bg_ghost_button
            );
        });

        View btnAdd = root.findViewById(R.id.btnAddCustomKey);
        if (btnAdd != null) {
            btnAdd.setOnClickListener(v -> showAddCustomKeyDialog());
        }

        // 音量与媒体控制坞
        seekVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                tvVolumeValue.setText(progress + "%");
                if (fromUser) {
                    dispatcher.onSetVolume(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                userDraggingVolume = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                userDraggingVolume = false;
                int v = bar.getProgress();
                dispatcher.onSetVolume(v);
                recordHistory("音量 " + v + "%", "音量滑块", () -> dispatcher.onSetVolume(v));
            }
        });

        btnSpeakerMute.setOnClickListener(v -> {
            dispatcher.onToggleSpeakerMute();
            recordHistory("切换扬声器静音", "音频控制", dispatcher::onToggleSpeakerMute);
        });

        btnMicMute.setOnClickListener(v -> {
            dispatcher.onToggleMicMute();
            recordHistory("切换麦克风静音", "音频控制", dispatcher::onToggleMicMute);
        });

        bindQuickHotkey(R.id.btnQuickSleep, "sleep", "睡眠");

        bindQuickHotkey(R.id.btnQuickShot, "win+shift+s", "区域截图");
        bindQuickHotkey(R.id.btnQuickDesktop, "win+d", "显示桌面");
        bindQuickHotkey(R.id.btnQuickLock, "win+l", "锁定屏幕");

        // 工具舱切换
        tabInput.setOnClickListener(v -> selectCompanionTab(0, true));
        tabTouchpad.setOnClickListener(v -> selectCompanionTab(1, true));
        tabHistory.setOnClickListener(v -> selectCompanionTab(2, true));
        selectCompanionTab(0, false);

        // 快捷文本输入
        View btnSendOnly = root.findViewById(R.id.btnSendTextOnly);
        View btnSendEnter = root.findViewById(R.id.btnSendTextEnter);
        if (btnSendOnly != null) btnSendOnly.setOnClickListener(v -> submitRemoteText(false));
        if (btnSendEnter != null) btnSendEnter.setOnClickListener(v -> submitRemoteText(true));
        if (etRemoteText != null) {
            etRemoteText.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    submitRemoteText(true);
                    return true;
                }
                return false;
            });
        }

        bindQuickHotkey(R.id.btnHotkeyCopy, "ctrl+c", "复制");
        bindQuickHotkey(R.id.btnHotkeyPaste, "ctrl+v", "粘贴");
        bindQuickHotkey(R.id.btnHotkeyUndo, "ctrl+z", "撤销");
        bindQuickHotkey(R.id.btnHotkeySelectAll, "ctrl+a", "全选");
        bindQuickHotkey(R.id.btnHotkeyAltTab, "alt+tab", "切换窗口");
        bindQuickHotkey(R.id.btnHotkeyClose, "alt+f4", "关闭窗口");

        // 无线触控板
        touchpadView.setOnTouchpadEventListener(new TouchpadView.OnTouchpadEventListener() {
            @Override
            public void onMove(int dx, int dy) {
                dispatcher.onTouchpadMove(dx, dy);
            }

            @Override
            public void onTapClick() {
                dispatcher.onTouchpadClick("left");
            }
        });

        bindMouseClick(R.id.btnMouseLeft, "left", "鼠标左键");
        bindMouseClick(R.id.btnMouseMiddle, "middle", "鼠标中键");
        bindMouseClick(R.id.btnMouseRight, "right", "鼠标右键");

        View btnScrollUp = root.findViewById(R.id.btnScrollUp);
        View btnScrollDown = root.findViewById(R.id.btnScrollDown);
        if (btnScrollUp != null) btnScrollUp.setOnClickListener(v -> dispatcher.onTouchpadScroll(3));
        if (btnScrollDown != null) btnScrollDown.setOnClickListener(v -> dispatcher.onTouchpadScroll(-3));

        Spinner sensSpinner = root.findViewById(R.id.spinnerTouchpadSens);
        if (sensSpinner != null) {
            ArrayAdapter<String> sensAdapter = new ArrayAdapter<>(
                    context,
                    android.R.layout.simple_spinner_item,
                    new String[]{"慢速", "标准", "快速"}
            );
            sensAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            sensSpinner.setAdapter(sensAdapter);
            sensSpinner.setSelection(1);
            sensSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                    if (view instanceof TextView) {
                        ((TextView) view).setTextColor(ContextCompat.getColor(context, R.color.text_primary));
                        ((TextView) view).setTextSize(12f);
                    }
                    touchpadView.setSensitivity(pos == 0 ? 0.75f : (pos == 2 ? 1.85f : 1.2f));
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
        }

        View btnClearHistory = root.findViewById(R.id.btnClearHistory);
        if (btnClearHistory != null) {
            btnClearHistory.setOnClickListener(v -> {
                historyEntries.clear();
                renderHistory();
            });
        }
    }

    public void updateDeckPages(@NonNull List<DeckPageMeta> pages, @NonNull String currentPageId) {
        this.activeDeckId = currentPageId;
        deckTabContainer.removeAllViews();
        View activeTab = null;

        for (DeckPageMeta meta : pages) {
            TextView tab = new TextView(context);
            tab.setText(meta.title);
            tab.setTextSize(12f);
            tab.setGravity(Gravity.CENTER);
            int padH = Math.round(11f * context.getResources().getDisplayMetrics().density);
            int heightPx = Math.round(32f * context.getResources().getDisplayMetrics().density);
            tab.setPadding(padH, 0, padH, 0);
            tab.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    heightPx
            ));
            boolean selected = meta.id.equals(currentPageId);
            tab.setTextColor(ContextCompat.getColor(
                    context,
                    selected ? R.color.text_on_accent : R.color.text_secondary
            ));
            tab.setOnClickListener(v -> dispatcher.onSwitchDeckPage(meta.id));
            deckTabContainer.addView(tab);
            if (selected) activeTab = tab;
        }

        if (activeTab != null) {
            UiMotionHelper.animateHorizontalIndicator(deckTabThumb, activeTab, 0, true);
        }
    }

    public void updateDeckButtons(
            @NonNull String pageTitle,
            @NonNull List<TactileDeckAdapter.DeckKeyItem> buttons,
            int configuredCols,
            int totalPages
    ) {
        tvDeckTitle.setText(pageTitle);
        tvDeckMeta.setText(buttons.size() + " 个按键 · " + Math.max(1, totalPages) + " 套场景面板");

        int maxCols = context.getResources().getInteger(R.integer.deck_default_columns);
        int effectiveCols = Math.max(2, Math.min(configuredCols > 0 ? configuredCols : 3, maxCols));
        RecyclerView.LayoutManager lm = rvDeckButtons.getLayoutManager();
        if (lm instanceof GridLayoutManager) {
            ((GridLayoutManager) lm).setSpanCount(effectiveCols);
        }
        deckAdapter.submitList(buttons);
    }

    public void updateAudioTelemetry(int volumePercent, boolean speakerMuted, boolean micMuted) {
        int clamped = Math.max(0, Math.min(100, volumePercent));
        if (!userDraggingVolume) {
            seekVolume.setProgress(clamped);
            tvVolumeValue.setText(clamped + "%");
        }

        btnSpeakerMute.setText(speakerMuted ? R.string.ctrl_speaker_muted : R.string.ctrl_speaker);
        btnSpeakerMute.setBackgroundResource(
                speakerMuted ? R.drawable.bg_danger_button : R.drawable.bg_ghost_button
        );
        btnSpeakerMute.setTextColor(ContextCompat.getColor(
                context,
                speakerMuted ? R.color.status_bad_text : R.color.text_primary
        ));

        btnMicMute.setText(micMuted ? R.string.ctrl_mic_muted : R.string.ctrl_mic);
        btnMicMute.setBackgroundResource(
                micMuted ? R.drawable.bg_danger_button : R.drawable.bg_ghost_button
        );
        btnMicMute.setTextColor(ContextCompat.getColor(
                context,
                micMuted ? R.color.status_bad_text : R.color.text_primary
        ));
    }

    public void resyncIndicators() {
        View activeComp = activeCompanionIndex == 0 ? tabInput
                : (activeCompanionIndex == 1 ? tabTouchpad : tabHistory);
        UiMotionHelper.animateHorizontalIndicator(companionTabThumb, activeComp, 0, false);

        for (int i = 0; i < deckTabContainer.getChildCount(); i++) {
            View child = deckTabContainer.getChildAt(i);
            if (child instanceof TextView && ((TextView) child).getCurrentTextColor()
                    == ContextCompat.getColor(context, R.color.text_on_accent)) {
                UiMotionHelper.animateHorizontalIndicator(deckTabThumb, child, 0, false);
                break;
            }
        }
    }

    private void selectCompanionTab(int index, boolean animate) {
        activeCompanionIndex = index;
        paneQuickInput.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        paneTouchpad.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        paneHistory.setVisibility(index == 2 ? View.VISIBLE : View.GONE);

        TextView[] tabs = new TextView[]{tabInput, tabTouchpad, tabHistory};
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setTextColor(ContextCompat.getColor(
                    context,
                    i == index ? R.color.text_on_accent : R.color.text_secondary
            ));
        }
        UiMotionHelper.animateHorizontalIndicator(companionTabThumb, tabs[index], 0, animate);
    }

    private void submitRemoteText(boolean pressEnter) {
        if (etRemoteText == null) return;
        String text = etRemoteText.getText().toString().trim();
        if (text.isEmpty()) return;
        dispatcher.onSendRemoteText(text, pressEnter);
        etRemoteText.setText("");
        recordHistory(
                pressEnter ? "输入并回车" : "远程输入",
                text,
                () -> dispatcher.onSendRemoteText(text, pressEnter)
        );
    }

    private void bindQuickMedia(int viewId, @NonNull String cmd, @NonNull String label) {
        View v = root.findViewById(viewId);
        if (v != null) {
            v.setOnClickListener(btn -> {
                dispatcher.onQuickMediaCommand(cmd);
                recordHistory(label, "媒体控制", () -> dispatcher.onQuickMediaCommand(cmd));
            });
        }
    }

    private void bindQuickHotkey(int viewId, @NonNull String combo, @NonNull String label) {
        View v = root.findViewById(viewId);
        if (v != null) {
            v.setOnClickListener(btn -> {
                dispatcher.onQuickHotkey(combo);
                recordHistory(label, combo.toUpperCase(Locale.ROOT), () -> dispatcher.onQuickHotkey(combo));
            });
        }
    }

    private void bindMouseClick(int viewId, @NonNull String mouseButton, @NonNull String label) {
        View v = root.findViewById(viewId);
        if (v != null) {
            v.setOnClickListener(btn -> {
                dispatcher.onTouchpadClick(mouseButton);
                recordHistory(label, "触控板", () -> dispatcher.onTouchpadClick(mouseButton));
            });
        }
    }

    private void recordHistory(@NonNull String title, @NonNull String subtitle, @NonNull Runnable replay) {
        String stamp = timeFmt.format(new Date());
        historyEntries.addFirst(new HistoryEntry(title, stamp + " · " + subtitle, replay));
        while (historyEntries.size() > MAX_HISTORY) {
            historyEntries.removeLast();
        }
        renderHistory();
    }

    private void renderHistory() {
        if (historyContainer == null) return;
        historyContainer.removeAllViews();
        float d = context.getResources().getDisplayMetrics().density;

        if (historyEntries.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText(R.string.history_empty);
            empty.setTextColor(ContextCompat.getColor(context, R.color.text_muted));
            empty.setTextSize(12f);
            empty.setPadding(Math.round(10 * d), Math.round(14 * d), Math.round(10 * d), Math.round(14 * d));
            empty.setGravity(Gravity.CENTER);
            empty.setBackgroundResource(R.drawable.bg_input);
            historyContainer.addView(empty);
            return;
        }

        for (HistoryEntry entry : historyEntries) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.bg_input);
            int pH = Math.round(10 * d);
            int pV = Math.round(8 * d);
            row.setPadding(pH, pV, pH, pV);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            lp.bottomMargin = Math.round(6 * d);
            row.setLayoutParams(lp);

            LinearLayout info = new LinearLayout(context);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView tvTitle = new TextView(context);
            tvTitle.setText(entry.title);
            tvTitle.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
            tvTitle.setTextSize(12.5f);
            tvTitle.setMaxLines(1);

            TextView tvSub = new TextView(context);
            tvSub.setText(entry.subtitle);
            tvSub.setTextColor(ContextCompat.getColor(context, R.color.text_muted));
            tvSub.setTextSize(11f);
            tvSub.setMaxLines(1);

            info.addView(tvTitle);
            info.addView(tvSub);

            TextView btnReplay = new TextView(context);
            btnReplay.setText(R.string.history_replay);
            btnReplay.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
            btnReplay.setTextSize(11.5f);
            btnReplay.setBackgroundResource(R.drawable.bg_ghost_button);
            btnReplay.setPadding(Math.round(10 * d), Math.round(5 * d), Math.round(10 * d), Math.round(5 * d));
            btnReplay.setOnClickListener(v -> entry.replayAction.run());

            row.addView(info);
            row.addView(btnReplay);
            historyContainer.addView(row);
        }
    }

    private void showAddCustomKeyDialog() {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View content = LayoutInflater.from(context).inflate(R.layout.dialog_add_key, null);
        dialog.setContentView(content);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = Math.min(
                    Math.round(context.getResources().getDisplayMetrics().widthPixels * 0.92f),
                    Math.round(480f * context.getResources().getDisplayMetrics().density)
            );
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        EditText etLabel = content.findViewById(R.id.etDialogKeyLabel);
        Spinner spType = content.findViewById(R.id.spinnerDialogKeyType);
        EditText etTarget = content.findViewById(R.id.etDialogKeyTarget);
        TextView tvError = content.findViewById(R.id.tvDialogError);

        final String[] typeLabels = new String[]{"快捷键组合", "启动程序", "打开网址", "媒体指令"};
        final String[] typeValues = new String[]{"hotkey", "launch", "url", "media"};
        ArrayAdapter<String> typeAdapter = new ArrayAdapter<>(
                context,
                android.R.layout.simple_spinner_item,
                typeLabels
        );
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spType.setAdapter(typeAdapter);

        final String[] selectedColor = new String[]{"#1E293B"};
        int[] swatchIds = new int[]{
                R.id.swatchSlate, R.id.swatchObsidian, R.id.swatchTeal, R.id.swatchCobalt, R.id.swatchCrimson
        };
        for (int id : swatchIds) {
            View sw = content.findViewById(id);
            if (sw != null) {
                sw.setOnClickListener(v -> {
                    selectedColor[0] = String.valueOf(v.getTag());
                    for (int otherId : swatchIds) {
                        View other = content.findViewById(otherId);
                        if (other != null) {
                            other.setAlpha(other == v ? 1f : 0.45f);
                        }
                    }
                });
            }
        }

        content.findViewById(R.id.btnDialogCancel).setOnClickListener(v -> dialog.dismiss());
        content.findViewById(R.id.btnDialogSave).setOnClickListener(v -> {
            String label = etLabel.getText().toString().trim();
            String target = etTarget.getText().toString().trim();
            String actionType = typeValues[Math.max(0, spType.getSelectedItemPosition())];

            if (label.isEmpty()) {
                tvError.setText("请输入按键名称");
                tvError.setVisibility(View.VISIBLE);
                return;
            }
            if (target.isEmpty()) {
                tvError.setText("请输入快捷键、程序名、网址或媒体指令");
                tvError.setVisibility(View.VISIBLE);
                return;
            }

            dispatcher.onAddCustomKey(new CustomKeyDraft(label, actionType, target, selectedColor[0]));
            dialog.dismiss();
        });

        dialog.show();
    }

    public String getActiveDeckId() {
        return activeDeckId;
    }
}
