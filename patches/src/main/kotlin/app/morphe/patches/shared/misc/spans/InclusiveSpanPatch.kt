/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

@file:Suppress("SpellCheckingInspection")

package app.morphe.patches.shared.misc.spans

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.SpannableStringBuilderFingerprint
import app.morphe.patches.shared.misc.textcomponent.hookSpannableString
import app.morphe.patches.shared.misc.textcomponent.textComponentPatch
import app.morphe.util.fiveRegisters
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import java.lang.ref.WeakReference

internal const val EXTENSION_SPANS_CLASS = "Lapp/morphe/extension/shared/patches/spans/InclusiveSpanPatch;"

internal const val EXTENSION_FILTER_ARRAY = "[Lapp/morphe/extension/shared/patches/spans/SpanFilter;"

// Registers used in extension helperMethod.
private const val REGISTER_FILTER_CLASS = 0
private const val REGISTER_FILTER_COUNT = 1
private const val REGISTER_FILTER_ARRAY = 2

private lateinit var helperMethodRef: WeakReference<MutableMethod>
private var addSpanFilterCount = 0

fun addSpanFilter(classDescriptor: String) {
    helperMethodRef.get()!!.addInstructions(
        0,
        """
            new-instance v$REGISTER_FILTER_CLASS, $classDescriptor
            invoke-direct { v$REGISTER_FILTER_CLASS }, $classDescriptor-><init>()V
            const/16 v$REGISTER_FILTER_COUNT, ${addSpanFilterCount++}
            aput-object v$REGISTER_FILTER_CLASS, v$REGISTER_FILTER_ARRAY, v$REGISTER_FILTER_COUNT
        """
    )
}

val inclusiveSpanPatch = bytecodePatch(
    description = "Hooks SpannableString setting and filters specific span components.",
) {
    dependsOn(textComponentPatch)

    execute {
        hookSpannableString(
            EXTENSION_SPANS_CLASS,
            "setConversionContext"
        )

        SpannableStringBuilderFingerprint.instructionMatches[1].getMethodCalled().apply {
            val insertIndex = indexOfFirstInstructionReversedOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL && getReference<MethodReference>().toString() ==
                        "Landroid/text/SpannableString;->setSpan(Ljava/lang/Object;III)V"
            }
            replaceInstruction(
                insertIndex,
                "invoke-static { ${fiveRegisters(insertIndex)} }, " +
                        "$EXTENSION_SPANS_CLASS->setSpan(Landroid/text/SpannableString;Ljava/lang/Object;III)V"
            )
        }


        GetSpanTypeFingerprint.let {
            val customCharacterStyle = CustomCharacterStyleFingerprint.classDef.type
            it.method.apply {
                val index = it.instructionMatches.first().index
                val instruction = getInstruction<TwoRegisterInstruction>(index)
                replaceInstruction(
                    index,
                    "instance-of v${instruction.registerA}, v${instruction.registerB}, $customCharacterStyle"
                )
            }
        }

        // Remove dummy filter from extension static field and add the filters included during patching.
        InclusiveSpanFilterFingerprint.let {
            it.method.apply {
                // Add a helper method to avoid finding multiple free registers.
                // This fixes an issue with extension compiled with Android Gradle Plugin 8.3.0+.
                val helperClass = definingClass
                val helperName = "patch_getFilterArray"
                val helperReturnType = EXTENSION_FILTER_ARRAY
                val helperMethod = ImmutableMethod(
                    helperClass,
                    helperName,
                    listOf(),
                    helperReturnType,
                    AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
                    null,
                    null,
                    MutableMethodImplementation(3),
                ).toMutable()
                it.classDef.methods.add(helperMethod)
                helperMethodRef = WeakReference(helperMethod)

                val insertIndex = it.instructionMatches.first().index
                val insertRegister = getInstruction<OneRegisterInstruction>(insertIndex).registerA

                addInstructions(
                    insertIndex,
                    """
                        invoke-static {}, $EXTENSION_SPANS_CLASS->$helperName()$EXTENSION_FILTER_ARRAY
                        move-result-object v$insertRegister
                    """
                )
            }
        }
    }

    finalize {
        helperMethodRef.get()!!.apply {
            addInstruction(
                implementation!!.instructions.size,
                "return-object v$REGISTER_FILTER_ARRAY"
            )

            addInstructions(
                0,
                """
                    const/16 v$REGISTER_FILTER_COUNT, $addSpanFilterCount
                    new-array v$REGISTER_FILTER_ARRAY, v$REGISTER_FILTER_COUNT, $EXTENSION_FILTER_ARRAY
                """
            )
        }
    }
}
