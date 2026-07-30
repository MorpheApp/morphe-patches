/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

/**
 * Third party lyrics backends, in the order they are queried.
 */
public enum LyricsSource {
    LRCLIB_THEN_KUGOU,
    LRCLIB,
    KUGOU
}
