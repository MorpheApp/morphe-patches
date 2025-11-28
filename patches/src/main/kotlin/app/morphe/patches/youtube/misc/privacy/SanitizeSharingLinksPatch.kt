package app.morphe.patches.youtube.misc.privacy

import app.morphe.patches.shared.misc.privacy.sanitizeSharingLinksPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch

@Suppress("unused")
val sanitizeSharingLinksPatch = sanitizeSharingLinksPatch(
    block = {
        dependsOn(
            sharedExtensionPatch,
            settingsPatch,
        )

        compatibleWith(
            "com.google.android.youtube"(
                "19.43.41",
                "20.14.43",
                "20.21.37",
                "20.31.40",
                "20.46.41",
            )
        )
    },
    preferenceScreen = PreferenceScreen.MISC
)
