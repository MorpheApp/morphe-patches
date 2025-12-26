package app.morphe.patches.music.misc.tracks

import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.playservice.is_8_05_or_greater
import app.morphe.patches.music.playservice.versionCheckPatch
import app.morphe.patches.music.shared.MainActivityOnCreateFingerprint
import app.morphe.patches.shared.misc.audio.forceOriginalAudioPatch

@Suppress("unused")
val forceOriginalAudioPatch = forceOriginalAudioPatch(
    block = {
        dependsOn(
            sharedExtensionPatch,
            settingsPatch,
            versionCheckPatch
        )

        compatibleWith(
            "com.google.android.apps.youtube.music"(
                "7.29.52",
                "8.10.52",
                "8.37.56",
            )
        )
    },
    fixUseLocalizedAudioTrackFlag = { is_8_05_or_greater },
    mainActivityOnCreateFingerprint = MainActivityOnCreateFingerprint,
    subclassExtensionClassDescriptor = "Lapp/morphe/extension/music/patches/ForceOriginalAudioPatch;",
    preferenceScreen = PreferenceScreen.MISC,
)
