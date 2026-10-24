/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3566
 * https://github.com/MorpheApp/morphe-patches/pull/3754
 * Adapted from https://github.com/itolkien/morphe-universal-captions
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.matchAllMethodIndicesForEach
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/AutoTranslateCaptionsPatch;"
private const val EXTENSION_CAPTION_TRACK_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/AutoTranslateCaptionsPatch$CaptionTrackInterface;"
private const val PREFERRED_CAPTION_TRACK_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/PreferredCaptionLanguagePatch$CaptionTrackInterface;"

internal val autoTranslateCaptionsPatch = bytecodePatch(
    description = "Adds an option to add more auto-translate languages and sort the languages by name."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        preferredCaptionLanguagePatch
    )

    execute {
        settingsMenuCaptionGroup.add(
            SwitchPreference("morphe_auto_translate_additional_languages", summary = true)
        )

        val labelField = CaptionTrackBuilderBuildFingerprint.instructionMatches[0]
            .instruction.getReference<FieldReference>()!!
        val urlField = CaptionTracksManagerAutoTranslateTracksFingerprint.instructionMatches[0]
            .instruction.getReference<FieldReference>()!!

        val setLanguageCodeMethod = CaptionTrackBuilderSetterFingerprint("Null languageCode").method
        val setUrlMethod = CaptionTrackBuilderSetterFingerprint("Null url").method
        val setVssIdMethod = CaptionTrackBuilderSetterFingerprint("Null vssId").method

        // Add interface and helper methods to allow extension code to call obfuscated code.
        mutableClassDefBy(CaptionTrackBuilderBuildFingerprint.method.returnType).apply {
            interfaces.add(EXTENSION_CAPTION_TRACK_INTERFACE)

            // 20.x tracks are an abstract class, and the url field is in the subclass.
            addHelperMethod(
                "patch_getUrl",
                "Ljava/lang/String;",
                smali = """
                    move-object v0, p0
                    check-cast v0, ${urlField.definingClass}
                    iget-object v0, v0, $urlField
                    return-object v0
                """
            )

            addHelperMethod(
                "patch_copyTranslation",
                EXTENSION_CAPTION_TRACK_INTERFACE,
                listOf(
                    "Ljava/lang/String;",
                    "Ljava/lang/String;",
                    "Ljava/lang/String;",
                    "Ljava/lang/CharSequence;"
                ),
                smali = """
                    new-instance v0, ${CaptionTrackBuilderBuildFingerprint.classDef.type}
                    invoke-direct { v0, p0 }, ${CaptionTrackBuilderCopyConstructorFingerprint.method}
                    invoke-virtual { v0, p1 }, $setLanguageCodeMethod
                    invoke-virtual { v0, p2 }, $setUrlMethod
                    invoke-virtual { v0, p3 }, $setVssIdMethod
                    iput-object p4, v0, $labelField
                    invoke-virtual { v0 }, ${CaptionTrackBuilderBuildFingerprint.method}
                    move-result-object v0
                    return-object v0
                """
            )
        }

        val directTracks = CaptionTracksManagerDirectTracksFingerprint.method
        val autoTracks = CaptionTracksManagerAutoTranslateTracksFingerprint.method
        // Added by the preferred caption language patch.
        val translationSource = "${directTracks.definingClass}->patch_getTranslationSource()" +
                CaptionTrackBuilderBuildFingerprint.method.returnType
        // Keep both original getters intact. The adapter calls them once and cannot call itself.
        CaptionTracksManagerDirectTracksFingerprint.classDef.addHelperMethod(
            "patch_getAutoTranslateTracks", "Ljava/util/List;", freeRegisters = 3,
            smali = """
                invoke-virtual { p0 }, $autoTracks
                move-result-object v0
                invoke-virtual { p0 }, $directTracks
                move-result-object v1
                invoke-virtual { p0 }, $translationSource
                move-result-object v2
                invoke-static { v0, v1, v2 }, $EXTENSION_CLASS->restoreTranslationTracks(Ljava/util/List;Ljava/util/List;$PREFERRED_CAPTION_TRACK_INTERFACE)Ljava/util/List;
                move-result-object v0
                return-object v0
            """
        )

        val adapter = "${directTracks.definingClass}->patch_getAutoTranslateTracks()Ljava/util/List;"
        // Route the UI getter and the existing preferred-language interface getter through it.
        AutoTranslateTracksCallFingerprint(autoTracks).matchAllMethodIndicesForEach { index ->
            val register = getInstruction(index).registersUsed[0]
            replaceInstruction(index, "invoke-virtual/range { v$register .. v$register }, $adapter")
        }

        // Reuse YouTube's localized option factory and preserve its remaining native checks.
        CaptionTracksManagerTranslationCountFingerprint.apply {
            val countMatch = instructionMatches[1]
            val count = countMatch.getInstruction<OneRegisterInstruction>().registerA
            val result = instructionMatches.last().getInstruction<OneRegisterInstruction>().registerA
            method.addInstructions(
                countMatch.index + 1,
                """
                    invoke-static { v$count, v$result }, $EXTENSION_CLASS->getTranslationCount(ILjava/util/List;)I
                    move-result v$count
                """
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
