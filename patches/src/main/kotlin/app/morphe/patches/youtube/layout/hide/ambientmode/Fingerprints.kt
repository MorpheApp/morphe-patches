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

internal object PowerSaveModeReceiverFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL,),
    parameters = listOf("Landroid/content/Context;", "Landroid/content/Intent;"),
    strings = listOf("android.os.action.POWER_SAVE_MODE_CHANGED"),
    custom = { method, classDef ->
        classDef.superclass == "Landroid/content/BroadcastReceiver;" &&
                method.name == "onReceive"
    }
)