package com.phoneconsole.client.ui;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.phoneconsole.client.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Tactile Obsidian 触控键帽适配器（可渐进替换或直接复用）：
 * - 支持自定义键帽背景色、激活态边框高亮、物理按压缩放与长按连发进度条
 * - 支持管理模式右上角一键移除
 */
public class TactileDeckAdapter extends RecyclerView.Adapter<TactileDeckAdapter.KeyViewHolder> {

    public static final class DeckKeyItem {
        public final String id;
        public final String label;
        public final String hint;
        public final String bgColorHex;
        public final boolean active;
        public final boolean repeatable;

        public DeckKeyItem(
                @NonNull String id,
                @NonNull String label,
                @NonNull String hint,
                @Nullable String bgColorHex,
                boolean active,
                boolean repeatable
        ) {
            this.id = id;
            this.label = label;
            this.hint = hint;
            this.bgColorHex = bgColorHex;
            this.active = active;
            this.repeatable = repeatable;
        }
    }

    public interface OnKeyInteractionListener {
        void onKeyClick(@NonNull DeckKeyItem item);

        void onKeyRepeatTick(@NonNull DeckKeyItem item);

        void onKeyDelete(@NonNull DeckKeyItem item);
    }

    private final List<DeckKeyItem> items = new ArrayList<>();
    private final Handler repeatHandler = new Handler(Looper.getMainLooper());
    private boolean editMode = false;

    @Nullable
    private OnKeyInteractionListener listener;

    public void setOnKeyInteractionListener(@Nullable OnKeyInteractionListener listener) {
        this.listener = listener;
    }

    public void submitList(@NonNull List<DeckKeyItem> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    public void setEditMode(boolean editMode) {
        if (this.editMode == editMode) return;
        this.editMode = editMode;
        notifyDataSetChanged();
    }

    public boolean isEditMode() {
        return editMode;
    }

    @NonNull
    @Override
    public KeyViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_button, parent, false);
        return new KeyViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull KeyViewHolder holder, int position) {
        DeckKeyItem item = items.get(position);
        holder.bind(item);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    final class KeyViewHolder extends RecyclerView.ViewHolder {
        final View cardRoot;
        final ImageView ivIcon;
        final TextView tvHint;
        final TextView tvLabel;
        final View viewHoldBar;
        final ImageView btnDelete;

        private Runnable repeatStarter;
        private Runnable repeatTicker;
        private boolean repeatFired;

        KeyViewHolder(@NonNull View itemView) {
            super(itemView);
            cardRoot = itemView.findViewById(R.id.keyCardRoot);
            ivIcon = itemView.findViewById(R.id.ivKeyIcon);
            tvHint = itemView.findViewById(R.id.tvKeyHint);
            tvLabel = itemView.findViewById(R.id.tvKeyLabel);
            viewHoldBar = itemView.findViewById(R.id.viewHoldBar);
            btnDelete = itemView.findViewById(R.id.btnDeleteKey);
        }

        void bind(@NonNull DeckKeyItem item) {
            tvLabel.setText(item.label);
            tvHint.setText(item.hint);
            ivIcon.setImageResource(resolveIcon(item.id));
            btnDelete.setVisibility(editMode ? View.VISIBLE : View.GONE);

            applyKeycapBackground(item);

            btnDelete.setOnClickListener(v -> {
                if (listener != null) listener.onKeyDelete(item);
            });

            cardRoot.setOnTouchListener((v, event) -> {
                if (editMode) return false;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        repeatFired = false;
                        UiMotionHelper.applyKeycapPress(v, true);
                        if (item.repeatable) {
                            startHoldRepeat(item);
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        UiMotionHelper.applyKeycapPress(v, false);
                        stopHoldRepeat();
                        if (!repeatFired && listener != null) {
                            listener.onKeyClick(item);
                        }
                        v.performClick();
                        return true;

                    case MotionEvent.ACTION_CANCEL:
                        UiMotionHelper.applyKeycapPress(v, false);
                        stopHoldRepeat();
                        return true;

                    default:
                        return false;
                }
            });
        }

        private void applyKeycapBackground(@NonNull DeckKeyItem item) {
            float radius = itemView.getResources().getDimension(R.dimen.keycap_radius);
            int strokeColor = ContextCompat.getColor(
                    itemView.getContext(),
                    item.active ? R.color.accent_bright : R.color.keycap_border
            );
            int fillColor = ContextCompat.getColor(itemView.getContext(), R.color.keycap_bg);
            if (item.bgColorHex != null && !item.bgColorHex.trim().isEmpty()) {
                try {
                    fillColor = Color.parseColor(item.bgColorHex.trim());
                } catch (IllegalArgumentException ignored) {
                    // 回退默认黑曜石键帽底色
                }
            }
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(radius);
            bg.setColor(fillColor);
            int strokeWidth = Math.round(
                    (item.active ? 1.5f : 1f) * itemView.getResources().getDisplayMetrics().density
            );
            bg.setStroke(strokeWidth, strokeColor);
            cardRoot.setBackground(bg);
        }

        private void startHoldRepeat(@NonNull DeckKeyItem item) {
            stopHoldRepeat();
            viewHoldBar.setAlpha(1f);
            viewHoldBar.setScaleX(0f);
            viewHoldBar.setPivotX(0f);
            viewHoldBar.animate().scaleX(1f).setDuration(380).start();

            repeatTicker = new Runnable() {
                @Override
                public void run() {
                    if (listener != null) listener.onKeyRepeatTick(item);
                    repeatHandler.postDelayed(this, 120);
                }
            };
            repeatStarter = () -> {
                repeatFired = true;
                repeatHandler.post(repeatTicker);
            };
            repeatHandler.postDelayed(repeatStarter, 380);
        }

        private void stopHoldRepeat() {
            if (repeatStarter != null) repeatHandler.removeCallbacks(repeatStarter);
            if (repeatTicker != null) repeatHandler.removeCallbacks(repeatTicker);
            viewHoldBar.animate().cancel();
            viewHoldBar.setAlpha(0f);
            viewHoldBar.setScaleX(0f);
        }

        private int resolveIcon(@NonNull String id) {
            String lower = id.toLowerCase();
            if (lower.contains("mic")) return R.drawable.ic_mic;
            if (lower.contains("vol") || lower.contains("mute")) return R.drawable.ic_volume;
            return R.drawable.ic_key_default;
        }
    }
}
