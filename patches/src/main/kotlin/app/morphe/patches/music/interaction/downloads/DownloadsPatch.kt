/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1881
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
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

private val downloadsResourcePatch = resourcePatch {
    dependsOn(settingsPatch)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_external_downloader_screen",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_external_downloader_action_button", summary = true),
                    TextPreference(
                        "morphe_external_downloader_name",
                        tag = "app.morphe.extension.shared.settings.preference.ExternalDownloaderPreference"
                    )
                )
            )
        )
    }
}

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/DownloadsPatch;"
private const val EXTENSION_PROTOCOL_BUFFER_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/DownloadsPatch$ProtocolBufferFieldInterface;"


@Suppress("unused")
val downloadsPatch = bytecodePatch(
    name = "Downloads",
    description = "Adds support to download songs with an external downloader app using the in-app download button.",
) {
    dependsOn(
        downloadsResourcePatch,
        settingsPatch,
        textComponentPatch,
        musicVideoInformationPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        hookSpannableString(EXTENSION_CLASS, "onLithoTextLoaded")

        CommandResolverFingerprint.method.apply {
            // Add interface to get buffer.
            mutableClassDefBy(parameterTypes[1].toString())
                .interfaces.add(EXTENSION_PROTOCOL_BUFFER_INTERFACE)

            addInstruction(
                0,
                "invoke-static { p1, p2 }, $EXTENSION_CLASS->" +
                        "commandResolverOnClick(${EXTENSION_PROTOCOL_BUFFER_INTERFACE}Ljava/util/Map;)Z"
            )
        }

        OfflineVideoEndpointFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static { p2 }, $EXTENSION_CLASS->inAppDownloadButtonOnClick(Ljava/util/Map;)Z
                move-result v0
                if-eqz v0, :show_native_downloader
                return-void
                :show_native_downloader
                nop
            """
        )
    }
}
