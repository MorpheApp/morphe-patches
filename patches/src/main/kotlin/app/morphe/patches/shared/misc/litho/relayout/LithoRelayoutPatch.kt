/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3384
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.litho.relayout

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/shared/patches/LithoRelayoutPatch;"

private const val EXTENSION_LITHO_VIEW_INTERFACE =
    $$"Lapp/morphe/extension/shared/patches/LithoRelayoutPatch$LithoViewInterface;"

/**
 * Adds support to force Litho views to calculate their layout again,
 * such as to show texts that changed after the views were laid out.
 */
val lithoRelayoutPatch = patch(
    description = "Adds support to force Litho views to calculate their layout again."
) {
    execute {
        // Verify stubbed classes are not obfuscated.
        classDefBy(COMPONENT_HOST_CLASS)
        classDefBy(COMPONENT_TEXT_CONTENT)

        LithoViewOnMeasureFingerprint.let {
            val (readFlag, clearFlag) = it.instructionMatches.take(2).map { match ->
                match.fieldReference
            }
            if (readFlag != clearFlag) {
                throw PatchException("Unexpected fields, read: $readFlag clear: $clearFlag")
            }

            it.classDef.apply {
                addPatchInterface(
                    EXTENSION_LITHO_VIEW_INTERFACE,
                    "patch_forceRelayout",
                    """
                        const/4 v0, 0x1
                        iput-boolean v0, p0, $readFlag
                        invoke-virtual { p0 }, $type->requestLayout()V
                        return-void
                    """
                )

                // Unmounting all content mounts it again with the next layout,
                // which loads the images again.
                addPatchInterface(
                    EXTENSION_LITHO_VIEW_INTERFACE,
                    "patch_forceRemount",
                    """
                        invoke-virtual { p0 }, $type->${LithoViewUnmountAllItemsFingerprint.method.name}()V
                        invoke-virtual { p0 }, $type->requestLayout()V
                        return-void
                    """
                )
            }

            // Remember the Litho views, so all shown views can be mounted again.
            it.method.addInstruction(
                0,
                "invoke-static/range { p0 .. p0 }, " +
                        "$EXTENSION_CLASS->onLithoViewMeasured(Landroid/view/View;)V"
            )
        }

        // Remember the mounted texts that are laid out again when outdated.
        LithoTextMountFingerprint.let {
            it.method.apply {
                val textIndex = it.instructionMatches.last().index
                val textInstruction = getInstruction<TwoRegisterInstruction>(textIndex)
                val textDrawableType = getInstruction(textIndex)
                    .getReference<FieldReference>()!!.definingClass
                if (COMPONENT_TEXT_CONTENT !in classDefBy(textDrawableType).interfaces) {
                    throw PatchException("Could not find the Litho text drawable")
                }

                addInstruction(
                    textIndex + 1,
                    "invoke-static { v${textInstruction.registerB}, v${textInstruction.registerA} }, " +
                            "$EXTENSION_CLASS->onLithoTextMounted(Landroid/graphics/drawable/Drawable;Ljava/lang/CharSequence;)V"
                )
            }
        }
    }
}
