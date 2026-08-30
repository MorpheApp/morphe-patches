/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.video.playerresponse

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

private val PLAYER_PARAMETER_STARTS_WITH_PARAMETER_LIST = listOf(
    "Ljava/lang/String;", // VideoId.
    "[B",
    "Ljava/lang/String;", // Player parameters proto buffer.
    "Ljava/lang/String;", // PlaylistId.
    "I"                   // PlaylistIndex.
)

internal object PlayerParameterBuilderFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    custom = { method, _ ->
        val parameterTypes = method.parameterTypes
        if (parameterTypes.size < 13) {
            false
        } else {
            parameterTypes.take(5).map { it.toString() } == PLAYER_PARAMETER_STARTS_WITH_PARAMETER_LIST
        }
    }
)
