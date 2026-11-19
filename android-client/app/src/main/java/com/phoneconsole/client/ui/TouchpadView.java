package com.phoneconsole.client.ui;

import android.content.Context;
import android.graphics.Canvas;
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
        if (touching) {
            glowPaint.setShader(new RadialGradient(
                    touchX,
                    touchY,
                    glowRadiusPx,
                    ContextCompat.getColor(getContext(), R.color.touchpad_glow),
                    0x003B82F6,
                    Shader.TileMode.CLAMP
            ));
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
                lastX = event.getX();
                lastY = event.getY();
                touchX = lastX;
                touchY = lastY;
                accumDx = 0f;
                accumDy = 0f;
                totalMoveDist = 0f;
                downTimeMs = System.currentTimeMillis();
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
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

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
                touching = false;
                long elapsed = System.currentTimeMillis() - downTimeMs;
                if (event.getActionMasked() == MotionEvent.ACTION_UP
                        && totalMoveDist < 14f
                        && elapsed < 240L) {
                    readoutText = "左键点击";
                    performClick();
                    if (listener != null) {
                        listener.onTapClick();
                    }
                } else {
                    readoutText = "拖动移动光标 · 轻点左键";
                }
                invalidate();
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
