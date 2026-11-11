/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3579
 * https://github.com/MorpheApp/morphe-patches/pull/3673
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.layout.font

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchDefinition
import app.morphe.patcher.patch.classDefForEach
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val EXTENSION_CLASS = "Lapp/morphe/extension/shared/patches/CustomFontPatch;"

/**
 * @param applicationInitFingerprint Application onCreate method.
 */
fun baseCustomFontPatch(
    preferenceScreen: BasePreferenceScreen.Screen,
    applicationInitFingerprint: Fingerprint,
    block: PatchDefinition.() -> Unit,
    executeBlock: () -> Unit = {},
) = patch(
    name = "Custom font",
    description = "Adds an option to show the app with the device system font or a custom TTF " +
            "or OTF font file instead of YouTube Sans.",
) {
    block()

    execute {
        preferenceScreen.addPreferences(
            noTitleUnsortedPreferenceCategory(
                ListPreference(
                    key = "morphe_custom_font_type",
                    tag = "app.morphe.extension.shared.settings.preference.CustomFontTypePreference"
                ),
                NonInteractivePreference(
                    key = "morphe_custom_font_file",
                    tag = "app.morphe.extension.shared.settings.preference.CustomFontFilePreference",
                    selectable = true
                )
            )
        )

        // Most text uses the platform default typeface, so the default typefaces
        // must be replaced with the custom font before any view is created.
        applicationInitFingerprint.method.addInstruction(
            0,
            "invoke-static { p0 }, $EXTENSION_CLASS->applyCustomFont(Landroid/content/Context;)V"
        )

        // YouTube Sans is loaded from bundled fonts and does not use the platform default typeface.

        FontResourceLoaderFingerprint.method.apply {
            val callbackSuccessMethod = Fingerprint(
                definingClass = parameterTypes[4].toString(),
                returnType = "V",
                parameters = listOf("Landroid/graphics/Typeface;"),
                custom = { method, _ ->
                    !AccessFlags.ABSTRACT.isSet(method.accessFlags)
                }
            ).originalMethod

            addInstructionsWithLabels(
                0,
                """
                    invoke-static { p0, p1, p3 }, $EXTENSION_CLASS->getCustomTypeface(Landroid/content/Context;II)Landroid/graphics/Typeface;
                    move-result-object v0
                    if-eqz v0, :original
                    if-eqz p4, :callback_done
                    invoke-virtual { p4, v0 }, $callbackSuccessMethod
                    :callback_done
                    return-object v0
                    :original
                    nop
                """
            )
        }

        FontEnumTypefaceFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static { p0, p2 }, $EXTENSION_CLASS->getCustomTypeface(Ljava/lang/Enum;I)Landroid/graphics/Typeface;
                move-result-object v0
                if-eqz v0, :original
                return-object v0
                :original
                nop
            """
        )

        // Text without a font of its own uses the default typefaces, whose fields are final and cannot be
        // replaced, so the app reads them through the extension. The system also uses the default typeface
        // for a font family that it does not have, so the app creates the typefaces of font families
        // through the extension too.
        val typefaceType = "Landroid/graphics/Typeface;"
        val defaultTypefaceGetters = mapOf(
            "DEFAULT" to "getDefaultTypeface",
            "DEFAULT_BOLD" to "getDefaultBoldTypeface",
            "SANS_SERIF" to "getSansSerifTypeface",
        )

        fun Instruction.isDefaultTypefaceRead() = opcode == Opcode.SGET_OBJECT &&
                ((this as ReferenceInstruction).reference as FieldReference).let {
                    it.definingClass == typefaceType && it.name in defaultTypefaceGetters
                }

        fun Instruction.isFamilyTypefaceCreate() =
                (opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE) &&
                ((this as ReferenceInstruction).reference as MethodReference).let {
                    it.definingClass == typefaceType && it.name == "create"
                            && it.parameterTypes == listOf("Ljava/lang/String;", "I")
                }

        val typefaceClasses = mutableListOf<String>()
        classDefForEach { classDef ->
            // The extension uses the original typefaces to create the fonts.
            if (classDef.type != EXTENSION_CLASS && classDef.methods.any { method ->
                    method.implementation?.instructions?.any {
                        it.isDefaultTypefaceRead() || it.isFamilyTypefaceCreate()
                    } == true
                }
            ) {
                typefaceClasses += classDef.type
            }
        }

        typefaceClasses.forEach { type ->
            mutableClassDefBy(type).methods.forEach { method ->
                val instructions = method.implementation?.instructions ?: return@forEach
                instructions.withIndex().filter { (_, instruction) ->
                    instruction.isDefaultTypefaceRead() || instruction.isFamilyTypefaceCreate()
                }.map { it.index }.reversed().forEach { index ->
                    val instruction = method.getInstruction(index)
                    val reference = (instruction as ReferenceInstruction).reference
                    if (reference is FieldReference) {
                        val register = (instruction as OneRegisterInstruction).registerA
                        method.replaceInstruction(
                            index,
                            "invoke-static { }, $EXTENSION_CLASS->${defaultTypefaceGetters.getValue(reference.name)}()$typefaceType"
                        )
                        method.addInstruction(index + 1, "move-result-object v$register")
                    } else {
                        val registers = if (instruction is RegisterRangeInstruction) {
                            "/range { v${instruction.startRegister} .. v${instruction.startRegister + 1} }"
                        } else {
                            (instruction as FiveRegisterInstruction).let { " { v${it.registerC}, v${it.registerD} }" }
                        }
                        method.replaceInstruction(
                            index,
                            "invoke-static$registers, $EXTENSION_CLASS->createTypeface(Ljava/lang/String;I)$typefaceType"
                        )
                    }
                }
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)

        executeBlock()
    }
}
