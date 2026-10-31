/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3955
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.medianotification

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
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

/**
 * Media session callback methods. Each one logs its own name, which is used to find it.
 * The previous/next methods are in the same class as the fast forward/rewind methods.
 */
internal object MediaSessionSkipToNextFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        string("onSkipToNext()")
    )
)

internal object MediaSessionSkipToPreviousFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        string("onSkipToPrevious()")
    )
)

internal object MediaSessionFastForwardFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        string("onFastForward()")
    )
)

internal object MediaSessionRewindFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        string("onRewind()")
    )
)

/**
 * The player's media session callback, which does the actual relative seek of the
 * fast-forward and rewind actions. It extends the same callback class as the class of
 * [MediaSessionFastForwardFingerprint], overrides the same fast forward/rewind method,
 * and passes the seek length to a method that takes a long.
 */
internal class MediaSessionSeekRelativeFingerprint(
    callbackClass: String,
    methodName: String,
) : Fingerprint(
    superclass = callbackClass,
    name = methodName,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(
            parameters = listOf("J"),
            returnType = "V"
        )
    )
)
