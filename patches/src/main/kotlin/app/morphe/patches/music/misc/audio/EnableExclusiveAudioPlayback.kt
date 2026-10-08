/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.misc.audio

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
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
 * A podcast episode can play on the phone but report FEATURE_AVAILABILITY_BLOCKED for audio-only mode.
 * This status is distinct from FEATURE_AVAILABILITY_MISSING_ENTITLEMENTS and gives no specific reason.
 * On VIDEO_PLAYBACK_LOADED or VIDEO_WATCH_LOADED, YTM checks that status.
 * With Android Auto or another external controller connected, it tries Next if audio-only is blocked,
 * then stops if Next returns "action unavailable" (11).
 * The override above enables the audio-only option but leaves this check active.
 */
private fun BytecodePatchContext.patchExternalAudioPlayback() {
    val stopMethod = StopMusicMediaSessionFingerprint.matchSingle().originalMethod
    val match = externalAudioPlaybackRestrictionFingerprint(stopMethod).matchSingle()
    val result = match.instructionMatches[1]
    val resultRegister = result.getInstruction<OneRegisterInstruction>().registerA
    val branchRegister = match.instructionMatches[2].getInstruction<OneRegisterInstruction>().registerA
    if (resultRegister != branchRegister) {
        throw PatchException("Audio-only playback check does not feed the expected branch")
    }

    // Override the result only here: the shared check also controls the phone's Audio/Video UI.
    match.method.replaceInstruction(result.index, "const/16 v$resultRegister, 0x0")
}
