package app.morphe.patches.youtube.misc.dns

import app.morphe.patches.shared.misc.dns.checkWatchHistoryDomainNameResolutionPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint

val checkWatchHistoryDomainNameResolutionPatch = checkWatchHistoryDomainNameResolutionPatch(
    block = {
        dependsOn(
            sharedExtensionPatch
        )

        compatibleWith(
            "com.google.android.youtube"(
                "20.14.43",
                "20.21.37",
                "20.26.46",
                "20.31.42",
                "20.37.48",
                "20.40.45",
            )
        )
    },
    mainActivityFingerprint = YouTubeActivityOnCreateFingerprint
)
