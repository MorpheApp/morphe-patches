/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public final class ColorizeCommentIndentLinesPatch {

    public enum CommentLinePalette {
        RAINBOW(new int[] {
                0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047, 0xFF1E88E5, 0xFF8E24AA
        }),
        PASTEL(new int[] {
                0xFFF4A6A6, 0xFFF7C59F, 0xFFF3E29B, 0xFFA8D8B9, 0xFF9EC9F0, 0xFFC8A8E9
        }),
        MUTED(new int[] {
                0xFFB5655B, 0xFFC08A4E, 0xFFA89B4E, 0xFF6E9468, 0xFF5E83A6, 0xFF8A6D9E
        });

        final int[] colors;

        CommentLinePalette(int[] colors) {
            this.colors = colors;
        }
    }

    private static final long ARGB_MASK = 0xFFFFFFFFL;
    private static final int COLOR_SHIFT = 32;
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
     */
    public static long getLineColor(int depth, long original) {
        if (!Settings.COLORIZE_COMMENT_INDENT_LINES.get()) {
            return original;
        }

        int[] palette = Settings.COLORIZE_COMMENT_INDENT_LINES_PALETTE.get().colors;
        final int argb = palette[Math.max(depth - 1, 0) % palette.length];

        // Compose sRGB color packing: ARGB in the upper 32 bits.
        return (argb & ARGB_MASK) << COLOR_SHIFT;
    }

    /**
     * Injection point.
     */
    public static float getLineAlpha(float alpha) {
        if (!Settings.COLORIZE_COMMENT_INDENT_LINES.get()) {
            return alpha;
        }
        return OPAQUE_ALPHA;
    }
}
