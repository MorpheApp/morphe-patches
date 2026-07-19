/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
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
     */
    public static Typeface getSystemTypeface(int style) {
        if (!Settings.FORCE_SYSTEM_FONT.get() || Settings.CUSTOM_FONT.get()) {
            return null;
        }

        return Typeface.create(Typeface.DEFAULT, style);
    }
}