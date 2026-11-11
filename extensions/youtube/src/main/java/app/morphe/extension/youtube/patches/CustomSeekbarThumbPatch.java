/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4494
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.util.Size;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.settings.Settings;

/**
 * All methods are called on the main thread.
 */
@SuppressWarnings("unused")
public final class CustomSeekbarThumbPatch {

    public enum ThumbScaleType {
        FIT_SEEKBAR,
        ORIGINAL
    }

    private static final String THUMB_FILE_NAME = "morphe_custom_seekbar_thumb";
    private static final String TEMP_THUMB_FILE_NAME = "morphe_custom_seekbar_thumb.tmp";

    /**
     * Largest width and height of the thumb.
     */
    private static final int MAX_THUMB_SIZE_DP = 64;
    /**
     * The seekbar uses a radius of about 1dp while the player controls are hidden,
     * which is only as thick as the seekbar line, so the thumb is not shown then.
     */
    private static final int MIN_RADIUS_DP = 2;
    /**
     * Gives a thumb height of 24dp while the player controls are shown,
     * and follows the radius as the seekbar animates.
     */
    private static final float FIT_SEEKBAR_HEIGHT_TO_RADIUS = 4;
    /**
     * Thumb height in other seekbars, if the thumb fits the seekbar.
     * Same as the height in the player seekbar while the player controls are shown.
     */
    private static final int FIT_SEEKBAR_HEIGHT_DP = 24;

    /**
     * Must be a strong reference, because a drawable only keeps a weak reference to its callback.
     */
    private static final Drawable.Callback THUMB_CALLBACK = new Drawable.Callback() {
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
                view.postDelayed(what, when - SystemClock.uptimeMillis());
            }
        }

        @Override
        public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {
            View view = seekbarViewRef.get();
            if (view != null) {
                view.removeCallbacks(what);
            }
        }
    };

    private static WeakReference<View> seekbarViewRef = new WeakReference<>(null);

    @Nullable
    private static Drawable thumbDrawable;

    /**
     * File name setting of {@link #thumbDrawable}, or null if the thumb must be loaded.
     */
    @Nullable
    private static String loadedFileName;

    private CustomSeekbarThumbPatch() {}

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * For seekbars of Morphe, such as in the On-the-go panel.
     * Each call loads a new drawable, as a drawable can only be in one view.
     * An animated image is started, and must be stopped when it is not used anymore.
     *
     * @return The custom thumb with the size it has in the player seekbar,
     *         or null if no custom thumb is used.
     */
    @Nullable
    public static Drawable createSeekBarThumb() {
        try {
            if (!isPatchIncluded() || !Settings.CUSTOM_SEEKBAR_THUMB.get()
                    || Settings.CUSTOM_SEEKBAR_THUMB_FILE.get().isEmpty()) {
                return null;
            }

            File file = getThumbFile(Utils.getContext());
            if (!file.isFile()) {
                // The player seekbar shows a message and resets the setting.
                Logger.printDebug(() -> "Custom seekbar thumb file not found");
                return null;
            }

            Drawable thumb = decodeThumb(file);
            if (thumb == null) {
                return null;
            }

            float scale = 1;
            if (Settings.CUSTOM_SEEKBAR_THUMB_SCALE_TYPE.get() == ThumbScaleType.FIT_SEEKBAR) {
                scale = (float) Dim.dp(FIT_SEEKBAR_HEIGHT_DP) / thumb.getIntrinsicHeight();
            }
            scale = Math.min(scale, (float) Dim.dp(MAX_THUMB_SIZE_DP)
                    / Math.max(thumb.getIntrinsicWidth(), thumb.getIntrinsicHeight()));

            ScaledThumbDrawable scaledThumb = new ScaledThumbDrawable(thumb, scale);
            scaledThumb.start();
            return scaledThumb;
        } catch (Exception ex) {
            Logger.printException(() -> "createSeekBarThumb failure", ex);
            return null;
        }
    }

    /**
     * Draws a thumb scaled, using the canvas because AnimatedImageDrawable ignores the drawable bounds.
     */
    private static final class ScaledThumbDrawable extends Drawable implements Drawable.Callback, Animatable {
        private final Drawable thumb;
        private final float scale;

        ScaledThumbDrawable(Drawable thumb, float scale) {
            this.thumb = thumb;
            this.scale = scale;
            thumb.setBounds(0, 0, thumb.getIntrinsicWidth(), thumb.getIntrinsicHeight());
            thumb.setCallback(this);
        }

        @Override
        public int getIntrinsicWidth() {
            return Math.max(1, Math.round(thumb.getIntrinsicWidth() * scale));
        }

        @Override
        public int getIntrinsicHeight() {
            return Math.max(1, Math.round(thumb.getIntrinsicHeight() * scale));
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            final int saveCount = canvas.save();
            canvas.translate(bounds.left, bounds.top);
            canvas.scale(scale, scale);
            thumb.draw(canvas);
            canvas.restoreToCount(saveCount);
        }

        @Override
        public void setAlpha(int alpha) {
            thumb.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            thumb.setColorFilter(colorFilter);
        }

        @Deprecated
        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public void invalidateDrawable(@NonNull Drawable who) {
            invalidateSelf();
        }

        @Override
        public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
            scheduleSelf(what, when);
        }

        @Override
        public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {
            unscheduleSelf(what);
        }

        @Override
        public void start() {
            if (thumb instanceof Animatable animatable) {
                animatable.start();
            }
        }

        @Override
        public void stop() {
            if (thumb instanceof Animatable animatable) {
                animatable.stop();
            }
        }

        @Override
        public boolean isRunning() {
            return thumb instanceof Animatable animatable && animatable.isRunning();
        }
    }

    /**
     * Injection point.
     */
    public static void setSeekbarView(View view) {
        if (Settings.CUSTOM_SEEKBAR_THUMB.get() && seekbarViewRef.get() != view) {
            seekbarViewRef = new WeakReference<>(view);
        }
    }

    /**
     * Injection point. Replaces the call that draws the seekbar circle.
     * Keeps the name of the original method, because other patches find that call by name.
     */
    public static void drawCircle(Canvas canvas, float cx, float cy, float radius, Paint paint) {
        try {
            if (Settings.CUSTOM_SEEKBAR_THUMB.get() && radius >= Dim.dp(MIN_RADIUS_DP)) {
                Drawable thumb = getThumbDrawable();
                if (thumb != null) {
                    drawThumb(canvas, thumb, cx, cy, radius);
                    return;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "drawCircle failure", ex);
        }

        canvas.drawCircle(cx, cy, radius, paint);
    }

    private static void drawThumb(Canvas canvas, Drawable thumb, float cx, float cy, float radius) {
        final int width = thumb.getIntrinsicWidth();
        final int height = thumb.getIntrinsicHeight();

        float scale = 1;
        if (Settings.CUSTOM_SEEKBAR_THUMB_SCALE_TYPE.get() == ThumbScaleType.FIT_SEEKBAR) {
            scale = radius * FIT_SEEKBAR_HEIGHT_TO_RADIUS / height;
        }
        scale = Math.min(scale, (float) Dim.dp(MAX_THUMB_SIZE_DP) / Math.max(width, height));
        // The seekbar view clips anything taller than itself.
        scale = Math.min(scale, (float) canvas.getHeight() / height);

        // Scale using the canvas, because AnimatedImageDrawable ignores the drawable bounds.
        final int saveCount = canvas.save();
        canvas.translate(cx - width * scale / 2, cy - height * scale / 2);
        canvas.scale(scale, scale);
        thumb.draw(canvas);
        canvas.restoreToCount(saveCount);
    }

    @Nullable
    private static Drawable getThumbDrawable() {
        String fileName = Settings.CUSTOM_SEEKBAR_THUMB_FILE.get();
        if (!fileName.equals(loadedFileName)) {
            loadedFileName = fileName;
            setThumbDrawable(fileName.isEmpty() ? null : loadThumbDrawable());
        }
        return thumbDrawable;
    }

    private static void setThumbDrawable(@Nullable Drawable drawable) {
        if (thumbDrawable instanceof Animatable animatable) {
            animatable.stop();
        }

        thumbDrawable = drawable;

        if (drawable != null) {
            drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
            drawable.setCallback(THUMB_CALLBACK);
            if (drawable instanceof Animatable animatable) {
                animatable.start();
            }
        }
    }

    @Nullable
    private static Drawable loadThumbDrawable() {
        File file = getThumbFile(Utils.getContext());
        if (!file.isFile()) {
            // Settings were imported from a different installation.
            Logger.printInfo(() -> "Custom seekbar thumb file not found");
            Settings.CUSTOM_SEEKBAR_THUMB_FILE.resetToDefault();
            // Posted to the main thread, because the seekbar is drawing.
            Utils.runOnMainThread(() -> Utils.showSnackbarShort(
                    str("morphe_custom_seekbar_thumb_file_not_found")));
            return null;
        }

        try {
            return decodeThumb(file);
        } catch (Exception ex) {
            Logger.printException(() -> "loadThumbDrawable failure", ex);
            return null;
        }
    }

    /**
     * Picked images can be any size, so they are always decoded close to the largest thumb size.
     *
     * @return The decoded image, or null if the file is not a supported image.
     */
    @Nullable
    private static Drawable decodeThumb(File file) throws IOException {
        final int maxSize = Dim.dp(MAX_THUMB_SIZE_DP);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.Source source = ImageDecoder.createSource(file);
            return ImageDecoder.decodeDrawable(source, (decoder, info, imageSource) -> {
                // Hardware bitmaps cannot be drawn to a software canvas.
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);

                Size size = info.getSize();
                final float scale = Math.min(1, (float) maxSize / Math.max(size.getWidth(), size.getHeight()));
                decoder.setTargetSize(
                        Math.max(1, Math.round(size.getWidth() * scale)),
                        Math.max(1, Math.round(size.getHeight() * scale))
                );
            });
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), options);

        final int largestSide = Math.max(options.outWidth, options.outHeight);
        options.inSampleSize = 1;
        while (largestSide / (options.inSampleSize * 2) >= maxSize) {
            options.inSampleSize *= 2;
        }
        options.inJustDecodeBounds = false;

        Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
        return bitmap == null ? null : new BitmapDrawable(null, bitmap);
    }

    private static File getThumbFile(Context context) {
        return new File(context.getFilesDir(), THUMB_FILE_NAME);
    }

    /**
     * Copies a picked image to the thumb file.
     *
     * @return If the file is a usable image and it was saved.
     */
    public static boolean importThumbFile(Context context, Uri uri) {
        File tempFile = new File(context.getFilesDir(), TEMP_THUMB_FILE_NAME);
        try {
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    return false;
                }
                Files.copy(in, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            if (decodeThumb(tempFile) == null) {
                Logger.printDebug(() -> "Picked file is not an image: " + uri);
                return false;
            }

            File thumbFile = getThumbFile(context);
            if (!tempFile.renameTo(thumbFile)) {
                Logger.printException(() -> "Could not save thumb file: " + thumbFile);
                return false;
            }
            return true;
        } catch (IOException ex) {
            // ImageDecoder throws an IOException if the file is not an image.
            Logger.printDebug(() -> "Could not import picked file: " + uri, ex);
            return false;
        } catch (Exception ex) {
            Logger.printException(() -> "importThumbFile failure", ex);
            return false;
        } finally {
            Utils.deleteQuietly(tempFile);
        }
    }

    /**
     * Loads the thumb file again on the next draw.
     */
    public static void reloadThumb() {
        loadedFileName = null;

        View view = seekbarViewRef.get();
        if (view != null) {
            view.postInvalidate();
        }
    }
}
