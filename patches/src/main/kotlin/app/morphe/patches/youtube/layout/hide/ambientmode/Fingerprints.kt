package app.morphe.patches.youtube.layout.hide.ambientmode

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

private const val GET_ACTION_METHOD_CALL = "Landroid/content/Intent;->getAction()Ljava/lang/String;"
internal const val IS_POWER_SAVE_MODE_METHOD_CALL = "Landroid/os/PowerManager;->isPowerSaveMode()Z"
private const val POWER_SAVE_MODE_CHANGED = "android.os.action.POWER_SAVE_MODE_CHANGED"

internal object AmbientModeFeatureFlagFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        literal(45376186L),
    )
)

internal object IntentActionBroadcastReceiverFingerprint : Fingerprint(
    name = "onReceive",
    returnType = "V",
    parameters = listOf(
        "Landroid/content/Context;",
        "Landroid/content/Intent;",
    ),
    filters = listOf(
        string(POWER_SAVE_MODE_CHANGED),
        methodCall(smali = GET_ACTION_METHOD_CALL),
        opcode(Opcode.INVOKE_DIRECT)
    ),
    custom = { _, classDef ->
        classDef.superclass == "Landroid/content/BroadcastReceiver;"
                // There are two classes that inherit [BroadcastReceiver].
                // Check the method count to find the correct class.
                && classDef.methods.count() == 2
    }
)

/**
 * YT 21.02+
 */
internal object IntentActionBroadcastReceiverAlternativeFingerprint : Fingerprint(
    name = "onReceive",
    returnType = "V",
    parameters = listOf(
        "Landroid/content/Context;",
        "Landroid/content/Intent;",
    ),
    filters = listOf(
        methodCall(smali = GET_ACTION_METHOD_CALL),
        string(POWER_SAVE_MODE_CHANGED),
        opcode(Opcode.INVOKE_DIRECT)
    ),
    custom = { _, classDef ->
        classDef.superclass == "Landroid/content/BroadcastReceiver;"
                // There are two classes that inherit [BroadcastReceiver].
                // Check the method count to find the correct class.
                && classDef.methods.count() == 2
    }
)

internal object IntentActionSyntheticFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        opcode(Opcode.CHECK_CAST),
        opcode(Opcode.NEW_INSTANCE),
        opcode(Opcode.INVOKE_DIRECT),
        string(POWER_SAVE_MODE_CHANGED),
    ),
    custom = { _, classDef ->
        AccessFlags.SYNTHETIC.isSet(classDef.accessFlags)
    }
)

/**
 * YT 21.03+
 */
internal object IntentActionSyntheticAlternativeFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        string("power"),
        methodCall(smali = IS_POWER_SAVE_MODE_METHOD_CALL),
        string(POWER_SAVE_MODE_CHANGED),
    ),
    custom = { _, classDef ->
        AccessFlags.SYNTHETIC.isSet(classDef.accessFlags)
    }
)

internal object PowerSaveModeSyntheticFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        methodCall(smali = IS_POWER_SAVE_MODE_METHOD_CALL)
    )
)

internal object SetFullScreenBackgroundColorFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/app/player/YouTubePlayerViewNotForReflection;",
    name = "onLayout",
    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Z", "I", "I", "I", "I")
)