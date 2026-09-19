/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1881
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.video.information.musicVideoInformationPatch
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.shared.misc.textcomponent.hookSpannableString
import app.morphe.patches.shared.misc.textcomponent.textComponentPatch
import app.morphe.util.cloneParameters

private val OFFLINE_PLAYBACK_PERMISSIONS = listOf(
    "android.permission.WAKE_LOCK",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
    "android.permission.POST_NOTIFICATIONS",
)

private const val OFFLINE_PLAYBACK_SERVICE =
    "app.morphe.extension.music.patches.downloads.OfflinePlaybackService"

private val downloadsResourcePatch = resourcePatch {
    dependsOn(settingsPatch)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_external_downloader_screen",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_external_downloader_action_button", summary = true),
                    SwitchPreference("morphe_external_downloader_flyout_menu", summary = true),
                    SwitchPreference("morphe_music_in_app_downloads", summary = true),
                    TextPreference(
                        "morphe_external_downloader_name",
                        tag = "app.morphe.extension.shared.settings.preference.ExternalDownloaderPreference"
                    )
                )
            )
        )

        val manifest = get("AndroidManifest.xml")
        var xml = manifest.readText()

        // YouTube Music already declares most of these, so each one is added only when missing.
        // The whole attribute is matched, since FOREGROUND_SERVICE is a prefix of
        // FOREGROUND_SERVICE_MEDIA_PLAYBACK and would otherwise look like it is already there.
        for (permission in OFFLINE_PLAYBACK_PERMISSIONS) {
            val declaration = "android:name=\"$permission\""
            if (!xml.contains(declaration)) {
                xml = xml.replace(
                    "<application",
                    "<uses-permission $declaration />\n    <application"
                )
            }
        }

        if (!xml.contains(OFFLINE_PLAYBACK_SERVICE)) {
            xml = xml.replace(
                "</application>",
                "<service android:name=\"$OFFLINE_PLAYBACK_SERVICE\" " +
                    "android:exported=\"false\" android:foregroundServiceType=\"mediaPlayback\" />\n</application>"
            )
        }

        manifest.writeText(xml)
    }
}

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/DownloadsPatch;"
private const val EXTENSION_PROTOCOL_BUFFER_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/DownloadsPatch$ProtocolBufferFieldInterface;"


@Suppress("unused")
val downloadsPatch = bytecodePatch(
    name = "Downloads",
    description = "Adds support to download songs using the in-app download button, " +
        "either with an external downloader app or inside YouTube Music.",
) {
    dependsOn(
        downloadsResourcePatch,
        settingsPatch,
        textComponentPatch,
        musicVideoInformationPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        hookSpannableString(EXTENSION_CLASS)

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

        OfflineVideoEndpointFingerprint.method.cloneParameters().apply {
            mutableClassDefBy(parameterTypes[0].toString()).interfaces.apply {
                if (!contains(EXTENSION_PROTOCOL_BUFFER_INTERFACE)) add(EXTENSION_PROTOCOL_BUFFER_INTERFACE)
            }

            addInstructionsWithLabels(
                0,
                """
                    invoke-static { p1, p2 }, $EXTENSION_CLASS->offlineVideoEndpointOnClick(${EXTENSION_PROTOCOL_BUFFER_INTERFACE}Ljava/util/Map;)Z
                    move-result v0
                    if-eqz v0, :show_native_downloader
                    return-void
                    :show_native_downloader
                    nop
                """
            )
        }
    }
}
