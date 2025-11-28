package app.morphe.patches.music.misc.privacy

import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.shared.misc.privacy.sanitizeSharingLinksPatch

@Suppress("unused")
val sanitizeSharingLinksPatch = sanitizeSharingLinksPatch(
    block = {
        dependsOn(
            sharedExtensionPatch,
            settingsPatch,
        )

        compatibleWith(
            "com.google.android.apps.youtube.music"(
                "7.29.52",
                "8.10.52",
                "8.46.57",
            )
        )
    },
    preferenceScreen = PreferenceScreen.MISC,
    replaceMusicLinksWithYouTube = true
)