/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1881
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.music.misc.auth.authHookPatch
import app.morphe.patches.music.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.video.information.musicVideoInformationPatch
import app.morphe.patches.shared.misc.litho.filter.addLithoFilter
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.shared.misc.textcomponent.hookSpannableString
import app.morphe.patches.shared.misc.textcomponent.textComponentPatch
import app.morphe.util.ResourceGroup
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.copyResources
import app.morphe.util.indexOfFirstInstruction
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private val DOWNLOADS_PERMISSIONS = listOf(
    "android.permission.WAKE_LOCK",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
    "android.permission.POST_NOTIFICATIONS",
)

/**
 * Foreground services and their types. The tracks of a playlist are downloaded by a service,
 * so the download outlives the app.
 */
private val DOWNLOADS_SERVICES = mapOf(
    "app.morphe.extension.music.patches.downloads.OfflinePlaybackService" to "mediaPlayback",
    "app.morphe.extension.music.patches.downloads.PlaylistDownloadService" to "dataSync",
)

private val downloadsResourcePatch = resourcePatch {
    dependsOn(settingsPatch)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_external_downloader_screen",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    ListPreference("morphe_download_mode"),
                    SwitchPreference("morphe_music_downloads_mark", summary = true),
                    SwitchPreference("morphe_external_downloader_menu", summary = true),
                    TextPreference(
                        "morphe_external_downloader_name",
                        tag = "app.morphe.extension.shared.settings.preference.ExternalDownloaderPreference"
                    )
                )
            )
        )

        copyResources(
            "downloads",
            ResourceGroup("drawable", "morphe_music_downloaded_mark.xml")
        )

        val manifest = get("AndroidManifest.xml")
        var xml = manifest.readText()

        // YouTube Music already declares most of these, so each one is added only when missing.
        // The whole attribute is matched, since FOREGROUND_SERVICE is a prefix of
        // FOREGROUND_SERVICE_MEDIA_PLAYBACK and would otherwise look like it is already there.
        for (permission in DOWNLOADS_PERMISSIONS) {
            val declaration = "android:name=\"$permission\""
            if (!xml.contains(declaration)) {
                xml = xml.replace(
                    "<application",
                    "<uses-permission $declaration />\n    <application"
                )
            }
        }

        for ((service, type) in DOWNLOADS_SERVICES) {
            if (!xml.contains(service)) {
                xml = xml.replace(
                    "</application>",
                    "<service android:name=\"$service\" " +
                        "android:exported=\"false\" android:foregroundServiceType=\"$type\" />\n</application>"
                )
            }
        }

        manifest.writeText(xml)
    }
}

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/DownloadsPatch;"
private const val SONG_MENU_FILTER = "Lapp/morphe/extension/music/patches/components/SongMenuFilter;"
private const val EXTENSION_PROTOCOL_BUFFER_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/DownloadsPatch$ProtocolBufferFieldInterface;"
private const val DOWNLOADED_MARK_FILTER =
    "Lapp/morphe/extension/music/patches/components/DownloadedMarkFilter;"


@Suppress("unused")
val downloadsPatch = bytecodePatch(
    name = "Downloads",
    description = "Adds support to download songs, albums and playlists using the in-app " +
        "download button, either with an external downloader app or inside YouTube Music.",
) {
    dependsOn(
        downloadsResourcePatch,
        settingsPatch,
        textComponentPatch,
        lithoFilterPatch,
        musicVideoInformationPatch,
        // Liked music and private playlists are listed with the account of the app.
        authHookPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        hookSpannableString(EXTENSION_CLASS, overrideSpan = true)
        addLithoFilter(DOWNLOADED_MARK_FILTER)

        // Finds the song of a menu, for the menu Download of accounts offered Premium.
        addLithoFilter(SONG_MENU_FILTER)

        CommandResolverFingerprint.method.cloneParameters().apply {
            // Add interface to get buffer.
            mutableClassDefBy(parameterTypes[1].toString())
                .interfaces.add(EXTENSION_PROTOCOL_BUFFER_INTERFACE)

            addInstructionsWithLabels(
                0,
                """
                    invoke-static { p1, p2 }, $EXTENSION_CLASS->commandResolverOnClick(${EXTENSION_PROTOCOL_BUFFER_INTERFACE}Ljava/util/Map;)Z
                    move-result v0
                    if-eqz v0, :continue_resolution
                    return v0
                    :continue_resolution
                    nop
                """
            )
        }

        MusicOfflineVideoEndpointFingerprint.let {
            it.method.apply {
                val downloadCall = it.instructionMatches.last()
                val videoIdRegister = downloadCall.instruction.registersUsed[1]
                // Reuse the ID YTM resolved for its single-video download action.
                // v1 is unused by the download call, which is followed by return-void.
                addInstructionsAtControlFlowLabel(
                    downloadCall.index,
                    """
                        invoke-static/range { v$videoIdRegister .. v$videoIdRegister }, $EXTENSION_CLASS->offlineVideoEndpointOnClick(Ljava/lang/String;)Z
                        move-result v1
                        if-eqz v1, :show_native_downloader
                        return-void
                        :show_native_downloader
                        nop
                    """
                )
            }
        }

        MusicOfflinePlaylistEndpointFingerprint.method.apply {
            // The command is the one the resolver hook above gave the buffer interface to.
            // v0 is set before it is read at the start of the method.
            addInstructionsWithLabels(
                0,
                """
                    invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->offlinePlaylistEndpointOnClick(Ljava/lang/Object;)Z
                    move-result v0
                    if-eqz v0, :save_offline_playlist
                    return-void
                    :save_offline_playlist
                    nop
                """
            )
        }

        // Accounts offered Premium get an upsell command from the Download option instead.
        offlineUpsellCommandFingerprint(
            OfflineUpsellRequestFingerprint.classDef.type
        ).method.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->offlineUpsellOnClick(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :show_upsell
                return-void
                :show_upsell
                nop
            """
        )

        // The rows of the playlists, of Liked music and of the song results are native views.
        // The details text of a row gets the mark when its song is bound, once the text is set.
        val rendererTypes = mutableSetOf<String>()
        TwoColumnItemPresenterConstructorFingerprint.matchAll().forEach { presenter ->
            val detailsField = presenter.instructionMatches.last().fieldReference
            val bind = twoColumnItemBindFingerprint(presenter.originalClassDef.type)

            bind.method.apply {
                val castIndex = bind.instructionMatches[0].index
                val boundType = getInstruction(castIndex).getReference<TypeReference>()!!.type
                // One presenter binds the renderer of the row, the other a wrapper that holds it
                // and reads the renderer out of it before the draggable flag of the row.
                val unwrapIndex = indexOfFirstInstruction(
                    castIndex,
                    fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = boundType)
                )
                val rendererField = if (unwrapIndex in castIndex until bind.instructionMatches[1].index) {
                    getInstruction(unwrapIndex).getReference<FieldReference>()!!
                } else {
                    null
                }
                rendererTypes.add(rendererField?.type ?: boundType)

                val insertIndex = bind.instructionMatches.last().index
                val detailsRegister = findFreeRegister(insertIndex)
                val rendererRegister = findFreeRegister(insertIndex, detailsRegister)
                val unwrap = rendererField?.let {
                    "check-cast v$rendererRegister, $boundType\n" +
                        "iget-object v$rendererRegister, v$rendererRegister, ${it.definingClass}->${it.name}:${it.type}"
                } ?: ""
                addInstructionsAtControlFlowLabel(
                    insertIndex,
                    """
                        move-object/from16 v$detailsRegister, p0
                        iget-object v$detailsRegister, v$detailsRegister, ${detailsField.definingClass}->${detailsField.name}:${detailsField.type}
                        move-object/from16 v$rendererRegister, p2
                        $unwrap
                        invoke-static { v$detailsRegister, v$rendererRegister }, $EXTENSION_CLASS->onTwoColumnItemBound(Landroid/widget/TextView;Ljava/lang/Object;)V
                    """
                )
            }
        }
        // The renderer of a row is read as a buffer, like the commands.
        rendererTypes.forEach {
            mutableClassDefBy(it).interfaces.add(EXTENSION_PROTOCOL_BUFFER_INTERFACE)
        }
    }
}
