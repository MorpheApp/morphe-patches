/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.graphics.Color;

import androidx.annotation.Nullable;

/** Resolves the selected text colour slot into an opaque ARGB colour. */
final class LyricsTextColor {

    static final int DEFAULT_SLOT = 0;
    static final int AUTO_SLOT = 1;

    private LyricsTextColor() {
    }

    /** Slot 0: plain white, the default and the reset target. */
    static int defaultWhite() {
        return 0xFFFFFFFF;
    }

    /** Slot 1: the album-sampled colour; live text follows the current track's palette. */
    static int auto() {
        return 0xFF000000 | LyricsPaletteCache.albumPalette().secondaryTextColor();
    }

    static int resolve(int slot, @Nullable String customColor) {
        if (slot == DEFAULT_SLOT) {
            return defaultWhite();
        }
        if (slot == AUTO_SLOT) {
            return auto();
        }
        if (slot > AUTO_SLOT) {
            return LyricsShareCardRenderer.textColorPreset(slot);
        }
        if (customColor != null) {
            try {
                return Color.parseColor(customColor) | 0xFF000000;
            } catch (IllegalArgumentException ignored) {
                // Invalid stored colours fall back to plain white silently.
            }
        }
        return defaultWhite();
    }
}
