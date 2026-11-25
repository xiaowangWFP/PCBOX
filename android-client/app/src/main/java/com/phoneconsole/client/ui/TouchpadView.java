package com.phoneconsole.client.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.phoneconsole.client.R;

/**
 * 原生安卓无线触控板自定义控件：
 * - Tactile Obsidian 深色点阵底纹 + 触控蓝光跟随光晕
 * - 支持灵敏度倍率、微抖动死区过滤、单指滑动与轻点左键
 * - 拖动期间自动阻止外层 NestedScrollView 抢占触摸事件
 */
public class TouchpadView extends View {

    public interface OnTouchpadEventListener {
        void onMove(int dx, int dy);

        void onTapClick();
    }

    /** 多指手势：双指滚动、三/四指滑动 */
    public interface OnGestureListener {
        /** 正数向上滚，负数向下滚 */
        void onTwoFingerScroll(int deltaY);

        /** fingers 为 3 或 4；direction：0 上、1 下、2 左、3 右 */
        void onMultiFingerSwipe(int fingers, int direction);
    }

    public static final int SWIPE_UP = 0;
    public static final int SWIPE_DOWN = 1;
    public static final int SWIPE_LEFT = 2;
    public static final int SWIPE_RIGHT = 3;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();

    private float cornerRadiusPx;
    private float dotSpacingPx;
    private float glowRadiusPx;
    private RadialGradient glowShader;
    private final Matrix glowMatrix = new Matrix();

    private float sensitivity = 1.2f;
    private float lastX;
    private float lastY;
    private float touchX;
    private float touchY;
    private float accumDx;
    private float accumDy;
    private float totalMoveDist;
    private long downTimeMs;
    private boolean touching;
    private String readoutText = "拖动移动光标 · 轻点左键";

    @Nullable
    private OnTouchpadEventListener listener;
    @Nullable
    private OnGestureListener gestureListener;

    private boolean gestureEnabled = true;
    private boolean multiTouch;
    private float gestureStartX;
    private float gestureStartY;
    private float lastMultiX;
    private float lastMultiY;
    private float scrollAccum;
    private boolean swipeTriggered;
    /** 三指被系统吃掉一指后，用剩余两指继续判定三指手势 */
    private boolean threeFingerDropped;
    private float threeDropX;
    private float threeDropY;

    public TouchpadView(@NonNull Context context) {
        super(context);
        init(context);
    }

    public TouchpadView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public TouchpadView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(@NonNull Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        cornerRadiusPx = 12f * density;
        dotSpacingPx = 18f * density;
        glowRadiusPx = 42f * density;
        glowShader = new RadialGradient(
                0f, 0f, glowRadiusPx,
                ContextCompat.getColor(context, R.color.touchpad_glow),
                0x003B82F6,
                Shader.TileMode.CLAMP);
        glowPaint.setShader(glowShader);

        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(ContextCompat.getColor(context, R.color.bg_obsidian));

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1f * density);
        borderPaint.setColor(ContextCompat.getColor(context, R.color.divider_strong));

        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(ContextCompat.getColor(context, R.color.touchpad_dot));

        titlePaint.setColor(ContextCompat.getColor(context, R.color.text_secondary));
        titlePaint.setTextSize(13f * density);
        titlePaint.setFakeBoldText(true);
        titlePaint.setTextAlign(Paint.Align.CENTER);

        subPaint.setColor(ContextCompat.getColor(context, R.color.text_muted));
        subPaint.setTextSize(11f * density);
        subPaint.setTextAlign(Paint.Align.CENTER);

        setContentDescription("无线触控板，拖动移动鼠标指针，轻点触发左键");
    }

    public void setSensitivity(float sensitivity) {
        this.sensitivity = Math.max(0.4f, Math.min(3.0f, sensitivity));
    }

    public void setOnTouchpadEventListener(@Nullable OnTouchpadEventListener listener) {
        this.listener = listener;
    }

    public void setGestureEnabled(boolean enabled) {
        this.gestureEnabled = enabled;
    }

    public void setOnGestureListener(@Nullable OnGestureListener listener) {
        this.gestureListener = listener;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float inset = borderPaint.getStrokeWidth();
        bounds.set(inset, inset, w - inset, h - inset);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRoundRect(bounds, cornerRadiusPx, cornerRadiusPx, bgPaint);

        // 绘制微弱点阵网格
        float start = dotSpacingPx * 0.75f;
        for (float y = start; y < getHeight() - start * 0.5f; y += dotSpacingPx) {
            for (float x = start; x < getWidth() - start * 0.5f; x += dotSpacingPx) {
                canvas.drawCircle(x, y, 1.3f, dotPaint);
            }
        }

        // 触控光晕
        if (touching && glowShader != null) {
            glowMatrix.setTranslate(touchX, touchY);
            glowShader.setLocalMatrix(glowMatrix);
            canvas.drawCircle(touchX, touchY, glowRadiusPx, glowPaint);
        }

        // 中心引导文案
        float cx = getWidth() * 0.5f;
        float cy = getHeight() * 0.5f;
        canvas.drawText("触控移动区域", cx, cy - 6f, titlePaint);
        canvas.drawText(readoutText, cx, cy + 28f, subPaint);

        borderPaint.setColor(ContextCompat.getColor(
                getContext(),
                touching ? R.color.accent_border : R.color.divider_strong
        ));
        canvas.drawRoundRect(bounds, cornerRadiusPx, cornerRadiusPx, borderPaint);
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        ViewParent parent = getParent();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                touching = true;
                multiTouch = false;
                swipeTriggered = false;
                scrollAccum = 0f;
                lastX = event.getX();
                lastY = event.getY();
                gestureStartX = lastX;
                gestureStartY = lastY;
                lastMultiX = lastX;
                lastMultiY = lastY;
                touchX = lastX;
                touchY = lastY;
                accumDx = 0f;
                accumDy = 0f;
                totalMoveDist = 0f;
                downTimeMs = System.currentTimeMillis();
                invalidate();
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                multiTouch = true;
                swipeTriggered = false;
                threeFingerDropped = false;
                scrollAccum = 0f;
                gestureStartX = averageX(event);
                gestureStartY = averageY(event);
                lastMultiX = gestureStartX;
                lastMultiY = gestureStartY;
                touchX = gestureStartX;
                touchY = gestureStartY;
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                int count = event.getPointerCount();
                if (count >= 2) {
                    if (!gestureEnabled) {
                        // 关闭手势时不移动光标，避免多指拖动导致指针乱跳
                        return true;
                    }
                    // 兼容多指同时落下时 POINTER_DOWN 丢失的情况：在 MOVE 中补做初始化
                    if (!multiTouch) {
                        multiTouch = true;
                        gestureStartX = averageX(event);
                        gestureStartY = averageY(event);
                        lastMultiX = gestureStartX;
                        lastMultiY = gestureStartY;
                        swipeTriggered = false;
                        scrollAccum = 0f;
                    }
                    float avgX = averageX(event);
                    float avgY = averageY(event);
                    if (count >= 3) {
                        if (!swipeTriggered) {
                            float dx = avgX - gestureStartX;
                            float dy = avgY - gestureStartY;
                            float threshold = dp(60);
                            if (Math.abs(dx) > threshold || Math.abs(dy) > threshold) {
                                int direction;
                                if (Math.abs(dx) > Math.abs(dy) * 1.2f) {
                                    direction = dx > 0 ? SWIPE_RIGHT : SWIPE_LEFT;
                                } else {
                                    direction = dy > 0 ? SWIPE_DOWN : SWIPE_UP;
                                }
                                swipeTriggered = true;
                                if (gestureListener != null) {
                                    gestureListener.onMultiFingerSwipe(count, direction);
                                }
                            }
                        }
                    } else if (threeFingerDropped && !swipeTriggered) {
                        float dx = avgX - threeDropX;
                        float dy = avgY - threeDropY;
                        float threshold = dp(60);
                        if (Math.abs(dx) > threshold || Math.abs(dy) > threshold) {
                            int direction;
                            if (Math.abs(dx) > Math.abs(dy) * 1.2f) {
                                direction = dx > 0 ? SWIPE_RIGHT : SWIPE_LEFT;
                            } else {
                                direction = dy > 0 ? SWIPE_DOWN : SWIPE_UP;
                            }
                            swipeTriggered = true;
                            threeFingerDropped = false;
                            if (gestureListener != null) {
                                gestureListener.onMultiFingerSwipe(3, direction);
                            }
                        }
                    } else {
                        float dy = avgY - lastMultiY;
                        scrollAccum += dy;
                        float threshold = dp(24);
                        int steps = (int) (scrollAccum / threshold);
                        if (steps != 0) {
                            scrollAccum -= steps * threshold;
                            int delta = Math.max(-3, Math.min(3, -steps));
                            if (gestureListener != null) {
                                gestureListener.onTwoFingerScroll(delta);
                            }
                        }
                    }
                    lastMultiX = avgX;
                    lastMultiY = avgY;
                    touchX = avgX;
                    touchY = avgY;
                    invalidate();
                    return true;
                }

                // 单指移动
                float rawDx = event.getX() - lastX;
                float rawDy = event.getY() - lastY;
                lastX = event.getX();
                lastY = event.getY();
                touchX = lastX;
                touchY = lastY;
                totalMoveDist += Math.hypot(rawDx, rawDy);

                // 微抖动死区过滤 + 灵敏度缩放
                if (Math.abs(rawDx) >= 0.4f) accumDx += rawDx * sensitivity;
                if (Math.abs(rawDy) >= 0.4f) accumDy += rawDy * sensitivity;

                int emitX = Math.round(accumDx);
                int emitY = Math.round(accumDy);
                if (emitX != 0 || emitY != 0) {
                    accumDx -= emitX;
                    accumDy -= emitY;
                    readoutText = "Δ " + emitX + ", " + emitY;
                    if (listener != null) {
                        listener.onMove(emitX, emitY);
                    }
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                // 剩余手指重新作为基准，避免跳变
                int index = event.getActionIndex();
                float sumX = 0f;
                float sumY = 0f;
                int remain = 0;
                for (int i = 0; i < event.getPointerCount(); i++) {
                    if (i == index) {
                        continue;
                    }
                    sumX += event.getX(i);
                    sumY += event.getY(i);
                    remain++;
                }
                if (remain > 0) {
                    lastMultiX = sumX / remain;
                    lastMultiY = sumY / remain;
                    lastX = lastMultiX;
                    lastY = lastMultiY;
                    gestureStartX = lastMultiX;
                    gestureStartY = lastMultiY;
                    touchX = lastMultiX;
                    touchY = lastMultiY;
                }
                if (remain == 2 && !swipeTriggered) {
                    // 三指中有一指被系统手势吃掉：用剩余两指继续判定三指滑动
                    threeFingerDropped = true;
                    threeDropX = lastMultiX;
                    threeDropY = lastMultiY;
                }
                scrollAccum = 0f;
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
                touching = false;
                long elapsed = System.currentTimeMillis() - downTimeMs;
                if (event.getActionMasked() == MotionEvent.ACTION_UP
                        && !multiTouch
                        && totalMoveDist < dp(8)
                        && elapsed < 240L) {
                    readoutText = "左键点击";
                    performClick();
                    if (listener != null) {
                        listener.onTapClick();
                    }
                } else {
                    readoutText = "拖动移动光标 · 轻点左键";
                }
                multiTouch = false;
                threeFingerDropped = false;
                invalidate();
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    private float averageX(MotionEvent event) {
        float sum = 0f;
        for (int i = 0; i < event.getPointerCount(); i++) {
            sum += event.getX(i);
        }
        return sum / Math.max(1, event.getPointerCount());
    }

    private float averageY(MotionEvent event) {
        float sum = 0f;
        for (int i = 0; i < event.getPointerCount(); i++) {
            sum += event.getY(i);
        }
        return sum / Math.max(1, event.getPointerCount());
    }

    private float dp(int value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
