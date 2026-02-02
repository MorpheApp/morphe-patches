package app.morphe.patches.youtube.layout.hide.ambientmode

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.indexOfFirstStringInstructionOrThrow
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/AmbientModePatch;"

@Suppress("unused")
val ambientModePatch = bytecodePatch(
    name = "Ambient mode",
    description = "Adds options to bypass power saving restrictions for Ambient mode and disable it entirely or in fullscreen.",
) {
    dependsOn(
        settingsPatch,
        sharedExtensionPatch,
    )

    compatibleWith(
        "com.google.android.youtube"(
            "20.14.43",
            "20.21.37",
            "20.26.46",
            "20.31.42",
            "20.37.48",
            "20.40.45",
        )
    )

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_ambient_mode_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_bypass_ambient_mode_restrictions"),
                    SwitchPreference("morphe_disable_ambient_mode"),
                    SwitchPreference("morphe_disable_fullscreen_ambient_mode"),
                )
            )
        )

        //
        // Bypass ambient mode restrictions.
        //
        val syntheticClasses = HashSet<String>()
        mapOf(
            PowerSaveModeBroadcastReceiverFingerprint to false,
            PowerSaveModeSyntheticFingerprint to true,
        ).forEach { (fingerprint, reversed) ->
            fingerprint.method.apply {
                val stringIndex = indexOfFirstStringInstructionOrThrow(
                    "android.os.action.POWER_SAVE_MODE_CHANGED"
                )
                val targetIndex =
                    if (reversed) { indexOfFirstInstructionReversedOrThrow(
                        stringIndex,
                        Opcode.INVOKE_DIRECT)
                    } else { indexOfFirstInstructionOrThrow(
                        stringIndex,
                        Opcode.INVOKE_DIRECT)
                    }
                val definingClass =
                    (getInstruction<ReferenceInstruction>(targetIndex).reference as MethodReference).definingClass

                syntheticClasses += definingClass
            }
        }

        syntheticClasses.forEach { classDescriptor ->
            val mutableClass = mutableClassDefBy(classDescriptor)
            mutableClass.methods
                .firstOrNull { it.name == "accept" || it.name == "invoke" }
                ?.apply {
                    implementation!!.instructions
                        .withIndex()
                        .filter { (_, instruction) ->
                            val reference = (instruction as? ReferenceInstruction)?.reference

                            instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                                    reference is MethodReference &&
                                    reference.name == "isPowerSaveMode"
                        }
                        .map { (index, _) -> index }
                        .asReversed()
                        .forEach { index ->
                            val register = getInstruction<OneRegisterInstruction>(index + 1).registerA

                            addInstructions(
                                index + 2,
                                """
                            invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->bypassAmbientModeRestrictions(Z)Z
                            move-result v$register
                        """
                            )
                        }
                }
        }

        //
        // Disable fullscreen ambient mode.
        //
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
