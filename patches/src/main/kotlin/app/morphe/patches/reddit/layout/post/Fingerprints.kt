/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3541
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.post

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Compose Arrangement.Horizontal interface method arrange(density, totalSize, sizes, layoutDirection, outPositions).
 */
internal object ArrangementHorizontalArrangeFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.ABSTRACT),
    returnType = "V",
    parameters = listOf("L", "I", "[I", "Landroidx/compose/ui/unit/LayoutDirection;", "[I")
)

/**
 * Class with the post action bar composables.
 */
internal object PostActionBarTagsFingerprint : Fingerprint(
    filters = listOf(
        string("actionBar_comment_button")
    )
)

/**
 * Post action bar used in the feed and post details: the vote buttons, then a row with
 * the comment button in a weighted row, then the crosspost, share and mod buttons.
 * Parameters: vote state, appearance, then the comment, crosspost, share and mod states.
 */
internal object PostActionBarRowFingerprint : Fingerprint(
    classFingerprint = PostActionBarTagsFingerprint,
    returnType = "V",
    parameters = listOf("L", "L", "L", "L", "L", "L", "L", "I"),
    filters = listOf(
        // Vote buttons appearance.
        methodCall(
            definingClass = "this",
            parameters = listOf("L"),
            returnType = "L"
        ),
        // Vote buttons.
        methodCall(
            definingClass = "this",
            parameters = listOf("L", "L", "L", "L", "I"),
            returnType = "V"
        ),
        // Buttons row measure policy, with the horizontal arrangement.
        literal(54),
        methodCall(
            parameters = listOf("L", "L", "L", "I"),
            returnType = "L",
            location = MatchAfterImmediately()
        ),
        // Spacing between the buttons.
        literal(6f),
        // Comment row measure policy.
        literal(54),
        methodCall(
            parameters = listOf("L", "L", "L", "I"),
            returnType = "L",
            location = MatchAfterImmediately()
        ),
        // Comment button.
        methodCall(
            definingClass = "this",
            parameters = listOf("L", "L", "L", "L", "L", "L", "L", "I"),
            returnType = "V"
        )
    )
)
