/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3384
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.litho.relayout

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/shared/patches/LithoRelayoutPatch;"

private const val EXTENSION_LITHO_VIEW_INTERFACE =
    $$"Lapp/morphe/extension/shared/patches/LithoRelayoutPatch$LithoViewInterface;"

/**
 * Adds support to force Litho views to calculate their layout again,
 * such as to show texts that changed after the views were laid out.
 */
val lithoRelayoutPatch = bytecodePatch(
    description = "Adds support to force Litho views to calculate their layout again."
) {
    execute {
        // Verify stubbed classes are not obfuscated.
        classDefBy(COMPONENT_HOST_CLASS)
        classDefBy("Lcom/facebook/litho/TextContent;")

        LithoViewOnMeasureFingerprint.let {
            val (readFlag, clearFlag) = it.instructionMatches.take(2).map { match ->
                match.instruction.getReference<FieldReference>()!!
            }
            if (readFlag != clearFlag) {
                throw PatchException("Could not find the force layout field")
            }
            val forceLayoutField = readFlag

            // Check the views again when attached again without mounting the texts.
            mapOf(
                "onAttachedToWindow" to "onLithoViewAttached",
                "onDetachedFromWindow" to "onLithoViewDetached"
            ).forEach { (methodName, hookName) ->
                it.classDef.methods.firstOrNull { method ->
                    method.name == methodName && method.parameterTypes.isEmpty()
                }?.addInstructions(
                    0,
                    "invoke-static { p0 }, $EXTENSION_CLASS->$hookName(Landroid/view/View;)V"
                ) ?: throw PatchException("Could not find $methodName")
            }

            it.classDef.apply {
                interfaces.add(EXTENSION_LITHO_VIEW_INTERFACE)
                methods.add(
                    ImmutableMethod(
                        type,
                        "patch_forceRelayout",
                        listOf(),
                        "V",
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        null,
                        null,
                        MutableMethodImplementation(2),
                    ).toMutable().apply {
                        addInstructions(
                            0,
                            """
                                const/4 v0, 0x1
                                iput-boolean v0, p0, $forceLayoutField
                                invoke-virtual { p0 }, $type->requestLayout()V
                                return-void
                            """
                        )
                    }
                )
            }
        }

        // Check the texts mounted later, such as texts scrolled into view.
        LithoTextMountFingerprint.let {
            it.method.apply {
                val textIndex = it.instructionMatches.last().index
                val textInstruction = getInstruction<TwoRegisterInstruction>(textIndex)
                val textDrawableType = getInstruction(textIndex)
                    .getReference<FieldReference>()!!.definingClass
                if ("Lcom/facebook/litho/TextContent;" !in classDefBy(textDrawableType).interfaces) {
                    throw PatchException("Could not find the Litho text drawable")
                }

                addInstructions(
                    textIndex + 1,
                    "invoke-static { v${textInstruction.registerB}, v${textInstruction.registerA} }, " +
                            "$EXTENSION_CLASS->onLithoTextMounted(Landroid/graphics/drawable/Drawable;Ljava/lang/CharSequence;)V"
                )
            }
        }
    }
}
