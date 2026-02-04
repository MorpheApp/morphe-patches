package app.morphe.patches.youtube.layout.hide.ambientmode

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.utils.compatibility.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_02_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_03_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

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
        versionCheckPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

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
        fun MutableMethod.hook() {
            findInstructionIndicesReversedOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL &&
                        getReference<MethodReference>()?.toString() == IS_POWER_SAVE_MODE_METHOD_CALL
            }.forEach { index ->
                val register = getInstruction<FiveRegisterInstruction>(index).registerC

                replaceInstruction(
                    index,
                    "invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS_DESCRIPTOR->" +
                            "bypassAmbientModeRestrictions(Landroid/os/PowerManager;)Z",
                )
            }
        }

        val intentActionFingerprints = mutableListOf(
            IntentActionBroadcastReceiverFingerprint,
            IntentActionSyntheticFingerprint
        )
        if (is_21_02_or_greater) {
            intentActionFingerprints += IntentActionBroadcastReceiverAlternativeFingerprint
        }
        intentActionFingerprints.forEach { fingerprint ->
            fingerprint.let {
                it.method.apply {
                    val index = it.instructionMatches[2].index
                    val reference =
                        getInstruction<ReferenceInstruction>(index).reference as MethodReference

                    // This fingerprint is used multiple times.
                    PowerSaveModeSyntheticFingerprint.clearMatch()

                    // Match may be null, as it may have already been replaced by another fingerprint.
                    PowerSaveModeSyntheticFingerprint.matchOrNull(
                                        mutableClassDefBy(reference.definingClass)
                                    )?.method?.hook()
                }
            }
        }
        if (is_21_03_or_greater) {
            IntentActionSyntheticAlternativeFingerprint.method.hook()
        }

        //
        // Disable ambient mode.
        //
        AmbientModeFeatureFlagFingerprint.let {
            it.method.insertLiteralOverride(
                it.instructionMatches.first().index,
                "$EXTENSION_CLASS_DESCRIPTOR->disableAmbientMode(Z)Z"
            )
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
