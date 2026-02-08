package app.morphe.patches.music.misc.backgroundplayback

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY
import app.morphe.util.returnEarly

val backgroundPlaybackPatch = bytecodePatch(
    name = "Remove background playback restrictions",
    description = "Removes restrictions on background playback, including playing kids videos in the background.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch
    )

    compatibleWith(COMPATIBILITY)

    execute {
        KidsBackgroundPlaybackPolicyControllerFingerprint.method.returnEarly()

        BackgroundPlaybackDisableFingerprint.method.returnEarly(true)
    }
}
