/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5298
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.commentflair

import app.morphe.patcher.Fingerprint

/**
 * Getter of the author's user flair text on a comment.
 */
internal object CommentAuthorFlairTextFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/domain/model/Comment;",
    name = "getAuthorFlairText",
    returnType = "Ljava/lang/String;",
    parameters = listOf()
)

/**
 * Getter of the author's rich-text user flair on a comment.
 */
internal object CommentAuthorFlairRichTextFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/domain/model/Comment;",
    name = "getAuthorFlairRichText",
    returnType = "Ljava/util/List;",
    parameters = listOf()
)

/**
 * Getter of the author's community achievement badge on a comment.
 */
internal object CommentAuthorAchievementsBadgeFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/domain/model/Comment;",
    name = "getAuthorAchievementsBadge",
    returnType = "Lcom/reddit/domain/model/AchievementBadge;",
    parameters = listOf()
)
