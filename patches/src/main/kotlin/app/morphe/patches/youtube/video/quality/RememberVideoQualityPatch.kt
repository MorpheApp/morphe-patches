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
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.fix.proto.fixProtoLibraryPatch
import app.morphe.patches.shared.misc.fix.proto.parseByteArrayMethodRef
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.VideoQualityChangedFingerprint
import app.morphe.patches.youtube.shared.hookVideoIntent
import app.morphe.patches.youtube.shared.openVideoIntentPatch
import app.morphe.patches.youtube.video.information.EXTENSION_VIDEO_QUALITY_INTERFACE
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findFieldFromToString
import app.morphe.util.findFreeRegister
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getFreeRegisterProvider
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/playback/quality/RememberVideoQualityPatch;"

val rememberVideoQualityPatch = bytecodePatch {
    dependsOn(
        sharedExtensionPatch,
        videoInformationPatch,
        playerTypeHookPatch,
        settingsPatch,
        versionCheckPatch,
        openVideoIntentPatch,
        fixProtoLibraryPatch,
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

        // Use the 'Advanced' video quality setting instead of the 'Video quality preferences'
        // (Auto, Higher picture quality, Data saver), both on the device and with the SABR requests.
        val videoQualitySettingMethod = VideoQualitySettingEnumFingerprint.let {
            val videoQualitySettingType = it.classDef.type
            val advancedMenuField = it.instructionMatches.last().instruction.getReference<FieldReference>()!!

            getVideoQualitySettingFingerprint(videoQualitySettingType).method.apply {
                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static { }, $EXTENSION_CLASS->useAdvancedVideoQualitySetting()Z
                        move-result v0
                        if-eqz v0, :original
                        sget-object v0, $advancedMenuField
                        return-object v0
                        :original
                        nop
                    """
                )
            }
        }

        // The server chooses the quality of SABR streams from the client ABR state sent with each request.
        // The first request of a video sends the initial video quality as the sticky resolution,
        // but the requests sent while the video plays have none, so the server can switch to another quality.
        // The quality is sent as if the user just selected it, in every request.
        ClientAbrStateFingerprint.let {
            val overrideClientAbrStateMethod = ImmutableMethod(
                it.classDef.type,
                "patch_overrideClientAbrState",
                listOf(ImmutableMethodParameter(CLIENT_ABR_STATE_CLASS, null, null)),
                CLIENT_ABR_STATE_CLASS,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                MutableMethodImplementation(3),
            ).toMutable().apply {
                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static { p0 }, $EXTENSION_CLASS->overrideClientAbrState(Lcom/google/protobuf/MessageLite;)[B
                        move-result-object v0
                        if-eqz v0, :keep
                        invoke-static { }, $CLIENT_ABR_STATE_CLASS->getDefaultInstance()$CLIENT_ABR_STATE_CLASS
                        move-result-object v1
                        invoke-static { v1, v0 }, ${parseByteArrayMethodRef.get()!!}
                        move-result-object v0
                        check-cast v0, $CLIENT_ABR_STATE_CLASS
                        return-object v0
                        :keep
                        return-object p0
                    """
                )
            }
            it.classDef.methods.add(overrideClientAbrStateMethod)

            it.method.apply {
                findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).forEach { index ->
                    val register = getInstruction<OneRegisterInstruction>(index).registerA

                    addInstructionsAtControlFlowLabel(
                        index,
                        """
                            invoke-static/range { v$register .. v$register }, $overrideClientAbrStateMethod
                            move-result-object v$register
                        """
                    )
                }
            }
        }

        // The initial video quality is only used when the video starts. While the video plays,
        // the player and the SABR requests use the quality last selected in the quality menu,
        // and without one the quality is chosen automatically, such as 720p for the size of the player.
        // The default quality is used as the quality last selected, as if the user just selected it.
        val videoIdField = LastSelectedVideoQualityConstructorFingerprint.let {
            val videoIdField = it.instructionMatches[1].instruction.getReference<FieldReference>()!!

            it.method.apply {
                val selectedTimeField = it.instructionMatches[0].instruction.getReference<FieldReference>()!!
                val playbackStartTimeField = it.instructionMatches[3].instruction.getReference<FieldReference>()!!
                val resolutionField = it.instructionMatches[4].instruction.getReference<FieldReference>()!!
                val directionField = it.instructionMatches[5].instruction.getReference<FieldReference>()!!

                // All fields are set, so the registers are free before the return.
                addInstructionsAtControlFlowLabel(
                    indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID),
                    """
                        invoke-static { }, $EXTENSION_CLASS->getLastSelectedVideoQuality()I
                        move-result v0
                        if-ltz v0, :original
                        iput v0, p0, $resolutionField

                        # The video starts with the default quality, so the direction is the one
                        # saved when the user selects the quality that is playing (no change).
                        const/4 v0, 0x0
                        iput v0, p0, $directionField

                        invoke-static { }, Ljava/lang/System;->currentTimeMillis()J
                        move-result-wide v0
                        iput-wide v0, p0, $selectedTimeField
                        iput-wide v0, p0, $playbackStartTimeField

                        # Without a video ID, YouTube saves the quality as the quality last selected.
                        iget-object v0, p0, $videoIdField
                        if-nez v0, :original
                        const-string v0, ""
                        iput-object v0, p0, $videoIdField
                        :original
                        nop
                    """
                )
            }

            videoIdField
        }

        // With some accounts, the quality selected in the quality menu is used only for the video
        // it was selected for, and the player of other videos chooses the quality automatically.
        // The default quality is used as if the user selected it for the current video.
        getVideoQualityConstraintFingerprint(
            LastSelectedVideoQualityConstructorFingerprint.classDef.type,
            videoQualitySettingMethod.toString()
        ).let {
            it.method.apply {
                val moveResultIndex = it.instructionMatches.first().index + 1
                val lastSelectedRegister = getInstruction<OneRegisterInstruction>(moveResultIndex).registerA
                val insertIndex = moveResultIndex + 1
                // The field is set with an instruction that can only use registers v0-v15.
                val registerProvider = getFreeRegisterProvider(insertIndex, 2, lastSelectedRegister)
                val videoIdRegister = registerProvider.getFreeRegister4Bit()
                val lastSelectedFreeRegister = registerProvider.getFreeRegister4Bit()

                addInstructionsWithLabels(
                    insertIndex,
                    """
                        invoke-static { }, $EXTENSION_CLASS->getLastSelectedVideoQuality()I
                        move-result v$videoIdRegister
                        if-ltz v$videoIdRegister, :original
                        move-object/from16 v$videoIdRegister, p3
                        move-object/from16 v$lastSelectedFreeRegister, v$lastSelectedRegister
                        iput-object v$videoIdRegister, v$lastSelectedFreeRegister, $videoIdField
                        :original
                        nop
                    """
                )
            }
        }

        // A quality selected by the user is used again if YouTube reloads the video,
        // until another video is opened.
        hookVideoIntent(EXTENSION_CLASS, detectVideo = true, detectShorts = true)

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
