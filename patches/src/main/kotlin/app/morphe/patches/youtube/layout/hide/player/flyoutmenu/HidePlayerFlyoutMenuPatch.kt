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

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.getResourceId
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.litho.filter.addLithoFilter
import app.morphe.patches.youtube.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/HidePlayerFlyoutMenuPatch;"

@Suppress("unused")
val hidePlayerFlyoutMenuPatch = bytecodePatch(
    name = "Hide player flyout menu items",
    description = "Adds options to hide menu items that appear when pressing the gear icon in the video player.",
) {
    dependsOn(
        lithoFilterPatch,
        playerTypeHookPatch,
        resourceMappingPatch,
        settingsPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        val filterClassDescriptor = "Lapp/morphe/extension/youtube/patches/components/PlayerFlyoutMenuItemsFilter;"

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

        val bottomSheetFooterText = getResourceId(ResourceType.ID, "bottom_sheet_footer_text")

        PlayerFlyoutQualityInflateFingerprint.method.apply {
            val instructions = implementation!!.instructions.toList()

            val footerConstIndex = instructions.indexOfFirst {
                it is ReferenceInstruction && it.reference.toString() == bottomSheetFooterText.toString()
            }
            if (footerConstIndex != -1) {

                val moveResultIndex = instructions.subList(footerConstIndex, instructions.size).indexOfFirst {
                    it.opcode == Opcode.MOVE_RESULT_OBJECT
                } + footerConstIndex

                val viewRegister = getInstruction<OneRegisterInstruction>(moveResultIndex).registerA

                addInstruction(
                    moveResultIndex + 1,
                    "invoke-static { v$viewRegister }, $EXTENSION_CLASS_DESCRIPTOR->hidePlayerFlyoutMenuQualityFooter(Landroid/view/View;)V"
                )
            }

            val addHeaderIndex = instructions.indexOfFirst {
                it is ReferenceInstruction && it.reference.toString().contains("addHeaderView")
            }
            if (addHeaderIndex != -1) {

                val headerReg = getInstruction<FiveRegisterInstruction>(addHeaderIndex).registerD

                addInstructions(
                    addHeaderIndex,
                    """
                    invoke-static { v$headerReg }, $EXTENSION_CLASS_DESCRIPTOR->hidePlayerFlyoutMenuQualityHeader(Landroid/view/View;)Landroid/view/View;
                    move-result-object v$headerReg
                    """
                )
            }
        }

        PlayerFlyoutCaptionsInflateFingerprint.method.apply {
            val instructions = implementation!!.instructions.toList()
            val footerConstIndex = instructions.indexOfFirst {
                it is ReferenceInstruction && it.reference.toString() == bottomSheetFooterText.toString()
            }
            if (footerConstIndex != -1) {
                val moveResultIndex = instructions.subList(footerConstIndex, instructions.size).indexOfFirst {
                    it.opcode == Opcode.MOVE_RESULT_OBJECT
                } + footerConstIndex

                val viewRegister = getInstruction<OneRegisterInstruction>(moveResultIndex).registerA

                addInstruction(
                    moveResultIndex + 1,
                    "invoke-static { v$viewRegister }, $EXTENSION_CLASS_DESCRIPTOR->hidePlayerFlyoutMenuCaptionsFooter(Landroid/view/View;)V"
                )
            }
        }

        addLithoFilter(filterClassDescriptor)
    }
}
