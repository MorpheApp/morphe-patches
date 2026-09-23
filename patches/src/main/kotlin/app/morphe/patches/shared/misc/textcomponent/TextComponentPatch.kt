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
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.newInstance
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.LithoSpannableStringCreationFingerprint
import app.morphe.patches.shared.SpannableStringBuilderFingerprint
import app.morphe.patches.shared.TextComponentConstructorFingerprint
import app.morphe.patches.shared.TextComponentFeatureFlagFingerprint
import app.morphe.patches.shared.TextComponentLookupFingerprint
import app.morphe.patches.shared.misc.litho.context.EXTENSION_CONTEXT_INTERFACE
import app.morphe.patches.shared.misc.litho.context.conversionContextClassDef
import app.morphe.patches.shared.misc.litho.context.conversionContextPatch
import app.morphe.patches.youtube.layout.returnyoutubedislike.TextComponentDataFingerprint
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.cloneParameters
import app.morphe.util.findFreeRegister
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.insertLiteralOverride
import app.morphe.util.numberOfParameterRegistersLogical
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import java.lang.ref.WeakReference

private lateinit var spannedMethodRef: WeakReference<MutableMethod>
private var spannedIndex = -1
private var spannedRegister = -1
private var spannedContextRegister = -1

private lateinit var textComponentLookupMethodRef : WeakReference<MutableMethod>
private var textComponentLookupInsertIndex = -1
private var textComponentLookupConversionContext = -1
private var textComponentLookupConversionContextField : Field? = null
private var textComponentLookupCharSequenceRegister = -1


private lateinit var lithoSpannableStringCreationMethodRef : WeakReference<MutableMethod>
private var lithoSpannableStringCreationInsertIndex = -1
private var lithoSpannableStringCreationConversionContextRegister = -1
private var lithoSpannableStringCreationConversionContextField : String = ""
private var lithoSpannableStringCreationCharSequenceRegister = -1

private const val EXTENSION_CLASS = "Lapp/morphe/extension/shared/patches/TextComponentPatch;"

val textComponentPatch = bytecodePatch(
    description = "Provides hooks into text components for extension filtering."
) {
    dependsOn(
        conversionContextPatch
    )

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

/**
 * Hooks the creation and cached lookup of YouTube Litho text Spans.
 */
internal val lithoSpannableStringPatch = bytecodePatch(
    description = "Provides hooks into Litho text Spans for extension filtering."
) {
    dependsOn(
        conversionContextPatch
    )

    execute {
        // region Hook code for creation and cached lookup of text Spans.

        // Alternatively the hook can be made in the creation of Spans in TextComponentSpec.
        // And it works in all situations except if the likes do not such as disliking.
        // This hook handles all situations, as it's where the created Spans are stored and later reused.

        // Find the field name of the conversion context.
        val textComponentConversionContextField = TextComponentConstructorFingerprint
            .originalClassDef.fields.find {
                it.type == conversionContextClassDef.type
            } ?: throw PatchException("Could not find conversion context field")

        // Old pre 20.40 and lower hook.
        TextComponentLookupFingerprint.let {
            // 21.05 clobbers p0 (this) register.
            // Add additional registers so all parameters including p0 are free to use anywhere in the method.
            it.method.cloneParameters().apply {
                // Find the instruction for creating the text data object.
                val insertIndex = indexOfFirstInstructionOrThrow(
                    newInstance(TextComponentDataFingerprint.originalClassDef.type)
                )
                val charSequenceIndex = indexOfFirstInstructionOrThrow(
                    insertIndex,
                    fieldAccess(
                        opcode = Opcode.IPUT_OBJECT,
                        type = "Ljava/lang/CharSequence;"
                    )
                )
                val charSequenceRegister = getInstruction<TwoRegisterInstruction>(charSequenceIndex).registerA
                val conversionContext = findFreeRegister(insertIndex, charSequenceRegister)

                textComponentLookupMethodRef = WeakReference(this)
                textComponentLookupInsertIndex = insertIndex
                textComponentLookupConversionContext = conversionContext
                textComponentLookupConversionContextField = textComponentConversionContextField
                textComponentLookupCharSequenceRegister = charSequenceRegister

                addInstructionsAtControlFlowLabel(
                    textComponentLookupInsertIndex,
                    """
                        # Copy conversion context.
                        move-object/from16 v$textComponentLookupConversionContext, p0
                        iget-object v$textComponentLookupConversionContext, v$textComponentLookupConversionContext, $textComponentLookupConversionContextField
                    """
                )

                textComponentLookupInsertIndex += 2
            }
        }

        LithoSpannableStringCreationFingerprint.let {
            val conversionContextField = it.classDef.type +
                    "->" + textComponentConversionContextField.name +
                    ":" + textComponentConversionContextField.type

            // 21.05+ clobbers p0 and must clone to preserve it.
            it.method.cloneParameters().apply {
                // Must offset match indexes since cloning adds additional move instructions.
                val insertIndex = it.instructionMatches[1].index + numberOfParameterRegistersLogical
                val charSequenceRegister = getInstruction<FiveRegisterInstruction>(insertIndex).registerD
                val conversionContextRegister = findFreeRegister(insertIndex, charSequenceRegister)

                lithoSpannableStringCreationMethodRef = WeakReference(this)
                lithoSpannableStringCreationInsertIndex = insertIndex
                lithoSpannableStringCreationConversionContextRegister = conversionContextRegister
                lithoSpannableStringCreationConversionContextField = conversionContextField
                lithoSpannableStringCreationCharSequenceRegister = charSequenceRegister

                addInstructions(
                    lithoSpannableStringCreationInsertIndex,
                    """
                        move-object/from16 v$lithoSpannableStringCreationConversionContextRegister, p0
                        iget-object v$lithoSpannableStringCreationConversionContextRegister, v$lithoSpannableStringCreationConversionContextRegister, $lithoSpannableStringCreationConversionContextField
                    """
                )

                lithoSpannableStringCreationInsertIndex += 2
            }
        }

        // Hook new litho text creation code.
        TextComponentFeatureFlagFingerprint.matchAll().forEach {
            it.method.insertLiteralOverride(
                it.instructionMatches.first().index,
                "$EXTENSION_CLASS->useNewLithoTextCreation(Z)Z"
            )
        }

        // endregion
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
                invoke-static { v$spannedContextRegister, v$spannedRegister }, $classDescriptor->$methodName(${EXTENSION_CONTEXT_INTERFACE}Ljava/lang/CharSequence;)Ljava/lang/CharSequence;
                move-result-object v$spannedRegister
            """
        )
        spannedIndex += 2
    } else {
        addInstruction(
            spannedIndex++,
            "invoke-static { v$spannedContextRegister, v$spannedRegister }, $classDescriptor->$methodName(${EXTENSION_CONTEXT_INTERFACE}Ljava/lang/CharSequence;)V"
        )
    }
}

internal fun hookLithoSpannableString(
    classDescriptor: String,
    methodName: String = "onLithoTextLoaded",
) {
    textComponentLookupMethodRef.get()!!.addInstructionsAtControlFlowLabel(
        textComponentLookupInsertIndex,
        """
            invoke-static { v$textComponentLookupConversionContext, v$textComponentLookupCharSequenceRegister }, $classDescriptor->$methodName(${EXTENSION_CONTEXT_INTERFACE}Ljava/lang/CharSequence;)Ljava/lang/CharSequence;
            move-result-object v$textComponentLookupCharSequenceRegister

            :ignore
            nop
        """
    )

    textComponentLookupInsertIndex += 3

    lithoSpannableStringCreationMethodRef.get()!!.addInstructions(
        lithoSpannableStringCreationInsertIndex,
        """
            invoke-static { v$lithoSpannableStringCreationConversionContextRegister, v$lithoSpannableStringCreationCharSequenceRegister }, $classDescriptor->$methodName(${EXTENSION_CONTEXT_INTERFACE}Ljava/lang/CharSequence;)Ljava/lang/CharSequence;
            move-result-object v$lithoSpannableStringCreationCharSequenceRegister
        """
    )

    lithoSpannableStringCreationInsertIndex += 2
}
