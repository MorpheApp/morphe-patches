/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/907
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.fix.backtoexitgesture

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.checkCast
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object ScrollPositionFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
    filters = listOf(
        opcode(Opcode.IF_NEZ),
        methodCall(opcode = Opcode.INVOKE_DIRECT, location = MatchAfterImmediately()),
        string("scroll_position")
    )
)

internal object RecyclerViewTopScrollingCallingMethodFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            returnType = "V",
            parameters = listOf(),
        ),
        literal(45359224L, location = MatchAfterWithin(5)),
        literal(45414493L)
    )
)

// FIXME? There exists at least two 100% identical methods in the same class, but each method
//        is called by different code even though the called methods are 100% the same.
//        Fingerprint also matches a nearly identical third or fourth method in the same class
//        but has slightly different behavior.
internal fun recyclerViewTopScrollingFingerprint() = object : Fingerprint(
    name = RecyclerViewTopScrollingCallingMethodFingerprint.instructionMatches.first().getMethodCalled().name,
    interfaces = listOf(
        RecyclerViewTopScrollingCallingMethodFingerprint.instructionMatches.first().getMethodCalled().definingClass
    ),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(smali = "Ljava/util/Iterator;->next()Ljava/lang/Object;"),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),
        checkCast("Landroid/support/v7/widget/RecyclerView;", MatchAfterImmediately()),
        literal(0, location = MatchAfterImmediately()),
        methodCall(definingClass = "Landroid/support/v7/widget/RecyclerView;", location = MatchAfterImmediately()),
        opcode(Opcode.GOTO, MatchAfterImmediately())
    )
) {}

/**
 * 21.44 and lower.
 */
internal object BackToRefreshFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45359221)
    )
)

private object PaneBackStackFingerprint : Fingerprint(
    returnType = "Landroid/os/Bundle;",
    filters = listOf(
        string("tabBackStack")
    )
)

/**
 * 21.45+
 *
 * Going back, after there is no pane left to go back to. The current pane is asked to handle it.
 */
internal object BackToRefreshFingerprint : Fingerprint(
    classFingerprint = PaneBackStackFingerprint,
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        methodCall(smali = "Lj\$/util/OptionalInt;->getAsInt()I"),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            returnType = "Z",
            parameters = listOf()
        ),
        opcode(Opcode.MOVE_RESULT, MatchAfterImmediately()),
        opcode(Opcode.IF_NEZ, MatchAfterImmediately())
    )
)
