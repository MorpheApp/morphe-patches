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
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/shared/patches/LithoRelayoutPatch;"

private const val EXTENSION_LITHO_VIEW_INTERFACE =
    $$"Lapp/morphe/extension/shared/patches/LithoRelayoutPatch$LithoViewInterface;"

private const val EXTENSION_COMPONENT_TREE_INTERFACE =
    $$"Lapp/morphe/extension/shared/patches/LithoRelayoutPatch$ComponentTreeInterface;"

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
            // The component tree of the Litho view is the tree that is measured.
            val measureIndex = it.instructionMatches.last().index
            val treeRegister = it.method.getInstruction<FiveRegisterInstruction>(measureIndex).registerC
            val treeField = it.method.getInstruction(
                it.method.indexOfFirstInstructionReversedOrThrow(measureIndex) {
                    opcode == Opcode.IGET_OBJECT && (this as TwoRegisterInstruction).registerA == treeRegister
                }
            ).getReference<FieldReference>()!!
            if (treeField.definingClass != it.classDef.type || treeField.type != COMPONENT_TREE_CLASS) {
                throw PatchException("Unexpected component tree field: $treeField")
            }

            it.classDef.apply {
                addPatchInterface(EXTENSION_LITHO_VIEW_INTERFACE, "patch_getComponentTree", treeField)

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

        // Calculates the layout again on the layout thread of the component tree, as Litho does
        // for the layouts of list items, so the main thread only mounts the new layout.
        // A null root and size specs of -1 keep the current root and size specs, and an async layout
        // returns no size. The source is setSizeSpecAsync (3), as an async source does not throw
        // if no layout is calculated, such as when the tree is released meanwhile.
        // The layout is forced, so the root is copied with a new id and the committed layout is not reused.
        ComponentTreeSetRootAndSizeSpecFingerprint.let {
            val invokeOpcode = if (AccessFlags.PRIVATE.isSet(it.method.accessFlags)) {
                "invoke-direct/range"
            } else {
                "invoke-virtual/range"
            }

            it.classDef.addPatchInterface(
                EXTENSION_COMPONENT_TREE_INTERFACE,
                "patch_forceLayoutAsync",
                """
                    move-object v0, p0
                    const/4 v1, 0x0
                    const/4 v2, -0x1
                    const/4 v3, -0x1
                    const/4 v4, 0x1
                    const/4 v5, 0x0
                    const/4 v6, 0x3
                    const/4 v7, 0x0
                    const/4 v8, 0x0
                    const/4 v9, 0x0
                    const/4 v10, 0x1
                    $invokeOpcode { v0 .. v10 }, ${it.method}
                    return-void
                """
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
