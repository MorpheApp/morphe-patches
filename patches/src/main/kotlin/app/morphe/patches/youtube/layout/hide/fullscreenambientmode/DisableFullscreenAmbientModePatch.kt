package app.morphe.patches.youtube.layout.hide.fullscreenambientmode

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/DisableFullscreenAmbientModePatch;"

val disableFullscreenAmbientModePatch = bytecodePatch(
    name = "Disable fullscreen ambient mode",
    description = "Adds an option to disable the ambient mode when in fullscreen.",
) {
    dependsOn(
        settingsPatch,
        sharedExtensionPatch,
    )

    compatibleWith(
        "com.google.android.youtube"(
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.46.41",
        )
    )

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_disable_fullscreen_ambient_mode"),
        )

        SetFullScreenBackgroundColorFingerprint.method.apply {
            val insertIndex = indexOfFirstInstructionReversedOrThrow {
                getReference<MethodReference>()?.name == "setBackgroundColor"
            }
            val register = getInstruction<FiveRegisterInstruction>(insertIndex).registerD

            addInstructions(
                insertIndex,
                """
                    invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->getFullScreenBackgroundColor(I)I
                    move-result v$register
                """,
            )
        }
    }
}
