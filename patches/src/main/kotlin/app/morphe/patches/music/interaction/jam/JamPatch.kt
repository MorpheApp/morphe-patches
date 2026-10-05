/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3014
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.jam

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.playservice.versionCheckPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.shared.MusicActivityOnCreateFingerprint
import app.morphe.patches.music.video.information.musicVideoInformationPatch
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.findElementByAttributeValueOrThrow

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/jam/JamUi;"

private val jamResources = resourcePatch {
    dependsOn(versionCheckPatch)
    execute {
        document("AndroidManifest.xml").use { doc ->
            val permissions = doc.getElementsByTagName("uses-permission")
            if (
                (0 until permissions.length).none {
                    (permissions.item(it) as org.w3c.dom.Element).getAttribute("android:name") ==
                        "android.permission.ACCESS_WIFI_STATE"
                }
            ) {
                doc.documentElement.appendChild(
                    doc.createElement("uses-permission").apply {
                        setAttribute("android:name", "android.permission.ACCESS_WIFI_STATE")
                    }
                )
            }
            val service = doc.createElement("service")
            service.setAttribute("android:name", "app.morphe.extension.music.jam.JamBridgeService")
            service.setAttribute("android:exported", "true")
            doc.getElementsByTagName("application").item(0).appendChild(service)
            val queries =
                doc.getElementsByTagName("queries").item(0)
                    ?: doc.createElement("queries").also { doc.documentElement.appendChild(it) }
            queries.appendChild(
                doc.createElement("package").apply {
                    setAttribute("android:name", "app.morphe.jam.companion")
                }
            )
        }

        document("res/layout/player_bottom_sheet.xml").use { doc ->
            val root = doc.documentElement
            val tabs =
                root.childNodes.findElementByAttributeValueOrThrow(
                    "android:id",
                    "@id/bottom_sheet_tabbed_view",
                )
            root.insertBefore(
                doc.createElement("app.morphe.extension.music.jam.JamBar").apply {
                    setAttribute("android:layout_width", "match_parent")
                    setAttribute("android:layout_height", "48dp")
                },
                tabs,
            )
        }

        document("res/layout/watch_while_layout.xml").use { doc ->
            doc.documentElement.setAttribute("app:bottomSheetPeekHeight", "68dp")
        }
    }
}

/**
 * Connects semantic YouTube Music anchors to the stable Jam extension API. Native discovery is kept
 * in [JamQueueAbi] and [JamUiAbi]; generated methods are small access or interception bridges.
 */
@Suppress("unused")
val jamQueueSharingPatch = bytecodePatch(
    name = "Jam queue sharing",
    description = "Shares the host queue and playback controls through an authenticated Jam bridge. "
            + "Root installation is not supported."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        jamResources,
        musicVideoInformationPatch,
        versionCheckPatch,
    )
    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        val baseQueue = resolveJamQueueAbi()
        val ui = resolveJamUiAbi(baseQueue)
        val queue = baseQueue.copy(item = baseQueue.item.copy(menuPayload = ui.queueRow.menuPayload))

        installJamQueueBridges(queue)
        installJamUiBridges(ui, queue)

        MusicActivityOnCreateFingerprint.method.addInstructions(
            0,
            "invoke-static/range {p0 .. p0}, $EXTENSION_CLASS->install(Landroid/app/Activity;)V",
        )

        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_music_jam_probe",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference(key = "morphe_music_jam_enabled", summary = true),
                    NonInteractivePreference(
                        key = "morphe_music_jam_download",
                        tag = "app.morphe.extension.music.jam.JamDownloadPreference",
                        selectable = true,
                    ),
                    NonInteractivePreference(
                        key = "morphe_music_jam_controls",
                        tag = "app.morphe.extension.music.jam.JamProbePreference",
                        selectable = true,
                    ),
                    NonInteractivePreference(
                        key = "morphe_music_jam_companion_package",
                        tag = "app.morphe.extension.music.jam.JamCompanionPackagePreference",
                        selectable = true
                    )
                )
            )
        )
    }
}
