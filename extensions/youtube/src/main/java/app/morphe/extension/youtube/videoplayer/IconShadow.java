/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3287
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.videoplayer;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;

/**
 * The soft drop shadow the app draws under its own player icons, which is what keeps a white
 * icon readable over bright video.
 */
final class IconShadow {

    static final int OFFSET_X;
    static final int OFFSET_Y;
    static final int BLUR_RADIUS;
    static final int COLOR;

    static {
        int offsetX = 0, offsetY = 0, blurRadius = 0, color = Color.TRANSPARENT;
        try {
            // The app has both integer and dimension versions of these. The player controls read
            // the integers as raw pixels, while only the miniplayer reads the dimensions.
            offsetX = ResourceUtils.getInteger("shadow_icon_offset_x");
            offsetY = ResourceUtils.getInteger("shadow_icon_offset_y");
            blurRadius = ResourceUtils.getInteger("shadow_icon_size");
            color = Color.argb(ResourceUtils.getInteger("shadow_icon_alpha"), 0, 0, 0);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not resolve player icon shadow resources", ex);
        }
        OFFSET_X = offsetX;
        OFFSET_Y = offsetY;
        BLUR_RADIUS = blurRadius;
        COLOR = color;
    }

    private IconShadow() {
    }

    static boolean isAvailable() {
        return BLUR_RADIUS > 0;
    }

    /**
     * @return The shadow of {@code icon} drawn at {@code width} by {@code height}, or null if it could not be built.
     */
    @Nullable
    static Bitmap build(Drawable icon, int width, int height) {
        try {
            Bitmap rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            // The wrapper draws the icon at the view bounds, so move it to the bitmap origin
            // and put it back afterward.
            Rect iconBounds = new Rect(icon.getBounds());
            icon.setBounds(0, 0, width, height);
            icon.draw(new Canvas(rendered));
            icon.setBounds(iconBounds);

            Bitmap mask = rendered.extractAlpha();
            rendered.recycle();

            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(COLOR);
            paint.setMaskFilter(new BlurMaskFilter(BLUR_RADIUS, BlurMaskFilter.Blur.NORMAL));

            // Blurring at draw time into a bitmap the size of the icon keeps the shadow
            // inside the icon box, the way the app builds its own player icon shadows.
            Bitmap blurred = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            new Canvas(blurred).drawBitmap(mask, OFFSET_X, OFFSET_Y, paint);
            mask.recycle();

            return blurred;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not build player icon shadow", ex);
            return null;
        }
    }
}
