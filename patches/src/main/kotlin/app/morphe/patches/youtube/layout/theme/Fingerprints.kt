package app.morphe.patches.youtube.layout.theme

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patches.youtube.shared.YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE
import com.android.tools.smali.dexlib2.Opcode

internal object UseGradientLoadingScreenFingerprint : Fingerprint(
    filters = listOf(
        literal(45412406L)
    )
)

internal object SplashScreenStyleFingerprint : Fingerprint(
    definingClass = YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE,
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
    filters = listOf(
        anyInstruction(
            literal(1074339245), // 20.30+
            literal(269032877L) // 20.29 and lower.
        )
    )
)

/**
 * Matches to the same method as [SplashScreenStyleFingerprint].
 */
internal object ShowSplashScreen1Fingerprint : Fingerprint(
    definingClass = YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE,
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
    filters = listOf(
        anyInstruction(
            opcode(Opcode.CONST_4),
            opcode(Opcode.CONST_16)
        ),
        methodCall(
            parameters = listOf("L", "Ljava/lang/Runnable;"),
            returnType = "V",
            location = MatchAfterWithin(20)
        ),
        opcode(
            opcode = Opcode.APUT_OBJECT,
            location = MatchAfterWithin(10)
        ),
        methodCall(
            parameters = listOf("[L"),
            returnType = "V",
            location = MatchAfterWithin(5)
        ),
        opcode(
            opcode = Opcode.IGET_OBJECT,
            location = MatchAfterImmediately()
        ),
        methodCall(
            parameters = listOf(),
            returnType = "I",
            location = MatchAfterImmediately()
        ),
        opcode(
            opcode = Opcode.MOVE_RESULT,
            location = MatchAfterImmediately()
        ),
        methodCall(
            parameters = listOf("I"),
            returnType = "Z",
            location = MatchAfterImmediately()
        ),
        opcode(
            opcode = Opcode.MOVE_RESULT,
            location = MatchAfterImmediately()
        )
    )
)

/**
 * Matches to the same method as [SplashScreenStyleFingerprint].
 */
internal object ShowSplashScreen2Fingerprint : Fingerprint(
    definingClass = YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE,
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            returnType = "V",
            parameters = listOf("[L")
        ),
        opcode(
            opcode = Opcode.IF_NE
        ),
        methodCall(
            smali = "Landroid/graphics/drawable/AnimatedVectorDrawable;->start()V"
        )
    )
)
