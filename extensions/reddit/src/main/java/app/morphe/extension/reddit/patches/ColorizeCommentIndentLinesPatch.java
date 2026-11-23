/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5241
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public final class ColorizeCommentIndentLinesPatch {

    public enum CommentLinePalette {
        RAINBOW(0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047, 0xFF1E88E5, 0xFF8E24AA),
        PASTEL(0xFFF4A6A6, 0xFFF7C59F, 0xFFF3E29B, 0xFFA8D8B9, 0xFF9EC9F0, 0xFFC8A8E9),
        MUTED(0xFFB5655B, 0xFFC08A4E, 0xFFA89B4E, 0xFF6E9468, 0xFF5E83A6, 0xFF8A6D9E);

        final int[] colors;

        CommentLinePalette(int... colors) {
            this.colors = colors;
        }
    }

    /**
     * Compose stores an sRGB color as the ARGB value in the upper 32 bits.
     */
    private static final int COMPOSE_COLOR_SHIFT = 32;
    private static final long ARGB_MASK = 0xFFFFFFFFL;
    private static final float OPAQUE_ALPHA = 1f;

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * Injection point.
     *
     * @param depth Reply level of the line, starting at 1.
     */
    public static long getLineColor(int depth, long original) {
        if (!Settings.COLORIZE_COMMENT_INDENT_LINES.get()) {
            return original;
        }

        int[] colors = Settings.COLORIZE_COMMENT_INDENT_LINES_PALETTE.get().colors;
        final int argb = colors[(depth - 1) % colors.length];
        return (argb & ARGB_MASK) << COMPOSE_COLOR_SHIFT;
    }

    /**
     * Injection point.
     */
    public static float getLineAlpha(float original) {
        return Settings.COLORIZE_COMMENT_INDENT_LINES.get()
                ? OPAQUE_ALPHA
                : original;
    }
}
