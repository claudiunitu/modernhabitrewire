package com.example.modernhabitrewire;

import android.content.Context;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.View;
import android.view.WindowManager;

public class SystemPhysicsController {

    private final Context context;
    private final WindowManager windowManager;
    private View grayscaleOverlay;

    public SystemPhysicsController(Context context) {
        this.context = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    /**
     * Applies or removes a grayscale overlay over the entire screen.
     * Requires ACTION_MANAGE_OVERLAY_PERMISSION (Display over other apps).
     */
    public synchronized void setGrayscaleEnabled(boolean enabled) {
        if (enabled) {
            if (grayscaleOverlay == null) {
                addOverlay();
            }
        } else {
            removeOverlay();
        }
    }

    private void addOverlay() {
        try {
            grayscaleOverlay = new View(context);
            grayscaleOverlay.setBackgroundColor(Color.TRANSPARENT);

            // Create a Grayscale Paint
            Paint paint = new Paint();
            ColorMatrix matrix = new ColorMatrix();
            matrix.setSaturation(0); // 0 = Grayscale
            paint.setColorFilter(new ColorMatrixColorFilter(matrix));
            
            // Apply the filter to the view layer
            grayscaleOverlay.setLayerType(View.LAYER_TYPE_HARDWARE, paint);

            int layoutParamType;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                layoutParamType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
            } else {
                layoutParamType = WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
            }

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    layoutParamType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
            );

            windowManager.addView(grayscaleOverlay, params);
        } catch (Exception e) {
            e.printStackTrace();
            grayscaleOverlay = null;
        }
    }

    private void removeOverlay() {
        if (grayscaleOverlay != null) {
            try {
                windowManager.removeView(grayscaleOverlay);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                grayscaleOverlay = null;
            }
        }
    }
}
