/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.snackbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.layout.theme.lithoColorHookPatch
import app.morphe.patches.shared.layout.theme.lithoDrawableColorHook
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.shared.misc.textcomponent.hookSpannableStringResult
import app.morphe.patches.shared.misc.textcomponent.textComponentPatch
import app.morphe.patches.youtube.misc.playservice.is_21_30_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/SnackbarPatch;"
private const val COLOR_PICKER_PREFERENCE =
    "app.morphe.extension.shared.settings.preference.ColorPickerPreference"
private const val COLOR_PICKER_WITH_OPACITY_PREFERENCE =
    "app.morphe.extension.shared.settings.preference.ColorPickerWithOpacitySliderPreference"

val snackbarPatch = patch(
    name = "Snackbar",
    description = "Adds options to hide the snackbar or change its colors and shape."
) {
    dependsOn(
        settingsPatch,
        versionCheckPatch,
        textComponentPatch,
        lithoColorHookPatch { is_21_30_or_greater }
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_snackbar_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_hide_snackbar"),
                    SwitchPreference("morphe_custom_snackbar_theme", summary = true),
                    TextPreference(
                        "morphe_custom_snackbar_color_dark",
                        tag = COLOR_PICKER_PREFERENCE,
                        inputType = InputType.TEXT_CAP_CHARACTERS
                    ),
                    TextPreference(
                        "morphe_custom_snackbar_color_light",
                        tag = COLOR_PICKER_PREFERENCE,
                        inputType = InputType.TEXT_CAP_CHARACTERS
                    ),
                    TextPreference(
                        "morphe_custom_snackbar_stroke_color",
                        tag = COLOR_PICKER_WITH_OPACITY_PREFERENCE,
                        inputType = InputType.TEXT_CAP_CHARACTERS
                    ),
                    TextPreference(
                        "morphe_custom_snackbar_corner_radius",
                        titleKey = "morphe_corner_radius_title",
                        inputType = InputType.NUMBER
                    )
                )
            )
        )

        // region hide snackbar

        SnackbarDisplayFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.first().index + 1
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                // Let YouTube skip displaying the snackbar while still running its callbacks.
                addInstructions(
                    index + 1,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->hideSnackbar(Z)Z
                        move-result v$register
                    """
                )
            }
        }

        LithoSnackbarLayoutFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.first().index
                val register = getInstruction<TwoRegisterInstruction>(index).registerA

                addInstruction(
                    index,
                    "invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->hideLithoSnackbar(Landroid/widget/FrameLayout;)V"
                )
            }
        }

        MealbarFingerprint.let {
            it.method.addInstruction(
                it.instructionMatches.first().index + 1,
                "invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->hideSnackbarView(Landroid/view/View;)V"
            )
        }

        // endregion

        // region custom snackbar theme

        arrayOf(
            QuantumSnackbarFingerprint,
            MaterialSnackbarFingerprint,
            AppSnackbarFingerprint,
            YouTubeSnackbarFingerprint
        ).forEach {
            it.method.addInstruction(
                it.instructionMatches.first().index + 1,
                "invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->onSnackbarCreated(Landroid/view/View;)V"
            )
        }

        // The background of a Litho snackbar is drawn by the Litho component, which covers
        // the background of the view that holds it. The view is changed here, and the
        // component in the Litho color hook.
        LithoSnackbarFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                // The other layout jumps to the return, so the call keeps the jump.
                addInstructionsAtControlFlowLabel(
                    index,
                    "invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->onLithoSnackbarCreated(Landroid/widget/FrameLayout;)V"
                )
            }
        }

        lithoDrawableColorHook("$EXTENSION_CLASS_DESCRIPTOR->getLithoColor(Landroid/graphics/drawable/Drawable;I)I")

        hookSpannableStringResult(EXTENSION_CLASS_DESCRIPTOR, "setLithoTextColor")

        // Texts that are not colored when they are created are colored when they are drawn.
        LithoTextDrawableDrawFingerprint.let {
            val fields = it.classDef.fields
            val layoutField = fields.single { field -> field.type == "Landroid/text/Layout;" }
            val textField = fields.single { field -> field.type == "Ljava/lang/CharSequence;" }

            it.method.addInstructions(
                0,
                """
                    iget-object v0, p0, $layoutField
                    iget-object v1, p0, $textField
                    invoke-static { p0, v0, v1 }, $EXTENSION_CLASS_DESCRIPTOR->setLithoTextDrawColor(Landroid/graphics/drawable/Drawable;Landroid/text/Layout;Ljava/lang/CharSequence;)V
                """
            )
        }

        // endregion
    }
}
