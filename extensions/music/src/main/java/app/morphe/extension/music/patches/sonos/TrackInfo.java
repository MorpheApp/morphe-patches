/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.sonos;

import androidx.annotation.Nullable;

/**
 * The current track, as shown in the speaker apps.
 */
record TrackInfo(String title, String artist, @Nullable String albumArtUrl) {
}
