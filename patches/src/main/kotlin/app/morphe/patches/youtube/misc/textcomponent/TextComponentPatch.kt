/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.textcomponent

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.youtube.shared.SPANNABLE_STRING_REFERENCE
import app.morphe.patches.youtube.shared.SpannableStringBuilderFingerprint
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.lang.ref.WeakReference

private lateinit var spannedMethodRef: WeakReference<MutableMethod>
private var spannedIndex = -1
private var spannedRegister = -1
private var spannedContextRegister = -1

private lateinit var textComponentMethodRef: WeakReference<MutableMethod>
private var textComponentIndex = -1
private var textComponentRegister = -1
private var textComponentContextRegister = -1

val textComponentPatch = bytecodePatch(
    description = "Provides hooks into text components for extension filtering."
) {
    execute {
        SpannableStringBuilderFingerprint.let{
            it.method.apply {
                spannedMethodRef = WeakReference(this)
                spannedIndex = it.instructionMatches.first().index
                spannedRegister = getInstruction<FiveRegisterInstruction>(spannedIndex).registerC
                spannedContextRegister = getInstruction<OneRegisterInstruction>(
                    spannedIndex + 1
                ).registerA

                replaceInstruction(
                    spannedIndex,
                    "move-object/from16 v$spannedContextRegister, p0"
                )
                addInstruction(
                    ++spannedIndex,
                    "invoke-static { v$spannedRegister }, $SPANNABLE_STRING_REFERENCE"
                )
            }
        }

        // FIXME: This is currently unused/untested and needs major cleanup.
        TextComponentContextFingerprint.let {
            it.method.apply {
                textComponentMethodRef = WeakReference(this)
                val conversionContextFieldIndex = it.instructionMatches.first().index - 1
//                indexOfFirstInstructionOrThrow {
//                    getReference<FieldReference>()?.type == "Ljava/util/Map;"
//                } - 1
                val conversionContextFieldReference =
                    getInstruction<ReferenceInstruction>(conversionContextFieldIndex).reference

                // ~ YouTube 19.32.xx
                val legacyCharSequenceIndex = indexOfFirstInstruction {
                    getReference<FieldReference>()?.type == "Ljava/util/BitSet;"
                } - 1
                val charSequenceIndex = indexOfFirstInstruction {
                    val reference = getReference<MethodReference>()
                    opcode == Opcode.INVOKE_VIRTUAL &&
                            reference?.returnType == "V" &&
                            reference.parameterTypes.firstOrNull() == "Ljava/lang/CharSequence;"
                }

                val insertIndex: Int

                if (legacyCharSequenceIndex > -2) {
                    textComponentRegister =
                        getInstruction<TwoRegisterInstruction>(legacyCharSequenceIndex).registerA
                    insertIndex = legacyCharSequenceIndex - 1
                } else if (charSequenceIndex > -1) {
                    textComponentRegister =
                        getInstruction<FiveRegisterInstruction>(charSequenceIndex).registerD
                    insertIndex = charSequenceIndex
                } else {
                    throw PatchException("Could not find insert index")
                }

                textComponentContextRegister = getInstruction<TwoRegisterInstruction>(
                    indexOfFirstInstructionOrThrow(insertIndex, Opcode.IGET_OBJECT)
                ).registerA

                // FIXME this needs an update for 21.x and may no longer be needed.
//                addInstructions(
//                    insertIndex,
//                    """
//                        move-object/from16 v$textComponentContextRegister, p0
//                        iget-object v$textComponentContextRegister, v$textComponentContextRegister, $conversionContextFieldReference
//                    """
//                )
                textComponentIndex = insertIndex + 2
            }
        }
    }
}

internal fun hookSpannableString(
    classDescriptor: String,
    methodName: String
) = spannedMethodRef.get()!!.apply {
    addInstructions(
        spannedIndex,
        """
            invoke-static { v$spannedContextRegister, v$spannedRegister }, $classDescriptor->$methodName(Ljava/lang/Object;Ljava/lang/CharSequence;)Ljava/lang/CharSequence;
            move-result-object v$spannedRegister
        """
    )
}

@Suppress("unused")
internal fun hookTextComponent(
    classDescriptor: String,
    methodName: String = "onLithoTextLoaded"
) = textComponentMethodRef.get()!!.apply {
    addInstructions(
        textComponentIndex,
        """
            invoke-static { v$textComponentContextRegister, v$textComponentRegister }, $classDescriptor->$methodName(Ljava/lang/Object;Ljava/lang/CharSequence;)Ljava/lang/CharSequence;
            move-result-object v$textComponentRegister
        """
    )
    textComponentIndex += 2
}
