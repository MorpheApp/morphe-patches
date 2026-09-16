package app.morphe.patches.music.interaction.jam

import app.morphe.patcher.Fingerprint

internal object QueueEnqueueFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    strings = listOf(
        "com/google/android/apps/youtube/music/player/queue/MusicPlaybackQueueOperationsManager",
        "enqueue",
        "enqueue item, QueueTarget: %s, position: %s",
    ),
)
