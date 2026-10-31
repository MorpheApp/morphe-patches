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

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.getFreeRegisterProvider
import app.morphe.patcher.util.matchSingle
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.FormatStreamModelToStringFingerprint
import app.morphe.patches.shared.misc.fix.proto.fixProtoLibraryPatch
import app.morphe.patches.shared.misc.fix.proto.parseByteArrayMethodRef
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.playservice.is_21_17_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_43_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.VideoQualityChangedFingerprint
import app.morphe.patches.youtube.shared.hookVideoIntent
import app.morphe.patches.youtube.shared.openVideoIntentPatch
import app.morphe.patches.youtube.video.format.hookAdaptiveFormat
import app.morphe.patches.youtube.video.format.videoFormatPatch
import app.morphe.patches.youtube.video.information.EXTENSION_VIDEO_QUALITY_INTERFACE
import app.morphe.patches.youtube.video.information.EXTENSION_VIDEO_QUALITY_MENU_INTERFACE
import app.morphe.patches.youtube.video.information.PlaybackStartDescriptorToStringFingerprint
import app.morphe.patches.youtube.video.information.SetVideoQualityFingerprint
import app.morphe.patches.youtube.video.information.VideoQualityFingerprint
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.cloneMutable
import app.morphe.util.findFieldFromToString
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.findMethodFromToString
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
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
        videoFormatPatch,
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

        // The player loads the video again with the playback start parameters it was started with,
        // such as when the app returns from the background with background playback off,
        // so the quality selected by the user for the video is used instead of the initial quality.
        // The Shorts player also loads the next Shorts before they play, so the video ID of the player
        // that loads the video is passed, from the playback start descriptor of its player.
        getPlaybackStartParametersInitialResolutionFingerprint(initialResolutionField).matchSingle().method.apply {
            val descriptorMatch = getPlayerPlaybackStartDescriptorFingerprint(
                definingClass,
                PlaybackStartDescriptorToStringFingerprint.classDef.type
            )
            val playerField = descriptorMatch.instructionMatches[0].getFieldAccessed()
            val descriptorMethod = descriptorMatch.instructionMatches[1].getMethodCalled()
            val invokeOpcode = if (AccessFlags.INTERFACE.isSet(classDefBy(playerField.type).accessFlags))
                "invoke-interface" else "invoke-virtual"
            val videoIdMethod = PlaybackStartDescriptorToStringFingerprint.instructionMatches[1].getMethodCalled()
            val playbackStartParametersGetterClass = mutableClassDefBy(definingClass)

            // The register of 'this' can be reused before the method returns,
            // so the original method is called by a method that also gets the video ID.
            val originalMethod = cloneMutable(
                name = "patch_getPlaybackStartInitialResolution",
                accessFlags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
            )
            playbackStartParametersGetterClass.methods.add(originalMethod)

            val getPlaybackStartVideoQualityMethod = ImmutableMethod(
                definingClass,
                "patch_getPlaybackStartVideoQuality",
                listOf(),
                "Ljava/lang/Integer;",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                null,
                null,
                MutableMethodImplementation(3),
            ).toMutable().apply {
                addInstructionsWithLabels(
                    0,
                    """
                        invoke-virtual { p0 }, $originalMethod
                        move-result-object v1
                        iget-object v0, p0, $playerField
                        if-eqz v0, :no_video_id
                        $invokeOpcode { v0 }, $descriptorMethod
                        move-result-object v0
                        if-eqz v0, :no_video_id
                        invoke-virtual { v0 }, $videoIdMethod
                        move-result-object v0
                        :get_quality
                        invoke-static { v1, v0 }, $EXTENSION_CLASS->getPlaybackStartVideoQuality(Ljava/lang/Integer;Ljava/lang/String;)Ljava/lang/Integer;
                        move-result-object v0
                        return-object v0
                        :no_video_id
                        const/4 v0, 0x0
                        goto :get_quality
                    """
                )
            }
            playbackStartParametersGetterClass.methods.add(getPlaybackStartVideoQualityMethod)

            addInstructions(
                0,
                """
                    invoke-virtual { p0 }, $getPlaybackStartVideoQualityMethod
                    move-result-object p0
                    return-object p0
                """
            )
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
            val advancedMenuField = it.instructionMatches.last().fieldReference

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
            val videoIdField = it.instructionMatches[1].fieldReference

            it.method.apply {
                val selectedTimeField = it.instructionMatches[0].fieldReference
                val playbackStartTimeField = it.instructionMatches[3].fieldReference
                val resolutionField = it.instructionMatches[4].fieldReference
                val directionField = it.instructionMatches[5].fieldReference

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
        // until another video is opened in the same player.
        hookVideoIntent(EXTENSION_CLASS, detectVideo = true, detectShorts = false)
        hookVideoIntent(EXTENSION_CLASS, "onShortsIntentLoaded", detectVideo = false, detectShorts = true)

        // Inject a call to remember the selected quality for Shorts.
        VideoQualityItemOnClickFingerprint.let {
            // The menu shows the qualities it was opened with, which YouTube can change after that,
            // so the quality selected is found in the qualities of the menu.
            val menuQualitiesField = getVideoQualityMenuQualitiesFingerprint(
                it.classDef.type,
                "[${VideoQualityFingerprint.classDef.type}"
            ).instructionMatches.first().getFieldAccessed()

            val userChangedShortsQualityMethod = ImmutableMethod(
                it.classDef.type,
                "patch_userChangedShortsQuality",
                listOf(ImmutableMethodParameter("I", null, null)),
                "I",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                null,
                null,
                MutableMethodImplementation(3),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        iget-object v0, p0, $menuQualitiesField
                        invoke-static { v0, p1 }, $EXTENSION_CLASS->userChangedShortsQuality([${EXTENSION_VIDEO_QUALITY_INTERFACE}I)I
                        move-result v0
                        return v0
                    """
                )
            }
            it.classDef.methods.add(userChangedShortsQualityMethod)

            it.method.apply {
                // A quality the player can play is set instead of a quality it cannot play.
                addInstructions(
                    0,
                    """
                        invoke-virtual { p0, p3 }, $userChangedShortsQualityMethod
                        move-result p3
                    """
                )

                findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
                    addInstructionsAtControlFlowLabel(
                        index,
                        "invoke-static { }, $EXTENSION_CLASS->clearShortsMenuSelectedQuality()V"
                    )
                }
            }
        }

        // The Shorts quality menu sets the selected quality by its resolution, which YouTube can get
        // wrong, such as 720 for the 480p quality of some Shorts, so another quality is played.
        // The quality selected is set instead, as the quality menu of regular videos does.
        val setResolutionMethod = getSetVideoQualityResolutionFingerprint(
            SetVideoQualityFingerprint.instructionMatches[1].fieldReference.type
        ).matchSingle().method

        getVideoQualityItemSetResolutionFingerprint(setResolutionMethod).matchAll().forEach {
            it.method.apply {
                val setResolutionMatch = it.instructionMatches.first()
                val index = setResolutionMatch.index
                val setResolutionInstruction = setResolutionMatch.getInstruction<FiveRegisterInstruction>()
                val menuRegister = setResolutionInstruction.registerC
                val qualitySetRegister = findFreeRegister(
                    index,
                    menuRegister,
                    setResolutionInstruction.registerD
                )

                addInstructionsAtControlFlowLabel(
                    index,
                    """
                        invoke-static { v$menuRegister }, $EXTENSION_CLASS->setShortsMenuSelectedQuality($EXTENSION_VIDEO_QUALITY_MENU_INTERFACE)Z
                        move-result v$qualitySetRegister
                        if-nez v$qualitySetRegister, :quality_set
                    """,
                    ExternalLabel("quality_set", getInstruction(index + 1))
                )
            }
        }

        // Inject a call to remember the user selected quality for regular videos.
        // The quality object is used, as its name is needed to get the fixed resolution.
        // A quality the player can play is set instead of a quality it cannot play.
        VideoQualityChangedFingerprint.let {
            it.method.apply {
                val qualityMatch = it.instructionMatches[3]
                val qualityRegister = qualityMatch.getInstruction<OneRegisterInstruction>().registerA
                val qualityType = qualityMatch.getInstruction<ReferenceInstruction>().reference
                val constructorIndex = it.instructionMatches.last().index

                addInstructions(
                    constructorIndex + 1,
                    """
                        invoke-static/range { v$qualityRegister .. v$qualityRegister }, $EXTENSION_CLASS->userChangedQuality($EXTENSION_VIDEO_QUALITY_INTERFACE)$EXTENSION_VIDEO_QUALITY_INTERFACE
                        move-result-object v$qualityRegister
                        check-cast v$qualityRegister, $qualityType
                    """
                )
            }
        }

        // The codecs of the video formats and the video format playing,
        // to know the qualities the player can play.
        hookAdaptiveFormat("$EXTENSION_CLASS->setVideoFormats")

        val formatIdMethod = FormatStreamModelToStringFingerprint.originalMethod.findMethodFromToString("itag")
        getCurrentVideoFormatConstructorFingerprint("[${VideoQualityFingerprint.classDef.type}").method.apply {
            // The first video format field set is the video format playing. The constructor
            // has no free register, so the register of the video format is used once it is set.
            val videoFormatIndex = indexOfFirstInstructionOrThrow(
                fieldAccess(opcode = Opcode.IPUT_OBJECT, type = formatIdMethod.definingClass)
            )
            val videoFormatRegister = getInstruction<TwoRegisterInstruction>(videoFormatIndex).registerA

            addInstructionsWithLabels(
                videoFormatIndex + 1,
                """
                    if-eqz v$videoFormatRegister, :no_video_format
                    invoke-virtual/range { v$videoFormatRegister .. v$videoFormatRegister }, $formatIdMethod
                    move-result v$videoFormatRegister
                    invoke-static/range { v$videoFormatRegister .. v$videoFormatRegister }, $EXTENSION_CLASS->setCurrentVideoFormat(I)V
                    :no_video_format
                    nop
                """
            )
        }

        // If this flag is enabled, Shorts restart whenever the quality changes.
        // The restart uses the initial quality of the Short, so the quality selected by the user is lost.
        ShortsQualityChangeObserverPrimaryFeatureFlagFingerprint.matchAll().forEach {
            it.method.insertLiteralOverride(
                it.instructionMatches.first().index, false
            )
        }

        // The secondary flag also saves the formats of the quality selected in the quality menu with the quality
        // last selected, so the player keeps playing them. Without the formats only the resolution YouTube
        // gives the quality is saved, and the player can switch to another quality of that resolution,
        // such as 720p60 after selecting a 480p quality that YouTube gives the resolution 720.
        // The flag is enabled only for the Short the user selected the quality for.
        ShortsQualityChangeObserverSecondaryFeatureFlagFingerprint.matchAll().forEach {
            it.method.insertLiteralOverride(
                it.instructionMatches.first().index,
                "$EXTENSION_CLASS->useSelectedQualityFormats(Z)Z"
            )
        }

        // The player plays another quality of the same resolution if this flag is off,
        // such as 720p after selecting a 480p quality that YouTube gives the resolution 720.
        // Without a quality selected in the Shorts quality menu, the player has no formats to use.
        QualityFormatsFeatureFlagFingerprint.let {
            it.method.insertLiteralOverride(
                it.instructionMatches.first().index,
                true
            )
        }

        // When the quality changes, the player keeps playing the video already buffered,
        // so the buffered video is cleared when the quality selected by the user is set.
        if (is_21_17_or_greater) {
            PlaybackControllerSetVideoFormatsFingerprint.method.addInstructions(
                0,
                """
                    invoke-static { p1 }, $EXTENSION_CLASS->clearVideoBufferOnQualityChange(Z)Z
                    move-result p1
                """
            )

            AsyncMediaFetchControllerSetVideoFormatsFingerprint.method.addInstructions(
                0,
                """
                    invoke-static { p2 }, $EXTENSION_CLASS->clearVideoBufferOnQualityChange(Z)Z
                    move-result p2
                """
            )

            // The player buffers the video again from the time the video started, such as the
            // start of a Short, so the video plays from there faster until it reaches the audio.
            // The buffered video is cleared after and before the segment that is playing.
            val videoTrackTypeField = PlaybackControllerSetVideoFormatsFingerprint.instructionMatches
                .last().getFieldAccessed()
            val discardBufferAfterSegmentMethod = MediaSegmentHolderDiscardBufferAfterSegmentFingerprint
                .instructionMatches.last().getMethodCalled()
            val getMediaSegmentHolderMethod = BufferManagerGetMediaSegmentHolderFingerprint.method

            // The video buffered before the segment that is playing is cleared too,
            // otherwise a Short plays the previous quality from its start when it loops.
            // The method of the player is not public before 21.26, so a public method calls it.
            // It returns if media was cleared: the player changes the end time of the first segment.
            val firstSegmentEndTimeField = MediaSegmentHolderSetFirstSegmentEndTimeFingerprint
                .instructionMatches.last().getFieldAccessed()
            val sampleHolderDiscardBufferMethod = if (is_21_43_or_greater) {
                SampleHolderDiscardBufferBeforeSegmentFingerprint
            } else {
                SampleHolderDiscardBufferBeforeSegmentLegacyFingerprint
            }.let {
                val discardBufferBeforeSegmentMethod = ImmutableMethod(
                    it.classDef.type,
                    "patch_discardBufferBeforeSegment",
                    listOf(ImmutableMethodParameter("J", null, null)),
                    "Z",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    null,
                    null,
                    MutableMethodImplementation(12),
                ).toMutable().apply {
                    addInstructionsWithLabels(
                        0,
                        if (is_21_43_or_greater) {
                            // No media is kept before the playback time.
                            """
                                iget-wide v7, p0, $firstSegmentEndTimeField
                                move-object v0, p0
                                move-wide v1, p1
                                move-wide v3, p1
                                const-wide/16 v5, 0x0
                                invoke-virtual/range { v0 .. v6 }, ${it.method}
                            """
                        } else {
                            """
                                iget-wide v7, p0, $firstSegmentEndTimeField
                                invoke-virtual { p0, p1, p2 }, ${it.method}
                            """
                        } + """
                            iget-wide v0, p0, $firstSegmentEndTimeField
                            cmp-long v0, v0, v7
                            if-eqz v0, :not_cleared
                            const/4 v0, 0x1
                            return v0
                            :not_cleared
                            const/4 v0, 0x0
                            return v0
                        """
                    )
                }
                it.classDef.methods.add(discardBufferBeforeSegmentMethod)

                discardBufferBeforeSegmentMethod
            }

            PlaybackControllerClearBufferFingerprint.let {
                val stopRequestsMatch = it.instructionMatches[0]
                // The method is private in some versions, so the call uses the original opcode.
                val stopRequestsOpcode = stopRequestsMatch.instruction.opcode.name
                val stopRequestsMethod = stopRequestsMatch.getMethodCalled()
                val bufferManagerField = it.instructionMatches[1].getFieldAccessed()

                it.method.addInstructionsWithLabels(
                    0,
                    """
                        sget-object v0, $videoTrackTypeField
                        if-ne p1, v0, :clear_buffer
                        invoke-static { }, $EXTENSION_CLASS->getClearVideoBufferTime()J
                        move-result-wide v0
                        const-wide/16 v2, 0x0
                        cmp-long v2, v0, v2
                        if-ltz v2, :clear_buffer
                        $stopRequestsOpcode { p0 }, $stopRequestsMethod
                        iget-object v2, p0, $bufferManagerField
                        invoke-virtual { v2, p1 }, $getMediaSegmentHolderMethod
                        move-result-object v2
                        invoke-virtual { v2, v0, v1 }, $discardBufferAfterSegmentMethod
                        check-cast v2, $SAMPLE_HOLDER_CLASS
                        invoke-virtual { v2, v0, v1 }, $sampleHolderDiscardBufferMethod
                        return-void
                        :clear_buffer
                        nop
                    """
                )
            }

            // The segment that was playing when the quality was selected keeps the previous quality,
            // and a Short plays it again when it loops, such as its first segment.
            // It is cleared once it was played: the player then clears the video buffered
            // before the playback time, as when the quality was selected.
            // The code is added after the checks of live streams, so the buffered video is not
            // cleared with the playback times the player does not clear the buffered media with.
            MediaPushMediaSourceDiscardBufferFingerprint.let {
                val bufferManagerMatch = it.instructionMatches[1]
                val bufferManagerField = bufferManagerMatch.getFieldAccessed()
                val insertIndex = bufferManagerMatch.index

                it.method.apply {
                    val registerProvider = getFreeRegisterProvider(insertIndex, 2)
                    val sampleHolderRegister = registerProvider.getFreeRegister4Bit()
                    val trackTypeRegister = registerProvider.getFreeRegister4Bit()

                    addInstructionsAtControlFlowLabel(
                        insertIndex,
                        """
                            invoke-static { }, $EXTENSION_CLASS->shouldClearPlayedVideoSegment()Z
                            move-result v$sampleHolderRegister
                            if-eqz v$sampleHolderRegister, :keep_buffer
                            iget-object v$sampleHolderRegister, p0, $bufferManagerField
                            sget-object v$trackTypeRegister, $videoTrackTypeField
                            invoke-virtual { v$sampleHolderRegister, v$trackTypeRegister }, $getMediaSegmentHolderMethod
                            move-result-object v$sampleHolderRegister
                            check-cast v$sampleHolderRegister, $SAMPLE_HOLDER_CLASS
                            invoke-virtual { v$sampleHolderRegister, p1, p2 }, $sampleHolderDiscardBufferMethod
                            move-result v$sampleHolderRegister
                            if-eqz v$sampleHolderRegister, :keep_buffer
                            invoke-static { }, $EXTENSION_CLASS->onPlayedVideoSegmentCleared()V
                            :keep_buffer
                            nop
                        """
                    )
                }
            }
        }
    }
}
