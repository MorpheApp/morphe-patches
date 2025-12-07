package app.morphe.patches.youtube.video.speed.remember

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

internal val initializePlaybackSpeedValuesFingerprint = Fingerprint(
    parameters = listOf("[L", "I"),
    filters = listOf(
        string("menu_item_playback_speed"),
    )
)
