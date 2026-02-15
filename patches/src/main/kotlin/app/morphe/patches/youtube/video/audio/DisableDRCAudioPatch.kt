package app.morphe.patches.youtube.video.audio

import app.morphe.patches.shared.misc.audio.drc.disableDRCAudioPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

@Suppress("unused")
val disableDRCAudioPatch = disableDRCAudioPatch(
    block = {
        dependsOn(
            sharedExtensionPatch,
            settingsPatch,
        )

        compatibleWith(COMPATIBILITY_YOUTUBE)
    },
    preferenceScreen = PreferenceScreen.VIDEO,
)
