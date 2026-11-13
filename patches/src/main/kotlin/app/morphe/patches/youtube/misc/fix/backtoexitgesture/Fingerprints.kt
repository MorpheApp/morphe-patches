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

/**
 * Back pressed in the feed, when there is no pane left to go back to.
 * Scrolls the feed to the top, and returns whether back was handled.
 *
 * The first match is the call that scrolls. Its interface has several implementations
 * that do the same, but this method is the only caller.
 */
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

/**
 * 21.44 and lower.
 */
internal object BackToRefreshFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45359221)
    )
)
