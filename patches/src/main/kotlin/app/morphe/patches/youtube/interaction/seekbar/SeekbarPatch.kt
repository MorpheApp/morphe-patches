package app.morphe.patches.youtube.interaction.seekbar

import app.morphe.patcher.patch.bytecodePatch

@Suppress("unused")
val seekbarPatch = bytecodePatch(
    name = "Seekbar"
) {
    dependsOn(
        disablePreciseSeekingGesturePatch,
        enableSlideToSeekPatch,
        enableSeekbarTappingPatch,
        hideSeekbarPatch,
    )

    compatibleWith(
        "com.google.android.youtube"(
            "20.14.43",
            "20.21.37",
            "20.26.46",
            "20.31.42",
            "20.37.48",
            "20.40.45",
        )
    )

    execute {
        // Empty execute, required for patcher to easily
        // figure out what jar file to look in to load the localied strings.
    }
}
