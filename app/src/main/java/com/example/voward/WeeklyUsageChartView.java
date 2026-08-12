package com.example.voward;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** Seven-bar chart of measured restricted-use time; no inferred or synthetic values. */
public class WeeklyUsageChartView extends View {
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final long[] values = new long[7];
    private final String[] labels = new String[]{"M", "T", "W", "T", "F", "S", "S"};

    public WeeklyUsageChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        TypedArray colors = context.obtainStyledAttributes(new int[]{
                androidx.appcompat.R.attr.colorPrimary,
                com.google.android.material.R.attr.colorOnSurfaceVariant});
        bar.setColor(colors.getColor(0, 0xff006b5b));
        label.setColor(colors.getColor(1, 0xff3f4945));
        colors.recycle();
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(12 * density);
    }

    public void setUsageSeconds(long[] seconds) {
        for (int i = 0; i < values.length; i++) {
            values[i] = seconds != null && i < seconds.length ? Math.max(0, seconds[i]) : 0;
        }
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float labelSpace = 24 * density;
        float chartHeight = Math.max(1, getHeight() - labelSpace);
        float slot = getWidth() / 7f;
        float width = Math.min(18 * density, slot * 0.55f);
        long max = 1;
        for (long value : values) max = Math.max(max, value);
        for (int i = 0; i < 7; i++) {
            float x = slot * (i + 0.5f);
            float height = chartHeight * values[i] / max;
            if (values[i] > 0) height = Math.max(height, 3 * density);
            canvas.drawRoundRect(x - width / 2, chartHeight - height, x + width / 2,
                    chartHeight, width / 2, width / 2, bar);
            canvas.drawText(labels[i], x, getHeight() - 4 * density, label);
        }
    }
}
