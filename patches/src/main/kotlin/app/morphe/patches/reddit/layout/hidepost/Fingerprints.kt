/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5243
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.hidepost

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

private const val BUILD_LOGGED_IN_MENU_CLASS =
    "Lcom/reddit/feeds/impl/ui/RedditOverflowMenuProvider\$buildLoggedInMenu\$1;"

private const val X_ON_POSTS_VARIANT_CLASS =
    "Lcom/reddit/feeds/domain/features/XOnPostsVariant;"

/**
 * Signed in feed post menu builder, the only method that creates [BUILD_LOGGED_IN_MENU_CLASS].
 * Reddit 2026.42.0 and newer: a single experiment check (FeedType)Z decides whether
 * recommendation options replace Hide.
 */
internal object PostMenuHideCheckFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/Object;",
    filters = listOf(
        newInstance(BUILD_LOGGED_IN_MENU_CLASS),
        methodCall(
            parameters = listOf("Lcom/reddit/feeds/data/FeedType;"),
            returnType = "Z"
        ),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately())
    )
)

/**
 * Same method as [PostMenuHideCheckFingerprint], Reddit 2026.41.0 and earlier.
 * Hide is replaced if either of the two experiment variant getters returns true.
 */
internal object PostMenuHideCheckLegacyFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/Object;",
    filters = listOf(
        newInstance(BUILD_LOGGED_IN_MENU_CLASS),
        methodCall(
            definingClass = X_ON_POSTS_VARIANT_CLASS,
            name = "getEnablesShowFewerForNonRecommendedPosts"
        ),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        methodCall(
            definingClass = X_ON_POSTS_VARIANT_CLASS,
            name = "getShowsXOnNonRecommendedPosts"
        ),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately())
    )
)
