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
    public static Typeface getSystemTypeface(String path, int style) {
        if (!Settings.FORCE_SYSTEM_FONT.get() || Settings.CUSTOM_FONT.get()) {
            return null;
        }

        int weight = weightFromPath(path);
        if ((style & Typeface.BOLD) != 0) {
            weight = Math.max(weight, 700);
        }

        boolean italic = (style & Typeface.ITALIC) != 0
                || (path != null && path.toLowerCase().contains("italic"));

        return Typeface.create(Typeface.DEFAULT, weight, italic);
    }

    private static int weightFromPath(String path) {
        if (path == null) {
            return 400;
        }

        String lowerCasePath = path.toLowerCase();
        if (lowerCasePath.contains("black")) {
            return 900;
        }
        if (lowerCasePath.contains("extrabold") || lowerCasePath.contains("extra_bold")
                || lowerCasePath.contains("extra-bold")) {
            return 800;
        }
        if (lowerCasePath.contains("semibold") || lowerCasePath.contains("semi_bold")
                || lowerCasePath.contains("semi-bold")
                || lowerCasePath.contains("demibold") || lowerCasePath.contains("demi_bold")) {
            return 600;
        }
        if (lowerCasePath.contains("bold")) {
            return 700;
        }
        if (lowerCasePath.contains("medium")) {
            return 500;
        }
        if (lowerCasePath.contains("light")) {
            return 300;
        }
        if (lowerCasePath.contains("thin")) {
            return 100;
        }

        return 400;
    }
}
