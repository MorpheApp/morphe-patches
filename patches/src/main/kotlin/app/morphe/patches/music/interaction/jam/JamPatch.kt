/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3014
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.jam

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.resourceXml
import app.morphe.patcher.patch.patch
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
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/jam/JamUI;"
private const val EXTENSION_BAR_CLASS = "Lapp/morphe/extension/music/patches/jam/JamBar;"

private val jamResources = patch {
    dependsOn(versionCheckPatch)
    execute {
        resourceXml("AndroidManifest.xml").use { manifest ->
            if (
                manifest.elements("uses-permission").none {
                    it.attribute("android:name") == "android.permission.ACCESS_WIFI_STATE"
                }
            ) {
                manifest.root.appendChild("uses-permission") {
                    setAttribute("android:name", "android.permission.ACCESS_WIFI_STATE")
                }
            }
            manifest.elements("application").first().appendChild("service") {
                setAttribute("android:name", "app.morphe.extension.music.patches.jam.JamBridgeService")
                setAttribute("android:exported", "true")
            }
            val queries = manifest.elements("queries").firstOrNull() ?: manifest.root.appendChild("queries")
            queries.appendChild("package") {
                setAttribute("android:name", "app.morphe.jam.companion")
            }
        }

        resourceXml("res/layout/player_bottom_sheet.xml").use { layout ->
            val tabs = layout.elementByAttribute("android:id", "@id/bottom_sheet_tabbed_view")
            tabs.parent!!.insertChild(tabs.index, "app.morphe.extension.music.patches.jam.JamBar") {
                setAttribute("android:layout_width", "match_parent")
                setAttribute("android:layout_height", "48dp")
            }
        }
    }
}

/**
 * Connects semantic YouTube Music anchors to the stable Jam extension API. Native discovery is kept
 * in [JamQueueAbi] and [JamUiAbi]; generated methods are small access or interception bridges.
 */
val jamQueueSharingPatch = patch(
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

        BottomSheetPeekHeightFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructions(
                    index + 1,
                    """
                        invoke-static { p0, v$register }, $EXTENSION_BAR_CLASS->getBottomSheetPeekHeight(Landroid/view/View;I)I
                        move-result v$register
                    """
                )
            }
        }

        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_music_jam_probe",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference(key = "morphe_music_jam_enabled", summary = true),
                    NonInteractivePreference(
                        key = "morphe_music_jam_download",
                        tag = "app.morphe.extension.music.patches.jam.JamDownloadPreference",
                        selectable = true,
                    ),
                    NonInteractivePreference(
                        key = "morphe_music_jam_controls",
                        tag = "app.morphe.extension.music.patches.jam.JamProbePreference",
                        selectable = true,
                    ),
                    NonInteractivePreference(
                        key = "morphe_music_jam_companion_package",
                        tag = "app.morphe.extension.music.patches.jam.JamCompanionPackagePreference",
                        selectable = true
                    )
                )
            )
        )
    }
}
