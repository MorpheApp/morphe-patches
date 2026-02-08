package app.morphe.patches.music.misc.privacy

import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY
import app.morphe.patches.shared.misc.privacy.sanitizeSharingLinksPatch

@Suppress("unused")
val sanitizeSharingLinksPatch = sanitizeSharingLinksPatch(
    block = {
        dependsOn(
            sharedExtensionPatch,
            settingsPatch,
        )

        compatibleWith(COMPATIBILITY)
    },
    preferenceScreen = PreferenceScreen.MISC,
    replaceMusicLinksWithYouTube = true
)