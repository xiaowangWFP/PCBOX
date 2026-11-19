package com.phoneconsole.client.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 环形进度指示器：外圈为低透明度轨道，内圈按进度绘制彩色圆弧。
 * 用于性能卡片右上角的占用率速览，不依赖任何第三方库。
 */
public class RingProgressView extends View {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private int progress;
    private int ringColor = Color.WHITE;
    private float strokeWidth;

    public RingProgressView(Context context) {
        this(context, null);
    }

    public RingProgressView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RingProgressView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        strokeWidth = 2.2f * getResources().getDisplayMetrics().density;

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(strokeWidth);

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(strokeWidth);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setRingColor(int color) {
        ringColor = color;
        invalidate();
    }

    /** @param value 0~100 的百分比 */
    public void setProgress(int value) {
        int clamped = Math.max(0, Math.min(100, value));
        if (clamped == progress) {
            return;
        }
        progress = clamped;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int defaultSize = Math.round(28f * getResources().getDisplayMetrics().density);
        int width = resolveSize(defaultSize, widthMeasureSpec);
        int height = resolveSize(defaultSize, heightMeasureSpec);
        int size = Math.min(width, height);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float inset = strokeWidth / 2f + 1f;
        oval.set(inset, inset, getWidth() - inset, getHeight() - inset);

        trackPaint.setColor(withAlpha(ringColor, 40));
        canvas.drawArc(oval, 0f, 360f, false, trackPaint);

        arcPaint.setColor(ringColor);
        if (progress <= 0) {
            // 0% 时保留一个小圆点，避免整圈消失显得像没数据
            canvas.drawArc(oval, -90f, 2f, false, arcPaint);
        } else {
            canvas.drawArc(oval, -90f, 360f * progress / 100f, false, arcPaint);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
