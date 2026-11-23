/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.hidepost

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags.FINAL
import com.android.tools.smali.dexlib2.AccessFlags.PUBLIC
import com.android.tools.smali.dexlib2.AccessFlags.STATIC
import com.android.tools.smali.dexlib2.Opcode

/**
 * Post menu builder: the check whether recommendation options replace Hide. 2026.44.0 and newer.
 */
internal object PostMenuHideCheckFingerprint : Fingerprint(
    accessFlags = listOf(PUBLIC, STATIC, FINAL),
    returnType = "Ljava/lang/Object;",
    filters = listOf(
        newInstance("Lcom/reddit/feeds/impl/ui/RedditOverflowMenuProvider\$buildLoggedInMenu\$1;"),
        methodCall(
            parameters = listOf("Lcom/reddit/feeds/data/FeedType;"),
            returnType = "Z"
        ),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately())
    )
)

/**
 * Post menu builder: the check whether recommendation options replace Hide. 2026.43.0 and earlier.
 */
internal object PostMenuHideCheckLegacyFingerprint : Fingerprint(
    accessFlags = listOf(PUBLIC, STATIC, FINAL),
    returnType = "Ljava/lang/Object;",
    filters = listOf(
        newInstance("Lcom/reddit/feeds/impl/ui/RedditOverflowMenuProvider\$buildLoggedInMenu\$1;"),
        methodCall(
            definingClass = "Lcom/reddit/feeds/domain/features/XOnPostsVariant;",
            name = "getShowsXOnNonRecommendedPosts"
        ),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately())
    )
)
