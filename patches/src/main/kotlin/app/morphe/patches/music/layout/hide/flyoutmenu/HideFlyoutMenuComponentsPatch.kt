/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.layout.hide.flyoutmenu

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.resourceId
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.litho.filter.addLithoFilter
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstLiteralInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/HideFlyoutMenuComponentsPatch;"

private const val PLAYER_FLYOUT_MENU_COMPONENTS_FILTER =
    "Lapp/morphe/extension/music/patches/components/PlayerFlyoutMenuComponentsFilter;"

val hideFlyoutMenuComponentsPatch = patch(
    name = "Hide flyout menu components",
    description = "Adds options to hide individual items from the player and queue flyout menus."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        lithoFilterPatch
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

        // Resolve the icon type enum the same way the app does when it sets the item icon,
        // and skip adding the item view to its container if the item is hidden.
        val iconMatches = MenuItemIconFingerprint.instructionMatches
        val menuItemRendererField = iconMatches[1].fieldReference
        val iconMethod = iconMatches[2].methodReference
        val iconTypeField = iconMatches[4].fieldReference
        val iconTypeEnumMethod = iconMatches[5].methodReference

        MenuItemFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches[1].index
                val register = it.instructionMatches[1].getInstruction<TwoRegisterInstruction>().registerA

                addInstructionsWithLabels(
                    index,
                    """
                        iget-object v$register, p0, $menuItemRendererField
                        invoke-static { v$register }, $iconMethod
                        move-result-object v$register
                        if-eqz v$register, :show
                        iget v$register, v$register, $iconTypeField
                        invoke-static { v$register }, $iconTypeEnumMethod
                        move-result-object v$register
                        invoke-static { v$register }, $EXTENSION_CLASS->hideComponents(Ljava/lang/Enum;)Z
                        move-result v$register
                        if-nez v$register, :hide
                        :show
                        nop
                    """,
                    ExternalLabel("hide", getInstruction(implementation!!.instructions.lastIndex))
                )
            }
        }

        // Legacy View-level hide for older YT Music versions where like/dislike is a
        // native child of `end_buttons_container`. From ~9.x the container is empty and
        // the button is drawn via Litho (see `like_toggle_button.` filter in
        // PlayerFlyoutMenuComponentsFilter). Kept side-by-side as a fallback for
        // pre-Litho builds; the two hooks share HIDE_FLYOUT_MENU_LIKE_DISLIKE and are
        // mutually harmless when both fire.
        val endButtonsContainer = resourceId(ResourceType.ID, "end_buttons_container")
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
