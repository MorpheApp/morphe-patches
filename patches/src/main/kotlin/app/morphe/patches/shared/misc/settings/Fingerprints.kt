package app.morphe.patches.shared.misc.settings

import app.morphe.patcher.Fingerprint
import app.morphe.patches.shared.misc.extension.EXTENSION_CLASS_DESCRIPTOR
import com.android.tools.smali.dexlib2.AccessFlags

internal val themeLightColorResourceNameFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    custom = { method, classDef ->
        method.name == "getThemeLightColorResourceName" && classDef.type == EXTENSION_CLASS_DESCRIPTOR
    }
)

internal val themeDarkColorResourceNameFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    custom = { method, classDef ->
        method.name == "getThemeDarkColorResourceName" && classDef.type == EXTENSION_CLASS_DESCRIPTOR
    }
)
