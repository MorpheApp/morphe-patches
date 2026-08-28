package app.morphe.patches.youtube.video.quality

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.video.format.hookAdaptiveFormat
import app.morphe.patches.youtube.video.format.videoFormatPatch

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/playback/quality/PrioritizeVideoQualityPatch;"

internal val prioritizeVideoQualityPatch = bytecodePatch {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        videoFormatPatch,
    )

    execute {
        settingsMenuVideoQualityGroup.add(
            SwitchPreference("morphe_video_quality_prioritize", summary = true)
        )

        hookAdaptiveFormat("$EXTENSION_CLASS->prioritizeVideoQuality")
    }
}
