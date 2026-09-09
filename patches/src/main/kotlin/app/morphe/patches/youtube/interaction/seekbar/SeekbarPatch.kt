package app.morphe.patches.youtube.interaction.seekbar

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

@Suppress("unused")
val seekbarPatch = bytecodePatch(
    name = "Seekbar",
    description = "Adds options to show old seekbar thumbnails, " +
            "disable precise seeking when swiping up on the seekbar, " +
            "slide to seek instead of playing at 2x speed when pressing and holding, " +
            "tapping the player seekbar to seek, " +
            "hiding the video player seekbar, " +
            "enabling seeking in live streams, " +
            "and expanding the live stream DVR duration."
) {
    dependsOn(
        disablePreciseSeekingGesturePatch,
        enableSlideToSeekPatch,
        enableTapToSeekPatch,
        hideSeekbarPatch,
        liveStreamDVRPatch,
        seekbarThumbnailPreviewPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)
}
