package app.morphe.patches.youtube.features.layout.player

import app.morphe.patches.youtube.video.videoid.videoIdPatch as legacyVideoIdPatch

/**
 * Feature-level migration bridge for the legacy video-id hook implementation.
 */
internal val videoIdPatch = legacyVideoIdPatch
