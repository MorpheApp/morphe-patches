package app.morphe.patches.youtube.layout.formfactor

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object FormFactorEnumConstructorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.STATIC, AccessFlags.CONSTRUCTOR),
    strings = listOf(
        "UNKNOWN_FORM_FACTOR",
        "SMALL_FORM_FACTOR",
        "LARGE_FORM_FACTOR",
        "AUTOMOTIVE_FORM_FACTOR"
    )
)

internal object PlayerLithoElementsListFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "V",
    strings = listOf("Number of sectionList models must be equal to the number of section states"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Ljava/util/List;",
        ),
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            smali = "Ljava/util/List;->get(I)Ljava/lang/Object;",
            location = MatchAfterImmediately(),
        ),
        opcode(
            Opcode.MOVE_RESULT_OBJECT,
            location = MatchAfterImmediately(),
        ),
        opcode(
            Opcode.CHECK_CAST,
            location = MatchAfterImmediately(),
        ),
        opcode(
            Opcode.INVOKE_VIRTUAL,
            location = MatchAfterImmediately(),
        ),
        opcode(
            Opcode.INSTANCE_OF,
            location = MatchAfterImmediately(),
        ),
        opcode(
            Opcode.IF_EQZ,
            location = MatchAfterImmediately(),
        ),
        opcode(
            Opcode.CHECK_CAST,
            location = MatchAfterImmediately(),
        ),
        opcode(
            Opcode.IGET_OBJECT,
            location = MatchAfterImmediately(),
        )
    )
)
