/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5197
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.privacy.ghostplay

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
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
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/ghostplay/GhostPlayPatch;"

private const val EXTENSION_BUTTON =
    "Lapp/morphe/extension/youtube/videoplayer/GhostPlayButton;"

private val ghostPlayResourcePatch = patch {
    dependsOn(
        settingsPatch,
        legacyPlayerControlsPatch
    )

    execute {
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

        addLegacyBottomControl("ghostplay")
    }
}

private fun MutableMethod.addGhostStatsGuard(videoIdField: FieldReference?) {
    val isStatic = AccessFlags.STATIC.isSet(accessFlags)
    if (videoIdField != null) {
        require(!isStatic) { "Cannot read instance field $videoIdField inside static method $this" }
    }
    val paramRegs = parameterTypes.sumOf { type ->
        val s = type.toString()
        if (s == "J" || s == "D") 2 else 1
    } + if (isStatic) 0 else 1
    val localRegs = implementation!!.registerCount - paramRegs
    require(localRegs >= 1) { "Method $this has no local register available for v0 guard" }

    val allowLabel = ExternalLabel("morphe_ghost_allow", getInstruction(0))
    if (videoIdField != null) {
        addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, p0
                iget-object v0, v0, $videoIdField
                invoke-static { v0 }, $EXTENSION_CLASS->shouldBlockStats(Ljava/lang/String;)Z
                move-result v0
                if-eqz v0, :morphe_ghost_allow
                return-void
            """,
            allowLabel
        )
    } else {
        addInstructionsWithLabels(
            0,
            """
                const/4 v0, 0x0
                invoke-static { v0 }, $EXTENSION_CLASS->shouldBlockStats(Ljava/lang/String;)Z
                move-result v0
                if-eqz v0, :morphe_ghost_allow
                return-void
            """,
            allowLabel
        )
    }
}

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
        ghostPlayResourcePatch
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
                            SwitchPreference("morphe_ghost_play_player_button", summary = true),
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
            SwitchPreference("morphe_ghost_play_player_button")
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)

        // Intercept InnerTube requests before downstream hooks so auth headers are stripped early.
        hookBuildRequest(
            "$EXTENSION_CLASS->overrideRequestHeaders",
            hookHeader = true,
            includeBody = true,
            highPriority = true
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

        // Block VSS2 (playback/watchtime/delayplay HTTP pings & GEL logging) for ghost videos.
        val vss2Class = VideoStats2SendPingFingerprint.classDef
        val vss2DocIdMatch = VideoStats2DocIdFingerprint.match(vss2Class)
        val vss2VideoIdField = vss2DocIdMatch.instructionMatches[3].fieldReference

        VideoStats2SendPingFingerprint.method.addGhostStatsGuard(vss2VideoIdField)
        val vss2GelMatch = VideoStats2SendGelFingerprint.matchOrNull(vss2Class)
        if (vss2GelMatch != null) {
            vss2GelMatch.method.addGhostStatsGuard(vss2VideoIdField)
        } else {
            VideoStats2FlushWatchtimeFingerprint.match(vss2Class).method.addGhostStatsGuard(vss2VideoIdField)
        }

        // Block VSS3 (WatchTimeSegment GEL logging) before monitor-enter for ghost videos.
        val vss3Class = VideoStats3FinishSegmentFingerprint.classDef
        val vss3FillStaticMethodRef = VideoStats3FinishSegmentFingerprint.method.instructions.first { ins ->
            ins.opcode == Opcode.INVOKE_DIRECT &&
                ins.getReference<MethodReference>()?.let { ref ->
                    ref.definingClass == vss3Class.type &&
                        ref.parameterTypes.isEmpty() &&
                        ref.returnType == "V"
                } == true
        }.getReference<MethodReference>()!!

        val vss3FillStaticMethod = vss3Class.methods.single {
            it.name == vss3FillStaticMethodRef.name &&
                it.parameterTypes.isEmpty() &&
                it.returnType == "V"
        }

        val vss3StringFields = vss3FillStaticMethod.instructions
            .filter { it.opcode == Opcode.IGET_OBJECT }
            .mapNotNull { it.getReference<FieldReference>() }
            .filter { it.definingClass == vss3Class.type && it.type == "Ljava/lang/String;" }
            .distinct()
        require(vss3StringFields.size >= 2) {
            "Expected at least 2 String fields in VSS3 static field filler $vss3FillStaticMethod"
        }
        val vss3VideoIdField = vss3StringFields[1]

        VideoStats3FinishSegmentFingerprint.method.addGhostStatsGuard(vss3VideoIdField)

        // Block PtrackingClient playback tracking pings (cmt/conn/cpn/remarketing).
        PtrackingSendFingerprint.method.addGhostStatsGuard(null)

        // Player overlay button (modern + legacy).
        addPlayerBottomButton(EXTENSION_BUTTON)
        initializeLegacyBottomControl(EXTENSION_BUTTON)
    }
}
