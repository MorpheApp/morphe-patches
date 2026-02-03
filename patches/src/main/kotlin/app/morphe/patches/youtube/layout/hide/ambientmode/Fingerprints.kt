package app.morphe.patches.youtube.layout.hide.ambientmode

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

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
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(
        "Landroid/content/Context;",
        "Landroid/content/Intent;",
    ),
    strings = listOf(
        "android.os.action.POWER_SAVE_MODE_CHANGED",
    ),
    custom = { _, classDef ->
        classDef.superclass == "Landroid/content/BroadcastReceiver;" &&
                classDef.methods.count() == 2
    }
)

internal object PowerSaveModeSyntheticFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    strings = listOf("android.os.action.POWER_SAVE_MODE_CHANGED"),
    custom = { _, classDef ->
        classDef.methods.any { method ->
            method.implementation?.instructions?.any { inst ->
                val reference = (inst as? ReferenceInstruction)?.reference
                inst.opcode == Opcode.INVOKE_VIRTUAL &&
                        reference is MethodReference &&
                        reference.name == "isPowerSaveMode"
            } == true
        }
    }
)