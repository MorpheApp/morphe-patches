/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4618
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.audioducking

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/DisableAudioDuckingPatch;"

@Suppress("unused")
val disableAudioDuckingPatch = patch(
    name = "Disable audio ducking",
    description = "Adds an option to keep the volume unchanged when another app plays " +
            "a short sound, such as a notification."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_music_disable_audio_ducking", summary = true)
        )

        // Android ducks the app itself, unless the request declares the app pauses when ducked.
        AudioFocusRequestFingerprint.let {
            val constructorCall = it.instructionMatches.last()
            val register = constructorCall.instruction.registersUsed.last()

            it.method.addInstructions(
                constructorCall.index,
                """
                    invoke-static { v$register }, $EXTENSION_CLASS->willPauseWhenDucked(Z)Z
                    move-result v$register
                """
            )
        }

        // The app is then told to duck, and would lower the volume itself.
        AudioFocusChangeFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->ignoreAudioFocusChange(I)Z
                move-result v0
                if-eqz v0, :handle
                return-void
                :handle
                nop
            """
        )
    }
}
