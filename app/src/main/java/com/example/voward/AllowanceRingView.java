package com.example.voward;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** Small, dependency-free allowance indicator for the Today screen. */
public class AllowanceRingView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progress = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private float fraction;

    public AllowanceRingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        track.setStyle(Paint.Style.STROKE);
        progress.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(10 * density);
        progress.setStrokeWidth(10 * density);
        track.setStrokeCap(Paint.Cap.ROUND);
        progress.setStrokeCap(Paint.Cap.ROUND);
        TypedArray colors = context.obtainStyledAttributes(new int[]{
                com.google.android.material.R.attr.colorOutlineVariant,
                androidx.appcompat.R.attr.colorPrimary});
        track.setColor(colors.getColor(0, 0x55777777));
        progress.setColor(colors.getColor(1, 0xff006b5b));
        colors.recycle();
    }

    public void setFraction(float value) {
        fraction = Math.max(0f, Math.min(1f, value));
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float inset = track.getStrokeWidth() / 2f;
        bounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
        canvas.drawArc(bounds, -90, 360, false, track);
        canvas.drawArc(bounds, -90, 360 * fraction, false, progress);
    }
}
