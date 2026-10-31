package app.morphe.patches.youtube.shared.models

/**
 * Shared state model container for YouTube patch features.
 */
data class YoutubeState(
    val enabled: Boolean = true,
    val version: String = "unknown"
)
