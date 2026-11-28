/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5137
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.video.effects

import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.videoeffects.videoEffectsPatch
import app.morphe.patches.youtube.layout.flyout.flyoutPatch
import app.morphe.patches.youtube.layout.hide.player.flyoutmenu.addPlayerFlyoutMenuPreferences
import app.morphe.patches.youtube.layout.hide.player.flyoutmenu.playerFlyoutPreferences
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.loadvideo.loadVideoHookPatch
import app.morphe.patches.youtube.misc.playservice.is_20_51_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.videoid.hookVideoId
import app.morphe.patches.youtube.video.videoid.videoIdPatch
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources

private val videoEffectsResourcePatch = patch {
    execute {
        copyResources(
            "videoeffects",
            ResourceGroup("drawable", "morphe_ic_video_effect.xml")
        )
    }
}

@Suppress("unused")
val videoEffectsPatch = videoEffectsPatch(
    description = "Adds an option to change the colors of videos or add visual effects, such as black and white. " +
            "This patch works with YouTube 20.51 and newer.",
    block = {
        dependsOn(
            sharedExtensionPatch,
            settingsPatch,
            versionCheckPatch,
            videoIdPatch,
            flyoutPatch,
            loadVideoHookPatch,
            playerFlyoutPreferences,
            videoEffectsResourcePatch
        )

        compatibleWith(COMPATIBILITY_YOUTUBE)
    },
    // 20.26 has an older video renderer without the passthrough shader.
    targetCompatible = { is_20_51_or_greater },
    preferenceScreen = PreferenceScreen.VIDEO,
    hookVideoId = ::hookVideoId,
    executeBlock = {
        addPlayerFlyoutMenuPreferences(
            SwitchPreference("morphe_video_effect_button_flyout", titleKey = "morphe_show_video_effect_menu_title")
        )
    }
)
