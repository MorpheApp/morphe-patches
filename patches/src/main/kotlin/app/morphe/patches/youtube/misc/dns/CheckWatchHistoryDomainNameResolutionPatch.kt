package app.morphe.patches.youtube.misc.dns

import app.morphe.patches.shared.misc.dns.checkWatchHistoryDomainNameResolutionPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint

val checkWatchHistoryDomainNameResolutionPatch = checkWatchHistoryDomainNameResolutionPatch(
    block = {
        dependsOn(sharedExtensionPatch)

        compatibleWith(COMPATIBILITY)
    },
    mainActivityFingerprint = YouTubeActivityOnCreateFingerprint
)
