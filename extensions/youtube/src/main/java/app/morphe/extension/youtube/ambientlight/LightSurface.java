/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4576
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.ambientlight;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import androidx.annotation.NonNull;

/**
 * A surface underneath the window that shows the light. A light frame only replaces this
 * buffer, so the app window is not redrawn.
 */
@SuppressLint("ViewConstructor")
final class LightSurface extends SurfaceView implements SurfaceHolder.Callback {
    private final Runnable onReady;
    private volatile boolean ready;

    LightSurface(Context context, Runnable onReady) {
        super(context);
        this.onReady = onReady;
        getHolder().setFormat(PixelFormat.RGBX_8888);
        getHolder().addCallback(this);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /**
     * Draws the part of the light that this surface covers. The GPU scales the small light up
     * and dithers it, which is what keeps a dark gradient free of steps.
     * Must be called from the light's worker thread. The holder lock keeps the main thread
     * from destroying the surface while it is drawn.
     *
     * @return False if there is no buffer yet.
     */
    boolean draw(Paint lightPaint, int left, int top, float scaleX, float scaleY,
                 int gridWidth, int gridHeight, int pageColor, int coverColor) {
        if (!ready) return false;

        SurfaceHolder holder = getHolder();
        Canvas canvas = holder.lockHardwareCanvas();
        if (canvas == null) return false;
        try {
            canvas.drawColor(pageColor);
            canvas.translate(-left, -top);
            canvas.scale(scaleX, scaleY);
            canvas.drawRect(0, 0, gridWidth, gridHeight, lightPaint);
            if (coverColor >>> 24 != 0) canvas.drawColor(coverColor);
            return true;
        } finally {
            holder.unlockCanvasAndPost(canvas);
        }
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        ready = true;
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        onReady.run();
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        ready = false;
    }
}
