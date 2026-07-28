package app.morphe.patches.youtube.video.codecs

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

internal object HDRCapabilityFingerprint : Fingerprint(
    filters = listOf(
        methodCall(
            definingClass = $$"Landroid/view/Display$HdrCapabilities;",
            name = "getSupportedHdrTypes",
        )
    ),
    custom = { _, classDef ->
        !classDef.type.startsWith("Lapp/morphe/")
    }
)

internal object Vp9CapabilityFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    strings = listOf(
        "vp9_supported",
        "video/x-vnd.on2.vp9"
    )
)
