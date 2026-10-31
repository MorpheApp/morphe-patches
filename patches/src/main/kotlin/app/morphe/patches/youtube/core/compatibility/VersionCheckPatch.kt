package app.morphe.patches.youtube.core.compatibility

import app.morphe.patches.youtube.misc.playservice.versionCheckPatch

/**
 * Compatibility-layer alias for the original YouTube version check patch.
 *
 * This keeps the refactored package usable without breaking the existing implementation.
 */
internal val versionCheckPatch = app.morphe.patches.youtube.misc.playservice.versionCheckPatch
