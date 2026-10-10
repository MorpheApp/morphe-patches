/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3516
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.feedscroll

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.newInstance
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * toString of the feed view state (com.reddit.feeds `Feed(sections=..., lastPostIdVisited=...)`).
 *
 * The field read right after ", lastPostIdVisited=" is a small wrapper class around the post id.
 * Its type is used to find the "last visited post id provider" that writes it.
 */
internal object FeedStateToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("Feed(sections="),
        string(", lastPostIdVisited="),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            location = MatchAfterWithin(3)
        )
    )
)

/**
 * Setter of the "last visited post id provider", which wraps the opened post id and stores it.
 */
internal fun rememberOpenedPostFingerprint(postIdType: String) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        newInstance(postIdType),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = postIdType
        )
    )
)
