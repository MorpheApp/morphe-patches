/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4958
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.feed

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object FeedContextToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        string("FeedContext(onEvent="),
        string(", feedVisibilityFlow=")
    )
)

/**
 * Composable that shows a feed as a staggered grid, with the feed context as the second parameter.
 */
internal object GridFeedFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/feeds/ui/composables/feed/",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        // rememberLazyStaggeredGridState()
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("I", "I", "L"),
            returnType = "Landroidx/compose/foundation/lazy/staggeredgrid/"
        ),
        opcode(
            opcode = Opcode.MOVE_RESULT_OBJECT,
            location = MatchAfterImmediately()
        )
    )
)

internal object FeedVisibilityEventFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/feeds/impl/ui/events/FeedVisibilityEvent;",
    name = "<init>",
    parameters = listOf("Lcom/reddit/feeds/ui/FeedVisibility;")
)
