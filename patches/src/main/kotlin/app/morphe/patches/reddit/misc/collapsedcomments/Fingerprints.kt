/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3517
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.collapsedcomments

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal const val COMMENT_CLASS = "Lcom/reddit/domain/model/Comment;"

/**
 * Getter of the domain comment collapsed flag.
 */
internal object CommentGetCollapsedFingerprint : Fingerprint(
    definingClass = COMMENT_CLASS,
    name = "getCollapsed",
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_BOOLEAN,
            definingClass = "this",
            type = "Z"
        ),
        opcode(
            opcode = Opcode.RETURN,
            location = MatchAfterImmediately()
        )
    )
)

/**
 * Comment tree store (RedditCommentTreeStore) helper that replaces one item of the
 * id -> comment map with the result of a transform:
 *
 * ```
 * static Map update(Map map, Object key, Function1 transform) {
 *     Object old = map.get(key);
 *     return old == null ? map : plus(map, new Pair(key, transform.invoke(old)));
 * }
 * ```
 *
 * Collapse, expand and collapse-thread all go through it (with a transform that copies
 * the comment with a different `collapsed` value).
 *
 * Before 2026.14.0 the Function1 type is obfuscated, so it is not used for matching.
 */
internal object CommentTreeReplaceItemFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/comments/tree/",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Ljava/util/Map;",
    parameters = listOf(
        "Ljava/util/Map;",
        "Ljava/lang/Object;",
        "L"
    ),
    filters = listOf(
        methodCall(
            definingClass = "Ljava/util/Map;",
            name = "get"
        ),
        methodCall(
            name = "invoke",
            parameters = listOf("Ljava/lang/Object;"),
            returnType = "Ljava/lang/Object;"
        ),
        opcode(
            opcode = Opcode.MOVE_RESULT_OBJECT,
            location = MatchAfterImmediately()
        )
    )
)
