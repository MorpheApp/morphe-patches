package app.morphe.patches.youtube.interaction.seekbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.util.findFreeRegister

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/DisablePreciseSeekingGesturePatch;"

val disablePreciseSeekingGesturePatch = bytecodePatch(
    description = "Adds an option to disable precise seeking when swiping up on the seekbar.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    execute {
        PreferenceScreen.SEEKBAR.addPreferences(
            SwitchPreference("morphe_disable_precise_seeking_gesture", summary = true),
        )

        AllowSwipingUpGestureFingerprint.method.apply {
            val freeRegister = findFreeRegister(0)

            addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $EXTENSION_CLASS->isGestureDisabled()Z
                    move-result v$freeRegister
                    if-eqz v$freeRegister, :disabled
                    return-void
                    :disabled
                    nop
                """
            )
        }

        ShowSwipingUpGuideFingerprint.method.apply {
            val freeRegister = findFreeRegister(0)

            addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $EXTENSION_CLASS->isGestureDisabled()Z
                    move-result v$freeRegister
                    if-eqz v$freeRegister, :disabled
                    const/4 v$freeRegister, 0x0
                    return v$freeRegister
                    :disabled
                    nop
                """
            )
        }
    }
}
