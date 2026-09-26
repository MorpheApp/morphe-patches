/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.video.speed

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.video.information.musicVideoInformationPatch
import app.morphe.patches.music.video.information.musicVideoTimeHook
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.videoinformation.PlaybackParametersToStringFingerprint
import app.morphe.patches.shared.misc.videoinformation.getExoPlayerImplFingerprint
import app.morphe.patches.shared.misc.videoinformation.getPlaybackParametersSetterFingerprint
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/PlaybackSpeedPatch;"
private const val EXTENSION_EXOPLAYERIMPL_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/PlaybackSpeedPatch$ExoPlayerImpl;"

@Suppress("unused")
val playbackSpeedPatch = bytecodePatch(
    name = "Playback speed",
    description = "Adds options to change the playback speed and pitch of tracks.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        // Used to apply a changed speed setting while a track is playing.
        musicVideoInformationPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_music_playback_speed_screen",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    NonInteractivePreference(
                        key = "morphe_music_playback_speed",
                        tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference",
                        selectable = true
                    ),
                    SwitchPreference("morphe_music_playback_speed_change_pitch", summary = true),
                )
            )
        )

        // region ExoPlayerImpl.

        val playbackParametersType = PlaybackParametersToStringFingerprint.classDef.type

        // The PlaybackParameters primary constructor with 2 arguments (speed, pitch).
        val playbackParametersConstructorReference = "$playbackParametersType-><init>(FF)V"

        // The toString() method reads the speed field before the pitch field.
        val playbackParametersSpeedField: FieldReference
        val playbackParametersPitchField: FieldReference
        PlaybackParametersToStringFingerprint.let {
            val speedMatch = it.instructionMatches.first()
            playbackParametersSpeedField = speedMatch.getFieldAccessed()

            it.method.apply {
                val pitchIndex = indexOfFirstInstructionOrThrow(speedMatch.index + 1) {
                    opcode == Opcode.IGET && getReference<FieldReference>()?.type == "F"
                }
                playbackParametersPitchField =
                    getInstruction<ReferenceInstruction>(pitchIndex).reference as FieldReference
            }
        }

        val setPlaybackParametersFingerprint = getPlaybackParametersSetterFingerprint(playbackParametersType)
        val setPlaybackParametersMethod = setPlaybackParametersFingerprint.method

        // A reference to the setPlaybackParameters implementation, to call from the helper method.
        val setPlaybackParametersReference = "${setPlaybackParametersMethod.definingClass}->" +
                "${setPlaybackParametersMethod.name}($playbackParametersType)V"

        // Every speed the app sets passes through here, including when a new track loads.
        // Need to construct new PlaybackParameters instance as it has final fields.
        setPlaybackParametersMethod.addInstructions(
            0,
            """
                iget v0, p1, $playbackParametersSpeedField
                iget v1, p1, $playbackParametersPitchField
                invoke-static { v0, v1 }, $EXTENSION_CLASS->overridePlaybackPitch(FF)F
                move-result v1
                invoke-static { v0 }, $EXTENSION_CLASS->overridePlaybackSpeed(F)F
                move-result v0
                new-instance p1, $playbackParametersType
                invoke-direct { p1, v0, v1 }, $playbackParametersConstructorReference
            """
        )

        // Capture each ExoPlayerImpl instance at its constructor.
        // More than one instance can be active at the same time, such as when crossfading.
        getExoPlayerImplFingerprint(playbackParametersType).matchAll().forEach {
            val firstInstructionMatch = it.instructionMatches.first()
            val register = firstInstructionMatch.getInstruction<FiveRegisterInstruction>().registerC
            it.method.addInstruction(
                firstInstructionMatch.index + 1,
                "invoke-static { v$register }, $EXTENSION_CLASS->" +
                        "initializeExoPlayerImpl($EXTENSION_EXOPLAYERIMPL_INTERFACE)V"
            )
        }

        setPlaybackParametersFingerprint.classDef.apply {
            // Add interface and helper method to allow extension code
            // to directly set the ExoPlayer playback parameters.
            interfaces.add(EXTENSION_EXOPLAYERIMPL_INTERFACE)

            methods.add(
                ImmutableMethod(
                    type,
                    "patch_setPlaybackParameters",
                    listOf(
                        ImmutableMethodParameter("F", null, null),
                        ImmutableMethodParameter("F", null, null)
                    ),
                    "V",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    null,
                    null,
                    MutableMethodImplementation(4),
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            new-instance v0, $playbackParametersType
                            invoke-direct { v0, p1, p2 }, $playbackParametersConstructorReference
                            invoke-virtual { p0, v0 }, $setPlaybackParametersReference
                            return-void
                        """
                    )
                }
            )
        }

        // endregion

        // Apply a changed speed setting without waiting for the next track.
        musicVideoTimeHook(EXTENSION_CLASS, "setVideoTime")
    }
}
