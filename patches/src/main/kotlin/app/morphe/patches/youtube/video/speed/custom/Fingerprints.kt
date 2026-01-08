package app.morphe.patches.youtube.video.speed.custom

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object GetOldPlaybackSpeedsFingerprint : Fingerprint(
    parameters = listOf("[L", "I"),
    strings = listOf("menu_item_playback_speed")
)

internal object ShowOldPlaybackSpeedMenuFingerprint : Fingerprint(
    filters = listOf(
        resourceLiteral(ResourceType.STRING, "varispeed_unavailable_message")
    )
)

internal object ShowOldPlaybackSpeedMenuExtensionFingerprint : Fingerprint(
    custom = { method, _ -> method.name == "showOldPlaybackSpeedMenu" }
)

internal object ServerSideMaxSpeedFeatureFlagFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    filters = listOf(
        literal(45719140L)
    )
)

internal object SpeedArrayGeneratorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "[L",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(name = "size", returnType = "I"),
        newInstance("Ljava/text/DecimalFormat;"),
        string("0.0#"),
        literal(7),
        opcode(Opcode.NEW_ARRAY),
        fieldAccess(type = "[F")
    )
)

/**
 * 20.34+
 */
internal object SpeedLimiterFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("F", "L"),
    filters = listOf(
        string("setPlaybackRate"),
        literal(0.25f),
        literal(4.0f)
    )
)

/**
 * 20.33 and lower.
 */
internal object SpeedLimiterLegacyFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("F"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.INVOKE_STATIC,
        Opcode.MOVE_RESULT,
        Opcode.IF_EQZ,
        Opcode.CONST_HIGH16,
        Opcode.GOTO,
        Opcode.CONST_HIGH16,
        Opcode.CONST_HIGH16,
        Opcode.INVOKE_STATIC,
    )
)
