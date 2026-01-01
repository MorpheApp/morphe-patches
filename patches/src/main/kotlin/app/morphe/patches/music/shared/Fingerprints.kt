package app.morphe.patches.music.shared

import app.morphe.patcher.Fingerprint

internal const val YOUTUBE_MUSIC_MAIN_ACTIVITY_CLASS_TYPE = "Lcom/google/android/apps/youtube/music/activities/MusicActivity;"

internal object MusicActivityOnCreateFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
    custom = { method, classDef ->
        method.name == "onCreate" && classDef.type == YOUTUBE_MUSIC_MAIN_ACTIVITY_CLASS_TYPE
    }
)
