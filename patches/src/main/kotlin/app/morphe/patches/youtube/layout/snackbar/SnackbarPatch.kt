/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.patches.youtube.layout.snackbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.layout.theme.lithoColorOverrideHook
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/SnackbarPatch;"

@Suppress("unused")
val snackbarPatch = bytecodePatch(
    name = "Snackbar",
    description = "Adds options to modify or hide the snackbar."
) {
    dependsOn(settingsPatch)
    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        lithoColorOverrideHook(EXTENSION_CLASS_DESCRIPTOR, "getLithoColor")

        PreferenceScreen.GENERAL.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_snackbar_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_custom_snackbar_theme"),
                    TextPreference("morphe_custom_snackbar_corner_radius", inputType = InputType.NUMBER_DECIMAL),
                    TextPreference("morphe_custom_snackbar_color_dark",
                        tag = "app.morphe.extension.shared.settings.preference.ColorPickerPreference",
                        inputType = InputType.TEXT_CAP_CHARACTERS
                    ),
                    TextPreference("morphe_custom_snackbar_color_light",
                        tag = "app.morphe.extension.shared.settings.preference.ColorPickerPreference",
                        inputType = InputType.TEXT_CAP_CHARACTERS
                    ),
                    TextPreference("morphe_custom_snackbar_stroke_color",
                        tag = "app.morphe.extension.shared.settings.preference.ColorPickerPreference",
                        inputType = InputType.TEXT_CAP_CHARACTERS
                    ),
                    TextPreference("morphe_custom_snackbar_text_color",
                        tag = "app.morphe.extension.shared.settings.preference.ColorPickerPreference",
                        inputType = InputType.TEXT_CAP_CHARACTERS
                    ),
                    SwitchPreference("morphe_hide_snackbar"),
                )
            )
        )

        BottomUIContainerPreFingerprint.method.addInstruction(
            0, "invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->onLithoSnackbarPrepare()V"
        )

        LithoSnackbarFingerprint.method.apply {
            val backGroundColorIndex = findInstructionIndicesReversedOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL &&
                        getReference<MethodReference>()?.name == "setBackgroundColor"
            }.first()

            val bgInstruction = getInstruction<FiveRegisterInstruction>(backGroundColorIndex)
            val viewRegister = bgInstruction.registerC
            val colorRegister = bgInstruction.registerD

            replaceInstruction(
                backGroundColorIndex,
                "invoke-static {v$viewRegister, v$colorRegister}, $EXTENSION_CLASS_DESCRIPTOR->" +
                        "setLithoSnackBarBackgroundColor(Landroid/widget/FrameLayout;I)V"
            )

            val targetClass = LithoSnackbarFingerprint.originalClassDef

            val initMethod = targetClass.methods.first { targetMethod ->
                targetMethod.implementation?.instructions?.any { instruction ->
                    instruction.opcode == Opcode.IPUT_OBJECT &&
                            (instruction as? ReferenceInstruction)?.reference?.let { ref ->
                                (ref as? FieldReference)?.type == "Landroid/widget/FrameLayout;"
                            } == true
                } == true
            }.toMutable()

            initMethod.apply {
                val viewIndex = indexOfFirstInstructionOrThrow {
                    opcode == Opcode.IPUT_OBJECT &&
                            getReference<FieldReference>()?.type == "Landroid/widget/FrameLayout;"
                }
                val viewRegister = getInstruction<TwoRegisterInstruction>(viewIndex).registerA

                addInstruction(
                    viewIndex,
                    "invoke-static {v$viewRegister}, $EXTENSION_CLASS_DESCRIPTOR->hideLithoSnackBar(Landroid/widget/FrameLayout;)V"
                )
            }
        }

        BottomUIContainerFingerprint.method.apply {
            addInstructionsWithLabels(
                0, """
                    invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->hideSnackbar()Z
                    move-result v0
                    if-eqz v0, :show
                    return-void
                    """, ExternalLabel("show", getInstruction(0))
            )
        }

        listOf(
            QuantumSnackbarFingerprint,
            MaterialSnackbarFingerprint,
            AppSnackbarFingerprint,
            YouTubeSnackbarFingerprint,
            MealbarFingerprint
        ).forEach { fingerprint ->
            runCatching {
                fingerprint.method.apply {
                    findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
                        addInstruction(
                            index,
                            "invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->handleLegacySnackbar(Landroid/view/View;)V"
                        )
                    }
                }
            }
        }
    }
}