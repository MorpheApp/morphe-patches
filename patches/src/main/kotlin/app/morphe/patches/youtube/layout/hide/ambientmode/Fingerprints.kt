package app.morphe.patches.youtube.layout.hide.ambientmode

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal object SetFullScreenBackgroundColorFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.FINAL),
    parameters = listOf("Z", "I", "I", "I", "I"),
    custom = { method, classDef ->
        classDef.type.endsWith("/YouTubePlayerViewNotForReflection;")
                && method.name == "onLayout"
    }
)

internal object PowerSaveModeBroadcastReceiverFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Landroid/content/Context;",
        "Landroid/content/Intent;",
    ),
    strings = listOf(
        "android.os.action.POWER_SAVE_MODE_CHANGED",
    ),
    custom = { _, classDef ->
        classDef.superclass == "Landroid/content/BroadcastReceiver;"
    }
)

internal object PowerSaveModeSyntheticFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;"),
    strings = listOf(
        "android.os.action.POWER_SAVE_MODE_CHANGED"
    ),
    custom = { method, classDef ->
        ((method.accessFlags and AccessFlags.SYNTHETIC.value) != 0) ||
                ((classDef.accessFlags and AccessFlags.SYNTHETIC.value) != 0)
    }
)