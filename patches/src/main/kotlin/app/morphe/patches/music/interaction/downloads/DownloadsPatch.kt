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
import app.morphe.patcher.util.addFreeRegisters
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.getReference
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.video.information.musicVideoInformationPatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.shared.misc.textcomponent.hookSpannableString
import app.morphe.patches.shared.misc.textcomponent.textComponentPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

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
                    ListPreference("morphe_download_mode"),
                    SwitchPreference("morphe_external_downloader_menu", summary = true),
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

        val (metadataCall, downloadCall) = OfflineVideoEndpointFingerprint.instructionMatches
        val endpointUtilsType = metadataCall.instruction.getReference<MethodReference>()!!.definingClass
        val videoIdFingerprint = selectedVideoIdFingerprint(endpointUtilsType)
        val selectedVideoIdMethod = videoIdFingerprint.method
        val rendererType = videoIdFingerprint.instructionMatches.first()
            .instruction.getReference<TypeReference>()!!.type
        val playerItem = playerMenuItemFingerprint(rendererType)
        val rendererField = playerItem.instructionMatches.single()
            .instruction.getReference<FieldReference>()!!
        mutableClassDefBy(EXTENSION_CLASS).methods.single { it.name == "getVideoId" }
            .cloneParameters().addInstructionsWithLabels(
                0,
                """
                    # The main-player menu wraps the same renderer used by queue rows.
                    instance-of v0, p0, ${rendererField.definingClass}
                    if-eqz v0, :read_video_id
                    check-cast p0, ${rendererField.definingClass}
                    iget-object p0, p0, $rendererField
                    :read_video_id
                    invoke-static { p0 }, $selectedVideoIdMethod
                    move-result-object p0
                    return-object p0
                """
            )

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

        OfflineVideoEndpointFingerprint.method.apply {
            val videoIdRegister = (downloadCall.instruction as FiveRegisterInstruction).registerD
            val resultRegister = implementation!!.registerCount
            val (method, addedInstructions) = addFreeRegisters(1)
            // Reuse the ID YTM resolved for its single-video download action.
            method.addInstructionsAtControlFlowLabel(
                downloadCall.index + addedInstructions,
                """
                    invoke-static/range { v$videoIdRegister .. v$videoIdRegister }, $EXTENSION_CLASS->offlineVideoEndpointOnClick(Ljava/lang/String;)Z
                    move-result v$resultRegister
                    if-eqz v$resultRegister, :show_native_downloader
                    return-void
                    :show_native_downloader
                    nop
                """
            )
        }
    }
}
