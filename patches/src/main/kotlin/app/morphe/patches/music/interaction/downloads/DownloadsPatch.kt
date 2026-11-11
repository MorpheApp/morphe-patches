/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.misc.spoof.spoofVideoStreamsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.video.information.musicVideoInformationPatch
import app.morphe.patches.shared.misc.downloads.exportDownloadFolderPreference
import app.morphe.patches.shared.misc.fix.proto.fixProtoLibraryPatch
import app.morphe.patches.shared.misc.fix.proto.parseByteArrayMethodRef
import app.morphe.patches.shared.misc.fix.proto.parseByteArrayWithRegistryMethodRef
import app.morphe.patches.shared.misc.fix.proto.protoDefaultInstanceFingerprint
import app.morphe.patches.shared.misc.litho.filter.addLithoFilter
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.shared.misc.textcomponent.hookSpannableStringResult
import app.morphe.patches.shared.misc.textcomponent.textComponentPatch
import app.morphe.patches.youtube.interaction.downloads.ResolveCommandFingerprint
import app.morphe.patches.youtube.interaction.downloads.UpdateOfflineVideoPolicyFingerprint
import app.morphe.patches.youtube.interaction.downloads.playerResponseGetterFingerprint
import app.morphe.patches.youtube.interaction.downloads.playerResponseModelFromBytesFingerprint
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/DownloadsPatch;"
private const val EXTENSION_SPOOF_CLASS =
    "Lapp/morphe/extension/shared/spoof/SpoofVideoStreamsPatch;"
private const val SONG_MENU_FILTER =
    "Lapp/morphe/extension/music/patches/components/SongMenuFilter;"
private const val PLAYLIST_PAGE_FILTER =
    "Lapp/morphe/extension/music/patches/components/PlaylistPageFilter;"
private const val EXPORT_MENU_ITEM =
    "Lapp/morphe/extension/music/patches/downloads/ExportMenuItem;"
private const val GET_GENERATED_REGISTRY =
    "Lcom/google/protobuf/ExtensionRegistryLite;->getGeneratedRegistry()Lcom/google/protobuf/ExtensionRegistryLite;"

/**
 * @return The default instance field of a proto message, which the message is parsed with.
 */
private fun protoDefaultInstance(messageType: String) =
    protoDefaultInstanceFingerprint(messageType).instructionMatches.first().fieldReference

private val downloadsResourcePatch = patch {
    dependsOn(settingsPatch)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_external_downloader_screen",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    ListPreference("morphe_download_mode"),
                    exportDownloadFolderPreference(),
                    SwitchPreference("morphe_external_downloader_menu", summary = true),
                    TextPreference(
                        "morphe_external_downloader_name",
                        tag = "app.morphe.extension.shared.settings.preference.ExternalDownloaderPreference"
                    )
                )
            )
        )
    }
}

@Suppress("unused")
val downloadsPatch = patch(
    name = "Downloads",
    description = "Adds support to download songs, albums, and playlists using the in-app " +
        "download button, either inside YouTube Music or with an external downloader app, " +
        "and exports songs and videos saved by the app to the device.",
) {
    dependsOn(
        downloadsResourcePatch,
        sharedExtensionPatch,
        settingsPatch,
        textComponentPatch,
        lithoFilterPatch,
        spoofVideoStreamsPatch,
        musicVideoInformationPatch,
        fixProtoLibraryPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        // Captures the localized download button label from Litho text creation.
        hookSpannableStringResult(EXTENSION_CLASS)

        // Finds the song of a menu, for the menu Download of accounts offered Premium.
        addLithoFilter(SONG_MENU_FILTER)

        // Finds the playlist or album of a page, for the page and menu Download.
        addLithoFilter(PLAYLIST_PAGE_FILTER)

        // Replace premium upsell and offline commands in CommandResolver with native OfflineCommand
        // so songs, albums, and playlists are saved directly by YouTube Music's offline engine.
        ResolveCommandFingerprint.method.apply {
            val commandType = parameterTypes.first().toString()

            addInstructionsWithLabels(
                0,
                """
                    invoke-static/range { p1 .. p2 }, $EXTENSION_CLASS->replaceDownloadCommand(Lcom/google/protobuf/MessageLite;Ljava/util/Map;)[B
                    move-result-object v0
                    if-eqz v0, :keep_command
                    array-length v1, v0
                    if-nez v1, :parse_command
                    return-void
                    :parse_command
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

        // Unlock the offlineability of the songs, albums and playlists the endpoints save.
        // The save method is an interface method in some app versions, so its call is changed.
        listOf(
            MusicOfflineVideoEndpointFingerprint,
            SaveOfflinePlaylistCallFingerprint,
        ).forEach { fingerprint ->
            val saveCall = fingerprint.instructionMatches.first()
            val offlineabilityType = saveCall.methodReference.parameterTypes[1].toString()

            val unlockMethod = ImmutableMethod(
                fingerprint.classDef.type,
                "patch_unlockOfflineability",
                listOf(ImmutableMethodParameter(offlineabilityType, null, null)),
                offlineabilityType,
                AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
                null,
                null,
                MutableMethodImplementation(4),
            ).toMutable().apply {
                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static { p0 }, $EXTENSION_CLASS->unlockOfflineability(Ljava/lang/Object;)[B
                        move-result-object v0
                        if-eqz v0, :keep_offlineability
                        sget-object v1, ${protoDefaultInstance(offlineabilityType)}
                        invoke-static { }, $GET_GENERATED_REGISTRY
                        move-result-object v2
                        invoke-static { v1, v0, v2 }, ${parseByteArrayWithRegistryMethodRef.get()!!}
                        move-result-object v0
                        check-cast v0, $offlineabilityType
                        return-object v0
                        :keep_offlineability
                        return-object p0
                    """
                )
            }
            fingerprint.classDef.methods.add(unlockMethod)

            // The registers of the call are the instance, the id, then the offlineability.
            val offlineabilityRegister = when (val call = saveCall.instruction) {
                is RegisterRangeInstruction -> call.startRegister + 2
                is FiveRegisterInstruction -> call.registerE
                else -> throw PatchException("Unexpected save call: $call")
            }

            fingerprint.method.addInstructionsAtControlFlowLabel(
                saveCall.index,
                """
                    invoke-static/range { v$offlineabilityRegister .. v$offlineabilityRegister }, $unlockMethod
                    move-result-object v$offlineabilityRegister
                """
            )
        }

        // Save the track instead of showing the Premium upsell of the offline upsell endpoint.
        MusicOfflineUpsellResponseFingerprint.let {
            val responseType = it.instructionMatches.first().instruction.getReference<TypeReference>()!!.type
            val commandField = it.instructionMatches[2].fieldReference

            it.method.addInstructionsWithLabels(
                0,
                """
                    move-object/from16 v0, p0
                    iget-object v0, v0, $commandField
                    move-object/from16 v1, p1
                    invoke-static { v0, v1 }, $EXTENSION_CLASS->replaceOfflineUpsell(Lcom/google/protobuf/MessageLite;Ljava/lang/Object;)[B
                    move-result-object v0
                    if-eqz v0, :keep_upsell_response

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

        // Save the track with streams of another client, if the server declines to save the track.
        MusicOfflinePlayerResponseFingerprint.let {
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
                        invoke-static { v0 }, $EXTENSION_CLASS->replaceDeclinedOfflinePlayerResponse(Ljava/lang/Object;)[B
                        move-result-object v0
                        if-eqz v0, :keep_player_response

                        const/4 v1, 0x1
                        invoke-static { v1 }, $EXTENSION_SPOOF_CLASS->setKeepStreamsOnThread(Z)V

                        invoke-static { }, Ljava/lang/System;->currentTimeMillis()J
                        move-result-wide v1
                        invoke-static { v0, v1, v2 }, $fromBytesMethod
                        move-result-object v0

                        invoke-static { v0 }, $EXTENSION_CLASS->onOfflinePlayerResponseReplaced(Ljava/lang/Object;)V
                        if-eqz v0, :keep_player_response
                        return-object v0

                        :keep_player_response
                        return-object p0
                    """
                )
            }
            it.classDef.methods.add(helperMethod)

            val moveResult = it.instructionMatches.last()
            val playerResponseModelRegister = moveResult.instruction.registersUsed.first()

            it.method.addInstructions(
                moveResult.index + 1,
                """
                    invoke-static/range { v$playerResponseModelRegister .. v$playerResponseModelRegister }, $helperMethod
                    move-result-object v$playerResponseModelRegister
                """
            )
        }

        // Keep saved tracks playable if the server disables them during background policy refreshes.
        UpdateOfflineVideoPolicyFingerprint.matchAll().forEach { match ->
            match.method.cloneParameters().apply {
                val offlineStateType = parameterTypes[2].toString()

                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static/range { p4 .. p4 }, $EXTENSION_CLASS->replaceDisabledOfflineState(Lcom/google/protobuf/MessageLite;)[B
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

        // region Export to device.

        // Add the export items to the menus of the songs and videos the app saved.
        MusicMenuBottomSheetItemsFingerprint.let {
            val menuField = it.instructionMatches.first().fieldReference
            val menuItemType = it.instructionMatches[3].instruction.getReference<TypeReference>()!!.type
            val addItemMethod = it.instructionMatches.last().methodReference
            val itemsType = addItemMethod.definingClass

            val addExportMenuItemMethod = ImmutableMethod(
                it.classDef.type,
                "patch_addExportMenuItems",
                listOf(
                    ImmutableMethodParameter(itemsType, null, null),
                    ImmutableMethodParameter("Ljava/lang/Object;", null, null)
                ),
                "V",
                AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
                null,
                null,
                MutableMethodImplementation(7),
            ).toMutable().apply {
                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static { p1 }, $EXPORT_MENU_ITEM->getExportMenuItems(Ljava/lang/Object;)[[B
                        move-result-object v0
                        array-length v1, v0
                        const/4 v2, 0x0
                        sget-object v3, ${protoDefaultInstance(menuItemType)}
                        :next_item
                        if-ge v2, v1, :no_more_items
                        aget-object v4, v0, v2
                        invoke-static { v3, v4 }, ${parseByteArrayMethodRef.get()!!}
                        move-result-object v4
                        invoke-virtual { p0, v4 }, $addItemMethod
                        add-int/lit8 v2, v2, 0x1
                        goto :next_item
                        :no_more_items
                        return-void
                    """
                )
            }
            it.classDef.methods.add(addExportMenuItemMethod)

            // The method overwrites the register of the fragment, which is needed for the menu renderer.
            it.method.cloneParameters().apply {
                findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).forEach { index ->
                    val itemsRegister = getInstruction<OneRegisterInstruction>(index).registerA
                    val menuRegister = findFreeRegister(index, itemsRegister)

                    // The loop jumps to the return when it ends, so the code goes at its label.
                    addInstructionsAtControlFlowLabel(
                        index,
                        """
                            iget-object v$menuRegister, p0, $menuField
                            invoke-static { v$itemsRegister, v$menuRegister }, $addExportMenuItemMethod
                        """
                    )
                }
            }
        }

        // The export items have no command, so the app only closes the menu when one is clicked.
        MenuItemClickFingerprint.let {
            val menuItemField = it.instructionMatches.first().fieldReference

            it.method.apply {
                val register = findFreeRegister(0)

                addInstructions(
                    0,
                    """
                        iget-object v$register, p0, $menuItemField
                        invoke-static { v$register }, $EXPORT_MENU_ITEM->onMenuItemClick(Ljava/lang/Object;)V
                    """
                )
            }
        }

        // endregion
    }
}
