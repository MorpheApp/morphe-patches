package app.morphe.patches.youtube.video.audio

import app.morphe.patches.shared.misc.audio.forceOriginalAudioPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_20_07_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.MainActivityOnCreateFingerprint

@Suppress("unused")
val forceOriginalAudioPatch = forceOriginalAudioPatch(
    block = {
        dependsOn(
            sharedExtensionPatch,
            settingsPatch,
            versionCheckPatch
        )

        compatibleWith(
            "com.google.android.youtube"(
                "20.14.43",
                "20.21.37",
                "20.31.42",
                "20.46.41",
            )
        )
    },
    fixUseLocalizedAudioTrackFlag = { is_20_07_or_greater },
    mainActivityOnCreateFingerprint = MainActivityOnCreateFingerprint,
    subclassExtensionClassDescriptor = "Lapp/morphe/extension/youtube/patches/ForceOriginalAudioPatch;",
    preferenceScreen = PreferenceScreen.VIDEO,
)
