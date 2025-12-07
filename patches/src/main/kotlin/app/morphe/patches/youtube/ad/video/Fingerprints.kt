package app.morphe.patches.youtube.ad.video

import app.morphe.patcher.Fingerprint

internal val loadVideoAdsFingerprint = Fingerprint(
    strings = listOf(
        "TriggerBundle doesn't have the required metadata specified by the trigger ",
        "Ping migration no associated ping bindings for activated trigger: ",
    )
)
