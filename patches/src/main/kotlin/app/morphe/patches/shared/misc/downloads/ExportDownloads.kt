/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4698
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.downloads

import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference

/**
 * Explains how videos and songs saved by the app are exported to the device, and picks the folder.
 * YouTube and YouTube Music save them the same way, so both apps use the same exporter.
 */
internal fun exportDownloadFolderPreference() = NonInteractivePreference(
    "morphe_export_download_folder",
    summaryKey = null,
    tag = "app.morphe.extension.shared.settings.preference.ExportFolderPreference",
    selectable = true
)
