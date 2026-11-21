/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5107
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.glassmenus;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.ColorStateListDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.os.Build;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Finds the color of a drawable that fills its bounds with one color, such as the background of a menu,
 * from the state of the drawable. Nothing is drawn: drawing a drawable of the app on a software canvas
 * can crash the app (hardware bitmaps, drawables animated by the render thread).
 */
final class SolidColors {

    /**
     * How many wrappers are unwrapped, such as an inset drawable or a Litho drawable around a color drawable.
     */
    private static final int MAX_DEPTH = 4;

    /**
     * @return The color the drawable fills its bounds with, or transparent if it is not one color.
     */
    @ColorInt
    static int colorOf(@Nullable Drawable drawable) {
        Integer color = resolve(drawable, 0, null);
        return color == null ? Color.TRANSPARENT : color;
    }

    /**
     * @param chain Gets the drawable and the drawables it wraps, down to the one with the color.
     * @return The color the drawable fills its bounds with, or transparent if it is not one color.
     */
    @ColorInt
    static int colorOf(@Nullable Drawable drawable, List<Drawable> chain) {
        Integer color = resolve(drawable, 0, chain);
        return color == null ? Color.TRANSPARENT : color;
    }

    @Nullable
    private static Integer resolve(@Nullable Drawable drawable, int depth, @Nullable List<Drawable> chain) {
        if (drawable == null || depth > MAX_DEPTH) return null;
        if (chain != null) chain.add(drawable);

        if (drawable instanceof ColorDrawable color) {
            return color.getColor();
        }
        if (drawable instanceof ShapeDrawable shape) {
            return shape.getPaint().getColor();
        }
        if (drawable instanceof GradientDrawable gradient) {
            return colorForState(gradient.getColor(), drawable);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && drawable instanceof ColorStateListDrawable list) {
            return colorForState(list.getColorStateList(), drawable);
        }
        if (drawable instanceof DrawableWrapper wrapper) {
            return resolve(wrapper.getDrawable(), depth + 1, chain);
        }
        if (drawable instanceof LayerDrawable layers) {
            // Ripples and drawables of more than one layer are not a fill of one color.
            return layers.getNumberOfLayers() == 1 && !(drawable instanceof android.graphics.drawable.RippleDrawable)
                    ? resolve(layers.getDrawable(0), depth + 1, chain)
                    : null;
        }

        if (!LithoDrawables.isPlatformClass(drawable.getClass())) {
            try {
                List<Field> fields = LithoDrawables.fieldsOf(drawable.getClass());
                // A wrapper of the app, such as the drawable Litho mounts around the background of a component.
                for (Field field : fields) {
                    if (field.get(drawable) instanceof Drawable wrapped && wrapped != drawable) {
                        return resolve(wrapped, depth + 1, chain);
                    }
                }
                // A fill of the app, such as the background of an Elements component: one paint that fills
                // a rectangle or a rounded rectangle with a color. Outlines, shadows and gradients are not a fill.
                Paint fill = null;
                for (Field field : fields) {
                    if (field.get(drawable) instanceof Paint paint) {
                        if (fill != null) return null;
                        fill = paint;
                    }
                }
                if (fill != null && fill.getStyle() == Paint.Style.FILL
                        && fill.getShader() == null && fill.getMaskFilter() == null) {
                    return fill.getColor();
                }
            } catch (Throwable ignored) {
                // Not accessible.
            }
        }
        return null;
    }

    @Nullable
    private static Integer colorForState(@Nullable ColorStateList colors, Drawable drawable) {
        if (colors == null) return null;
        return colors.getColorForState(drawable.getState(), colors.getDefaultColor());
    }

    private SolidColors() {
    }
}
