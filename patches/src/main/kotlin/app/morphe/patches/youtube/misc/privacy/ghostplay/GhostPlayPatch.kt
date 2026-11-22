/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5197
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.privacy.ghostplay

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.misc.request.buildRequestPatch
import app.morphe.patches.shared.misc.request.hookBuildRequest
import app.morphe.patches.shared.misc.request.hookMediaRequest
import app.morphe.patches.shared.misc.request.mediaRequestPatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.buttons.overlay.addPlayerOverlayPreferences
import app.morphe.patches.youtube.layout.buttons.overlay.playerOverlayButtonsSettingsPatch
import app.morphe.patches.youtube.layout.flyout.flyoutPatch
import app.morphe.patches.youtube.layout.player.buttons.addPlayerBottomButton
import app.morphe.patches.youtube.layout.player.buttons.playerOverlayButtonsHookPatch
import app.morphe.patches.youtube.layout.player.icons.copyPlayerButtonIcons
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.loadvideo.loadVideoHookPatch
import app.morphe.patches.youtube.misc.navigation.navigationBarHookPatch
import app.morphe.patches.youtube.misc.playercontrols.addLegacyBottomControl
import app.morphe.patches.youtube.misc.playercontrols.initializeLegacyBottomControl
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.hookVideoStarted
import app.morphe.patches.youtube.shared.startVideoInformerPatch
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.patches.youtube.video.playerresponse.Hook
import app.morphe.patches.youtube.video.playerresponse.addPlayerResponseMethodHook
import app.morphe.patches.youtube.video.playerresponse.playerResponseMethodHookPatch
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/ghostplay/GhostPlayPatch;"

private const val EXTENSION_BUTTON =
    "Lapp/morphe/extension/youtube/videoplayer/GhostPlayButton;"

/**
 * Returns at the start of the method if the extension blocks the stats of the video.
 *
 * @param videoIdField Field of this class with the video id of the stats, or null to use the current video.
 */
private fun MutableMethod.addStatsGuard(videoIdField: FieldReference? = null) {
    // v0 is free at the start of the method, as long as it is not a parameter.
    require(p0Register > 0) { "No free register in $this" }

    addInstructionsWithLabels(
        0,
        if (videoIdField == null) {
            "const/4 v0, 0x0"
        } else {
            """
                move-object/from16 v0, p0
                iget-object v0, v0, $videoIdField
            """
        } + """
            invoke-static { v0 }, $EXTENSION_CLASS->shouldBlockStats(Ljava/lang/String;)Z
            move-result v0
            if-eqz v0, :allow
            return-void
            :allow
            nop
        """
    )
}

@Suppress("unused")
val ghostPlayPatch = patch(
    name = "Ghost mode",
    description = "Adds a ghost mode to watch videos without affecting YouTube watch history or recommendations."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        buildRequestPatch,
        mediaRequestPatch,
        flyoutPatch,
        loadVideoHookPatch,
        videoInformationPatch,
        playerResponseMethodHookPatch,
        startVideoInformerPatch,
        navigationBarHookPatch,
        playerOverlayButtonsSettingsPatch,
        playerOverlayButtonsHookPatch,
        legacyPlayerControlsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_ghost_play_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    PreferenceCategory(
                        key = "morphe_ghost_play_cat_general",
                        sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                        preferences = setOf(
                            SwitchPreference("morphe_ghost_play_enabled", summary = true),
                            SwitchPreference("morphe_ghost_play_mode_active", summary = true),
                            SwitchPreference("morphe_ghost_play_persist_mode", summary = true)
                        )
                    ),
                    PreferenceCategory(
                        key = "morphe_ghost_play_cat_buttons",
                        sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                        preferences = setOf(
                            SwitchPreference("morphe_ghost_play_toolbar_button", summary = true),
                            SwitchPreference("morphe_ghost_play_flyout_button", summary = true)
                        )
                    ),
                    PreferenceCategory(
                        key = "morphe_ghost_play_cat_rules",
                        sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                        preferences = setOf(
                            NonInteractivePreference(
                                key = "morphe_ghost_play_manage_channels",
                                tag = "app.morphe.extension.youtube.settings.preference.GhostChannelsPreference"
                            ),
                            SwitchPreference("morphe_ghost_play_auto_shorts", summary = true),
                            SwitchPreference("morphe_ghost_play_auto_search", summary = true)
                        )
                    ),
                    PreferenceCategory(
                        key = "morphe_ghost_play_cat_history",
                        sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                        preferences = setOf(
                            NonInteractivePreference(
                                key = "morphe_ghost_play_view_history",
                                tag = "app.morphe.extension.youtube.settings.preference.GhostHistoryPreference"
                            ),
                            SwitchPreference("morphe_ghost_play_local_history", summary = true),
                            ListPreference(
                                key = "morphe_ghost_play_history_limit",
                                tag = "app.morphe.extension.youtube.settings.preference.GhostHistoryLimitPreference"
                            )
                        )
                    ),
                    PreferenceCategory(
                        key = "morphe_ghost_play_cat_advanced",
                        sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                        preferences = setOf(
                            SwitchPreference("morphe_ghost_play_anonymize_requests", summary = true)
                        )
                    )
                )
            )
        )

        addPlayerOverlayPreferences(
            SwitchPreference("morphe_ghost_play_player_button", summary = true)
        )

        copyPlayerButtonIcons(
            "ghostplay",
            "morphe_ghost_play_button_off",
            "morphe_ghost_play_button_on"
        )

        copyResources(
            "ghostplay",
            ResourceGroup(
                "drawable",
                "morphe_ghost_play_flyout.xml",
                "morphe_ghost_play_timer.xml",
                "morphe_ghost_play_power.xml",
                "morphe_ghost_play_channels.xml"
            )
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)

        // Before the other hooks, so they also use the headers without the account.
        hookBuildRequest(
            "$EXTENSION_CLASS->overrideRequestHeaders",
            hookHeader = true,
            includeBody = true,
            first = true
        )

        // Intercept native C++ NetFetch requests (Onesie / player / stats) to strip auth headers.
        hookMediaRequest(
            "$EXTENSION_CLASS->onMediaRequest",
            replacesRequest = true
        )

        // Capture incoming video loads (including Shorts & search opens) before stats clients start.
        addPlayerResponseMethodHook(
            Hook.VideoId("$EXTENSION_CLASS->onVideoResponse(Ljava/lang/String;Z)V")
        )
        hookVideoStarted("$EXTENSION_CLASS->onVideoStarted()V")

        // Block the playback, watchtime and delayplay pings and their log events.
        val videoStats2VideoIdField = VideoStats2DocIdFingerprint.instructionMatches[3].fieldReference
        VideoStats2SendPingFingerprint.method.addStatsGuard(videoStats2VideoIdField)
        VideoStats2LogEventFingerprint.method.addStatsGuard(videoStats2VideoIdField)

        // Block the WatchTimeSegment log events.
        VideoStats3FinishSegmentFingerprint.method.addStatsGuard(
            VideoStats3SegmentIdsFingerprint.instructionMatches[2].fieldReference
        )

        // Block the playback tracking pings.
        PtrackingSendFingerprint.method.addStatsGuard()

        // Player overlay button (modern + legacy).
        addPlayerBottomButton(EXTENSION_BUTTON)
        initializeLegacyBottomControl(EXTENSION_BUTTON)

        addLegacyBottomControl("ghostplay")
    }
}
