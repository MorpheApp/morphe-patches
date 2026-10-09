/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3114
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.video.series

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.buttons.navigation.navigationBarPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playercontrols.addTopControl
import app.morphe.patches.youtube.misc.playercontrols.initializeTopControl
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsPatch
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsResourcePatch
import app.morphe.patches.youtube.misc.playservice.is_21_02_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.onCreateHook
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.patches.youtube.video.information.videoTimeHook
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources
import java.util.logging.Logger

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
                "morphe_series_tracker_button.xml",
                "morphe_series_tracker_button_bold.xml",
            ),
            ResourceGroup("layout", "morphe_series_tracker_history.xml"),
        )
    }

    finalize {
        addTopControl(
            "seriestracker",
            "@+id/morphe_series_tracker_button",
            "@+id/morphe_series_tracker_button"
        )
    }
}

@Suppress("unused")
val seriesTrackerPatch = bytecodePatch(
    name = "Series tracking",
    description = "Adds a series library to History with local progress and episode continuation. " +
            "This patch works with YouTube 21.02 and newer.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        versionCheckPatch,
        videoInformationPatch,
        navigationBarPatch,
        seriesTrackerResourcesPatch,
        legacyPlayerControlsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        if (!is_21_02_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Series tracking' requires YouTube 21.02+"
            )
        }

        PreferenceScreen.GENERAL.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_series_tracker_screen",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_series_tracker", summary = true),
                    SwitchPreference("morphe_series_tracker_show_history_tab", summary = true),
                    SwitchPreference("morphe_series_tracker_show_button", summary = true),
                    SwitchPreference(
                        "morphe_series_tracker_record_followed_progress",
                        summary = true,
                        tag = "$EXTENSION_PACKAGE.RecordingPreference"
                    ),
                    SwitchPreference(
                        "morphe_series_tracker_youtube_progress",
                        summary = true,
                        tag = "$EXTENSION_PACKAGE.SyncPreference"
                    ),
                    NonInteractivePreference(
                        "morphe_series_tracker_completion",
                        summaryKey = null,
                        tag = "$EXTENSION_PACKAGE.CompletionPreference",
                        selectable = true
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
    }
}
