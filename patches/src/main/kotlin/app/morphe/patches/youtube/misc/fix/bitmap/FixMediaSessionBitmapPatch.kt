package app.morphe.patches.youtube.misc.fix.bitmap

import app.morphe.patches.all.misc.transformation.IMethodCall
import app.morphe.patches.all.misc.transformation.filterMapInstruction35c
import app.morphe.patches.all.misc.transformation.transformInstructionsPatch

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/shared/youtube/patches/MediaSessionBitmapMitigationPatch;"

@Suppress("unused")
private enum class MethodCall(
    override val definedClassName: String,
    override val methodName: String,
    override val methodParams: Array<String>,
    override val returnType: String,
) : IMethodCall {
    PutBitmapCompat(
        "Landroid/support/v4/media/MediaMetadataCompat\$Builder;",
        "putBitmap",
        arrayOf("Ljava/lang/String;", "Landroid/graphics/Bitmap;"),
        "Landroid/support/v4/media/MediaMetadataCompat\$Builder;",
    ),
    PutBitmapFramework(
        "Landroid/media/MediaMetadata\$Builder;",
        "putBitmap",
        arrayOf("Ljava/lang/String;", "Landroid/graphics/Bitmap;"),
        "Landroid/media/MediaMetadata\$Builder;",
    );
}

val fixMediaSessionBitmapPatch = transformInstructionsPatch(
    name = "Fix notification thumbnail crash",
    description = "Fixes a crash that may occur caused by the notification thumbnail when a video is opened",
    filterMap = { classDef, _, instruction, instructionIndex ->
        filterMapInstruction35c<MethodCall>(
            "Lapp/morphe/extension",
            classDef,
            instruction,
            instructionIndex,
        )
    },
    transform = transform@{ mutableMethod, entry ->
        val (methodCall, invokeInstruction, instructionIndex) = entry
        
        methodCall.replaceInvokeVirtualWithExtension(
            EXTENSION_CLASS_DESCRIPTOR,
            mutableMethod,
            invokeInstruction,
            instructionIndex
        )
    }
)
