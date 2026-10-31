/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4232
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import java.util.List;

/**
 * A local playlist.
 *
 * @param trackPaths Absolute paths of the audio files, in play order.
 */
record OfflinePlaylist(String id, String name, List<String> trackPaths) {
}
