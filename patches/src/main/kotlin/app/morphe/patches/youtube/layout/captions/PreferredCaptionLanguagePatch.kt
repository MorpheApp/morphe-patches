/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3566
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.getReference
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_02_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_10_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.hookVideoStarted
import app.morphe.patches.youtube.shared.startVideoInformerPatch
import app.morphe.patches.youtube.video.information.onCreateHook
import app.morphe.patches.youtube.video.information.videoInformationPatch
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
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
            ListPreference(
                "morphe_preferred_caption_language",
                tag = "app.morphe.extension.youtube.settings.preference.CaptionLanguagePreference",
                entriesKey = null,
                entryValuesKey = null,
            )
        )

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

            interfaces.add(EXTENSION_CAPTION_TRACK_INTERFACE)

            addHelperMethod(
                "patch_getLanguageCode",
                "Ljava/lang/String;",
                smali = """
                    ${getterSmali(isDisableOptionFingerprint, 1)}
                    return-object v0
                """
            )

            addHelperMethod(
                "patch_getVssId",
                "Ljava/lang/String;",
                smali = """
                    ${getterSmali(isAutoTranslatedFingerprint, 0)}
                    return-object v0
                """
            )
        }

        // Source YouTube uses to translate captions of the current audio track,
        // or the default caption track of the current audio track.
        val defaultSourceMethod = CaptionTracksManagerDefaultSourceFingerprint(
            CaptionTrackBuilderBuildFingerprint.method.returnType
        ).method
        val translationSourceMethod = CaptionTracksManagerTranslationSourceFingerprint.let {
            val captionTrackType = CaptionTrackBuilderBuildFingerprint.method.returnType
            val metadataField = it.instructionMatches[0].fieldReference
            val audioTrackField = it.instructionMatches[1].fieldReference
            val sourceIndicesField = it.instructionMatches[5].fieldReference
            val audioCaptionIndicesField = it.instructionMatches[8].fieldReference
            val captionTracksField = it.instructionMatches[10].fieldReference
            val getMethod = it.instructionMatches[11].methodReference
            val convertMethod = it.instructionMatches[12].methodReference

            it.classDef.addHelperMethod(
                "patch_getTranslationSource",
                captionTrackType,
                freeRegisters = 5,
                smali = """
                    iget-object v0, p0, $metadataField
                    iget-object v1, p0, $audioTrackField
                    if-eqz v1, :default
                    iget-object v2, v0, $sourceIndicesField
                    invoke-interface { v2 }, Ljava/util/List;->iterator()Ljava/util/Iterator;
                    move-result-object v2
                    :next
                    invoke-interface { v2 }, Ljava/util/Iterator;->hasNext()Z
                    move-result v3
                    if-eqz v3, :default
                    invoke-interface { v2 }, Ljava/util/Iterator;->next()Ljava/lang/Object;
                    move-result-object v3
                    iget-object v4, v1, $audioCaptionIndicesField
                    invoke-interface { v4, v3 }, Ljava/util/List;->contains(Ljava/lang/Object;)Z
                    move-result v4
                    if-eqz v4, :next
                    check-cast v3, Ljava/lang/Integer;
                    invoke-virtual { v3 }, Ljava/lang/Integer;->intValue()I
                    move-result v3
                    iget-object v4, v0, $captionTracksField
                    invoke-interface { v4, v3 }, $getMethod
                    move-result-object v4
                    check-cast v4, ${convertMethod.parameterTypes.single()}
                    invoke-direct { p0, v4 }, $convertMethod
                    move-result-object v0
                    return-object v0
                    :default
                    invoke-virtual { p0 }, $defaultSourceMethod
                    move-result-object v0
                    return-object v0
                """
            )

            "${it.classDef.type}->patch_getTranslationSource()$captionTrackType"
        }

        DefaultCaptionTrackFingerprint.classDef.apply {
            val tracksManagerField = SubtitleManagerDirectTracksFingerprint(
                CaptionTracksManagerDirectTracksFingerprint.method
            ).instructionMatches[0].fieldReference

            interfaces.add(EXTENSION_SUBTITLE_MANAGER_INTERFACE)

            fun addTracksGetter(name: String, tracksFingerprint: Fingerprint) {
                addHelperMethod(
                    name,
                    "Ljava/util/List;",
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
            fun addSourceGetter(name: String, method: String) = addHelperMethod(
                name,
                EXTENSION_CAPTION_TRACK_INTERFACE,
                smali = """
                    iget-object v0, p0, $tracksManagerField
                    if-eqz v0, :null
                    invoke-virtual { v0 }, $method
                    move-result-object v0
                    :null
                    return-object v0
                """
            )
            addSourceGetter("patch_getCaptionTranslationSource", translationSourceMethod)
            addSourceGetter("patch_getCurrentAudioCaptionSource", defaultSourceMethod.toString())
        }

        // Override the default caption track.

        // Hook the call site and not the default track method itself,
        // so the subtitle manager instance is available.
        val trackType = DefaultCaptionTrackFingerprint.method.returnType

        preferredCaptionsTrackFingerprint().let {
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

        if (is_21_10_or_greater) {
            val setter = SetSubtitleTrackFingerprint.method
            val selector = CaptionSelectionFingerprint(setter).method
            val enumType = selector.parameterTypes[1]
            val defaultReason = captionDefaultSelectionFingerprint(enumType.toString())
                .instructionMatches.last().fieldReference
            SetSubtitleTrackFingerprint.classDef.addHelperMethod(
                "patch_selectCaptionTrack", "Z", listOf(EXTENSION_CAPTION_TRACK_INTERFACE), freeRegisters = 1,
                smali = """
                    sget-object v0, $defaultReason
                    check-cast p1, $trackType
                    invoke-virtual { p0, p1, v0 }, $selector
                    const/4 v0, 0x1
                    return v0
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
