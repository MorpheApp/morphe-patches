/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3580
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.continuewatching

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Autoplay overlay method that compares the time since the last user interaction against the
 * server provided inactivity limit (in minutes). Once the limit is exceeded autoplay stops
 * counting down and the next video is not started.
 */
internal object AutoplayInactivityLimitFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.SGET_OBJECT,
            definingClass = "Ljava/util/concurrent/TimeUnit;",
            name = "MINUTES"
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            definingClass = "Ljava/util/concurrent/TimeUnit;",
            name = "toMillis",
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.CMP_LONG, location = MatchAfterWithin(2)),
        anyInstruction(
            opcode(Opcode.IF_LTZ),
            opcode(Opcode.IF_GEZ), // 21.40+
            location = MatchAfterImmediately()
        ),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            definingClass = "Landroid/animation/ObjectAnimator;",
            name = "ofObject"
        )
    )
)

/**
 * Autoplay overlay method that decides if the 'Video paused. Continue watching?' confirm dialog
 * is shown when a video ends.
 */
internal object AutoplayConfirmDialogEnabledFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        literal(45381478L),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf("J", "Z"),
            returnType = "Z",
            location = MatchAfterWithin(2)
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf(),
            returnType = "I",
            location = MatchAfterWithin(6)
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            definingClass = "Lj\$/util/Optional;",
            name = "isPresent",
            location = MatchAfterWithin(6)
        )
    )
)

/**
 * Same as [AutoplayConfirmDialogEnabledFingerprint], but the feature flag is read
 * through a wrapper method. 21.29 and lower.
 */
internal object AutoplayConfirmDialogEnabledLegacyFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf(),
            returnType = "Z"
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf(),
            returnType = "I",
            location = MatchAfterWithin(6)
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            definingClass = "Lj\$/util/Optional;",
            name = "isPresent",
            location = MatchAfterWithin(6)
        )
    )
)
