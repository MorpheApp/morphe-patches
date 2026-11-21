/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5107
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.glassmenus

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.flyout.flyoutPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/glassmenus/GlassMenusPatch;"
private const val SEEKBAR_PREFERENCE = "app.morphe.extension.shared.settings.preference.SeekBarPreference"

@Suppress("unused")
val glassMenusPatch = patch(
    name = "Glass menus",
    description = "Adds an option to show the menus as frosted glass, with the content behind the menu blurred. " +
        "This patch works on Android 12 and newer.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        // The popup menus are passed on by the flyout menu hooks.
        flyoutPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_glass_menus_screen",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_glass_menus", summary = true),
                    NonInteractivePreference(key = "morphe_glass_menus_opacity", tag = SEEKBAR_PREFERENCE),
                    NonInteractivePreference(key = "morphe_glass_menus_blur", tag = SEEKBAR_PREFERENCE),
                    NonInteractivePreference(key = "morphe_glass_menus_dim", tag = SEEKBAR_PREFERENCE),
                ),
            ),
        )

        // Bottom sheets: every bottom sheet dialog of the app, when it is attached to its window.
        BottomSheetDialogOnAttachedToWindowFingerprint.method.addInstruction(
            0,
            "invoke-static { p0 }, $EXTENSION_CLASS->onBottomSheetDialog(Landroid/app/Dialog;)V",
        )

        // The sheet to save a video to a playlist, which is a dialog of its own.
        AddToPlaylistCreateViewFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).forEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA
                addInstruction(
                    index,
                    "invoke-static { v$register }, $EXTENSION_CLASS->onAddToPlaylistView(Landroid/view/View;)V",
                )
            }
        }

        // Dialogs of dialog fragments, right after they are shown: the panels at the bottom of the screen,
        // such as the video quality and the download quality.
        DialogFragmentOnStartFingerprint.let {
            it.method.apply {
                val showIndex = it.instructionMatches.first().index
                val dialogRegister = getInstruction<FiveRegisterInstruction>(showIndex).registerC
                addInstruction(
                    showIndex + 1,
                    "invoke-static { v$dialogRegister }, $EXTENSION_CLASS->onDialogShown(Landroid/app/Dialog;)V",
                )
            }
        }

        // The sheets of Morphe, such as the playback speed and the voice over translation.
        SlideDialogCreateFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).forEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA
                addInstruction(
                    index,
                    "invoke-static { v$register }, $EXTENSION_CLASS->onSlideDialog(Landroid/app/Dialog;)V",
                )
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
