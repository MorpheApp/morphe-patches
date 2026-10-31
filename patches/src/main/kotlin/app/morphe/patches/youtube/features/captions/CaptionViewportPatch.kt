package app.morphe.patches.youtube.features.captions

import app.morphe.patches.youtube.layout.captions.captionViewportPatch

/**
 * Feature-layer alias for the real caption viewport hook.
 *
 * This is the first actual migration step: new package resolves to the original patch.
 */
internal val captionViewportPatch = app.morphe.patches.youtube.layout.captions.captionViewportPatch
