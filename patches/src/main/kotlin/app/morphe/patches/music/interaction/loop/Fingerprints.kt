/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.loop

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation
import app.morphe.patcher.InstructionLocation.MatchAfterAnywhere
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

internal const val TIME_BAR_CLASS =
    "Lcom/google/android/apps/youtube/music/watchpage/MusicPlaybackControlsTimeBar;"

internal object MedialibPlayerSeekToFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("J", "L"),
    filters = listOf(
        string("MedialibPlayer.seekTo(positionMillis=%d)")
    )
)

/**
 * Sets the scrub thumb position from the touch x coordinate.
 */
internal object TimeBarSetScrubPositionFingerprint : Fingerprint(
    definingClass = TIME_BAR_CLASS,
    returnType = "V",
    parameters = listOf("F"),
    filters = listOf(
        // Thumb center bounds.
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Landroid/graphics/Rect;"
        ),
        methodCall(definingClass = "Ljava/lang/Math;", name = "max")
    )
)

/**
 * Time formatter call, its toString() and the resulting label String.
 */
private fun timeLabelFilters(location: InstructionLocation) = arrayOf(
    methodCall(
        opcode = Opcode.INVOKE_STATIC,
        parameters = listOf("J"),
        returnType = "Ljava/lang/CharSequence;",
        location = location
    ),
    methodCall(
        definingClass = "Ljava/lang/CharSequence;",
        name = "toString",
        location = MatchAfterWithin(3)
    ),
    opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
)

internal object TimeBarDrawFingerprint : Fingerprint(
    definingClass = TIME_BAR_CLASS,
    name = "draw",
    returnType = "V",
    parameters = listOf("Landroid/graphics/Canvas;"),
    filters = listOf(
        // Remaining time, formatted from a negated value.
        opcode(Opcode.NEG_LONG),
        *timeLabelFilters(MatchAfterImmediately()),
        // Elapsed time.
        *timeLabelFilters(MatchAfterAnywhere())
    )
)
