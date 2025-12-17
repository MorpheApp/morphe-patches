package app.morphe.patches.youtube.layout.hide.player.flyoutmenupanel

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.litho.filter.addLithoFilter
import app.morphe.patches.youtube.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch

val hidePlayerFlyoutMenuPatch = bytecodePatch(
    name = "Hide player flyout menu items",
    description = "Adds options to hide menu items that appear when pressing the gear icon in the video player.",
) {
    dependsOn(
        lithoFilterPatch,
        playerTypeHookPatch,
        settingsPatch
    )

    compatibleWith(
        "com.google.android.youtube"(
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.46.41",
        )
    )

    execute {
        val filterClassDescriptor = "Lapp/morphe/extension/youtube/patches/components/PlayerFlyoutMenuItemsFilter;"


        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_hide_player_flyout",
                preferences = setOf(
                    SwitchPreference("morphe_hide_player_flyout_captions"),
                    SwitchPreference("morphe_hide_player_flyout_listen_with_youtube_music"),
                    SwitchPreference("morphe_hide_player_flyout_help"),
                    SwitchPreference("morphe_hide_player_flyout_speed"),
                    SwitchPreference("morphe_hide_player_flyout_lock_screen"),
                    SwitchPreference(
                        key = "morphe_hide_player_flyout_audio_track",
                        tag = "app.morphe.extension.youtube.settings.preference.HideAudioFlyoutMenuPreference"
                    ),
                    SwitchPreference("morphe_hide_player_flyout_video_quality"),
                    SwitchPreference("morphe_hide_player_flyout_video_quality_footer"),
                    SwitchPreference("morphe_hide_player_flyout_additional_settings"),
                    SwitchPreference("morphe_hide_player_flyout_ambient_mode"),
                    SwitchPreference("morphe_hide_player_flyout_stable_volume"),
                    SwitchPreference("morphe_hide_player_flyout_loop_video"),
                    SwitchPreference("morphe_hide_player_flyout_sleep_timer"),
                    SwitchPreference("morphe_hide_player_flyout_watch_in_vr"),
                ),
            )
        )

        addLithoFilter(filterClassDescriptor)
    }
}
