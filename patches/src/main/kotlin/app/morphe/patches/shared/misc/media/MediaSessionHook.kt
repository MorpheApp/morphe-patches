/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.media

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

/**
 * Passes the argument of the matched `MediaSession` call to an extension method,
 * before the call itself runs.
 *
 * @param extensionMethod Method descriptor to invoke, without the register list.
 */
context(_: BytecodePatchContext)
internal fun Fingerprint.hookMediaSessionArgument(extensionMethod: String) {
    // Several patches hook the same call, and each insertion shifts the index of
    // the following ones, so the match is resolved again on every call.
    clearMatch()

    method.apply {
        val index = instructionMatches.first().index
        val register = getInstruction<FiveRegisterInstruction>(index).registerD
        addInstruction(index, "invoke-static { v$register }, $extensionMethod")
    }
}
