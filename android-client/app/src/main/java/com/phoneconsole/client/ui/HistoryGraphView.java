package com.phoneconsole.client.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * 轻量历史曲线：固定 60 个采样点的环形缓冲，随遥测每秒推进。
 */
public class HistoryGraphView extends View {

    private static final int CAPACITY = 60;

    private final float[] values = new float[CAPACITY];
    private int start;
    private int size;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path fillPath = new Path();

    public HistoryGraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        gridPaint.setColor(0xFF1E293B);
        gridPaint.setStrokeWidth(1f);
        gridPaint.setStyle(Paint.Style.STROKE);

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(3f);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);

        fillPaint.setStyle(Paint.Style.FILL);
        setLineColor(0xFF38BDF8);
    }

    public void setLineColor(int color) {
        linePaint.setColor(color);
        fillPaint.setColor(color);
        fillPaint.setAlpha(38);
        invalidate();
    }

    /** 追加一个 0~100 的采样点，负值视为无效并记为 0 */
    public void addPoint(float value) {
        float v = Math.max(0, Math.min(100, value < 0 ? 0 : value));
        if (size < CAPACITY) {
            values[(start + size) % CAPACITY] = v;
            size++;
        } else {
            values[start] = v;
            start = (start + 1) % CAPACITY;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        for (int i = 1; i < 4; i++) {
            float y = h * i / 4f;
            canvas.drawLine(0, y, w, y, gridPaint);
        }
        if (size < 2) {
            return;
        }
        linePath.reset();
        fillPath.reset();
        float stepX = w / (CAPACITY - 1f);
        for (int i = 0; i < size; i++) {
            float value = values[(start + i) % CAPACITY];
            float x = i * stepX;
            float y = h - value / 100f * (h - 6f) - 3f;
            if (i == 0) {
                linePath.moveTo(x, y);
                fillPath.moveTo(x, h);
                fillPath.lineTo(x, y);
            } else {
                linePath.lineTo(x, y);
                fillPath.lineTo(x, y);
            }
        }
        fillPath.lineTo((size - 1) * stepX, h);
        fillPath.close();
        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(linePath, linePaint);
    }
}
