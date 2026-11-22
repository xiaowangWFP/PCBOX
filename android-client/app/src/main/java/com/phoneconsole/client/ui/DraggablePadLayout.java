package com.phoneconsole.client.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

/**
 * 悬浮按键容器：整体可拖动，内部子按钮仍可正常点击。
 * <p>
 * 手指按下后移动超过 touchSlop 才进入拖动，子 View 会收到 ACTION_CANCEL，
 * 因此不会出现「拖完又触发一次点击」。
 */
public class DraggablePadLayout extends LinearLayout {

    public interface OnPositionChangedListener {
        void onPositionChanged(float xRatio, float yRatio);
    }

    private final int touchSlop;
    private float downX;
    private float downY;
    private float startTranslationX;
    private float startTranslationY;
    private boolean dragging;
    private boolean positionInitialized;

    @Nullable
    private OnPositionChangedListener positionChangedListener;

    public DraggablePadLayout(Context context) {
        this(context, null);
    }

    public DraggablePadLayout(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DraggablePadLayout(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClipChildren(false);
    }

    public void setOnPositionChangedListener(@Nullable OnPositionChangedListener listener) {
        this.positionChangedListener = listener;
    }

    public boolean isPositionInitialized() {
        return positionInitialized;
    }

    public void markPositionInitialized() {
        positionInitialized = true;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getRawX();
                downY = ev.getRawY();
                startTranslationX = getTranslationX();
                startTranslationY = getTranslationY();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!dragging
                        && (Math.abs(ev.getRawX() - downX) > touchSlop
                        || Math.abs(ev.getRawY() - downY) > touchSlop)) {
                    dragging = true;
                    // 拿到事件流，避免被子按钮继续消费
                    ViewParent parent = getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    }
                    return true;
                }
                break;
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    moveTo(startTranslationX + (event.getRawX() - downX),
                            startTranslationY + (event.getRawY() - downY));
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    notifyPositionChanged();
                }
                return true;
            default:
                return true;
        }
    }

    /** 位移范围：[-left, parentWidth - right]，保证整组始终留在父容器内 */
    private float[] translationBounds() {
        View parent = getParent() instanceof View ? (View) getParent() : null;
        if (parent == null) {
            return new float[]{0f, 0f, 0f, 0f};
        }
        float minX = -getLeft();
        float maxX = parent.getWidth() - getRight();
        float minY = -getTop();
        float maxY = parent.getHeight() - getBottom();
        return new float[]{minX, Math.max(minX, maxX), minY, Math.max(minY, maxY)};
    }

    private void moveTo(float translationX, float translationY) {
        float[] bounds = translationBounds();
        setTranslationX(Math.max(bounds[0], Math.min(bounds[1], translationX)));
        setTranslationY(Math.max(bounds[2], Math.min(bounds[3], translationY)));
    }

    /** 按可移动范围的比例恢复位置（0~1），用于旋转或尺寸变化后保持相对位置 */
    public void applyPositionRatio(float xRatio, float yRatio) {
        post(() -> {
            float[] bounds = translationBounds();
            float ratioX = Math.max(0f, Math.min(1f, xRatio));
            float ratioY = Math.max(0f, Math.min(1f, yRatio));
            setTranslationX(bounds[0] + ratioX * (bounds[1] - bounds[0]));
            setTranslationY(bounds[2] + ratioY * (bounds[3] - bounds[2]));
            positionInitialized = true;
        });
    }

    /** 当前位移占可移动范围的比例，范围未就绪时按 0.5 处理 */
    public float currentXRatio() {
        float[] bounds = translationBounds();
        float span = bounds[1] - bounds[0];
        if (span <= 0) {
            return 0.5f;
        }
        return Math.max(0f, Math.min(1f, (getTranslationX() - bounds[0]) / span));
    }

    public float currentYRatio() {
        float[] bounds = translationBounds();
        float span = bounds[3] - bounds[2];
        if (span <= 0) {
            return 0.5f;
        }
        return Math.max(0f, Math.min(1f, (getTranslationY() - bounds[2]) / span));
    }

    private void notifyPositionChanged() {
        if (positionChangedListener != null) {
            positionChangedListener.onPositionChanged(currentXRatio(), currentYRatio());
        }
    }
}
