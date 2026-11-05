/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4451
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.mediasession

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.MediaSessionSetMetadataFingerprint
import app.morphe.patches.shared.MediaSessionSetPlaybackStateFingerprint
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

private class Hook(val extensionMethod: String, val passSession: Boolean = false)

private val setMetadataHooks = mutableListOf<Hook>()
private val setPlaybackStateHooks = mutableListOf<Hook>()

/**
 * Calls an extension method before each `MediaSession.setMetadata`.
 *
 * @param extensionMethod Static method descriptor that takes the `MediaMetadata`.
 */
internal fun addSetMetadataHook(extensionMethod: String) {
    setMetadataHooks += Hook(extensionMethod)
}

/**
 * Calls an extension method before each `MediaSession.setMetadata`.
 *
 * @param extensionMethod Static method descriptor that takes the `MediaSession` and the
 *                        `MediaMetadata`.
 */
internal fun addSetMetadataWithSessionHook(extensionMethod: String) {
    setMetadataHooks += Hook(extensionMethod, passSession = true)
}

/**
 * Calls an extension method before each `MediaSession.setPlaybackState`.
 *
 * @param extensionMethod Static method descriptor that takes the `PlaybackState`.
 */
internal fun addSetPlaybackStateHook(extensionMethod: String) {
    setPlaybackStateHooks += Hook(extensionMethod)
}

internal val mediaSessionHookPatch = patch(
    description = "Hooks the metadata and the playback state that YouTube Music gives to its media session."
) {
    finalize {
        MediaSessionSetMetadataFingerprint.addHooks(setMetadataHooks)
        MediaSessionSetPlaybackStateFingerprint.addHooks(setPlaybackStateHooks)

        setMetadataHooks.clear()
        setPlaybackStateHooks.clear()
    }
}

private fun Fingerprint.addHooks(hooks: List<Hook>) {
    // Other patches can change the same call, which shifts its index.
    clearMatch()

    method.apply {
        val index = instructionMatches.last().index
        val call = getInstruction<FiveRegisterInstruction>(index)

        // Reversed, as each one goes in front of the previous one.
        hooks.asReversed().forEach { hook ->
            val registers = if (hook.passSession) "v${call.registerC}, v${call.registerD}" else "v${call.registerD}"
            addInstruction(index, "invoke-static { $registers }, ${hook.extensionMethod}")
        }
    }
}
