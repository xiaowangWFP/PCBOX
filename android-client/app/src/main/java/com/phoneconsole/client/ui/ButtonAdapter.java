package com.phoneconsole.client.ui;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.phoneconsole.client.R;
import com.phoneconsole.client.model.ButtonItem;

import java.util.ArrayList;
import java.util.List;

/**
 * 触控网格适配器：
 * 单击 -> click；长按 -> long_press_start / long_press_end；取消 -> long_press_cancel。
 */
public class ButtonAdapter extends RecyclerView.Adapter<ButtonAdapter.ButtonHolder> {

    public interface OnButtonActionListener {
        void onAction(ButtonItem item, String event);
    }

    private static final long LONG_PRESS_MS = 400L;
    private static final String DEFAULT_BG = "#1E293B";

    private final List<ButtonItem> items = new ArrayList<>();
    private final OnButtonActionListener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 单行高度（px），0 表示由布局自行决定 */
    private int rowHeight;

    public ButtonAdapter(OnButtonActionListener listener) {
        this.listener = listener;
    }

    /** 按页面配置的行数把网格切成等高行，避免少量按钮被拉满整屏 */
    public void setRowHeight(int heightPx) {
        if (rowHeight == heightPx) {
            return;
        }
        rowHeight = heightPx;
        notifyDataSetChanged();
    }

    public void submit(List<ButtonItem> data) {
        items.clear();
        if (data != null) {
            items.addAll(data);
        }
        notifyDataSetChanged();
    }

    public void updateButton(String buttonId, String state, String label, String icon, String bgColor) {
        for (int i = 0; i < items.size(); i++) {
            ButtonItem item = items.get(i);
            if (item.id != null && item.id.equals(buttonId)) {
                item.state = state;
                if (label != null) {
                    item.label = label;
                }
                if (icon != null) {
                    item.icon = icon;
                }
                if (bgColor != null) {
                    item.bgColor = bgColor;
                }
                notifyItemChanged(i);
                return;
            }
        }
    }

    @NonNull
    @Override
    public ButtonHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_button, parent, false);
        return new ButtonHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ButtonHolder holder, int position) {
        holder.bind(items.get(position));
    }

    @Override
    public void onViewRecycled(@NonNull ButtonHolder holder) {
        holder.resetGesture();
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    class ButtonHolder extends RecyclerView.ViewHolder {

        private final TextView label;
        private ButtonItem current;
        private boolean longPressed;
        private Runnable longPressTask;

        ButtonHolder(@NonNull View itemView) {
            super(itemView);
            label = itemView.findViewById(R.id.btn_label);
            itemView.setOnTouchListener(this::onTouch);
        }

        void bind(ButtonItem item) {
            current = item;
            resetGesture();
            itemView.setAlpha(1f);
            label.setText(item.label == null ? "" : item.label);

            ViewGroup.LayoutParams params = itemView.getLayoutParams();
            if (rowHeight > 0 && params != null && params.height != rowHeight) {
                params.height = rowHeight;
                itemView.setLayoutParams(params);
            }

            int fill = parseColor(item.bgColor);
            boolean active = item.state != null && !"normal".equals(item.state);
            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.RECTANGLE);
            background.setCornerRadius(dp(14));
            background.setColor(fill);
            background.setStroke((int) dp(1), active ? 0xFF38BDF8 : 0x33FFFFFF);
            itemView.setBackground(background);
            label.setTextColor(active ? 0xFF7DD3FC : 0xFFE2E8F0);

            // TODO: icon 非空时可用图片库加载 /static 下的图标
        }

        void resetGesture() {
            if (longPressTask != null) {
                handler.removeCallbacks(longPressTask);
                longPressTask = null;
            }
            longPressed = false;
        }

        private boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.setAlpha(0.6f);
                    Haptics.tap(view.getContext());
                    longPressed = false;
                    resetGesture();
                    longPressTask = () -> {
                        longPressed = true;
                        emit("long_press_start");
                    };
                    handler.postDelayed(longPressTask, LONG_PRESS_MS);
                    return true;

                case MotionEvent.ACTION_UP:
                    view.setAlpha(1f);
                    // 必须先取出长按状态：resetGesture() 会把 longPressed 置回 false，
                    // 否则长按松手会误发 click，PC 端收不到 long_press_end 而一直连发。
                    boolean wasLongPress = longPressed;
                    resetGesture();
                    emit(wasLongPress ? "long_press_end" : "click");
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    view.setAlpha(1f);
                    if (longPressed) {
                        emit("long_press_cancel");
                    }
                    resetGesture();
                    return true;

                default:
                    return false;
            }
        }

        private void emit(String event) {
            if (listener != null && current != null) {
                listener.onAction(current, event);
            }
        }

        private int parseColor(String value) {
            try {
                return Color.parseColor(value == null || value.isEmpty() ? DEFAULT_BG : value);
            } catch (IllegalArgumentException e) {
                return Color.parseColor(DEFAULT_BG);
            }
        }

        private float dp(int value) {
            return value * itemView.getResources().getDisplayMetrics().density;
        }
    }
}
