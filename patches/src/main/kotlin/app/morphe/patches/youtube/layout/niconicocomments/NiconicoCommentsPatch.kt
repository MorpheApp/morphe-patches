/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4080
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 * 
 * Niconico-style live/replay comments implementation is adapted from
 * https://github.com/david419kr/niconico-yt-morphe-patches (GPLv3)
 * Feature design is based on david419kr/niconico-yt (MIT License)
 */

package app.morphe.patches.youtube.layout.niconicocomments

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.player.buttons.addPlayerBottomButton
import app.morphe.patches.youtube.layout.player.buttons.playerOverlayButtonsHookPatch
import app.morphe.patches.youtube.layout.player.icons.copyPlayerButtonIcons
import app.morphe.patches.youtube.layout.sponsorblock.ControlsOverlayFingerprint
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playercontrols.addLegacyBottomControl
import app.morphe.patches.youtube.misc.playercontrols.initializeLegacyBottomControl
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.patches.youtube.video.information.videoTimeHook
import app.morphe.patches.youtube.video.videoid.hookVideoId
import app.morphe.patches.youtube.video.videoid.videoIdPatch
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/niconicocomments/NiconicoCommentsController;"

private const val EXTENSION_BUTTON =
    "Lapp/morphe/extension/youtube/niconicocomments/NiconicoCommentsToggleButton;"

private val niconicoCommentsResourcePatch = resourcePatch {
    dependsOn(legacyPlayerControlsPatch)

    execute {
        copyPlayerButtonIcons(
            "niconicocommentsbutton",
            "morphe_niconico_comments_button_on",
            "morphe_niconico_comments_button_off"
        )
        addLegacyBottomControl("niconicocommentsbutton")
    }
}

@Suppress("unused")
val niconicoCommentsPatch = bytecodePatch(
    name = "Niconico comments",
    description = "Adds an option to show live chat and chat replay as comments that scroll across the video."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        playerTypeHookPatch,
        videoIdPatch,
        videoInformationPatch,
        playerOverlayButtonsHookPatch,
        legacyPlayerControlsPatch,
        niconicoCommentsResourcePatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_niconico_comments_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference(
                        "morphe_niconico_comments",
                        titleKey = "morphe_niconico_comments_screen_title",
                        summary = true
                    ),
                    NonInteractivePreference(
                        "morphe_niconico_comments_font_size",
                        tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference",
                        selectable = true
                    ),
                    NonInteractivePreference(
                        "morphe_niconico_comments_opacity",
                        tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference",
                        selectable = true
                    ),
                    NonInteractivePreference(
                        "morphe_niconico_comments_travel_duration",
                        tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference",
                        selectable = true
                    ),
                    NonInteractivePreference(
                        "morphe_niconico_comments_max_length",
                        tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference",
                        selectable = true
                    )
                )
            )
        )

        hookVideoId("$EXTENSION_CLASS->newVideoLoaded(Ljava/lang/String;)V")
        videoTimeHook(EXTENSION_CLASS, "setVideoTime")

        addPlayerBottomButton(EXTENSION_BUTTON)
        initializeLegacyBottomControl(EXTENSION_BUTTON)

        // Add the comments overlay to the same player layout as the SponsorBlock overlay.
        ControlsOverlayFingerprint.let {
            it.clearMatch() // Parent fingerprint is shared and may have changed.
            it.method.apply {
                val checkCastIndex = it.instructionMatches.last().index
                val frameLayoutRegister = getInstruction<OneRegisterInstruction>(checkCastIndex).registerA
                addInstruction(
                    checkCastIndex + 1,
                    "invoke-static { v$frameLayoutRegister }, $EXTENSION_CLASS->initialize(Landroid/view/ViewGroup;)V"
                )
            }
        }
    }
}
