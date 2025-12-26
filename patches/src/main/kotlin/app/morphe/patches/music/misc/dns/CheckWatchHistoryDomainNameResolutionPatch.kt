package app.morphe.patches.music.misc.dns

import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.shared.MainActivityOnCreateFingerprint
import app.morphe.patches.shared.misc.dns.checkWatchHistoryDomainNameResolutionPatch

val checkWatchHistoryDomainNameResolutionPatch = checkWatchHistoryDomainNameResolutionPatch(
    block = {
        dependsOn(
            sharedExtensionPatch
        )

        compatibleWith(
            "com.google.android.apps.youtube.music"(
                "7.29.52",
                "8.10.52",
                "8.37.56",
            )
        )
    },

    mainActivityFingerprint = MainActivityOnCreateFingerprint
)
