package app.morphe.patches.youtube.layout.buttons.action

import app.revanced.patcher.patch.resourcePatch
import app.morphe.patches.all.misc.resources.addResources
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.litho.filter.addLithoFilter
import app.morphe.patches.youtube.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.youtube.misc.playservice.is_20_22_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import java.util.logging.Logger

@Suppress("unused")
val hideVideoActionButtonsPatch = resourcePatch(
    name = "Hide video action buttons",
    description = "Adds options to hide action buttons (such as the Download button) under videos. " +
            "Patching version 20.21.37 or lower can hide more player button types"
) {
    dependsOn(
        resourceMappingPatch,
        lithoFilterPatch,
        addResourcesPatch,
        versionCheckPatch,
    )

    compatibleWith(
        "com.google.android.youtube"(
            "19.43.41",
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.46.41",
        )
    )

    execute {
        addResources("youtube", "layout.buttons.action.hideButtonsPatch")

        val preferences = mutableSetOf(
            SwitchPreference("morphe_disable_like_subscribe_glow"),
            SwitchPreference("morphe_hide_download_button"),
            SwitchPreference("morphe_hide_like_dislike_button"),
            SwitchPreference("morphe_hide_comments_button"),
            SwitchPreference("morphe_hide_save_button"),
            SwitchPreference("morphe_hide_remix_button"),
            SwitchPreference("morphe_hide_share_button"),
        )

        if (is_20_22_or_greater) {
            // 20.22+ filtering of the action buttons doesn't work because
            // the buffer is the same for all buttons.
            // TODO: Eventually remove this warning.
            Logger.getLogger(this::class.java.name).warning(
                "\n!!!" +
                        "\n!!! Not all player action buttons can be set hidden when patching 20.22+" +
                        "\n!!! Patch 20.21.37 if you want to hide more player action buttons" +
                        "\n!!!"
            )
        } else {
            preferences.addAll(
                listOf(
                    SwitchPreference("morphe_hide_hype_button"),
                    SwitchPreference("morphe_hide_ask_button"),
                    SwitchPreference("morphe_hide_clip_button"),
                    SwitchPreference("morphe_hide_promote_button"),
                    SwitchPreference("morphe_hide_report_button"),
                    SwitchPreference("morphe_hide_shop_button"),
                    SwitchPreference("morphe_hide_stop_ads_button"),
                    SwitchPreference("morphe_hide_thanks_button"),
                )
            )
        }

        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                "morphe_hide_buttons_screen",
                preferences = preferences
            )
        )

        addLithoFilter("Lapp/morphe/extension/youtube/patches/components/VideoActionButtonsFilter;")
    }
}
