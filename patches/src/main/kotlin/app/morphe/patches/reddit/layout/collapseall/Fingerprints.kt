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
 * Post-page action bar composable containing the comment onClick handler (2026.40.0+).
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
 * Action bar pill class static initializer defining the comment slot field (2026.40.0+).
 */
internal object ActionBarCommentButtonFingerprint : Fingerprint(
    name = "<clinit>",
    filters = listOf(
        string("actionBar_comment_button"),
        opcode(Opcode.SPUT_OBJECT, location = MatchAfterWithin(2))
    )
)

/**
 * Action bar action model toString method (2026.40.0+).
 */
internal object ActionBarActionToStringFingerprint : Fingerprint(
    name = "toString",
    filters = listOf(
        string("ActionBarAction(contentDescription=")
    )
)

/**
 * Action bar action model constructor assigning the onClick handler (2026.40.0+).
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

/**
 * Action bar icon model toString method (2026.40.0+).
 */
internal object IconToStringFingerprint : Fingerprint(
    name = "toString",
    filters = listOf(
        string("Icon(resourceId=")
    )
)

/**
 * Action bar icon model constructor assigning size and flag fields (2026.40.0+).
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
 * Comment tree store method returning visible comment index or count (2026.40.0+).
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
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        opcode(Opcode.IF_EQZ, location = MatchAfterImmediately()),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Ljava/util/List;",
            location = MatchAfterImmediately()
        )
    )
)

/**
 * Comment tree store method returning comment by id (2026.40.0+).
 */
internal object CommentTreeGetCommentFingerprint : Fingerprint(
    classFingerprint = CommentTreeIndexOfFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Lcom/reddit/domain/model/IComment;",
    parameters = listOf("Ljava/lang/String;")
)

/**
 * Comment tree store method applying a comment operation (2026.40.0+).
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

/**
 * Collapse comment operation toString method (2026.40.0+).
 */
internal object CollapseCommentOperationFingerprint : Fingerprint(
    name = "toString",
    filters = listOf(
        string("CollapseComment(kindWithId=")
    )
)

/**
 * Expand comment operation toString method (2026.40.0+).
 */
internal object ExpandCommentOperationFingerprint : Fingerprint(
    name = "toString",
    filters = listOf(
        string("ExpandComment(kindWithId=")
    )
)
