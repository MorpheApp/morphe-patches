package app.morphe.patches.music.misc.backgroundplayback

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.reddit.utils.compatibility.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.util.returnEarly

val backgroundPlaybackPatch = bytecodePatch(
    name = "Remove background playback restrictions",
    description = "Removes restrictions on background playback, including playing kids videos in the background.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        KidsBackgroundPlaybackPolicyControllerFingerprint.method.returnEarly()

        BackgroundPlaybackDisableFingerprint.method.returnEarly(true)
    }
}
