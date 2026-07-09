/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.flyoutmenu.components

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.getResourceId
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.litho.filter.addLithoFilter
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstLiteralInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/HideFlyoutMenuComponentsPatch;"

private const val PLAYER_FLYOUT_MENU_COMPONENTS_FILTER =
    "Lapp/morphe/extension/music/patches/components/PlayerFlyoutMenuComponentsFilter;"

@Suppress("unused")
val hideFlyoutMenuComponentsPatch = bytecodePatch(
    name = "Hide flyout menu components",
    description = "Adds options to hide individual items from the player and queue flyout menus."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        lithoFilterPatch,
        resourceMappingPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_music_hide_flyout_menu_components_screen",
                preferences = setOf(
                    SwitchPreference("morphe_music_hide_flyout_menu_3_column_component"),
                    SwitchPreference("morphe_music_hide_flyout_menu_like_dislike"),
                    SwitchPreference("morphe_music_hide_flyout_menu_taste_match"),
                    SwitchPreference("morphe_music_hide_flyout_menu_add_to_listen_later"),
                    SwitchPreference("morphe_music_hide_flyout_menu_add_to_queue"),
                    SwitchPreference("morphe_music_hide_flyout_menu_captions"),
                    SwitchPreference("morphe_music_hide_flyout_menu_delete_playlist"),
                    SwitchPreference("morphe_music_hide_flyout_menu_dismiss_queue"),
                    SwitchPreference("morphe_music_hide_flyout_menu_dont_recommend_artist"),
                    SwitchPreference("morphe_music_hide_flyout_menu_download"),
                    SwitchPreference("morphe_music_hide_flyout_menu_edit_playlist"),
                    SwitchPreference("morphe_music_hide_flyout_menu_go_to_album"),
                    SwitchPreference("morphe_music_hide_flyout_menu_go_to_artist"),
                    SwitchPreference("morphe_music_hide_flyout_menu_go_to_episode"),
                    SwitchPreference("morphe_music_hide_flyout_menu_go_to_podcast"),
                    SwitchPreference("morphe_music_hide_flyout_menu_help"),
                    SwitchPreference("morphe_music_hide_flyout_menu_mark_episode_as_played"),
                    SwitchPreference("morphe_music_hide_flyout_menu_not_interested"),
                    SwitchPreference("morphe_music_hide_flyout_menu_pin_to_speed_dial"),
                    SwitchPreference("morphe_music_hide_flyout_menu_play_next"),
                    SwitchPreference("morphe_music_hide_flyout_menu_quality"),
                    SwitchPreference("morphe_music_hide_flyout_menu_remove_from_library"),
                    SwitchPreference("morphe_music_hide_flyout_menu_remove_from_playlist"),
                    SwitchPreference("morphe_music_hide_flyout_menu_report"),
                    SwitchPreference("morphe_music_hide_flyout_menu_save_episode_for_later_save_to_library"),
                    SwitchPreference("morphe_music_hide_flyout_menu_save_to_playlist"),
                    SwitchPreference("morphe_music_hide_flyout_menu_share"),
                    SwitchPreference("morphe_music_hide_flyout_menu_shuffle_play"),
                    SwitchPreference("morphe_music_hide_flyout_menu_sleep_timer"),
                    SwitchPreference("morphe_music_hide_flyout_menu_start_radio"),
                    SwitchPreference("morphe_music_hide_flyout_menu_stats_for_nerds"),
                    SwitchPreference("morphe_music_hide_flyout_menu_subscribe"),
                    SwitchPreference("morphe_music_hide_flyout_menu_unpin_from_speed_dial"),
                    SwitchPreference("morphe_music_hide_flyout_menu_view_song_credit")
                )
            )
        )

        MenuItemFingerprint.method.apply {
            // Register that receives the OR_INT_LIT16 result is free to reuse as the
            // return value of hideComponents(Enum) before the following instructions run.
            val freeIndex = indexOfFirstInstructionOrThrow(Opcode.OR_INT_LIT16)
            val freeRegister = getInstruction<TwoRegisterInstruction>(freeIndex).registerA

            // The enum is produced by a static (I)L... call resolving an int to the enum instance.
            val enumIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_STATIC &&
                    getReference<MethodReference>()?.let { ref ->
                        ref.parameterTypes.size == 1 &&
                            ref.parameterTypes.first() == "I" &&
                            ref.returnType.startsWith("L")
                    } == true
            } + 1
            val enumRegister = getInstruction<OneRegisterInstruction>(enumIndex).registerA

            addInstructionsWithLabels(
                enumIndex + 1,
                """
                    invoke-static { v$enumRegister }, $EXTENSION_CLASS->hideComponents(Ljava/lang/Enum;)Z
                    move-result v$freeRegister
                    if-nez v$freeRegister, :hide
                """,
                ExternalLabel("hide", getInstruction(implementation!!.instructions.lastIndex))
            )
        }

        // Legacy View-level hide for older YT Music versions where like/dislike is a
        // native child of `end_buttons_container`. From ~9.x the container is empty and
        // the button is drawn via Litho (see `like_toggle_button.` filter in
        // PlayerFlyoutMenuComponentsFilter). Kept side-by-side as a fallback for
        // pre-Litho builds; the two hooks share HIDE_FLYOUT_MENU_LIKE_DISLIKE and are
        // mutually harmless when both fire.
        val endButtonsContainer = getResourceId(ResourceType.ID, "end_buttons_container")
        EndButtonsContainerFingerprint.method.apply {
            val startIndex = indexOfFirstLiteralInstructionOrThrow(endButtonsContainer)
            val targetIndex = indexOfFirstInstructionOrThrow(startIndex, Opcode.MOVE_RESULT_OBJECT)
            val targetRegister = getInstruction<OneRegisterInstruction>(targetIndex).registerA

            addInstruction(
                targetIndex + 1,
                "invoke-static { v$targetRegister }, $EXTENSION_CLASS->hideLikeDislikeContainer(Landroid/view/View;)V"
            )
        }

        addLithoFilter(PLAYER_FLYOUT_MENU_COMPONENTS_FILTER)
    }
}
