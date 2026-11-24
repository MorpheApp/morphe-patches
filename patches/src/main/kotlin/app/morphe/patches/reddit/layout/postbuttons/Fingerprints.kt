/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5022
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.postbuttons

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.patches.reddit.misc.version.is_2026_37_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_39_0_or_greater
import com.android.tools.smali.dexlib2.Opcode

/**
 * toString of the state of an action bar button, such as the comment, repost, share and mod
 * buttons of posts and the reply button of comments.
 */
internal object ActionBarActionToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("ActionBarAction(contentDescription=")
    )
)

/**
 * toString of the state of the vote buttons of an action bar.
 */
internal object ActionBarVoteStateToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("ActionBarVoteState(selectedDirection=")
    )
)

/**
 * toString of the action bar section of the post detail screen, which logs its data.
 */
internal object PostActionBarSectionToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("PostActionBarSection(data="),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            location = MatchAfterWithin(5)
        )
    )
)

private val actionType get() = ActionBarActionToStringFingerprint.originalClassDef.type
private val voteStateType get() = ActionBarVoteStateToStringFingerprint.originalClassDef.type

/**
 * Call of the action bar, which posts, comments and the media viewer use.
 * Parameters: vote state, comment button, modifier, appearance, then the buttons that are null
 * when not shown: the award button before 2026.39.0, a composable lambda, and the repost, share
 * and mod buttons. 2026.37.0 also has a second award button after the mod button,
 * which is shown instead of the first by the refreshed action bar of a server flag.
 */
private val actionBarCallFilter = methodCall(
    parameters = {
        if (is_2026_39_0_or_greater) {
            listOf(voteStateType, actionType, "L", "L", actionType, actionType, actionType, "L", "I", "I")
        } else if (is_2026_37_0_or_greater) {
            listOf(
                voteStateType, actionType, "L", "L", "Lkotlin/jvm/functions/Function2;",
                actionType, actionType, actionType, "L", "L", "I", "I"
            )
        } else {
            listOf(
                voteStateType, actionType, "L", "L", "Lkotlin/jvm/functions/Function2;",
                actionType, actionType, actionType, "L", "I", "I"
            )
        }
    },
    returnType = "V",
    opcode = Opcode.INVOKE_STATIC_RANGE
)

/**
 * Footer of a post in the feeds.
 */
internal object PostFooterFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("post_footer"),
        // Modifier.testTag
        methodCall(
            parameters = listOf("L", "Ljava/lang/String;"),
            returnType = "L",
            location = MatchAfterImmediately()
        ),
        actionBarCallFilter
    )
)

/**
 * Footer of a post in the feeds that use the older post layout, with RplLinkFooterActionBarState.
 */
internal object LinkFooterActionBarFingerprint : Fingerprint(
    filters = listOf(
        string("rpl_action_bar"),
        actionBarCallFilter
    )
)

/**
 * Action bar of the post in the post detail screen, which shows the comments of the post.
 */
internal object PostDetailActionBarFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        // A value of the section data.
        fieldAccess(
            definingClass = {
                PostActionBarSectionToStringFingerprint.instructionMatches.last().fieldReference.type
            }
        ),
        actionBarCallFilter
    )
)

/**
 * Action bar of a post in the media viewer.
 */
internal object MediaViewerActionBarFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("action_bar"),
        actionBarCallFilter
    )
)

/**
 * Buttons on the side of a post in the media viewer: the vote buttons, then the comment, repost,
 * and share or mod buttons, which are shown by the same button composable.
 * Button parameters: icon, on click, content description, count test tag, and more.
 */
internal object MediaViewerRailFingerprint : Fingerprint(
    filters = listOf(
        string("comment_count"),
        string("comment_button", location = MatchAfterWithin(8)),
        // Comment button.
        methodCall(
            returnType = "V",
            opcode = Opcode.INVOKE_STATIC_RANGE,
            location = MatchAfterWithin(8)
        ),
        string("post_share_count")
    )
)

/**
 * Vote buttons on the side of a post in the media viewer.
 */
internal object MediaViewerRailVoteButtonsFingerprint : Fingerprint(
    definingClass = { MediaViewerRailFingerprint.instructionMatches[2].methodReference.definingClass },
    returnType = { "V" },
    filters = listOf(
        string("upvote_button")
    )
)

/**
 * Action bar button, such as the comment, repost, share and mod buttons.
 */
internal object ActionBarButtonFingerprint : Fingerprint(
    definingClass = { PostFooterFingerprint.instructionMatches.last().methodReference.definingClass },
    returnType = { "V" },
    parameters = {
        listOf(
            actionType,
            "L",
            "Lcom/reddit/ui/compose/ds/ContentActionButtonAppearance;",
            "L",
            "L",
            "Lcom/reddit/ui/compose/ds/ContentActionButtonSize;",
            "L",
            "L",
            "I",
            "I"
        )
    },
    filters = listOf(
        // Default button size. Methods with the same parameters that other patches add,
        // such as the button of 'Collapse all comments', pass the size on and do not read it.
        fieldAccess(
            opcode = Opcode.SGET_OBJECT,
            definingClass = "Lcom/reddit/ui/compose/ds/ContentActionButtonSize;",
            name = "Small"
        )
    )
)

/**
 * Action bar button before 2026.37.0.
 * Parameters: button state, icon, appearance, modifier, composer, flags.
 */
internal object ActionBarButtonLegacyFingerprint : Fingerprint(
    definingClass = { PostFooterFingerprint.instructionMatches.last().methodReference.definingClass },
    returnType = { "V" },
    parameters = { listOf(actionType, "L", "L", "L", "L", "I") }
)

/**
 * Upvote and downvote buttons of an action bar.
 */
internal object ActionBarVoteButtonsFingerprint : Fingerprint(
    definingClass = { PostFooterFingerprint.instructionMatches.last().methodReference.definingClass },
    returnType = { "V" },
    parameters = {
        listOf(
            voteStateType,
            "Lcom/reddit/ui/compose/ds/VoteButtonGroupAppearance;",
            "L",
            "F",
            "F",
            "Z",
            "Lcom/reddit/ui/compose/ds/VoteButtonGroupSize;",
            "L",
            "I",
            "I"
        )
    }
)

/**
 * Upvote and downvote buttons of an action bar before 2026.37.0.
 * Parameters: vote state, appearance, modifier, composer, flags.
 */
internal object ActionBarVoteButtonsLegacyFingerprint : Fingerprint(
    definingClass = { PostFooterFingerprint.instructionMatches.last().methodReference.definingClass },
    returnType = { "V" },
    parameters = { listOf(voteStateType, "L", "L", "L", "I") }
)

/**
 * Join button in the header of a post in the feeds.
 * Parameters: joined, on click, visibility tracking, on joined, modifier, enabled, composer, flags.
 */
internal object PostJoinButtonFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Z",
        "Lkotlin/jvm/functions/Function0;",
        "L",
        "Lkotlin/jvm/functions/Function0;",
        "L",
        "Z",
        "L",
        "I",
        "I"
    ),
    filters = listOf(
        string("post_join_button")
    )
)

/**
 * Post header in the feeds before 2026.37.0, which shows the Join button itself if the first
 * checked flag is set and the post is not of a certain type.
 */
internal object PostJoinButtonLegacyFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        opcode(Opcode.IF_EQZ),
        opcode(Opcode.INSTANCE_OF, location = MatchAfterImmediately()),
        opcode(Opcode.IF_NEZ, location = MatchAfterImmediately()),
        string("post_join_button", location = MatchAfterWithin(15))
    )
)

/**
 * Award button of a comment.
 * Parameters: enabled, comment id, awards state, on award click, composer, flags.
 */
internal object CommentAwardButtonFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Z", "Ljava/lang/String;", "L", "L", "L", "I"),
    filters = listOf(
        string("award_comment_icon")
    )
)

private val postDetailHostScreenFilter = fieldAccess(
    opcode = Opcode.SGET_OBJECT,
    definingClass = "Lcom/reddit/subscriptions/HostScreen;",
    name = "POST_DETAIL"
)

/**
 * Join button in the header of the post in the post detail screen. It also tracks if the button
 * is shown, for a Join banner that some screens show when it is scrolled away.
 */
internal object PostDetailJoinButtonFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        postDetailHostScreenFilter,
        newInstance(
            $$"Lcom/reddit/postdetail/refactor/elements/postmetadata/composables/PostUnitMetadataElementKt$communityJoinBannerVisibilityTracking$1$1;"
        )
    )
)

/**
 * Header of the post in the post detail screen before 2026.39.0,
 * which shows the Join button with the subscription button composable.
 */
internal object PostDetailJoinButtonLegacyFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        postDetailHostScreenFilter,
        // Subscription button, with the subscription state.
        methodCall(
            parameters = listOf("L", "Ljava/lang/Object;", "L", "I"),
            returnType = "V",
            opcode = Opcode.INVOKE_STATIC,
            location = MatchAfterWithin(30)
        )
    )
)
