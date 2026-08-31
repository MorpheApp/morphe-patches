/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.shared

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

internal const val YOUTUBE_MUSIC_MAIN_ACTIVITY_CLASS_TYPE = "Lcom/google/android/apps/youtube/music/activities/MusicActivity;"

internal object MusicActivityOnCreateFingerprint : Fingerprint(
    definingClass = YOUTUBE_MUSIC_MAIN_ACTIVITY_CLASS_TYPE,
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;")
)

internal object MediaSessionSetMetadataFingerprint : Fingerprint(
    filters = listOf(
        methodCall(
            definingClass = "Landroid/media/session/MediaSession;",
            name = "setMetadata",
            parameters = listOf("Landroid/media/MediaMetadata;")
        )
    )
)

/**
 * Passes the argument of the matched `MediaSession` call to an extension method,
 * before the call itself runs.
 *
 * @param extensionMethod Method descriptor to invoke, without the register list.
 */
context(_: BytecodePatchContext)
internal fun Fingerprint.hookMediaSessionArgument(extensionMethod: String) {
    // Several patches hook the same call, and each insertion shifts the index of
    // the following ones, so the match is resolved again on every call.
    clearMatch()

    method.apply {
        val index = instructionMatches.first().index
        val register = getInstruction<FiveRegisterInstruction>(index).registerD
        addInstruction(index, "invoke-static { v$register }, $extensionMethod")
    }
}
