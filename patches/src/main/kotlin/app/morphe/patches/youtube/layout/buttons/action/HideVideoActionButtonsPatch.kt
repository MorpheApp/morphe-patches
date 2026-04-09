/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.buttons.action

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.fix.proto.fixProtoLibraryPatch
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.litho.filter.addLithoFilter
import app.morphe.patches.youtube.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.youtube.misc.litho.node.treeNodeElementHookPatch
import app.morphe.patches.youtube.misc.litho.node.hookTreeNodeResult
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.WatchNextResponseParserFingerprint
import app.morphe.patches.youtube.video.information.videoInformationPatch
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val FILTER_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/components/VideoActionButtonsFilter;"

@Suppress("unused")
val hideVideoActionButtonsPatch = bytecodePatch(
    name = "Hide video action buttons",
    description = "Adds options to hide action buttons (such as the Download button) under videos."
) {
    dependsOn(
        settingsPatch,
        sharedExtensionPatch,
        lithoFilterPatch,
        treeNodeElementHookPatch,
        fixProtoLibraryPatch,
        videoInformationPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_hide_buttons_screen",
                preferences = setOf(
                    SwitchPreference("morphe_disable_like_subscribe_glow"),
                    SwitchPreference("morphe_hide_action_bar"),
                    SwitchPreference("morphe_hide_ask_button"),
                    SwitchPreference("morphe_hide_clip_button"),
                    SwitchPreference("morphe_hide_comments_button"),
                    SwitchPreference("morphe_hide_download_button"),
                    SwitchPreference("morphe_hide_hype_button"),
                    SwitchPreference("morphe_hide_like_dislike_button"),
                    SwitchPreference("morphe_hide_promote_button"),
                    SwitchPreference("morphe_hide_remix_button"),
                    SwitchPreference("morphe_hide_report_button"),
                    SwitchPreference("morphe_hide_save_button"),
                    SwitchPreference("morphe_hide_share_button"),
                    SwitchPreference("morphe_hide_shop_button"),
                    SwitchPreference("morphe_hide_stop_ads_button"),
                    SwitchPreference("morphe_hide_thanks_button"),
                )
            )
        )

        addLithoFilter(FILTER_CLASS_DESCRIPTOR)

        hookTreeNodeResult("$FILTER_CLASS_DESCRIPTOR->onLazilyConvertedElementLoaded")

        WatchNextResponseParserFingerprint.let {
            it.clearMatch() // Fingerprint is shared and indexes may no longer be correct.
            it.method.apply {
                val index = it.instructionMatches[5].index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstruction(
                    index + 1,
                    "invoke-static { v$register }, $FILTER_CLASS_DESCRIPTOR->" +
                            "onSingleColumnWatchNextResultsLoaded(Lcom/google/protobuf/MessageLite;)V"
                )
            }
        }
    }
}
