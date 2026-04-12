/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.hide.player.flyoutmenu

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.fix.proto.fixProtoLibraryPatch
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.litho.filter.addLithoFilter
import app.morphe.patches.youtube.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.youtube.misc.litho.node.hookTreeNodeResult
import app.morphe.patches.youtube.misc.litho.node.treeNodeElementHookPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.proto.elementProtoParserHookPatch
import app.morphe.patches.youtube.misc.proto.hookElement
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.fiveRegisters

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/HidePlayerFlyoutMenuPatch;"
private const val EXTENSION_FILTER_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/components/PlayerFlyoutMenuComponentsFilter;"

@Suppress("unused")
val hidePlayerFlyoutMenuComponentsPatch = bytecodePatch(
    name = "Hide player flyout menu components",
    description = "Adds options to hide menu components that appear when pressing the gear icon in the video player.",
) {
    dependsOn(
        lithoFilterPatch,
        playerTypeHookPatch,
        resourceMappingPatch,
        settingsPatch,
        elementProtoParserHookPatch,
        fixProtoLibraryPatch,
        treeNodeElementHookPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_hide_player_flyout",
                preferences = setOf(
                    SwitchPreference("morphe_hide_player_flyout_captions"),
                    SwitchPreference("morphe_hide_player_flyout_captions_footer"),
                    SwitchPreference("morphe_hide_player_flyout_captions_header"),
                    SwitchPreference("morphe_hide_player_flyout_listen_with_youtube_music"),
                    SwitchPreference("morphe_hide_player_flyout_help"),
                    SwitchPreference("morphe_hide_player_flyout_speed"),
                    SwitchPreference("morphe_hide_player_flyout_lock_screen"),
                    SwitchPreference(
                        key = "morphe_hide_player_flyout_audio_track",
                        tag = "app.morphe.extension.youtube.settings.preference.HideAudioFlyoutMenuPreference"
                    ),
                    SwitchPreference(
                        key = "morphe_hide_player_flyout_audio_track_footer",
                        tag = "app.morphe.extension.youtube.settings.preference.HideAudioFlyoutMenuPreference"
                    ),
                    SwitchPreference("morphe_hide_player_flyout_quality"),
                    SwitchPreference("morphe_hide_player_flyout_quality_footer"),
                    SwitchPreference("morphe_hide_player_flyout_quality_header"),
                    SwitchPreference("morphe_hide_player_flyout_additional_settings"),
                    SwitchPreference("morphe_hide_player_flyout_ambient_mode"),
                    SwitchPreference("morphe_hide_player_flyout_stable_volume"),
                    SwitchPreference("morphe_hide_player_flyout_loop_video"),
                    SwitchPreference("morphe_hide_player_flyout_sleep_timer"),
                    SwitchPreference("morphe_hide_player_flyout_watch_in_vr"),
                )
            )
        )

        addLithoFilter(EXTENSION_FILTER_CLASS_DESCRIPTOR)
        hookElement("$EXTENSION_CLASS_DESCRIPTOR->hideNativeBottomSheetHeader([B)[B")
        hookTreeNodeResult(
            descriptor = "$EXTENSION_CLASS_DESCRIPTOR->hideNativeBottomSheetFooter",
            isLazilyConvertedElement = false
        )

        // region Patch for the Shorts flyout

        CaptionsOldBottomSheetLayoutInflaterFingerprint.matchAll().forEach { match ->
            match.let {
                it.method.apply {
                    val footerViewIndex = it.instructionMatches.last().index
                    val footerViewArgs = fiveRegisters(footerViewIndex)

                    replaceInstruction(
                        footerViewIndex,
                        "invoke-static { $footerViewArgs }, $EXTENSION_CLASS_DESCRIPTOR->" +
                                "hideCaptionsOldBottomSheetFooter(Landroid/widget/ListView;Landroid/view/View;Ljava/lang/Object;Z)V"
                    )

                    val headerViewIndex = it.instructionMatches[1].index
                    val headerViewArgs = fiveRegisters(headerViewIndex)

                    replaceInstruction(
                        headerViewIndex,
                        "invoke-static { $headerViewArgs }, $EXTENSION_CLASS_DESCRIPTOR->" +
                                "hideCaptionsOldBottomSheetHeader(Landroid/view/View;I)Landroid/view/View;"
                    )
                }
            }
        }

        QualityOldBottomSheetLayoutInflaterFingerprint.matchAll().forEach { match ->
            match.let {
                it.method.apply {
                    val footerViewIndex = it.instructionMatches.last().index
                    val footerViewArgs = fiveRegisters(footerViewIndex)

                    replaceInstruction(
                        footerViewIndex,
                        "invoke-static { $footerViewArgs }, $EXTENSION_CLASS_DESCRIPTOR->" +
                                "hideQualityOldBottomSheetFooter(Landroid/widget/ListView;Landroid/view/View;Ljava/lang/Object;Z)V"
                    )

                    val headerViewIndex = it.instructionMatches[1].index
                    val headerViewArgs = fiveRegisters(headerViewIndex)

                    replaceInstruction(
                        headerViewIndex,
                        "invoke-static { $headerViewArgs }, $EXTENSION_CLASS_DESCRIPTOR->" +
                                "hideQualityOldBottomSheetHeader(Landroid/widget/ListView;Landroid/view/View;Ljava/lang/Object;Z)V"
                    )
                }
            }
        }

        // endregion

    }
}
