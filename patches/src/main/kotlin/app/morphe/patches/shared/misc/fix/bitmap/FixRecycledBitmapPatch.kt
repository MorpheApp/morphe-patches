/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.shared.misc.fix.bitmap

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.fiveRegisters
import app.morphe.util.matchAllMethodIndicesForEach

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/shared/patches/FixRecycledBitmapPatch;"

val fixRecycledBitmapPatch = bytecodePatch(
    description = "Fixes recycled bitmap crashes by routing putBitmap through the extension class."
) {

    execute {
        Fingerprint(
            filters = listOf(
                methodCall(
                    definingClass = $$"Landroid/media/MediaMetadata$Builder;",
                    name = "putBitmap",
                    parameters = listOf("Ljava/lang/String;", "Landroid/graphics/Bitmap;")
                )
            ),
            custom = { _, classDef ->
                !classDef.type.startsWith("Lapp/morphe/extension")
            }
        ).matchAllMethodIndicesForEach(requireMatches = false) { index ->
            val registers = fiveRegisters(index)

            replaceInstruction(
                index,
                $"invoke-static { $registers }, $EXTENSION_CLASS->putBitmap(" +
                        "Landroid/media/MediaMetadata\$Builder;Ljava/lang/String;Landroid/graphics/Bitmap;)" +
                        "Landroid/media/MediaMetadata\$Builder;"
            )
        }
    }
}