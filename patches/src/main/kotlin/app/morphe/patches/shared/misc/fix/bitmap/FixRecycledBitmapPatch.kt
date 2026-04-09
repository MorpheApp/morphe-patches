/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.shared.misc.fix.bitmap

import app.morphe.patches.all.misc.transformation.IMethodCall
import app.morphe.patches.all.misc.transformation.filterMapInstruction35c
import app.morphe.patches.all.misc.transformation.transformInstructionsPatch

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/shared/patches/FixRecycledBitmapPatch;"

@Suppress("unused")
private enum class MethodCall(
    override val definedClassName: String,
    override val methodName: String,
    override val methodParams: Array<String>,
    override val methodReturnType: String,
) : IMethodCall {
    PutBitmapFramework(
        "Landroid/media/MediaMetadata\$Builder;",
        "putBitmap",
        arrayOf("Ljava/lang/String;", "Landroid/graphics/Bitmap;"),
        "Landroid/media/MediaMetadata\$Builder;",
    );
}

val fixRecycledBitmapPatch = transformInstructionsPatch(
    filterMap = { classDef, _, instruction, instructionIndex ->
        filterMapInstruction35c<MethodCall>(
            "Lapp/morphe/extension",
            classDef,
            instruction,
            instructionIndex,
        )
    },
    transform = transform@{ mutableMethod, entry ->
        val (methodCall, _, instructionIndex) = entry
        
        methodCall.replaceInvokeVirtualWithExtension(
            EXTENSION_CLASS_DESCRIPTOR,
            mutableMethod,
            instructionIndex
        )
    }
)