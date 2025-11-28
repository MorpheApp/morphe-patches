package app.morphe.patches.music.ad.video

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.addResources
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/music/patches/HideVideoAdsPatch;"

@Suppress("unused")
val hideVideoAdsPatch = bytecodePatch(
    name = "Hide music video ads",
    description = "Adds an option to hide ads that appear while listening to or streaming music videos, podcasts, or songs.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
    )

    compatibleWith(
        "com.google.android.apps.youtube.music"(
            "7.29.52",
            "8.10.52",
            "8.46.57",
        )
    )

    execute {
        addResources("music", "ad.video.hideVideoAdsPatch")

        PreferenceScreen.ADS.addPreferences(
            SwitchPreference("revanced_music_hide_video_ads"),
        )

        navigate(showVideoAdsParentFingerprint.originalMethod)
            .to(showVideoAdsParentFingerprint.instructionMatches.first().index + 1)
            .stop()
            .addInstructions(
                0,
                """
                    invoke-static { p1 }, $EXTENSION_CLASS_DESCRIPTOR->showVideoAds(Z)Z
                    move-result p1
                """
            )
    }
}
