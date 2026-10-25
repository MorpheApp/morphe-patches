/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

/**
 * Pure helper functions of {@link CaptionStylePatch}. Has no Android or settings dependencies,
 * so it can be unit tested on the JVM.
 */
public final class CaptionStyleUtils {

    /**
     * Smallest and largest caption size scale, in percent.
     */
    public static final int MIN_FONT_SCALE_PERCENT = 25;
    public static final int MAX_FONT_SCALE_PERCENT = 400;

    private CaptionStyleUtils() {
    }

    /**
     * Parses {@code #RRGGBB} or {@code #AARRGGBB} (the leading '#' is optional).
     *
     * @return The color as an ARGB int. A 6 digit color is fully opaque.
     * If the string is invalid then {@code fallback} is returned.
     */
    public static int parseColor(String color, int fallback) {
        if (color == null) return fallback;

        String hex = color.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (hex.length() != 6 && hex.length() != 8) return fallback;

        try {
            long value = Long.parseLong(hex, 16);
            if (hex.length() == 6) {
                value |= 0xFF000000L;
            }
            return (int) value;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /**
     * @return The system caption scale multiplied by the user scale percentage.
     * The percentage is clamped to the supported range.
     */
    public static float scaleFontScale(float systemScale, int userPercent) {
        final int percent = Math.max(MIN_FONT_SCALE_PERCENT, Math.min(MAX_FONT_SCALE_PERCENT, userPercent));
        return systemScale * (percent / 100f);
    }
}
