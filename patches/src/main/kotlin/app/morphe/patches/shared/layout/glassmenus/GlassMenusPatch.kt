/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5107
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.layout.glassmenus

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchDefinition
import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.layout.theme.lithoColorHookPatch
import app.morphe.patches.shared.layout.theme.lithoDrawableDrawnColorHook
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

internal const val GLASS_MENUS_EXTENSION_CLASS =
    "Lapp/morphe/extension/shared/patches/glassmenus/GlassMenusPatch;"

private const val SEEKBAR_PREFERENCE = "app.morphe.extension.shared.settings.preference.SeekBarPreference"

/**
 * Patch shared with YouTube and YT Music.
 */
internal fun glassMenusPatch(
    block: PatchDefinition.() -> Unit = {},
    executeBlock: () -> Unit = {},
    useModernLithoColorHook: PatchDefinition.() -> Boolean,
    preferenceScreen: BasePreferenceScreen.Screen,
) = patch(
    name = "Glass menus",
    description = "Adds an option to show the menus as frosted glass, with the content behind the menu blurred. " +
        "This patch works on Android 12 and newer.",
) {
    block()

    dependsOn(
        // The backgrounds Litho draws inside the menus are passed on by the Litho color hook.
        lithoColorHookPatch(useModernLithoColorHook),
    )

    execute {
        preferenceScreen.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_glass_menus_screen",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_glass_menus", summary = true),
                    NonInteractivePreference(key = "morphe_glass_menus_opacity", titleKey = "morphe_glass_opacity_title", tag = SEEKBAR_PREFERENCE),
                    NonInteractivePreference(key = "morphe_glass_menus_blur", tag = SEEKBAR_PREFERENCE),
                    NonInteractivePreference(key = "morphe_glass_menus_dim", tag = SEEKBAR_PREFERENCE),
                ),
            ),
        )

        // Bottom sheets: every bottom sheet dialog of the app, when it is attached to its window.
        BottomSheetDialogOnAttachedToWindowFingerprint.method.addInstruction(
            0,
            "invoke-static { p0 }, $GLASS_MENUS_EXTENSION_CLASS->onBottomSheetDialog(Landroid/app/Dialog;)V",
        )

        // Dialogs of dialog fragments, right after they are shown: the panels at the bottom of the screen,
        // such as the video quality and the download quality.
        DialogFragmentOnStartFingerprint.let {
            it.method.apply {
                val showIndex = it.instructionMatches.first().index
                val dialogRegister = getInstruction<FiveRegisterInstruction>(showIndex).registerC
                addInstruction(
                    showIndex + 1,
                    "invoke-static { v$dialogRegister }, $GLASS_MENUS_EXTENSION_CLASS->onDialogShown(Landroid/app/Dialog;)V",
                )
            }
        }

        // The sheets of Morphe, such as the playback speed and the voice-over translation.
        SlideDialogCreateFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).forEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA
                addInstructionsAtControlFlowLabel(
                    index,
                    "invoke-static { v$register }, $GLASS_MENUS_EXTENSION_CLASS->onSlideDialog(Landroid/app/Dialog;)V",
                )
            }
        }

        lithoDrawableDrawnColorHook(
            "$GLASS_MENUS_EXTENSION_CLASS->onLithoBackgroundColor(Landroid/graphics/drawable/Drawable;I)V"
        )

        setExtensionIsPatchIncluded(GLASS_MENUS_EXTENSION_CLASS)

        executeBlock()
    }
}
