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
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.os.Handler;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * A surface underneath the window that shows the light. A light frame only replaces this
 * buffer, so the app window is not redrawn. It is as large as the window and see-through
 * wherever the window shows no light, as where the video is, so one buffer holds all of the
 * light and a new light is one draw.
 * <p>
 * Public so other features that look for the video surface can leave it out.
 */
@SuppressLint("ViewConstructor")
public final class LightSurface extends SurfaceView implements SurfaceHolder.Callback {
    private final Runnable onReady;
    private final Handler worker;
    private volatile boolean ready;
    /** If the frame on the surface shows no light. A new surface has no frame. */
    private volatile boolean clear = true;
    /** Counts the surfaces of this view. */
    private volatile int generation;
    /** Draws the light, where the device can. Used on the worker thread. */
    @Nullable
    private final LightGl renderer = LightGl.isSupported() ? new LightGl() : null;
    /**
     * If a canvas draws this view, as the renderer could not. A surface takes one of the two
     * for as long as it lives, and a canvas stays with the surfaces of its view. Used on the
     * worker thread.
     */
    private boolean drawnByCanvas;
    /** Held while the renderer draws, so that the surface is not destroyed under it. */
    private final Object rendererLock = new Object();

    /**
     * @param worker The light's worker thread, which draws the surface.
     */
    LightSurface(Context context, Runnable onReady, Handler worker) {
        super(context);
        this.onReady = onReady;
        this.worker = worker;
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        getHolder().addCallback(this);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /**
     * Draws the light in the regions of the window that show it, and leaves the rest
     * see-through. The GPU scales the small light up and dithers it, which is what keeps a
     * dark gradient free of steps.
     * Must be called from the light's worker thread.
     *
     * @param lightPaint   The light as a shader, and its strength as the alpha, for a canvas.
     * @param light        The light, 4 half floats for each of its pixels.
     * @param lightVersion Changes when the light does.
     * @param pageColor    What is under the light of a page.
     * @param barColor     What is under the light of the player.
     * @param coverColor   What lies over the light of a page, to keep text readable.
     * @return False if there is no buffer yet.
     */
    boolean draw(Paint lightPaint, short[] light, int lightVersion, List<AmbientLightController.Region> regions,
                 float scaleX, float scaleY, int gridWidth, int gridHeight, int pageColor, int barColor,
                 int coverColor) {
        if (!ready) return false;

        if (renderer != null && !drawnByCanvas) {
            // Not the light of a window of another shape: the next one fits.
            if (light.length != gridWidth * gridHeight * 4) return false;
            synchronized (rendererLock) {
                if (!ready) return false;
                if (renderer.draw(getHolder().getSurface(), generation, getWidth(), getHeight(), light, lightVersion,
                        gridWidth, gridHeight, regions, lightPaint.getAlpha() / 255f, pageColor, barColor, coverColor)) {
                    clear = regions.isEmpty();
                    return true;
                }
            }
            drawnByCanvas = true;
        }

        // The holder lock keeps the main thread from destroying the surface while it is drawn.
        SurfaceHolder holder = getHolder();
        Canvas canvas = holder.lockHardwareCanvas();
        if (canvas == null) return false;
        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            clear = regions.isEmpty();
            for (AmbientLightController.Region region : regions) {
                final int state = canvas.save();
                canvas.clipRect(region.left(), region.top(), region.right(), region.bottom());
                canvas.drawColor(region.onPage() ? pageColor : barColor);
                canvas.scale(scaleX, scaleY);
                canvas.drawRect(0, 0, gridWidth, gridHeight, lightPaint);
                if (region.onPage() && coverColor >>> 24 != 0) canvas.drawColor(coverColor);
                canvas.restoreToCount(state);
            }
            return true;
        } finally {
            holder.unlockCanvasAndPost(canvas);
        }
    }

    /**
     * Takes the light off the surface. Must be called from the light's worker thread.
     */
    void clear() {
        if (clear || !ready) return;

        if (renderer != null && !drawnByCanvas) {
            synchronized (rendererLock) {
                if (!ready) return;
                if (renderer.clear(getHolder().getSurface(), generation, getWidth(), getHeight())) {
                    clear = true;
                    return;
                }
            }
            drawnByCanvas = true;
        }

        SurfaceHolder holder = getHolder();
        Canvas canvas = holder.lockHardwareCanvas();
        if (canvas == null) return;
        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            clear = true;
        } finally {
            holder.unlockCanvasAndPost(canvas);
        }
    }

    /**
     * @return If the surface shows no light.
     */
    boolean isClear() {
        return clear || !ready;
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        generation++;
        clear = true;
        ready = true;
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        onReady.run();
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        // Waits for a draw of the renderer that is under way. None starts after this.
        synchronized (rendererLock) {
            ready = false;
        }
        if (renderer != null) worker.post(renderer::releaseSurface);
    }
}
