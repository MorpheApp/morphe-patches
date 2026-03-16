/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.shared.misc.initialization

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/shared/patches/InitializationPatch;"

internal fun initializationPatch(
    mainActivityFingerprint: Fingerprint
) = bytecodePatch(
    description = "Prompts to restart the app on first load of a clean install",
) {
    execute {
        mainActivityFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS_DESCRIPTOR->onCreate(Landroid/app/Activity;)V",
        )
    }
}
