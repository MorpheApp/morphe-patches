/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1881
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.findFreeRegister
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
import app.morphe.patches.shared.misc.textcomponent.hookSpannableStringResult
import app.morphe.patches.shared.misc.textcomponent.textComponentPatch
import app.morphe.util.ResourceGroup
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.copyResources

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

private val downloadsResourcePatch = patch {
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

        resourceXml("AndroidManifest.xml").use { manifest ->
            val declaredNames = manifest.elements().mapNotNullTo(HashSet()) { it.attribute("android:name") }
            val application = manifest.elements("application").first()

            // YouTube Music already declares most of these, so each one is added only when missing.
            for (permission in DOWNLOADS_PERMISSIONS) {
                if (permission !in declaredNames) {
                    application.parent!!.insertChild(application.index, "uses-permission") {
                        setAttribute("android:name", permission)
                    }
                }
            }

            for ((service, type) in DOWNLOADS_SERVICES) {
                if (service !in declaredNames) {
                    application.appendChild("service") {
                        setAttribute("android:name", service)
                        setAttribute("android:exported", "false")
                        setAttribute("android:foregroundServiceType", type)
                    }
                }
            }
        }
    }
}

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/DownloadsPatch;"
private const val SONG_MENU_FILTER = "Lapp/morphe/extension/music/patches/components/SongMenuFilter;"
private const val EXTENSION_PROTOCOL_BUFFER_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/DownloadsPatch$ProtocolBufferFieldInterface;"
private const val DOWNLOADED_MARK_FILTER =
    "Lapp/morphe/extension/music/patches/components/DownloadedMarkFilter;"


@Suppress("unused")
val downloadsPatch = patch(
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
        // Once the style runs are applied, so the downloaded mark does not shift them.
        hookSpannableStringResult(EXTENSION_CLASS)
        // Finds the song rows of the downloaded songs, for the mark of their details text.
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
        OfflineUpsellCommandFingerprint.method.addInstructionsWithLabels(
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

        // The rows of the playlists, of Liked music and of the song results are native views,
        // bound by two presenters. One binds the renderer of the row, the other a wrapper of it.
        val wrapperType = TwoColumnItemWrapperBindFingerprint.originalClassDef.type
        val rendererField = TwoColumnItemWrapperBindFingerprint.instructionMatches[1].fieldReference
        // The renderer is read as a buffer, like the commands.
        mutableClassDefBy(rendererField.type).interfaces.add(EXTENSION_PROTOCOL_BUFFER_INTERFACE)

        TwoColumnItemPresenterConstructorFingerprint.matchAll().forEach { presenter ->
            val presenterType = presenter.originalClassDef.type
            val detailsField = presenter.instructionMatches.last().fieldReference

            twoColumnItemBindFingerprint(presenterType).let {
                it.method.apply {
                    val insertIndex = it.instructionMatches.last().index
                    val detailsRegister = findFreeRegister(insertIndex)
                    val rendererRegister = findFreeRegister(insertIndex, detailsRegister)
                    val unwrapRenderer = if (presenterType == wrapperType) {
                        """
                            check-cast v$rendererRegister, ${rendererField.definingClass}
                            iget-object v$rendererRegister, v$rendererRegister, $rendererField
                        """
                    } else {
                        ""
                    }

                    addInstructionsAtControlFlowLabel(
                        insertIndex,
                        """
                            move-object/from16 v$detailsRegister, p0
                            iget-object v$detailsRegister, v$detailsRegister, $detailsField
                            move-object/from16 v$rendererRegister, p2
                            $unwrapRenderer
                            invoke-static { v$detailsRegister, v$rendererRegister }, $EXTENSION_CLASS->onTwoColumnItemBound(Landroid/widget/TextView;Ljava/lang/Object;)V
                        """
                    )
                }
            }
        }
    }
}
