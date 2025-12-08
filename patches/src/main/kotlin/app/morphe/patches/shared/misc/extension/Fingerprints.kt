package app.morphe.patches.shared.misc.extension

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal object MorpheUtilsPatchesVersionFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    custom = { method, classDef ->
        method.name == "getPatchesReleaseVersion" && method.definingClass == EXTENSION_CLASS_DESCRIPTOR
    }
)
