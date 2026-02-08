package app.morphe.patches.reddit.misc.fix.signature

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal val ApplicationFingerprint = Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Landroid/content/Context;"),
    custom = { method, classDef ->
        classDef.superclass == "Landroid/app/Application;" &&
                method.name == "attachBaseContext"
    }
)
