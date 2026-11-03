/*
 * Portions Copyright (C) 2023-2024 Anil Beesetti (Next Player)
 * https://github.com/anilbeesetti/nextplayer
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 *
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

/**
 * Pure helper functions and constants for {@link CaptionStylePatch}. Has no Android UI
 * dependencies, so it can be unit tested on the JVM.
 * <p>
 * Matches Next Player subtitle styling and reference preset constants.
 */
public final class CaptionStyleUtils {

    /**
     * Colors matching Next Player and reference subtitle appearance:
     * - White text (0xFFFFFFFF)
     * - Black background / drop shadow (0xFF000000)
     * - Transparent background / window (0x00000000)
     */
    public static final int COLOR_WHITE = 0xFFFFFFFF;
    public static final int COLOR_BLACK = 0xFF000000;
    public static final int COLOR_TRANSPARENT = 0x00000000;

    /**
     * CaptionStyle.EDGE_TYPE_DROP_SHADOW = 2.
     */
    public static final int EDGE_TYPE_DROP_SHADOW = 2;

    private CaptionStyleUtils() {
    }

    public static int backgroundColor(int original, boolean active, boolean reference, boolean showBackground) {
        if (!active) return original;
        return reference || !showBackground ? COLOR_TRANSPARENT : COLOR_BLACK;
    }

    /** Android Typeface style values: NORMAL=0, BOLD=1, BOLD_ITALIC=3. */
    public static int fontStyle(boolean reference, boolean bold) {
        return reference ? 3 : (bold ? 1 : 0);
    }

    public static boolean shouldBypassPen(boolean active, boolean reference, boolean applyEmbeddedStyles) {
        return active && (reference || !applyEmbeddedStyles);
    }
}
