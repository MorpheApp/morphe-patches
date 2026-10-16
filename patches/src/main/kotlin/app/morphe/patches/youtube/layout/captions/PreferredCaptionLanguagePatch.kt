/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3566
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_02_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.hookVideoStarted
import app.morphe.patches.youtube.shared.startVideoInformerPatch
import app.morphe.patches.youtube.video.information.onCreateHook
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.Reference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/PreferredCaptionLanguagePatch;"
private const val EXTENSION_SUBTITLE_MANAGER_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/PreferredCaptionLanguagePatch$SubtitleManagerInterface;"
private const val EXTENSION_CAPTION_TRACK_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/PreferredCaptionLanguagePatch$CaptionTrackInterface;"

internal val preferredCaptionLanguagePatch = bytecodePatch(
    description = "Adds an option to automatically select captions in your preferred language " +
            "(provider subtitles first, then auto-translated).",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        startVideoInformerPatch,
        versionCheckPatch,
        videoInformationPatch
    )

    execute {
        settingsMenuCaptionGroup.add(
            ListPreference("morphe_preferred_caption_language")
        )

        val labelField = CaptionTrackBuilderBuildFingerprint.instructionMatches[0]
            .instruction.getReference<FieldReference>()!!
        val urlField = CaptionTracksManagerAutoTranslateTracksFingerprint.instructionMatches[0]
            .instruction.getReference<FieldReference>()!!

        val setLanguageCodeMethod = CaptionTrackBuilderSetterFingerprint("Null languageCode").method
        val setUrlMethod = CaptionTrackBuilderSetterFingerprint("Null url").method
        val setVssIdMethod = CaptionTrackBuilderSetterFingerprint("Null vssId").method

        // Add interfaces and helper methods to allow extension code to call obfuscated code.

        val isDisableOptionFingerprint: Fingerprint
        val isAutoTranslatedFingerprint: Fingerprint
        if (is_21_02_or_greater) {
            isDisableOptionFingerprint = CaptionTrackIsDisableOptionFingerprint
            isAutoTranslatedFingerprint = CaptionTrackIsAutoTranslatedFingerprint
        } else {
            isDisableOptionFingerprint = CaptionTrackIsDisableOptionLegacyFingerprint
            isAutoTranslatedFingerprint = CaptionTrackIsAutoTranslatedLegacyFingerprint
        }

        isDisableOptionFingerprint.classDef.apply {
            /**
             * Field access or getter call of the matched instruction.
             */
            fun getterSmali(fingerprint: Fingerprint, matchIndex: Int): String {
                val reference = fingerprint.instructionMatches[matchIndex].instruction.getReference<Reference>()
                return if (is_21_02_or_greater) {
                    "iget-object v0, p0, $reference"
                } else {
                    """
                        invoke-virtual { p0 }, $reference
                        move-result-object v0
                    """
                }
            }

            if (!interfaces.contains(EXTENSION_CAPTION_TRACK_INTERFACE)) {
                interfaces.add(EXTENSION_CAPTION_TRACK_INTERFACE)
            }

            addHelperMethod(
                name = "patch_getLanguageCode",
                returnType = "Ljava/lang/String;",
                smali = """
                    ${getterSmali(isDisableOptionFingerprint, 1)}
                    return-object v0
                """
            )

            addHelperMethod(
                name = "patch_getVssId",
                returnType = "Ljava/lang/String;",
                smali = """
                    ${getterSmali(isAutoTranslatedFingerprint, 0)}
                    return-object v0
                """
            )

            // 20.x tracks are an abstract class, and the url field is in the subclass.
            addHelperMethod(
                name = "patch_getUrl",
                returnType = "Ljava/lang/String;",
                smali = """
                    move-object v0, p0
                    check-cast v0, ${urlField.definingClass}
                    iget-object v0, v0, $urlField
                    return-object v0
                """
            )

            addHelperMethod(
                name = "patch_copyTranslation",
                returnType = EXTENSION_CAPTION_TRACK_INTERFACE,
                parameters = listOf(
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

        DefaultCaptionTrackFingerprint.classDef.apply {
            val tracksManagerField = SubtitleManagerDirectTracksFingerprint(
                CaptionTracksManagerDirectTracksFingerprint.method
            ).instructionMatches[0].instruction.getReference<FieldReference>()!!

            interfaces.add(EXTENSION_SUBTITLE_MANAGER_INTERFACE)

            fun addTracksGetter(name: String, tracksFingerprint: Fingerprint) {
                addHelperMethod(
                    name = name,
                    returnType = "Ljava/util/List;",
                    smali = """
                        iget-object v0, p0, $tracksManagerField
                        if-eqz v0, :null
                        invoke-virtual { v0 }, ${tracksFingerprint.method}
                        move-result-object v0
                        :null
                        return-object v0
                    """
                )
            }

            addTracksGetter("patch_getDirectCaptionTracks", CaptionTracksManagerDirectTracksFingerprint)
            addTracksGetter("patch_getAutoTranslateCaptionTracks", CaptionTracksManagerAutoTranslateTracksFingerprint)
        }

        // Override the default caption track.

        // Hook the call site and not the default track method itself,
        // so the subtitle manager instance is available.
        val trackType = DefaultCaptionTrackFingerprint.method.returnType

        Fingerprint(
            definingClass = DefaultCaptionTrackFingerprint.classDef.type,
            filters = listOf(
                methodCall(reference = DefaultCaptionTrackFingerprint.method),
                opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
            )
        ).match(DefaultCaptionTrackFingerprint.classDef).let {
            val subtitleManagerRegister = it.instructionMatches[0]
                .getInstruction<FiveRegisterInstruction>().registerC
            val moveResultIndex = it.instructionMatches[1].index
            val trackRegister = it.instructionMatches[1].getInstruction<OneRegisterInstruction>().registerA

            it.method.addInstructions(
                moveResultIndex + 1,
                """
                    invoke-static { v$subtitleManagerRegister, v$trackRegister }, $EXTENSION_CLASS->getPreferredCaptionTrack($EXTENSION_SUBTITLE_MANAGER_INTERFACE$EXTENSION_CAPTION_TRACK_INTERFACE)$EXTENSION_CAPTION_TRACK_INTERFACE
                    move-result-object v$trackRegister
                    check-cast v$trackRegister, $trackType
                """
            )
        }

        SetSubtitleTrackFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p0, p1, p2 }, $EXTENSION_CLASS->onSetSubtitleTrack($EXTENSION_SUBTITLE_MANAGER_INTERFACE${EXTENSION_CAPTION_TRACK_INTERFACE}Ljava/lang/Enum;)$EXTENSION_CAPTION_TRACK_INTERFACE
                move-result-object p1
                check-cast p1, $trackType
            """
        )

        onCreateHook(EXTENSION_CLASS, "newVideoStarted")

        hookVideoStarted("$EXTENSION_CLASS->videoInformationLoaded()V")
    }
}
