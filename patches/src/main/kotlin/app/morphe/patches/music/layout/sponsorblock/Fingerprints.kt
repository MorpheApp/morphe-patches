/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.layout.sponsorblock

import app.morphe.patcher.Fingerprint

/**
 * Matches {@code MusicPlaybackControlsTimeBar.draw(Canvas)}.
 * Draws segment markers on the compact/mini player seekbar.
 */
internal object MusicTimeBarDrawFingerprint : Fingerprint(
    definingClass = "/MusicPlaybackControlsTimeBar;",
    name = "draw",
    returnType = "V"
)

/**
 * Matches {@code MusicPlaybackControlsTimeBar.onMeasure(int, int)}.
 * Resolves the Rect field used for seekbar bounds.
 */
internal object MusicTimeBarOnMeasureFingerprint : Fingerprint(
    definingClass = "/MusicPlaybackControlsTimeBar;",
    name = "onMeasure",
    returnType = "V"
)
