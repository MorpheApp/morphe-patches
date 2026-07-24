package app.morphe.patches.youtube.layout.shortsnoresume

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.checkCast
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * 21.03+
 */
internal object UserWasInShortsEvaluateFingerprint : Fingerprint(
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_DIRECT_RANGE,
            name = "<init>",
            parameters = listOf("L", "Z", "Z", "L", "Z")
        ),
        anyInstruction(
            methodCall(
                opcode = Opcode.INVOKE_DIRECT_RANGE,
                name = "<init>",
                parameters = listOf("L", "L", "L", "L", "L", "I"),
                location = InstructionLocation.MatchAfterWithin(50)
            ),
            methodCall( // 21.30+
                opcode = Opcode.INVOKE_DIRECT_RANGE,
                name = "<init>",
                parameters = listOf("L", "L", "L", "L", "L", "L",  "Ljava/lang/String;"),
                location = InstructionLocation.MatchAfterWithin(50)
            )
        )
    )
)

/**
 * 20.02+
 */
internal object UserWasInShortsListenerFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        checkCast("Ljava/lang/Boolean;"),
        methodCall(smali = "Ljava/lang/Boolean;->booleanValue()Z", location = InstructionLocation.MatchAfterImmediately()),
        opcode(Opcode.MOVE_RESULT, InstructionLocation.MatchAfterImmediately()),
        string("ShortsStartup SetUserWasInShortsListener", StringComparisonType.CONTAINS, InstructionLocation.MatchAfterWithin(30))
    )
)

/**
 * 18.15.40+
 */
internal object UserWasInShortsConfigFingerprint : Fingerprint(
    filters = listOf(
        literal(45358360L)
    )
)
