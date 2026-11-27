/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.graphics.Bitmap;

import app.morphe.extension.music.patches.lyrics.LyricsManager;

/** The album colour for the auto swatch, refreshed when the track changes. */
final class LyricsPaletteCache {

    private static volatile LyricsShareCardRenderer.CardPalette albumPalette =
            LyricsShareCardRenderer.CardPalette.DEFAULT;

    private LyricsPaletteCache() {
    }

    static LyricsShareCardRenderer.CardPalette albumPalette() {
        return albumPalette;
    }

    static void refresh() {
        Bitmap artwork = LyricsManager.getInstance().getCurrentArtworkBitmap();
        albumPalette = artwork != null && !artwork.isRecycled()
                ? LyricsShareCardRenderer.extractPaletteFromBitmap(artwork)
                : LyricsShareCardRenderer.CardPalette.DEFAULT;
    }
}
