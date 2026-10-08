/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3850
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.misc.audio

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.playservice.is_9_32_or_greater
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.util.matchSingle
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

@Suppress("unused")
val enableExclusiveAudioPlaybackPatch = bytecodePatch(
    name = "Enable exclusive audio playback",
    description = "Enables the option to play audio without video.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        val fingerprint = if (is_9_32_or_greater) {
            AllowExclusiveAudioPlaybackFingerprint
        } else {
            AllowExclusiveAudioPlaybackLegacyFingerprint
        }

        fingerprint.method.returnEarly(true)
        patchExternalAudioPlayback()
    }
}

/**
 * YTM tries to skip podcast episodes marked FEATURE_AVAILABILITY_BLOCKED ("Audio-only not available")
 * and stops playback if the skip request returns "action unavailable" (11). Bypass that restriction
 * so playback can start from Android Auto without pressing Play on the phone.
 */
private fun BytecodePatchContext.patchExternalAudioPlayback() {
    val stopMethod = StopMediaSessionFingerprint.matchSingle().originalMethod
    val match = externalAudioRestrictionFingerprint(stopMethod).matchSingle()
    val result = match.instructionMatches[1]
    val resultRegister = result.getInstruction<OneRegisterInstruction>().registerA
    val branchRegister = match.instructionMatches[2].getInstruction<OneRegisterInstruction>().registerA
    if (resultRegister != branchRegister) {
        throw PatchException("Playback restriction result and branch use different registers")
    }

    // The phone's Audio/Video switch also uses this restriction check.
    // Force false only here to prevent automatic skips and stops.
    match.method.replaceInstruction(result.index, "const/16 v$resultRegister, 0x0")
}
