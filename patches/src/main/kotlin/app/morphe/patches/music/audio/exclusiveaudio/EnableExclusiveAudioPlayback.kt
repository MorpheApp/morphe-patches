package app.morphe.patches.music.audio.exclusiveaudio

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.util.returnEarly

@Suppress("unused")
val enableExclusiveAudioPlaybackPatch = bytecodePatch(
    name = "Enable exclusive audio playback",
    description = "Enables the option to play audio without video.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(
        "com.google.android.apps.youtube.music"(
            "7.29.52",
            "8.10.52",
            "8.37.56",
        )
    )

    execute {
        AllowExclusiveAudioPlaybackFingerprint.method.returnEarly(true)
    }
}
