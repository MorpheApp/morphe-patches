/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5107
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.glassmenus

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patches.shared.layout.glassmenus.GLASS_MENUS_EXTENSION_CLASS
import app.morphe.patches.shared.layout.glassmenus.glassMenusPatch
import app.morphe.patches.youtube.layout.flyout.flyoutPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_30_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

@Suppress("unused")
val glassMenusPatch = glassMenusPatch(
    block = {
        dependsOn(
            sharedExtensionPatch,
            settingsPatch,
            versionCheckPatch,
            // The popup menus are passed on by the flyout menu hooks.
            flyoutPatch,
        )

        compatibleWith(COMPATIBILITY_YOUTUBE)
    },
    executeBlock = {
        // The sheet to save a video to a playlist, which is a dialog of its own.
        AddToPlaylistCreateViewFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).forEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA
                // A jump to the return must also reach the call.
                addInstructionsAtControlFlowLabel(
                    index,
                    "invoke-static { v$register }, $GLASS_MENUS_EXTENSION_CLASS->onAddToPlaylistView(Landroid/view/View;)V",
                )
            }
        }
    },
    useModernLithoColorHook = { is_21_30_or_greater },
    preferenceScreen = PreferenceScreen.GENERAL,
)
