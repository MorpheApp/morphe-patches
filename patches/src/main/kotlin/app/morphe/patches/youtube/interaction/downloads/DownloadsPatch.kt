/*
 * Copyright 2026 Morphe.
 *
 * https://github.com/MorpheApp/morphe-patches/pull/3905
 * https://github.com/MorpheApp/morphe-patches/pull/4890
 * https://github.com/MorpheApp/morphe-patches/pull/4909
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.interaction.downloads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.cloneMutable
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.numberOfParameterRegistersLogical
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.shared.misc.downloads.EXTENSION_FORMAT_STREAM_INTERFACE
import app.morphe.patches.shared.misc.downloads.EXTENSION_STREAMING_DATA_INTERFACE
import app.morphe.patches.shared.misc.downloads.OfflineSettingsConstructorFingerprint
import app.morphe.patches.shared.misc.downloads.exportDownloadFolderPreference
import app.morphe.patches.shared.misc.downloads.hookDownloadQualities
import app.morphe.patches.shared.misc.fix.proto.fixProtoLibraryPatch
import app.morphe.patches.shared.misc.fix.proto.parseByteArrayWithRegistryMethodRef
import app.morphe.patches.shared.misc.fix.proto.protoDefaultInstanceFingerprint
import app.morphe.patches.shared.misc.proto.hookElement
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.layout.flyout.flyoutPatch
import app.morphe.patches.youtube.layout.player.icons.copyPlayerButtonIcons
import app.morphe.patches.youtube.misc.auth.authHookPatch
import app.morphe.patches.youtube.misc.commandrouter.commandRouterHookPatch
import app.morphe.patches.youtube.misc.playercontrols.addTopControl
import app.morphe.patches.youtube.misc.playercontrols.initializeTopControl
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsPatch
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.proto.elementProtoParserHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.ResourceGroup
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.copyResources
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private val downloadsResourcePatch = patch {
    dependsOn(
        legacyPlayerControlsPatch,
        settingsPatch
    )

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_external_downloader_screen",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    ListPreference("morphe_download_mode"),
                    SwitchPreference("morphe_shorts_download_flyout_button", summary = true),
                    SwitchPreference("morphe_feed_download_flyout_button", summary = true),
                    SwitchPreference("morphe_download_force_4k", summary = true),
                    exportDownloadFolderPreference(),
                    SwitchPreference("morphe_external_downloader_overlay_button", summary = true),
                    TextPreference(
                        "morphe_external_downloader_name",
                        tag = "app.morphe.extension.shared.settings.preference.ExternalDownloaderPreference",
                    ),
                    SwitchPreference("morphe_external_downloader_quick_menu", summary = true),
                    SwitchPreference("morphe_external_downloader_mark_watched", summary = true)
                )
            )
        )

        copyPlayerButtonIcons("downloads", "morphe_yt_download_button")

        copyResources(
            "downloads",
            ResourceGroup(
                "drawable",
                "morphe_external_downloader_quick_video.xml",
                "morphe_external_downloader_quick_video_bold.xml",
                "morphe_external_downloader_quick_audio.xml",
                "morphe_external_downloader_quick_audio_bold.xml"
            )
        )
    }

    finalize {
        addTopControl("downloads",
            "@+id/morphe_external_download_button",
            "@+id/morphe_external_download_button")
    }
}

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/DownloadsPatch;"

internal const val EXTENSION_IN_APP_DOWNLOADS_CLASS = "Lapp/morphe/extension/youtube/patches/InAppDownloadsPatch;"

private const val EXTENSION_STREAM_SELECTOR_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/InAppDownloadsPatch$DownloadStreamSelector;"

private const val EXTENSION_SPOOF_CLASS = "Lapp/morphe/extension/shared/spoof/SpoofVideoStreamsPatch;"

private const val EXTENSION_SHORTS_DOWNLOAD_BUTTON =
    "Lapp/morphe/extension/youtube/videoplayer/ShortsDownloadButton;"

private const val EXTENSION_EXPORT_DOWNLOAD_BUTTON =
    "Lapp/morphe/extension/youtube/videoplayer/ExportDownloadButton;"

private const val EXTENSION_BUTTON = "Lapp/morphe/extension/youtube/videoplayer/ExternalDownloadButton;"

private const val GET_GENERATED_REGISTRY =
    "Lcom/google/protobuf/ExtensionRegistryLite;->getGeneratedRegistry()Lcom/google/protobuf/ExtensionRegistryLite;"

/**
 * @return The default instance field of a proto message, which the message is parsed with.
 */
private fun protoDefaultInstance(messageType: String) =
    protoDefaultInstanceFingerprint(messageType).instructionMatches.first().fieldReference

val downloadsPatch = patch(
    name = "Downloads",
    description = "Adds support to download videos with an external downloader app " +
        "using the in-app download button or a video player action button, " +
        "adds an option to download videos in-app, and exports saved videos to the device.",
) {
    dependsOn(
        downloadsResourcePatch,
        authHookPatch,
        videoInformationPatch,
        legacyPlayerControlsPatch,
        fixProtoLibraryPatch,
        elementProtoParserHookPatch,
        flyoutPatch,
        commandRouterHookPatch,
        versionCheckPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        initializeTopControl(EXTENSION_BUTTON)

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
        setExtensionIsPatchIncluded(EXTENSION_SHORTS_DOWNLOAD_BUTTON)
        setExtensionIsPatchIncluded(EXTENSION_EXPORT_DOWNLOAD_BUTTON)

        YouTubeOfflineVideoEndpointFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p3 .. p3 }, $EXTENSION_CLASS->inAppDownloadButtonOnClick(Ljava/lang/String;)Z
                move-result v0
                if-eqz v0, :show_native_downloader
                return-void
                :show_native_downloader
                nop
            """
        )

        // Open the external downloader with the playlist link, instead of saving the playlist.
        YouTubeOfflinePlaylistEndpointFingerprint.method.addInstructionsWithLabels(
            0,
            """
                # The parameter registers can be higher than 15.
                move-object/from16 v0, p3
                move-object/from16 v1, p4
                invoke-static { v0, v1 }, $EXTENSION_CLASS->offlinePlaylistEndpointOnClick(Ljava/lang/String;Ljava/lang/Enum;)Z
                move-result v0
                if-eqz v0, :show_native_downloader
                return-void
                :show_native_downloader
                nop
            """
        )

        // Add the Download button to the playlist page headers, which only Premium accounts get.
        hookElement("$EXTENSION_IN_APP_DOWNLOADS_CLASS->addPlaylistDownloadButton")

        // region In-app downloads.

        // Save the video instead of asking the server for the download action, which answers with the Premium upsell.
        ResolveCommandFingerprint.method.apply {
            val commandType = parameterTypes.first().toString()

            addInstructionsWithLabels(
                0,
                """
                    # The parameter register can be higher than 15.
                    invoke-static/range { p1 .. p1 }, $EXTENSION_IN_APP_DOWNLOADS_CLASS->replaceDownloadActionCommand(Lcom/google/protobuf/MessageLite;)[B
                    move-result-object v0
                    if-eqz v0, :keep_command

                    # The endpoint is an extension, which is lost if parsed without the registry.
                    sget-object v1, ${protoDefaultInstance(commandType)}
                    invoke-static { }, $GET_GENERATED_REGISTRY
                    move-result-object v2
                    invoke-static { v1, v0, v2 }, ${parseByteArrayWithRegistryMethodRef.get()!!}
                    move-result-object v0
                    check-cast v0, $commandType
                    move-object/from16 p1, v0
                    :keep_command
                    nop
                """
            )
        }

        // Unlock the qualities of the video, and save the video instead of showing the Premium upsell.
        SaveOfflineVideoCallFingerprint.instructionMatches.first().getMethodCalled().apply {
            val offlineabilityType = parameterTypes[1].toString()

            addInstructionsWithLabels(
                0,
                """
                    # The parameter register can be higher than 15.
                    invoke-static/range { p2 .. p2 }, $EXTENSION_IN_APP_DOWNLOADS_CLASS->unlockOfflineability(Ljava/lang/Object;)[B
                    move-result-object v0
                    if-eqz v0, :keep_offlineability

                    sget-object v1, ${protoDefaultInstance(offlineabilityType)}
                    invoke-static { }, $GET_GENERATED_REGISTRY
                    move-result-object v2
                    invoke-static { v1, v0, v2 }, ${parseByteArrayWithRegistryMethodRef.get()!!}
                    move-result-object v0
                    check-cast v0, $offlineabilityType
                    move-object/from16 p2, v0
                    :keep_offlineability
                    nop
                """
            )
        }

        // Save the video instead of showing the Premium upsell of the offline upsell endpoint.
        OfflineUpsellResponseFingerprint.let {
            val responseType = it.instructionMatches.first().instruction.getReference<TypeReference>()!!.type
            val commandField = it.instructionMatches[2].fieldReference

            it.method.addInstructionsWithLabels(
                0,
                """
                    # The parameter registers can be higher than 15.
                    move-object/from16 v0, p0
                    iget-object v0, v0, $commandField
                    move-object/from16 v1, p1
                    invoke-static { v0, v1 }, $EXTENSION_IN_APP_DOWNLOADS_CLASS->replaceOfflineUpsell(Lcom/google/protobuf/MessageLite;Ljava/lang/Object;)[B
                    move-result-object v0
                    if-eqz v0, :keep_upsell_response

                    # The endpoint of the command is an extension, which is lost if parsed without the registry.
                    sget-object v1, ${protoDefaultInstance(responseType)}
                    invoke-static { }, $GET_GENERATED_REGISTRY
                    move-result-object v2
                    invoke-static { v1, v0, v2 }, ${parseByteArrayWithRegistryMethodRef.get()!!}
                    move-result-object v0
                    move-object/from16 p1, v0
                    :keep_upsell_response
                    nop
                """
            )
        }

        // Save the video with streams of another client, if the server declines to save the video.
        OfflinePlayerResponseFingerprint.let {
            val playerResponseModelType = it.method.returnType

            val fromBytesFingerprint = playerResponseModelFromBytesFingerprint(playerResponseModelType)
            val fromBytesMethod = fromBytesFingerprint.method
            val playerResponseType = fromBytesFingerprint.instructionMatches.last()
                .instruction.getReference<TypeReference>()!!.type
            val playerResponseGetter = playerResponseGetterFingerprint(
                fromBytesMethod.definingClass,
                playerResponseType
            ).method

            val helperMethod = ImmutableMethod(
                it.classDef.type,
                "patch_replaceDeclinedOfflinePlayerResponse",
                listOf(ImmutableMethodParameter(playerResponseModelType, null, null)),
                playerResponseModelType,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                MutableMethodImplementation(4),
            ).toMutable().apply {
                addInstructionsWithLabels(
                    0,
                    """
                        if-eqz p0, :keep_player_response
                        invoke-interface { p0 }, $playerResponseModelType->${playerResponseGetter.name}()$playerResponseType
                        move-result-object v0
                        invoke-static { v0 }, $EXTENSION_IN_APP_DOWNLOADS_CLASS->replaceDeclinedOfflinePlayerResponse(Ljava/lang/Object;)[B
                        move-result-object v0
                        if-eqz v0, :keep_player_response

                        # The playback stream override would replace the streams that can be saved.
                        const/4 v1, 0x1
                        invoke-static { v1 }, $EXTENSION_SPOOF_CLASS->setKeepStreamsOnThread(Z)V

                        # The response time, used for the expiration of the stream urls.
                        invoke-static { }, Ljava/lang/System;->currentTimeMillis()J
                        move-result-wide v1
                        invoke-static { v0, v1, v2 }, $fromBytesMethod
                        move-result-object v0

                        invoke-static { v0 }, $EXTENSION_IN_APP_DOWNLOADS_CLASS->onOfflinePlayerResponseReplaced(Ljava/lang/Object;)V
                        if-eqz v0, :keep_player_response
                        return-object v0

                        :keep_player_response
                        return-object p0
                    """
                )
            }
            it.classDef.methods.add(helperMethod)

            // Replace the player response before it is cached and returned.
            val cachePut = it.instructionMatches.last()
            val playerResponseModelRegister = cachePut.instruction.registersUsed[2]

            it.method.addInstructions(
                cachePut.index,
                """
                    invoke-static/range { v$playerResponseModelRegister .. v$playerResponseModelRegister }, $helperMethod
                    move-result-object v$playerResponseModelRegister
                """
            )
        }

        // Start saved videos played from Downloads at the position saved in Downloads.
        hookOfflineDownloadResume()

        // Keep saved videos playable, if the server disables them when they are refreshed.
        UpdateOfflineVideoPolicyFingerprint.matchAll().forEach { match ->
            // The method has too few free registers.
            match.method.cloneParameters().apply {
                val offlineStateType = parameterTypes[2].toString()

                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static/range { p4 .. p4 }, $EXTENSION_IN_APP_DOWNLOADS_CLASS->replaceDisabledOfflineState(Lcom/google/protobuf/MessageLite;)[B
                        move-result-object v0
                        if-eqz v0, :keep_offline_state

                        sget-object v1, ${protoDefaultInstance(offlineStateType)}
                        invoke-static { }, $GET_GENERATED_REGISTRY
                        move-result-object v2
                        invoke-static { v1, v0, v2 }, ${parseByteArrayWithRegistryMethodRef.get()!!}
                        move-result-object v0
                        check-cast v0, $offlineStateType
                        move-object/from16 p4, v0
                        :keep_offline_state
                        nop
                    """
                )
            }
        }

        // Show the download settings in YouTube Settings > Background & downloads.
        // Without the offline setting category, the settings are hidden until a video is saved.
        val offlineSettingCategoryClass = OfflineSettingCategoryToStringFingerprint.classDef
        val offlineSettingCategoryType = offlineSettingCategoryClass.type
        val offlineSettingCategoryFromProto =
            offlineSettingCategoryFromProtoFingerprint(offlineSettingCategoryType).method

        val addOfflineSettingCategoryMethod = ImmutableMethod(
            offlineSettingCategoryType,
            "patch_addOfflineSettingCategory",
            listOf(ImmutableMethodParameter("Ljava/util/List;", null, null)),
            "Ljava/util/List;",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            MutableMethodImplementation(3),
        ).toMutable().apply {
            addInstructionsWithLabels(
                0,
                """
                    const-class v0, $offlineSettingCategoryType
                    invoke-static { p0, v0 }, $EXTENSION_IN_APP_DOWNLOADS_CLASS->needsOfflineSettingCategory(Ljava/util/List;Ljava/lang/Class;)Z
                    move-result v0
                    if-eqz v0, :keep_setting_categories

                    # Category with the settings of the server all off.
                    sget-object v0, ${protoDefaultInstance(offlineSettingCategoryFromProto.parameterTypes.first().toString())}
                    invoke-static { v0 }, $offlineSettingCategoryFromProto
                    move-result-object v0

                    # The original list can be immutable.
                    new-instance v1, Ljava/util/ArrayList;
                    invoke-direct { v1, p0 }, Ljava/util/ArrayList;-><init>(Ljava/util/Collection;)V
                    invoke-virtual { v1, v0 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
                    return-object v1

                    :keep_setting_categories
                    return-object p0
                """
            )
        }
        offlineSettingCategoryClass.methods.add(addOfflineSettingCategoryMethod)

        SettingCategoriesFingerprint.let {
            val returnMatch = it.instructionMatches.last()
            val categoriesRegister = returnMatch.instruction.registersUsed.first()

            it.method.addInstructionsAtControlFlowLabel(
                returnMatch.index,
                """
                    invoke-static { v$categoriesRegister }, $addOfflineSettingCategoryMethod
                    move-result-object v$categoriesRegister
                """
            )
        }

        // Offer up to 2160p on every screen. The app offers only the qualities up to the
        // shorter side of the screen, and the server offers up to 1080p.
        hookDownloadQualities()
        val copyImmutableListMethod = OfflineSettingsConstructorFingerprint
            .instructionMatches.last().methodReference

        // The download quality setting lists the same qualities as the quality picker.
        OfflineSettingCategoryConstructorFingerprint.method.addInstructions(
            0,
            """
                # Full HD format option is available, which adds 1080p to the download quality formats.
                invoke-static { p1 }, $EXTENSION_IN_APP_DOWNLOADS_CLASS->isFullHdFormatOptionAvailable(Z)Z
                move-result p1

                # Download quality is enabled.
                invoke-static { p3 }, $EXTENSION_IN_APP_DOWNLOADS_CLASS->unlockOfflineSetting(Z)Z
                move-result p3

                # Download quality formats.
                invoke-static { p5, p7 }, $EXTENSION_IN_APP_DOWNLOADS_CLASS->getDownloadQualityFormatTypes(Ljava/util/List;Ljava/lang/Object;)Ljava/util/List;
                move-result-object p5
                invoke-static { p5 }, $copyImmutableListMethod
                move-result-object p5
            """
        )

        // Open the quality picker of a download fully expanded, since the qualities above
        // 1080p push the lowest qualities below the collapsed sheet.
        val skipCollapsedField = ShowActionSheetCommandFingerprint
            .instructionMatches.last().fieldReference
        DownloadQualityPickerCommandFingerprint.let {
            val bitFieldMatch = it.instructionMatches.last()
            val bitField = bitFieldMatch.fieldReference
            if (bitField.definingClass != skipCollapsedField.definingClass) {
                throw PatchException("Unexpected command class: ${bitField.definingClass}")
            }
            val bitFieldInstruction = bitFieldMatch.getInstruction<TwoRegisterInstruction>()
            val valueRegister = bitFieldInstruction.registerA
            val commandRegister = bitFieldInstruction.registerB

            it.method.addInstructions(
                bitFieldMatch.index + 1,
                """
                    invoke-static { }, $EXTENSION_IN_APP_DOWNLOADS_CLASS->expandDownloadQualityPicker()Z
                    move-result v$valueRegister
                    iput-boolean v$valueRegister, v$commandRegister, $skipCollapsedField
                    iget v$valueRegister, v$commandRegister, $bitField
                    or-int/lit16 v$valueRegister, v$valueRegister, 0x200
                    iput v$valueRegister, v$commandRegister, $bitField
                """
            )
        }

        // Log the download qualities that are dropped or not available, and optionally save 1440p
        // and 2160p even if the device or server checks of the app rule out their streams.
        // The audio stream of a download is matched to the container of the video stream,
        // so the download can be exported as one file.
        DownloadStreamSelectorFingerprint.let {
            it.classDef.apply {
                val originalSelectorMethod = it.method.cloneMutable(name = "patch_originalSelectStream")
                methods.add(originalSelectorMethod)

                addPatchInterface(
                    EXTENSION_STREAM_SELECTOR_INTERFACE,
                    "patch_selectStream",
                    originalSelectorMethod
                )
                addPatchInterface(
                    EXTENSION_STREAM_SELECTOR_INTERFACE,
                    "patch_fetchContentLength",
                    FetchContentLengthFingerprint.method
                )
            }

            it.method.addInstructions(
                0,
                """
                    invoke-static/range { p0 .. p6 }, $EXTENSION_IN_APP_DOWNLOADS_CLASS->selectDownloadStream(${EXTENSION_STREAM_SELECTOR_INTERFACE}III${EXTENSION_STREAMING_DATA_INTERFACE}ZLjava/lang/Object;)${EXTENSION_FORMAT_STREAM_INTERFACE}
                    move-result-object v0
                    check-cast v0, ${it.method.returnType}
                    return-object v0
                """
            )
        }
        DownloadTransferStreamsFingerprint.let {
            // The method has no free registers. Its parameters stay in the p registers.
            it.method.cloneParameters().apply {
                val pairIndex = it.instructionMatches[1].index + numberOfParameterRegistersLogical
                val pairInstruction = getInstruction(pairIndex)
                val videoStreamRegister = pairInstruction.registersUsed[1]
                val audioStreamRegister = pairInstruction.registersUsed[2]
                val streamType = pairInstruction.getReference<MethodReference>()!!.parameterTypes.first()

                // p3 and p4 are the audio track and the video ID, which are not needed.
                addInstructions(
                    pairIndex,
                    """
                        move-object/from16 p3, v$videoStreamRegister
                        move/from16 p4, p1
                        invoke-static/range { p3 .. p5 }, $EXTENSION_IN_APP_DOWNLOADS_CLASS->selectDownloadVideoStream(${EXTENSION_FORMAT_STREAM_INTERFACE}I${EXTENSION_STREAMING_DATA_INTERFACE})${EXTENSION_FORMAT_STREAM_INTERFACE}
                        move-result-object v$videoStreamRegister
                        check-cast v$videoStreamRegister, $streamType

                        move-object/from16 p3, v$audioStreamRegister
                        move-object/from16 p4, v$videoStreamRegister
                        invoke-static/range { p3 .. p5 }, $EXTENSION_IN_APP_DOWNLOADS_CLASS->selectDownloadAudioStream(${EXTENSION_FORMAT_STREAM_INTERFACE}${EXTENSION_FORMAT_STREAM_INTERFACE}${EXTENSION_STREAMING_DATA_INTERFACE})${EXTENSION_FORMAT_STREAM_INTERFACE}
                        move-result-object v$audioStreamRegister
                        check-cast v$audioStreamRegister, $streamType
                    """
                )
            }
        }

        // Commands run by the extension have no interaction logger, which saving a video
        // can use without checking it is set. The logger interface has a logger that logs nothing.
        // Everything reading the logger from a context uses the same type, so any match will do.
        val interactionLoggerType = InteractionLoggerContextFingerprint.matchAll().first()
            .instructionMatches.last().instruction.getReference<TypeReference>()!!.type
        val noOpInteractionLogger = classDefBy(interactionLoggerType).fields.single { field ->
            AccessFlags.STATIC.isSet(field.accessFlags) && field.type == interactionLoggerType
        }
        NoOpInteractionLoggerFingerprint.method.addInstructions(
            0,
            """
                sget-object v0, $interactionLoggerType->${noOpInteractionLogger.name}:$interactionLoggerType
                return-object v0
            """
        )

        // endregion
    }
}
