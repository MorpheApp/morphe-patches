/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared.misc.textcomponent

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.SpannableStringBuilderFingerprint
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findFreeRegister
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import java.lang.ref.WeakReference

private lateinit var spannedMethodRef: WeakReference<MutableMethod>
private var spannedIndex = -1
private var spannedRegister = -1
private var spannedContextRegister = -1

val textComponentPatch = bytecodePatch(
    description = "Provides hooks into text components for extension filtering."
) {
    execute {
        SpannableStringBuilderFingerprint.let{
            it.method.apply {
                spannedMethodRef = WeakReference(this)
                spannedIndex = it.instructionMatches.first().index
                spannedRegister = getInstruction<FiveRegisterInstruction>(spannedIndex).registerC
                spannedContextRegister = findFreeRegister(spannedIndex, spannedRegister)

                addInstructionsAtControlFlowLabel(
                    spannedIndex++,
                    "move-object/from16 v$spannedContextRegister, p0"
                )
            }
        }
    }
}

internal fun hookSpannableString(
    classDescriptor: String,
    methodName: String = "onLithoTextLoaded",
    overrideSpan: Boolean = false
) = spannedMethodRef.get()!!.apply {
    if (overrideSpan) {
        addInstructions(
            spannedIndex,
            """
                invoke-static { v$spannedContextRegister, v$spannedRegister }, $classDescriptor->$methodName(Ljava/lang/Object;Ljava/lang/CharSequence;)Ljava/lang/CharSequence;
                move-result-object v$spannedRegister
            """
        )
        spannedIndex += 2
    } else {
        addInstruction(
            spannedIndex++,
            "invoke-static { v$spannedContextRegister, v$spannedRegister }, $classDescriptor->$methodName(Ljava/lang/Object;Ljava/lang/CharSequence;)V"
        )
    }
}