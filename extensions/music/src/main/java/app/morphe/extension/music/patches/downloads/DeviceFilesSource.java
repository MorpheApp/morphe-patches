/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.downloads;

public enum DeviceFilesSource {
    /**
     * Blend both local device tracks and downloaded songs (default).
     */
    BOTH,
    /**
     * Only show and play local files from system MediaStore.
     */
    DEVICE_ONLY,
    /**
     * Only show and play downloaded songs from Morphe private storage.
     */
    DOWNLOADS_ONLY
}
