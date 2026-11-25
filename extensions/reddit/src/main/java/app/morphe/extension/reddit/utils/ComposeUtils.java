/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5272
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.utils;

import android.graphics.Canvas;

import app.morphe.extension.shared.Utils;
import kotlin.jvm.functions.Function1;

/**
 * Access to obfuscated Compose classes of the app.
 * <p>
 * Implementations of Function1 must not be lambdas, because the extension shrinker removes the
 * invoke method of lambda classes as it is only called by app code. They also must not be
 * created by a class that the settings load, because Function1 is obfuscated in Reddit 2026.10.0.
 */
public final class ComposeUtils {

    /**
     * Compose Canvas.
     */
    public interface ComposeCanvas {
        // Methods are added during patching.
        Canvas patch_getNativeCanvas();

        void patch_setNativeCanvas(Canvas canvas);
    }

    /**
     * Compose ContentDrawScope. Sizes, offsets and corner radii are two packed floats.
     */
    public interface ContentDrawScope {
        // Methods are added during patching.
        ComposeCanvas patch_getCanvas();

        long patch_getSize();

        void patch_drawContent();

        void patch_drawRoundRect(long color, long topLeft, long size, long cornerRadius);
    }

    /**
     * Compose Modifier.
     */
    public interface Modifier {
        // Methods are added during patching.
        Object patch_drawWithContent(Function1<?, ?> draw);

        Object patch_offset(float x, float y);

        Object patch_padding(float start, float top, float end, float bottom);

        /**
         * Available only with 2026.43.0+
         */
        Object patch_windowInsetsPadding(WindowInsets insets);
    }

    /**
     * Compose WindowInsets.
     */
    public interface WindowInsets {
        // Methods are added during patching.

        /**
         * Available only with 2026.43.0+
         *
         * @return Top inset in pixels.
         */
        int patch_getTop();
    }

    private ComposeUtils() {
    }

    public static float getDensity() {
        return Utils.getContext().getResources().getDisplayMetrics().density;
    }

    public static long pack(float first, float second) {
        return ((long) Float.floatToRawIntBits(first) << 32)
                | (Float.floatToRawIntBits(second) & 0xFFFFFFFFL);
    }

    public static float unpackFirst(long packed) {
        return Float.intBitsToFloat((int) (packed >>> 32));
    }

    public static float unpackSecond(long packed) {
        return Float.intBitsToFloat((int) packed);
    }
}
