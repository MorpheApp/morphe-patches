/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3769
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.feedcards

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.patches.youtube.shared.EngagementPanelControllerFingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * toString of the spacing above and below a list item, which the lists add with an item decoration.
 * Not available on 20.29 and older.
 */
internal object DividerInfoToStringFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        string("DividerInfo(topPadding=")
    )
)

/**
 * Returns the spacing of a list item, or null if the item has no spacing.
 *
 * @param dividerInfoType Class of [DividerInfoToStringFingerprint].
 */
internal class DividerInfoLookupFingerprint(dividerInfoType: String) : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = dividerInfoType,
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        methodCall(
            definingClass = "Ljava/util/HashMap;",
            name = "get"
        )
    )
)

/**
 * Sets the spacing above and below the view of a list item, which is looked up with the item of the view.
 *
 * @param dividerInfoLookupMethod Method of [DividerInfoLookupFingerprint].
 */
internal class DividerItemOffsetsFingerprint(dividerInfoLookupMethod: MethodReference) : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Landroid/graphics/Rect;",
        "Landroid/view/View;",
        "Landroid/support/v7/widget/RecyclerView;",
        "L"
    ),
    filters = listOf(
        methodCall(reference = dividerInfoLookupMethod)
    )
)

/**
 * List item of an element, which keeps the bytes of the element.
 */
internal object ElementItemConstructorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            returnType = "[B"
        ),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            type = "[B",
            location = MatchAfterWithin(1)
        ),
        opcode(Opcode.CONST_4, MatchAfterImmediately()),
        opcode(Opcode.IPUT_OBJECT, MatchAfterImmediately()),
        opcode(Opcode.RETURN_VOID, MatchAfterImmediately())
    )
)


/**
 * Returns the engagement panel controller of a surface: the controller of the watch page,
 * or the controller of the feed, search and Shorts, which shows its panels over the feed.
 * The fourth match reads the provider of the controller of the feed.
 */
internal object EngagementPanelControllerOfSurfaceFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "contains",
            parameters = listOf("Ljava/lang/Object;"),
            returnType = "Z"
        ),
        opcode(Opcode.MOVE_RESULT, MatchAfterImmediately()),
        opcode(Opcode.IF_EQZ, MatchAfterImmediately()),
        fieldAccess(opcode = Opcode.IGET_OBJECT, location = MatchAfterImmediately()),
        methodCall(opcode = Opcode.INVOKE_INTERFACE, location = MatchAfterImmediately())
    )
)

/**
 * Id of the panel that the engagement panel controller shows, or null.
 */
internal object EngagementPanelControllerShownPanelIdFingerprint : Fingerprint(
    classFingerprint = EngagementPanelControllerFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = listOf()
)
