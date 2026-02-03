package app.morphe.patches.youtube.interaction.seekbar

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.utils.compatibility.Constants.COMPATIBILITY_YOUTUBE

@Suppress("unused")
val seekbarPatch = bytecodePatch(
    name = "Seekbar",
    description = "Adds options to disable precise seeking when swiping up on the seekbar, " +
            "slide to seek instead of playing at 2x speed when pressing and holding, " +
            "tapping the player seekbar to seek, and hiding the video player seekbar."
) {
    dependsOn(
        disablePreciseSeekingGesturePatch,
        enableSlideToSeekPatch,
        enableSeekbarTappingPatch,
        hideSeekbarPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)
}
