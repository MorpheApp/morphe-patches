package app.morphe.patches.music.interaction.permanentrepeat

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.findFreeRegister

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/music/patches/PermanentRepeatPatch;"

@Suppress("unused")
val permanentRepeatPatch = bytecodePatch(
    name = "Permanent repeat",
    description = "Adds an option to always repeat even if the playlist ends or another track is played."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(
        "com.google.android.apps.youtube.music"(
            "7.29.52",
            "8.10.52",
            "8.46.57",
        )
    )

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_music_play_permanent_repeat"),
        )

        val startIndex = RepeatTrackFingerprint.instructionMatches.last().index
        val repeatIndex = startIndex + 1

        RepeatTrackFingerprint.method.apply {
            // Start index is at a branch, but the same
            // register is clobbered in both branch paths.
            val freeRegister = findFreeRegister(startIndex + 1)

            addInstructionsWithLabels(
                startIndex,
                """
                    invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->permanentRepeat()Z
                    move-result v$freeRegister
                    if-nez v$freeRegister, :repeat 
                """,
                ExternalLabel("repeat", instructions[repeatIndex]),
            )
        }
    }
}
