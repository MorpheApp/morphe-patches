/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.toolbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.shared.ToolBarButtonFingerprint
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import java.lang.ref.WeakReference

internal const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/ToolBarPatch;"

private lateinit var toolbarMethod : WeakReference<MutableMethod>

val toolBarHookPatch = bytecodePatch(
    description = "toolBarHookPatch"
) {
    dependsOn(
        sharedExtensionPatch,
    )

    execute {
        ToolBarButtonFingerprint.let {
            it.clearMatch()
            it.method.apply {
                val enumIndex = it.instructionMatches[3].index
                val enumRegister = getInstruction<OneRegisterInstruction>(enumIndex).registerA

                val imageViewIndex = it.instructionMatches[6].index
                val imageViewRegister = getInstruction<OneRegisterInstruction>(imageViewIndex).registerA

                // Capture each value at the instruction where its type is known. YouTube reuses
                // the enum register before the ImageView load, and free locals can be live on
                // another verifier path in newer builds, so do not carry either through a temp.
                addInstructionsAtControlFlowLabel(
                    imageViewIndex + 1,
                    """
                        invoke-static { v$imageViewRegister }, $EXTENSION_CLASS->setToolbarImageView(Landroid/widget/ImageView;)V
                    """
                )
                addInstructionsAtControlFlowLabel(
                    enumIndex + 1,
                    "invoke-static { v$enumRegister }, $EXTENSION_CLASS->setToolbarIconEnum(Ljava/lang/Enum;)V"
                )
            }
        }

        toolbarMethod = WeakReference(ToolBarPatchFingerprint.method)
    }
}

internal fun hookToolBar(descriptor: String) = toolbarMethod.get()!!.addInstructions(
    0,
    "invoke-static { p0, p1, p2 }, " +
            "$descriptor(Ljava/lang/String;Landroid/view/View;Landroid/widget/ImageView;)V"
)
