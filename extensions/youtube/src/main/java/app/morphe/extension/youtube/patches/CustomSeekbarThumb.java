/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class CustomSeekbarThumb {

    private static WeakReference<View> seekbarViewRef = new WeakReference<>(null);
    private static volatile Drawable currentDrawable = null;
    private static volatile boolean isLoaded = false;
    private static volatile String lastLoadedPath = "";

    private CustomSeekbarThumb() {}

    /**
     * Injection point at the start of Seekbar onDraw(Canvas).
     */
    public static void setSeekbarView(View view) {
        if (view == null) return;
        View prev = seekbarViewRef.get();
        if (prev != view) {
            seekbarViewRef = new WeakReference<>(view);
            attachDrawableCallback();
        }
    }

    @Nullable
    public static View getSeekbarView() {
        return seekbarViewRef.get();
    }

    /**
     * Injection point before drawCircle call.
     * Returns 0.0f to suppress the original circle drawing,
     * or original radius to let YouTube draw the circle.
     */
    public static float drawThumb(Canvas canvas, float cx, float cy, float radius, Paint paint) {
        if (radius <= 0.001f || !Settings.CUSTOM_SEEKBAR_THUMB.get()) {
            return radius;
        }

        ensureLoaded();

        Drawable drawable = currentDrawable;
        if (drawable == null) {
            return radius;
        }

        try {
            int intrinsicWidth = drawable.getIntrinsicWidth();
            int intrinsicHeight = drawable.getIntrinsicHeight();
            if (intrinsicWidth <= 0 || intrinsicHeight <= 0) {
                intrinsicWidth = Math.round(radius * 2.5f);
                intrinsicHeight = Math.round(radius * 2.5f);
            }

            String scaleMode = Settings.CUSTOM_SEEKBAR_THUMB_SCALE_TYPE.get();
            float targetWidth;
            float targetHeight;

            if ("original".equals(scaleMode)) {
                float maxDim = Dim.dp(64);
                float widthRatio = intrinsicWidth > maxDim ? (maxDim / intrinsicWidth) : 1.0f;
                float heightRatio = intrinsicHeight > maxDim ? (maxDim / intrinsicHeight) : 1.0f;
                float ratio = Math.min(widthRatio, heightRatio);
                targetWidth = intrinsicWidth * ratio;
                targetHeight = intrinsicHeight * ratio;
            } else {
                targetHeight = Math.max(radius * 2.5f, Dim.dp(24));
                float aspect = (float) intrinsicWidth / (float) intrinsicHeight;
                targetWidth = targetHeight * aspect;
            }

            float left = cx - (targetWidth / 2.0f);
            float top = cy - (targetHeight / 2.0f);
            float right = cx + (targetWidth / 2.0f);
            float bottom = cy + (targetHeight / 2.0f);

            drawable.setBounds((int) left, (int) top, (int) right, (int) bottom);
            drawable.draw(canvas);

            return 0.0f;
        } catch (Exception ex) {
            Logger.printException(() -> "drawThumb error", ex);
            return radius;
        }
    }

    public static synchronized void reload() {
        stopAnimation();
        currentDrawable = null;
        isLoaded = false;
        lastLoadedPath = "";
        ensureLoaded();

        View view = seekbarViewRef.get();
        if (view != null) {
            view.postInvalidate();
        }
    }

    private static synchronized void ensureLoaded() {
        String path = Settings.CUSTOM_SEEKBAR_THUMB_FILE_PATH.get();
        if (isLoaded && path.equals(lastLoadedPath)) {
            return;
        }

        stopAnimation();
        currentDrawable = null;
        lastLoadedPath = path;
        isLoaded = true;

        if (path.isEmpty()) {
            return;
        }

        File file = new File(path);
        if (!file.isFile() || file.length() == 0) {
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.Source source = ImageDecoder.createSource(file);
                currentDrawable = ImageDecoder.decodeDrawable(source, (decoder, info, s) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            } else {
                Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
                if (bitmap != null) {
                    currentDrawable = new BitmapDrawable(null, bitmap);
                }
            }

            attachDrawableCallback();

            if (currentDrawable instanceof Animatable animatable) {
                animatable.start();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to load custom seekbar thumb from " + path, ex);
            try {
                Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
                if (bitmap != null) {
                    currentDrawable = new BitmapDrawable(null, bitmap);
                }
            } catch (Exception ignored) {}
        }
    }

    private static void attachDrawableCallback() {
        Drawable drawable = currentDrawable;
        if (drawable == null) return;

        drawable.setCallback(new Drawable.Callback() {
            @Override
            public void invalidateDrawable(@NonNull Drawable who) {
                View view = seekbarViewRef.get();
                if (view != null) {
                    view.postInvalidate();
                }
            }

            @Override
            public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
                View view = seekbarViewRef.get();
                if (view != null) {
                    view.postDelayed(what, Math.max(0, when - SystemClock.uptimeMillis()));
                }
            }

            @Override
            public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {
                View view = seekbarViewRef.get();
                if (view != null) {
                    view.removeCallbacks(what);
                }
            }
        });
    }

    private static void stopAnimation() {
        Drawable drawable = currentDrawable;
        if (drawable instanceof Animatable animatable) {
            try {
                animatable.stop();
            } catch (Exception ignored) {}
        }
    }
}
