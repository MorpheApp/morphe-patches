/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.downloadall

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.string

/**
 * Builds the preview for a post in the share sheet.
 */
internal object ShareSheetLinkFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Lcom/reddit/domain/model/Link;"),
    filters = listOf(
        methodCall(definingClass = "Lcom/reddit/domain/model/Link;", name = "getCrossPostParentList"),
        string(" · shared from "),
        methodCall(definingClass = "Lcom/reddit/sharing/SharePreview;", name = "<init>")
    )
)

/**
 * Launches the download of the item in view from the share sheet.
 *
 * 2026.44.0+
 */
internal object LaunchDownloadActionFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Lkotlin/jvm/functions/Function1;"),
    filters = listOf(
        newInstance($$"Lcom/reddit/sharing/actions/handler/ActionsScreenShareActionHandler$launchDownloadAction$1;")
    )
)
