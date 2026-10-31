package app.morphe.patches.youtube.layout.hide.ambientmode

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterLast
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val GET_ACTION_METHOD_CALL = "Landroid/content/Intent;->getAction()Ljava/lang/String;"
internal const val IS_POWER_SAVE_MODE_METHOD_CALL = "Landroid/os/PowerManager;->isPowerSaveMode()Z"
private const val POWER_SAVE_MODE_CHANGED = "android.os.action.POWER_SAVE_MODE_CHANGED"

internal object AmbientModeFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        anyInstruction(
            literal(45376186L),
            literal(45372814L)
        )
    )
)

/**
 * 21.12+ Server experiment of the new watch page that replaces ambient mode with a black background.
 */
internal object AmbientModeBlackBackgroundFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45767512L)
    )
)

/**
 * CinematicContainerRenderer, the ambient mode proto of the /next response.
 * The literal is its extension field number.
 */
internal object CinematicContainerRendererFingerprint : Fingerprint(
    name = "<clinit>",
    returnType = "V",
    filters = listOf(
        literal(455752576L),
    )
)

/**
 * Reads the presentation style (field 3) of a CinematicContainerRenderer, if it has one.
 */
internal fun cinematicContainerPresentationStyleFingerprint(rendererType: String) = Fingerprint(
    returnType = "L",
    parameters = listOf(rendererType),
    filters = listOf(
        // Has-bits field.
        fieldAccess(opcode = Opcode.IGET, definingClass = rendererType, type = "I"),
        opcode(Opcode.AND_INT_LIT8, location = MatchAfterImmediately()),
        opcode(Opcode.IF_EQZ, location = MatchAfterImmediately()),
        // Presentation style field.
        fieldAccess(
            opcode = Opcode.IGET,
            definingClass = rendererType,
            type = "I",
            location = MatchAfterImmediately()
        ),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("I"),
            location = MatchAfterImmediately()
        ),
    )
)

/**
 * Reads the config (field 4) of a CinematicContainerRenderer.
 */
internal fun cinematicContainerConfigFingerprint(rendererType: String) = Fingerprint(
    filters = listOf(
        methodCall(name = "get", parameters = listOf(), returnType = "Ljava/lang/Object;"),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        checkCast(rendererType, location = MatchAfterImmediately()),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = rendererType,
            location = MatchAfterImmediately()
        ),
    )
)

/**
 * Reads the 'ambient mode on by default' value (field 11) of the CinematicContainerRenderer config,
 * used if the flyout menu toggle was never changed.
 */
internal fun cinematicContainerEnabledByDefaultFingerprint(configType: String) = Fingerprint(
    filters = listOf(
        // Has-bits field.
        fieldAccess(opcode = Opcode.IGET, definingClass = configType, type = "I"),
        anyInstruction(
            opcode(Opcode.AND_INT_LIT16), // 20.51 - 21.20
            opcode(Opcode.AND_INT_2ADDR), // 20.21, 21.21+
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.IF_EQZ, location = MatchAfterImmediately()),
        fieldAccess(
            opcode = Opcode.IGET_BOOLEAN,
            definingClass = configType,
            type = "Z",
            location = MatchAfterImmediately()
        ),
        methodCall(
            smali = "Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;",
            location = MatchAfterImmediately()
        ),
    )
)

/**
 * Holds the CinematicContainerRenderer of the current video and its loading state.
 */
internal object CinematicContainerRendererWithStateFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("RendererWithState{rendererLoadedState="),
    )
)

/**
 * Loading state enum of [CinematicContainerRendererWithStateFingerprint].
 * Other enums have the same constant names, so the type is required.
 */
internal fun cinematicContainerRendererLoadedStateFingerprint(stateType: String) = Fingerprint(
    definingClass = stateType,
    name = "<clinit>",
    returnType = "V",
    filters = listOf(
        string("WAITING"),
        fieldAccess(definingClass = stateType, type = stateType, opcode = Opcode.SPUT_OBJECT),
        string("LOADED"),
        fieldAccess(definingClass = stateType, type = stateType, opcode = Opcode.SPUT_OBJECT),
    )
)

internal object IntentActionBroadcastReceiverFingerprint : Fingerprint(
    superclass = "Landroid/content/BroadcastReceiver;",
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
        // There are two classes that inherit [BroadcastReceiver].
        // Check the method count to find the correct class.
        classDef.methods.count() == 2
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
    superclass = "Landroid/content/BroadcastReceiver;",
    custom = { _, classDef ->
        // There are two classes that inherit [BroadcastReceiver].
        // Check the method count to find the correct class.
        classDef.methods.count() == 2
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
 * 21.43+
 */
internal object IntentActionSyntheticAlternativeFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf(),
    filters = listOf(
        checkCast("Landroid/os/PowerManager;"),
        methodCall(smali = IS_POWER_SAVE_MODE_METHOD_CALL),
        string(POWER_SAVE_MODE_CHANGED),
    )
)

/**
 * YT 21.03 - 21.42
 */
internal object IntentActionSyntheticAlternativeLegacyFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        anyInstruction(
            string("power"),
            checkCast("Landroid/os/PowerManager;") // 21.21+
        ),
        methodCall(smali = IS_POWER_SAVE_MODE_METHOD_CALL),
        string(POWER_SAVE_MODE_CHANGED),
    )
)

/**
 * 21.43+ Registers the receiver of [IntentActionBroadcastReceiverFingerprint].
 */
internal object PowerSaveModeReceiverRegisterFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    parameters = listOf("Landroid/content/Context;", "L"),
    filters = listOf(
        string(POWER_SAVE_MODE_CHANGED),
        methodCall(definingClass = "Landroid/content/Context;", name = "registerReceiver"),
    )
)

/**
 * 21.43+ Reads the first power save mode value, then calls [PowerSaveModeReceiverRegisterFingerprint].
 */
internal fun powerSaveModeInitialValueFingerprint(registerMethod: MethodReference) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        checkCast("Landroid/os/PowerManager;"),
        methodCall(smali = IS_POWER_SAVE_MODE_METHOD_CALL),
        methodCall(reference = registerMethod),
    )
)

/**
 * 21.43+ Reads the first power save mode value, if the receiver is registered asynchronously.
 */
internal object PowerSaveModeInitialValueAsyncFingerprint : Fingerprint(
    classFingerprint = PowerSaveModeReceiverRegisterFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    parameters = listOf("L", "Landroid/content/Context;"),
    filters = listOf(
        checkCast("Landroid/os/PowerManager;"),
        methodCall(smali = IS_POWER_SAVE_MODE_METHOD_CALL),
    )
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
    parameters = listOf("Z", "I", "I", "I", "I"),
    filters = listOf(
        methodCall(name = "setBackgroundColor", location = MatchAfterLast())
    )
)
