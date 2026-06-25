/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.video.information

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Matches the method that contains a call to the actual video-time setter (~every 1000ms).
 * The log string is stable across YTM versions.
 */
internal object PlayerControllerSetTimeFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        opcode(Opcode.INVOKE_DIRECT_RANGE),
        opcode(Opcode.IGET_OBJECT),
    ),
    strings = listOf("Media progress reported outside media playback: ")
)

/**
 * Matches the player class that exposes a seek method.
 * Used to add a seekTo(J)Z bridge and hook the constructor.
 */
internal object VideoEndFingerprint : Fingerprint(
    strings = listOf("Attempting to seek during an ad")
)

/**
 * Matches the method called with the player response model when a new track loads.
 * Parameters are (playerResponseModel, videoId).
 */
internal object VideoIdFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("L", "Ljava/lang/String;"),
    strings = listOf("Null initialPlayabilityStatus")
)
