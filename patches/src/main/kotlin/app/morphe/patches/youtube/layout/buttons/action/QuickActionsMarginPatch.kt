/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.buttons.action

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/QuickActionsMarginPatch;"

internal val quickActionsMarginPatch = bytecodePatch(
    description = "Injects a configurable top margin into the quick actions container view."
) {
    dependsOn(
        sharedExtensionPatch,
        resourceMappingPatch
    )

    execute {
        QuickActionsElementSyntheticFingerprint.let {
            it.method.apply {
                val checkCastIndex = it.instructionMatches.last().index
                val insertRegister = getInstruction<OneRegisterInstruction>(checkCastIndex).registerA

                addInstruction(
                    checkCastIndex + 1,
                    "invoke-static { v$insertRegister }, $EXTENSION_CLASS->setQuickActionsMargin(Landroid/view/View;)V"
                )
            }
        }
    }
}
