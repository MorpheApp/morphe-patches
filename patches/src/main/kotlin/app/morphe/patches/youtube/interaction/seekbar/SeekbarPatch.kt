package app.morphe.patches.youtube.interaction.seekbar

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

@Suppress("unused")
val seekbarPatch = bytecodePatch(
    name = "Seekbar",
    description = "Adds options to disable precise seeking when swiping up on the seekbar, " +
            "slide to seek instead of playing at 2x speed when pressing and holding, " +
            "tapping the player seekbar to seek, " +
            "hiding the video player seekbar, " +
            "enabling seeking in livestreams, " +
            "and expanding the livestream DVR duration."
) {
    dependsOn(
        disablePreciseSeekingGesturePatch,
        enableSlideToSeekPatch,
        enableTapToSeekPatch,
        hideSeekbarPatch,
        livestreamDvrPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)
}
