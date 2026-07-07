package app.morphe.patches.youtube.interaction.dialog

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object AdultContentRunnableFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        opcode(Opcode.IGET_OBJECT),
        string("allowControversialContent"),
        methodCall(
            parameters = listOf(),
            returnType = "Z",
            location = MatchAfterWithin(1)
        ),
        string("allowAdultContent")
    )
)

internal object AdultContentSetPropertiesFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    strings = listOf(
        "lastAudioTurnedOnInlinePlaybackId",
        "lastAudioTurnedOffInlinePlaybackId",
        "captionsRequested",
    ),
    filters = listOf(
        opcode(Opcode.IGET_BOOLEAN),
        string("allowAdultContent", location = MatchAfterImmediately()),
        fieldAccess(opcode = Opcode.IGET_BOOLEAN, location = MatchAfterWithin(2)),
        string("allowControversialContent", location = MatchAfterImmediately()),
    )
)

