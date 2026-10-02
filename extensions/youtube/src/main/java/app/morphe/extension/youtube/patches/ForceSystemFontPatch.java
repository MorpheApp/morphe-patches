/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3579
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;

import androidx.annotation.Nullable;

import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class ForceSystemFontPatch {

    private static final int DEFAULT_WEIGHT = 400;

    private static int weightFromName(String name) {
        name = name.toLowerCase(Locale.ENGLISH);
        if (name.contains("extrabold")) return 800;
        if (name.contains("semibold")) return 600;
        if (name.contains("black")) return 900;
        if (name.contains("bold")) return 700;
        if (name.contains("medium")) return 500;
        if (name.contains("light")) return 300;
        if (name.contains("thin")) return 100;
        return DEFAULT_WEIGHT;
    }

    private static Typeface create(int weight, int style) {
        if ((style & Typeface.BOLD) != 0) {
            weight = Math.max(weight, 700);
        }
        return Typeface.create(Typeface.DEFAULT, weight, (style & Typeface.ITALIC) != 0);
    }

    /**
     * Injection point.
     */
    @Nullable
    public static Typeface getSystemTypeface(int weight, int style, @Nullable String fontSettings) {
        if (!Settings.FORCE_SYSTEM_FONT.get()) {
            return null;
        }

        // Other fonts are chosen by the user, such as the Shorts text styles.
        if (fontSettings != null && !fontSettings.isEmpty() && !fontSettings.contains("YouTube Sans")) {
            return null;
        }

        if (weight < 1 || weight > 1000) {
            weight = DEFAULT_WEIGHT;
        }
        return create(weight, style);
    }

    /**
     * Injection point.
     */
    @Nullable
    public static Typeface getSystemTypeface(Context context, int fontResourceId, int style) {
        if (!Settings.FORCE_SYSTEM_FONT.get()) {
            return null;
        }

        try {
            return create(weightFromName(context.getResources().getResourceEntryName(fontResourceId)), style);
        } catch (Resources.NotFoundException ex) {
            Logger.printDebug(() -> "Font resource not found: " + fontResourceId);
            return null;
        }
    }

    /**
     * Injection point.
     * Roboto fonts are already the system font family.
     */
    @Nullable
    public static Typeface getSystemTypeface(Enum<?> font, int style) {
        if (!Settings.FORCE_SYSTEM_FONT.get()) {
            return null;
        }

        String name = font.name();
        if (!name.startsWith("YOUTUBE_SANS_") && !name.startsWith("YTSANS_")) {
            return null;
        }
        return create(weightFromName(name), style);
    }
}
