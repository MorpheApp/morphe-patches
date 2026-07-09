/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.video.information

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.parametersMatch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Matches the player class that exposes a seek method.
 * Used to add a seekTo(J)Z bridge and hook the constructor.
 */
internal object VideoEndFingerprint : Fingerprint(
    parameters = listOf("J", "L"),
    filters = listOf(
        string("currentPositionMs."),
        string(";seekTimeUs.")
    )
)

/**
 * Matches the method called with the player response model when a new track loads.
 * Parameters are (playerResponseModel, videoId).
 */
internal object VideoIdFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    filters = listOf(
      string("Null initialPlayabilityStatus")
    ),
    custom = { method, _ ->
        parametersMatch(
            method.parameters,
            listOf("L", "Ljava/lang/String;")
        ) || parametersMatch(
            method.parameters,
            listOf("L", "Ljava/lang/String;", "Z") // 9.24 only
        )
    },
)
