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

/**
 * Matches the method to skip the viewer discretion dialog.
 * Caller must supply the class resolved from [AdultContentRunnableFingerprint].
 */
internal fun skipDialogFingerprint(adultContentRunnableClass: String) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = "Ljava/lang/Boolean;->booleanValue()Z"
        ),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        opcode(Opcode.INVOKE_VIRTUAL, location = MatchAfterWithin(2)),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            name = "<init>",
            definingClass = adultContentRunnableClass,
            location = MatchAfterWithin(3)
        )
    )
)

/**
 * Matches the method unlocking related videos for restricted videos.
 * Caller must supply the skipDialog defining class and resolved properties from [AdultContentSetPropertiesFingerprint].
 */
internal fun unlockRelatedVideosFingerprint(
    skipDialogClass: String,
    adultContentProperty1: String,
    adultContentProperty2: String) = Fingerprint(
    definingClass = skipDialogClass,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_BOOLEAN,
            smali = adultContentProperty1
        ),
        fieldAccess(
            opcode = Opcode.IPUT_BOOLEAN,
            location = MatchAfterWithin(3),
            smali = adultContentProperty2
        ),
    )
)
