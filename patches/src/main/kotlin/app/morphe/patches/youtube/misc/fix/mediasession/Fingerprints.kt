/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.fix.mediasession

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import app.morphe.patcher.anyInstruction
import com.android.tools.smali.dexlib2.AccessFlags

internal const val MEDIA_SESSION_OWNER_FEATURE_FLAG = 45857115L

/**
 * 21.42+
 *
 * A/B flag: a player stops the media session only if the session was started by that player.
 */
internal object MediaSessionOwnerFeatureFlagFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        literal(MEDIA_SESSION_OWNER_FEATURE_FLAG)
    )
)

/**
 * Starts the media session shared by all players.
 */
internal object MediaSessionStartFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        string("MediaSession setActive(true)")
    )
)

/**
 * Stops the media session shared by all players (the parameter is true if the playback is destroyed).
 */
internal object MediaSessionStopFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Z"),
    filters = listOf(
        string("MediaSession setActive(false)")
    )
)

/**
 * Stops the background playback: removes the media notification and stops the background player service.
 */
internal object BackgroundPlaybackStopFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        anyInstruction(
            string("About to stop background service while in a pending state."),
            string("%s: About to stop background service while in a pending state.") // 21.04+
        )
    )
)

/**
 * Method of the player that starts the media session.
 */
internal object PlayerMediaSessionStartFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(reference = { MediaSessionStartFingerprint.originalMethod })
    )
)

/**
 * Every method that calls a method of the media session or of the background playback.
 * Use with [Fingerprint.matchAll].
 */
internal fun methodCallFingerprint(
    definingClass: String,
    methodName: String,
    parameters: List<String>
) = Fingerprint(
    filters = listOf(
        methodCall(
            definingClass = definingClass,
            name = methodName,
            parameters = parameters,
            returnType = "V"
        )
    )
)
