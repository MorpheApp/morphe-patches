/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.video.quality

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.VideoQualityChangedFingerprint
import app.morphe.patches.youtube.video.information.EXTENSION_VIDEO_QUALITY_INTERFACE
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findFieldFromToString
import app.morphe.util.findFreeRegister
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getReference
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/playback/quality/RememberVideoQualityPatch;"

val rememberVideoQualityPatch = bytecodePatch {
    dependsOn(
        sharedExtensionPatch,
        videoInformationPatch,
        playerTypeHookPatch,
        settingsPatch,
        versionCheckPatch,
    )

    execute {
        settingsMenuVideoQualityGroup.addAll(listOf(
            ListPreference(
                key = "morphe_video_quality_default_mobile",
                entriesKey = "morphe_video_quality_default_entries",
                entryValuesKey = "morphe_video_quality_default_entry_values"
            ),
            ListPreference(
                key = "morphe_video_quality_default_wifi",
                entriesKey = "morphe_video_quality_default_entries",
                entryValuesKey = "morphe_video_quality_default_entry_values"
            ),
            SwitchPreference("morphe_remember_video_quality_last_selected", summary = true),

            ListPreference(
                key = "morphe_shorts_quality_default_mobile",
                entriesKey = "morphe_shorts_quality_default_entries",
                entryValuesKey = "morphe_shorts_quality_default_entry_values",
            ),
            ListPreference(
                key = "morphe_shorts_quality_default_wifi",
                entriesKey = "morphe_shorts_quality_default_entries",
                entryValuesKey = "morphe_shorts_quality_default_entry_values"
            ),
            SwitchPreference("morphe_remember_shorts_quality_last_selected", summary = true),
            SwitchPreference("morphe_remember_video_quality_last_selected_toast", summary = true)
        ))

        val initialResolutionField = PlaybackStartParametersToStringFingerprint.method
                .findFieldFromToString(FIXED_RESOLUTION_STRING)

        // Inject a call to override initial video quality.
        getPlaybackStartParametersConstructorFingerprint(initialResolutionField).let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = getInstruction<TwoRegisterInstruction>(index).registerA

                addInstructions(
                    index,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->getInitialVideoQuality(Lj$/util/Optional;)Lj$/util/Optional;
                        move-result-object v$register
                    """
                )
            }
        }

        // Turn off the YouTube automatic quality selection when the video starts,
        // so the initial video quality is used as a fixed quality.
        InitialVideoQualityFeatureFlagFingerprint.matchAll().forEach {
            it.method.insertLiteralOverride(
                it.instructionMatches.first().index,
                "$EXTENSION_CLASS->useInitialVideoQuality(Z)Z"
            )
        }

        // Send the minimum and maximum quality with the SABR requests,
        // so the server cannot choose a quality outside the quality of the player.
        SabrQualityConstraintFeatureFlagFingerprint.matchAllOrNull()?.forEach {
            it.method.insertLiteralOverride(
                it.instructionMatches.first().index,
                "$EXTENSION_CLASS->sendQualityConstraintToServer(Z)Z"
            )
        }

        // Use the 'Advanced' video quality setting instead of the 'Video quality preferences'
        // (Auto, Higher picture quality, Data saver), both on the device and with the SABR requests.
        VideoQualitySettingEnumFingerprint.let {
            val videoQualitySettingType = it.classDef.type
            val advancedMenuField = it.instructionMatches.last().instruction.getReference<FieldReference>()!!

            val videoQualitySettingMethod = getVideoQualitySettingFingerprint(videoQualitySettingType).method

            videoQualitySettingMethod.apply {
                val register = findFreeRegister(0)

                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static { }, $EXTENSION_CLASS->useAdvancedVideoQualitySetting()Z
                        move-result v$register
                        if-eqz v$register, :original
                        sget-object v$register, $advancedMenuField
                        return-object v$register
                        :original
                        nop
                    """
                )
            }

            // The initial video quality is only used when the video starts. YouTube chooses the quality
            // again while the video plays, and without a quality selected in the quality menu it is
            // then chosen automatically, such as 720p for the size of the player.
            // The default quality is used as a fixed quality for these choices too.
            getVideoQualityConstraintFingerprint(videoQualitySettingMethod.toString()).method.apply {
                val constraintType = returnType
                val fixedConstraintMethod = mutableClassDefBy(constraintType).methods.single { method ->
                    method.returnType == constraintType && method.parameterTypes.map { it.toString() } == listOf("I")
                }

                findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).forEach { index ->
                    val register = getInstruction<OneRegisterInstruction>(index).registerA
                    // The returned register can be above v15, which a non range invoke can not use.
                    val qualityRegister = findFreeRegister(index, register)
                    val constraintRegister = findFreeRegister(index, register, qualityRegister)

                    addInstructionsAtControlFlowLabel(
                        index,
                        """
                            invoke-static { }, $EXTENSION_CLASS->getFixedVideoQuality()I
                            move-result v$qualityRegister
                            if-ltz v$qualityRegister, :automatic_quality
                            move-object/from16 v$constraintRegister, v$register
                            invoke-virtual { v$constraintRegister, v$qualityRegister }, $fixedConstraintMethod
                            move-result-object v$register
                            :automatic_quality
                            nop
                        """
                    )
                }
            }
        }

        // Inject a call to remember the selected quality for Shorts.
        VideoQualityItemOnClickFingerprint.method.addInstruction(
            0,
            "invoke-static { p3 }, $EXTENSION_CLASS->userChangedShortsQuality(I)V"
        )

        // Inject a call to remember the user selected quality for regular videos.
        // The quality object is used, as its name is needed to get the fixed resolution.
        VideoQualityChangedFingerprint.let {
            it.method.apply {
                val qualityRegister = getInstruction<OneRegisterInstruction>(
                    it.instructionMatches[3].index
                ).registerA
                val constructorIndex = it.instructionMatches.last().index

                addInstruction(
                    constructorIndex + 1,
                    "invoke-static/range { v$qualityRegister .. v$qualityRegister }, " +
                            "$EXTENSION_CLASS->userChangedQuality($EXTENSION_VIDEO_QUALITY_INTERFACE)V",
                )
            }
        }

        // If this flag is enabled, Shorts restart whenever the quality changes.
        // The restart uses the initial quality of the Short, so the quality selected by the user is lost.
        listOf(
            ShortsQualityChangeObserverPrimaryFeatureFlagFingerprint,
            ShortsQualityChangeObserverSecondaryFeatureFlagFingerprint
        ).forEach { fingerprint ->
            fingerprint.matchAll().forEach {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index, false
                )
            }
        }
    }
}
