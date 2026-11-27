/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

import androidx.annotation.Nullable;

import app.morphe.extension.music.patches.lyrics.ui.LyricsShareCardRenderer.BackgroundMode;
import app.morphe.extension.music.patches.lyrics.ui.LyricsShareCardRenderer.CardPalette;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.theme.ThemeUtils;

/** Maps share-card background style to the fullscreen lyrics background. */
public final class LyricsBackgroundStyle {

    /** A little deeper than the share-card scrim: full-screen lyrics sit directly on the cover. */
    static final int[] FULLSCREEN_SCRIM_COLORS = {0x96000000, 0x86000000, 0xB2000000};

    private static Runnable styleChangedListener;

    private LyricsBackgroundStyle() {
    }

    /** Notifies the panel so share-dialog changes apply to fullscreen at once. */
    static void setStyleChangedListener(@Nullable Runnable listener) {
        styleChangedListener = listener;
    }

    public static void notifyStyleChanged() {
        if (styleChangedListener != null) {
            styleChangedListener.run();
        }
    }

    static BackgroundMode savedMode() {
        String value = Settings.LYRICS_SHARE_BG_MODE.get();
        if (Settings.SHARE_BG_COVER.equals(value)) return BackgroundMode.ALBUM_COVER;
        if (Settings.SHARE_BG_COLOR.equals(value)) return BackgroundMode.SOLID_COLOR;
        if (Settings.SHARE_BG_PHOTO.equals(value)) return BackgroundMode.CUSTOM_IMAGE;
        return BackgroundMode.BLUR_MESH;
    }

    static void saveMode(BackgroundMode mode) {
        String value;
        switch (mode) {
            case ALBUM_COVER -> value = Settings.SHARE_BG_COVER;
            case SOLID_COLOR -> value = Settings.SHARE_BG_COLOR;
            case CUSTOM_IMAGE -> value = Settings.SHARE_BG_PHOTO;
            default -> value = Settings.SHARE_BG_BLUR;
        }
        Settings.LYRICS_SHARE_BG_MODE.save(value);
    }

    static void saveColor(int argb) {
        Settings.LYRICS_SHARE_BG_COLOR.save("#" + Integer.toHexString(argb));
    }

    static void saveColorSlot(int slot) {
        Settings.LYRICS_SHARE_BG_COLOR_SLOT.save(slot);
    }

    static int savedColorSlot() {
        return Settings.LYRICS_SHARE_BG_COLOR_SLOT.get();
    }

    /** Resolves the selected swatch to a colour; slot 0 follows the current album palette. */
    static int solidColor(int fallback) {
        int slot = savedColorSlot();
        if (slot == 0) {
            return LyricsPaletteCache.albumPalette().backgroundColor();
        }
        if (slot > 0) {
            return LyricsShareCardRenderer.solidPresetColor(slot);
        }
        return savedColor(fallback);
    }

    static int savedColor(int fallback) {
        String value = Settings.LYRICS_SHARE_BG_COLOR.get();
        if (value == null || value.isEmpty()) return fallback;
        try {
            String hex = value.trim();
            if (hex.startsWith("#")) hex = hex.substring(1);
            if (hex.length() == 6) hex = "FF" + hex;
            if (hex.length() != 8) return fallback;
            return (int) Long.parseLong(hex, 16);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /** App-theme colour background, used when the dynamic background is turned off. */
    static Drawable createThemeBackground() {
        int color = ThemeUtils.getAppBackgroundColor();
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{color, color});
    }

    /** Fullscreen background drawable for the remembered style. */
    static Drawable createBackground(@Nullable Bitmap artwork, @Nullable Bitmap photo) {
        BackgroundMode mode = savedMode();
        if (mode == BackgroundMode.ALBUM_COVER && artwork != null && !artwork.isRecycled()) {
            return new CenterCropDrawable(artwork, true);
        }
        if (mode == BackgroundMode.CUSTOM_IMAGE && photo != null && !photo.isRecycled()) {
            return new CenterCropDrawable(photo, true);
        }
        if (mode == BackgroundMode.SOLID_COLOR) {
            CardPalette palette = LyricsShareCardRenderer.forCustomSolidColor(solidColor(
                    ThemeUtils.getAppBackgroundColor())).palette();
            return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{
                    palette.gradientStartColor(), palette.backgroundColor(), palette.gradientEndColor()});
        }
        // Blur / default: the app's entity colour, the same base the panel has in normal mode.
        return createThemeBackground();
    }

    /** Centre-crops a bitmap to the view bounds, matching the card's cover rendering. */
    private static final class CenterCropDrawable extends Drawable {
        private final Bitmap bitmap;
        private final boolean withScrim;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        CenterCropDrawable(Bitmap bitmap, boolean withScrim) {
            this.bitmap = bitmap;
            this.withScrim = withScrim;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            int bw = bitmap.getWidth();
            int bh = bitmap.getHeight();
            if (bw <= 0 || bh <= 0 || bounds.isEmpty()) return;
            float dstAspect = (float) bounds.width() / bounds.height();
            float srcAspect = (float) bw / bh;
            Rect src;
            if (srcAspect > dstAspect) {
                int cropW = Math.max(1, Math.round(bh * dstAspect));
                int left = (bw - cropW) / 2;
                src = new Rect(left, 0, left + cropW, bh);
            } else {
                int cropH = Math.max(1, Math.round(bw / dstAspect));
                int top = (bh - cropH) / 2;
                src = new Rect(0, top, bw, top + cropH);
            }
            canvas.drawBitmap(bitmap, src, bounds, paint);
            if (withScrim) {
                int[] colors = FULLSCREEN_SCRIM_COLORS;
                canvas.save();
                canvas.translate(bounds.left, bounds.top);
                LyricsShareCardRenderer.drawReadabilityScrim(
                        canvas, new RectF(0f, 0f, bounds.width(), bounds.height()),
                        bounds.height(), colors[0], colors[1], colors[2]);
                canvas.restore();
            }
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(@Nullable ColorFilter colorFilter) { paint.setColorFilter(colorFilter); }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }
}
