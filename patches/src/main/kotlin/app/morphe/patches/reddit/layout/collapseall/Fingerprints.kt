/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5217
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.collapseall

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Action bar of the post page. Its third parameter is the click handler of the comment button.
 */
internal object PostActionBarFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(
        "L",
        "Lkotlin/jvm/functions/Function1;",
        "Lkotlin/jvm/functions/Function0;",
        "L",
        "L",
        "I"
    ),
    filters = listOf(
        resourceLiteral(ResourceType.STRING, "action_repost_on_reddit")
    )
)

/**
 * Static initializer of the class that draws the action bar buttons.
 * The matched field holds the test tags of the comment button.
 */
internal object ActionBarCommentButtonFingerprint : Fingerprint(
    name = "<clinit>",
    filters = listOf(
        string("actionBar_comment_button"),
        opcode(Opcode.SPUT_OBJECT, location = MatchAfterWithin(5))
    )
)

internal object ActionBarActionToStringFingerprint : Fingerprint(
    name = "toString",
    filters = listOf(
        string("ActionBarAction(contentDescription=")
    )
)

/**
 * Constructor of an action bar button. The matched field is the click handler.
 */
internal object ActionBarActionConstructorFingerprint : Fingerprint(
    classFingerprint = ActionBarActionToStringFingerprint,
    name = "<init>",
    parameters = listOf(
        "Ljava/lang/String;",
        "L",
        "L",
        "Z",
        "Lkotlin/jvm/functions/Function0;",
        "J",
        "I"
    ),
    filters = listOf(
        fieldAccess(opcode = Opcode.IPUT_OBJECT, type = "Lkotlin/jvm/functions/Function0;")
    )
)

internal object IconToStringFingerprint : Fingerprint(
    name = "toString",
    filters = listOf(
        string("Icon(resourceId=")
    )
)

/**
 * Icon constructor (size, resource id, flag).
 */
internal object IconConstructorFingerprint : Fingerprint(
    classFingerprint = IconToStringFingerprint,
    name = "<init>",
    parameters = listOf("J", "I", "Z"),
    filters = listOf(
        fieldAccess(opcode = Opcode.IPUT_BOOLEAN),
        fieldAccess(opcode = Opcode.IPUT_WIDE)
    )
)

/**
 * Comment tree store (RedditCommentTreeStore) method that returns the position of a comment:
 *
 * ```
 * public final int indexOf(String id) {
 *     State state = getLoadedState(this.state);
 *     if (state == null) return -1;
 *     List<String> visibleIds = state.visibleIds;
 *     ...
 * }
 * ```
 */
internal object CommentTreeIndexOfFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/comments/tree/",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "I",
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "this"),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            definingClass = "this",
            location = MatchAfterWithin(3)
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        opcode(Opcode.IF_EQZ, location = MatchAfterWithin(5)),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Ljava/util/List;",
            location = MatchAfterWithin(5)
        )
    )
)

/**
 * Comment tree store method that returns a loaded comment by id.
 */
internal object CommentTreeGetCommentFingerprint : Fingerprint(
    classFingerprint = CommentTreeIndexOfFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Lcom/reddit/domain/model/IComment;",
    parameters = listOf("Ljava/lang/String;")
)

/**
 * Comment tree store method that applies an operation, such as collapsing a comment:
 * `static void apply(RedditCommentTreeStore store, Operation operation)`
 */
internal object CommentTreeApplyOperationFingerprint : Fingerprint(
    classFingerprint = CommentTreeIndexOfFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "L"),
    filters = listOf(
        methodCall(smali = "Lkotlin/jvm/internal/Intrinsics;->areEqual(Ljava/lang/Object;Ljava/lang/Object;)Z")
    )
)

internal object CollapseCommentOperationToStringFingerprint : Fingerprint(
    name = "toString",
    filters = listOf(
        string("CollapseComment(kindWithId=")
    )
)

internal object ExpandCommentOperationToStringFingerprint : Fingerprint(
    name = "toString",
    filters = listOf(
        string("ExpandComment(kindWithId=")
    )
)
