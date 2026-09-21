package app.morphe.patches.youtube.video.series

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.BasePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.youtube.layout.buttons.navigation.navigationBarPatch
import app.morphe.patches.youtube.layout.player.buttons.addPlayerBottomButton
import app.morphe.patches.youtube.layout.player.buttons.playerOverlayButtonsHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playercontrols.addTopControl
import app.morphe.patches.youtube.misc.playercontrols.initializeTopControl
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsPatch
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsResourcePatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.video.information.onCreateHook
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.patches.youtube.video.information.videoTimeHook
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources

private const val EXTENSION_CLASS = "${OUR_PREFIX}SeriesTrackerPatch;"
private const val BUTTON = "${OUR_PREFIX}SeriesPlayerButton;"
private const val EXTENSION_PACKAGE = "app.morphe.extension.youtube.series"

private val seriesTrackerResourcesPatch = resourcePatch {
    dependsOn(legacyPlayerControlsResourcePatch)
    execute {
        copyResources(
            "seriestracker",
            ResourceGroup(
                "drawable",
                "series_tracker_button.xml",
                "series_tracker_button_bold.xml",
            ),
            ResourceGroup("layout", "series_tracker_history.xml"),
        )
    }
    finalize {
        addTopControl("seriestracker", "@+id/series_tracker_button", "@+id/series_tracker_button")
    }
}

@Suppress("unused")
val seriesTrackerPatch =
    bytecodePatch(
        name = "Series tracking",
        description =
            "Adds a series library to History with local progress and episode continuation.",
    ) {
        dependsOn(
            sharedExtensionPatch,
            settingsPatch,
            videoInformationPatch,
            navigationBarPatch,
            seriesTrackerResourcesPatch,
            legacyPlayerControlsPatch,
            playerOverlayButtonsHookPatch,
        )
        // Keep support limited to versions covered by the host and device checks.
        compatibleWith(
            Compatibility(
                name = "YouTube",
                packageName = "com.google.android.youtube",
                apkFileType = ApkFileType.APK_REQUIRED,
                targets =
                    listOf(
                        AppTarget(version = "21.38.123", minSdk = 29, isExperimental = true),
                        AppTarget(version = "21.16.256", minSdk = 28),
                        AppTarget(version = "21.13.164", minSdk = 28),
                    ),
                appIconColor = 0xFF0033,
                signatures =
                    setOf(
                        "5aad2bee6db95d17e05a08d7d1e64c10a1511879154483916b6ae6c7fd9cb0c6",
                        "3d7a1223019aa39d9ea0e3436ab7c0896bfb4fb679f4de5fe7c23f326c8f994a",
                    ),
            )
        )
        execute {
            fun preference(key: String, title: String, summary: String?, tag: String) =
                object :
                    BasePreference(key = key, titleKey = title, summaryKey = summary, tag = tag) {}
            PreferenceScreen.GENERAL.addPreferences(
                PreferenceScreenPreference(
                    key = "series_tracker_screen",
                    titleKey = "series_tracker_title",
                    summaryKey = "series_tracker_summary",
                    sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                    preferences =
                        linkedSetOf(
                            preference(
                                "series_tracker_show_history_tab",
                                "series_tracker_history_tab_title",
                                "series_tracker_history_tab_summary",
                                "SwitchPreference",
                            ),
                            preference(
                                "series_tracker_show_button",
                                "series_tracker_show_button_title",
                                "series_tracker_show_button_summary",
                                "SwitchPreference",
                            ),
                            preference(
                                "series_tracker_record_followed_progress",
                                "series_tracker_record_title",
                                "series_tracker_record_summary",
                                "$EXTENSION_PACKAGE.RecordingPreference",
                            ),
                            preference(
                                "series_tracker_youtube_progress",
                                "series_tracker_sync_title",
                                "series_tracker_sync_summary",
                                "$EXTENSION_PACKAGE.SyncPreference",
                            ),
                            preference(
                                "series_tracker_completion",
                                "series_tracker_completion_title",
                                null,
                                "$EXTENSION_PACKAGE.CompletionPreference",
                            ),
                        ),
                )
            )
            wirePlaybackSource()
            wirePlaybackSession()
            val accountContract = wirePrivacy()
            wireHistory()
            wireNativeHistory(accountContract)
            onCreateHook(EXTENSION_CLASS, "newVideoStarted")
            videoTimeHook(EXTENSION_CLASS, "videoTimeChanged")
            initializeTopControl(BUTTON)
            addPlayerBottomButton(BUTTON)
        }
    }
