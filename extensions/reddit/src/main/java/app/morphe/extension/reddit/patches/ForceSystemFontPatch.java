/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.extension.reddit.patches;

import android.graphics.Typeface;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public final class ForceSystemFontPatch {

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     *
     * Returns the system default {@link Typeface} for the given style when the
     * "Force system font" setting is enabled, otherwise returns {@code null} so
     * the caller falls back to its original font-loading logic.
     *
     * @param style Typeface style (Typeface.NORMAL, BOLD, ITALIC, or BOLD_ITALIC).
     */
    public static Typeface getSystemTypeface(int style) {
        if (!Settings.FORCE_SYSTEM_FONT.get()) {
            return null;
        }
        return Typeface.create(Typeface.DEFAULT, style);
    }
}
