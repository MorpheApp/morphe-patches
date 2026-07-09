/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.player.fullscreen

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * 19.46+
 */
internal object OpenVideosFullscreenPortraitFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "Lj$/util/Optional;"),
    filters = listOf(
        opcode(Opcode.MOVE_RESULT), // Conditional check to modify.
        // Open videos fullscreen portrait feature flag.
        literal(45666112L, location = MatchAfterWithin(5)), // Cannot be more than 5.
        opcode(Opcode.MOVE_RESULT, location = MatchAfterWithin(10)),
    )
)

internal object AdPlayerFullscreenFingerprint : Fingerprint(
    filters = listOf(
        string("Ad player fullscreen state entity is null in onSuccess on exit"),
        methodCall(
            name = "getFullscreenForced", // Oddly only this method is not obfuscated.
            returnType = "Ljava/lang/Boolean;",
            parameters = listOf(),
            location = MatchAfterWithin(10)
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf(),
            returnType = "V",
            location = MatchAfterWithin(10)
        )
    )
)

internal object PlayerDragGestureTypeFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    parameters = listOf("I"),
    strings = listOf(
        "FULLSCREEN_DRAGGED_DOWN",
        "MAXIMIZED_PULLED_UP"
    )
)

internal object PlayerDragGestureInitFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "I",
    parameters = listOf("I", "I", "I", "I"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.IGET_OBJECT,
        Opcode.IF_EQZ,
        Opcode.INVOKE_VIRTUAL,
        Opcode.IGET,
        Opcode.IF_NE,
        Opcode.IGET,
        Opcode.IF_NE,
        Opcode.CONST_4,
        Opcode.RETURN
    ) + fieldAccess(
        opcode = Opcode.IGET_OBJECT,
        type = "/NextGenWatchLayout;",
        location = MatchAfterImmediately()
    )
)

