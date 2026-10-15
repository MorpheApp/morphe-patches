/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3754
 * Adapted from https://github.com/itolkien/morphe-universal-captions
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/AutoTranslateCaptionsPatch;"
private const val EXTENSION_CAPTION_TRACK_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/AutoTranslateCaptionsPatch$CaptionTrackInterface;"

internal val autoTranslateCaptionsPatch = bytecodePatch(
    description = "Adds more native auto-translate languages and sorts their displayed names.",
) {
    dependsOn(preferredCaptionLanguagePatch)

    execute {
        val labelField = CaptionTrackBuilderBuildFingerprint.instructionMatches[0]
            .instruction.getReference<FieldReference>()!!
        val urlField = CaptionTracksManagerAutoTranslateTracksFingerprint.instructionMatches[0]
            .instruction.getReference<FieldReference>()!!

        val setLanguageCodeMethod = CaptionTrackBuilderSetterFingerprint("Null languageCode").method
        val setUrlMethod = CaptionTrackBuilderSetterFingerprint("Null url").method
        val setVssIdMethod = CaptionTrackBuilderSetterFingerprint("Null vssId").method

        // Add interface and helper methods to allow extension code to call obfuscated code.
        CaptionTrackIsDisableOptionFingerprint.classDef.apply {
            interfaces.add(EXTENSION_CAPTION_TRACK_INTERFACE)

            // 20.x tracks are an abstract class, and the url field is in the subclass.
            addHelperMethod(
                "patch_getUrl",
                "Ljava/lang/String;",
                """
                    move-object v0, p0
                    check-cast v0, ${urlField.definingClass}
                    iget-object v0, v0, $urlField
                    return-object v0
                """
            )

            addHelperMethod(
                "patch_copyTranslation",
                EXTENSION_CAPTION_TRACK_INTERFACE,
                """
                    new-instance v0, ${CaptionTrackBuilderBuildFingerprint.classDef.type}
                    invoke-direct { v0, p0 }, ${CaptionTrackBuilderCopyConstructorFingerprint.method}
                    invoke-virtual { v0, p1 }, $setLanguageCodeMethod
                    invoke-virtual { v0, p2 }, $setUrlMethod
                    invoke-virtual { v0, p3 }, $setVssIdMethod
                    iput-object p4, v0, $labelField
                    invoke-virtual { v0 }, ${CaptionTrackBuilderBuildFingerprint.method}
                    move-result-object v0
                    return-object v0
                """,
                listOf(
                    "Ljava/lang/String;",
                    "Ljava/lang/String;",
                    "Ljava/lang/String;",
                    "Ljava/lang/CharSequence;"
                )
            )
        }

        CaptionTracksManagerAutoTranslateTracksFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).forEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructionsAtControlFlowLabel(
                    index,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->addTranslationLanguages(Ljava/util/List;)Ljava/util/List;
                        move-result-object v$register
                    """
                )
            }
        }
    }
}
