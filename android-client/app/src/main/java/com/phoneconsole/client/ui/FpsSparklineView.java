package com.phoneconsole.client.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 实时帧率走势折线图：保留最近若干次采集点，随主题强调色绘制平滑曲线与浅色面积填充。
 */
public class FpsSparklineView extends View {

    private static final int MAX_SAMPLES = 24;

    private final List<Float> samples = new ArrayList<>();
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path fillPath = new Path();

    private int accentColor = 0xFF38BDF8;
    private float maxScale = 200f;

    public FpsSparklineView(@NonNull Context context) {
        super(context);
        init(context);
    }

    public FpsSparklineView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public FpsSparklineView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(@NonNull Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.2f * density);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setColor(accentColor);
        fillPaint.setStyle(Paint.Style.FILL);
    }

    public void applyTheme(int accentColor) {
        this.accentColor = accentColor;
        linePaint.setColor(accentColor);
        updateFillShader(getHeight());
        invalidate();
    }

    public void pushSample(float fps, float displayHz) {
        if (displayHz > 0f) {
            maxScale = Math.max(60f, displayHz * 1.05f);
        }
        samples.add(Math.max(0f, fps));
        if (samples.size() > MAX_SAMPLES) {
            samples.remove(0);
        }
        if (samples.size() > 1 && fps > maxScale) {
            maxScale = fps * 1.1f;
        }
        invalidate();
    }

    public void clearSamples() {
        samples.clear();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateFillShader(h);
    }

    private void updateFillShader(int height) {
        if (height <= 0) {
            return;
        }
        int top = Color.argb(48, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor));
        int bottom = Color.argb(0, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor));
        fillPaint.setShader(new LinearGradient(0f, 0f, 0f, height, top, bottom, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 4 || h <= 4) {
            return;
        }
        float padV = 6f * getResources().getDisplayMetrics().density;
        float usableH = Math.max(4f, h - padV * 2f);

        linePath.reset();
        fillPath.reset();

        if (samples.isEmpty()) {
            // 未连接或暂无采样时绘制柔和静态基线
            float y = h - padV - usableH * 0.2f;
            linePath.moveTo(0f, y);
            linePath.lineTo(w, y);
            linePaint.setAlpha(90);
            canvas.drawPath(linePath, linePaint);
            linePaint.setAlpha(255);
            return;
        }

        int count = samples.size();
        float[] xs = new float[count];
        float[] ys = new float[count];
        float stepX = count == 1 ? w : (float) w / (count - 1);
        for (int i = 0; i < count; i++) {
            float ratio = Math.max(0f, Math.min(1f, samples.get(i) / Math.max(30f, maxScale)));
            xs[i] = count == 1 ? w : i * stepX;
            ys[i] = h - padV - ratio * usableH;
        }

        if (count == 1) {
            linePath.moveTo(0f, ys[0]);
            linePath.lineTo(w, ys[0]);
            fillPath.moveTo(0f, ys[0]);
            fillPath.lineTo(w, ys[0]);
            fillPath.lineTo(w, h);
            fillPath.lineTo(0f, h);
            fillPath.close();
        } else {
            linePath.moveTo(xs[0], ys[0]);
            fillPath.moveTo(xs[0], ys[0]);
            for (int i = 1; i < count; i++) {
                float cx = (xs[i - 1] + xs[i]) * 0.5f;
                linePath.cubicTo(cx, ys[i - 1], cx, ys[i], xs[i], ys[i]);
                fillPath.cubicTo(cx, ys[i - 1], cx, ys[i], xs[i], ys[i]);
            }
            fillPath.lineTo(xs[count - 1], h);
            fillPath.lineTo(xs[0], h);
            fillPath.close();
        }

        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(linePath, linePaint);
    }
}

