/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5156
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.automod

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

/**
 * Constructor of the comments loaded for a post. Every comment load goes through it,
 * before Reddit builds its comment tree (and its presentation models in older versions).
 */
internal object CommentsResultConstructorFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/domain/model/CommentsResultWithSource;",
    name = "<init>",
    returnType = "V",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            name = "comments",
            type = "Ljava/util/List;"
        )
    )
)

/**
 * Reddit 2026.40.0 and newer.
 */
internal object SetCommentTreesToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        string("SetCommentTrees(commentTrees=")
    )
)

/**
 * Constructor of the tree event that sets comment trees for a post.
 * Reddit 2026.40.0 and newer.
 */
internal object SetCommentTreesConstructorFingerprint : Fingerprint(
    classFingerprint = SetCommentTreesToStringFingerprint,
    name = "<init>",
    returnType = "V",
    parameters = listOf("Ljava/util/List;")
)

/**
 * Reddit 2026.40.0 and newer.
 */
internal object AppendCommentTreesToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        string("AppendCommentTrees(commentTrees=")
    )
)

/**
 * Constructor of the tree event that appends comment trees for a post.
 * Reddit 2026.40.0 and newer.
 */
internal object AppendCommentTreesConstructorFingerprint : Fingerprint(
    classFingerprint = AppendCommentTreesToStringFingerprint,
    name = "<init>",
    returnType = "V",
    parameters = listOf("Ljava/util/List;")
)

/**
 * Reddit 2026.40.0 and newer.
 */
internal object CommentTreeToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        string("CommentTree(rootCommentId=")
    )
)

/**
 * Constructor of CommentTree. The matched field holds the tree's comments list.
 * Reddit 2026.40.0 and newer.
 */
internal object CommentTreeConstructorFingerprint : Fingerprint(
    classFingerprint = CommentTreeToStringFingerprint,
    name = "<init>",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Ljava/util/ArrayList;"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Ljava/util/ArrayList;"
        )
    )
)
