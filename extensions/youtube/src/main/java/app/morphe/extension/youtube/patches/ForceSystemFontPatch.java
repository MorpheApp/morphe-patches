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
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class ForceSystemFontPatch {

    private static final int DEFAULT_WEIGHT = 400;

    /**
     * Brand fonts bundled in the app. Roboto and the Material font families are already the
     * system font family, and monospace, icon and emoji fonts must keep their look.
     */
    private static boolean isYouTubeSansName(String name) {
        name = name.toLowerCase(Locale.ENGLISH);
        return name.startsWith("youtube_sans")
                || name.startsWith("ytsans")
                || name.startsWith("youtubemarquee")
                || name.startsWith("google_sans")
                || name.startsWith("gm3_ref_typeface")
                || name.startsWith("yt_ref_typography");
    }

    private static int weightFromName(String name) {
        name = name.toLowerCase(Locale.ENGLISH);
        if (name.contains("extrabold")) return 800;
        if (name.contains("semibold")) return 600;
        if (name.contains("black") || name.contains("heavy")) return 900;
        if (name.contains("bold")) return 700;
        if (name.contains("medium")) return 500;
        if (name.contains("light")) return 300;
        if (name.contains("thin")) return 100;
        return DEFAULT_WEIGHT;
    }

    private static Typeface create(int weight, int style, boolean italic) {
        // A negative style means the caller did not ask for one, which must not read as bold italic.
        if (style < 0) {
            style = Typeface.NORMAL;
        }
        if ((style & Typeface.BOLD) != 0) {
            weight = Math.max(weight, 700);
        }
        italic |= (style & Typeface.ITALIC) != 0;

        if (Utils.isSDKAbove(28)) {
            return Typeface.create(Typeface.DEFAULT, weight, italic);
        }

        // Before Android 9, a weight can only be selected using the font family name.
        final String family;
        int legacyStyle = italic ? Typeface.ITALIC : Typeface.NORMAL;
        if (weight >= 900) {
            family = "sans-serif-black";
        } else if (weight >= 600) {
            family = "sans-serif";
            legacyStyle |= Typeface.BOLD;
        } else if (weight >= 500) {
            family = "sans-serif-medium";
        } else if (weight >= 400) {
            family = "sans-serif";
        } else if (weight >= 300) {
            family = "sans-serif-light";
        } else {
            family = "sans-serif-thin";
        }
        return Typeface.create(family, legacyStyle);
    }

    private static Typeface createFromName(String name, int style) {
        return create(weightFromName(name), style, name.toLowerCase(Locale.ENGLISH).contains("italic"));
    }

    /**
     * Injection point.
     *
     * @param original Typeface from the font provider.
     * @return The system typeface, or the original typeface if it should not be replaced.
     */
    @Nullable
    public static Typeface getSystemTypeface(@Nullable Typeface original, int weight, int style,
                                             @Nullable String fontSettings) {
        try {
            if (!Settings.FORCE_SYSTEM_FONT.get()) {
                return original;
            }

            // Other fonts are chosen by the user, such as the Shorts text styles.
            if (fontSettings != null && !fontSettings.isEmpty() && !fontSettings.contains("YouTube Sans")) {
                return original;
            }

            if (weight < 1 || weight > 1000) {
                weight = DEFAULT_WEIGHT;
            }
            return create(weight, style, false);
        } catch (Exception ex) {
            Logger.printException(() -> "getSystemTypeface failure", ex);
            return original;
        }
    }

    /**
     * Injection point.
     */
    @Nullable
    public static Typeface getSystemTypeface(Context context, int fontResourceId, int style) {
        try {
            if (!Settings.FORCE_SYSTEM_FONT.get()) {
                return null;
            }

            String name = context.getResources().getResourceEntryName(fontResourceId);

            // Other bundled fonts, such as icon or monospace fonts, are left unchanged.
            if (!isYouTubeSansName(name)) {
                return null;
            }
            return createFromName(name, style);
        } catch (Resources.NotFoundException ex) {
            Logger.printDebug(() -> "Font resource not found: 0x" + Integer.toHexString(fontResourceId));
            return null;
        } catch (Exception ex) {
            Logger.printException(() -> "getSystemTypeface failure", ex);
            return null;
        }
    }

    /**
     * Injection point.
     * Roboto fonts are already the system font family.
     */
    @Nullable
    public static Typeface getSystemTypeface(Enum<?> font, int style) {
        try {
            if (!Settings.FORCE_SYSTEM_FONT.get()) {
                return null;
            }

            String name = font.name();
            if (!isYouTubeSansName(name)) {
                return null;
            }
            return createFromName(name, style);
        } catch (Exception ex) {
            Logger.printException(() -> "getSystemTypeface failure", ex);
            return null;
        }
    }
}
