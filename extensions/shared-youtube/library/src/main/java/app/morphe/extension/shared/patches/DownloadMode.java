/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

public enum DownloadMode {
    /**
     * The download feature of the app is left as it is.
     */
    DEFAULT,
    /**
     * Videos are saved with the Download button of the app.
     */
    IN_APP,
    /**
     * Videos are downloaded with an external downloader app.
     */
    EXTERNAL
}
