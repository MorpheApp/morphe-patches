/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.medianotification

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Adds one button action to the playback notification.
 * Parameters: builder, icon resource id, title resource id, pending intent,
 * list of compact view indices, and if the action is shown in the compact view.
 */
internal object PlaybackNotificationAddActionFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "I", "I", "Landroid/app/PendingIntent;", "Ljava/util/List;", "Z"),
    filters = listOf(
        methodCall(
            smali = "Landroid/content/Context;->getText(I)Ljava/lang/CharSequence;"
        )
    )
)
