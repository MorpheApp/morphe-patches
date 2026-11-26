/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3769
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.feedcards

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.patches.youtube.shared.EngagementPanelControllerFingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
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
 * toString of the layout of a section of a list: column count, gaps between rows and columns, and margins.
 * Not available on 21.13 and older.
 */
internal object SectionLayoutDescriptorToStringFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        string("SectionLayoutDescriptor(columnCount=")
    )
)

/**
 * Constructor of the layout of a section: column count, row gap, column gap, start, end, top and bottom margin
 * (in dp), and section id. The matches set the column count and the row gap.
 */
internal object SectionLayoutDescriptorConstructorFingerprint : Fingerprint(
    classFingerprint = SectionLayoutDescriptorToStringFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    parameters = listOf("I", "F", "F", "F", "F", "F", "F", "I"),
    filters = listOf(
        fieldAccess(opcode = Opcode.IPUT, type = "I"),
        fieldAccess(opcode = Opcode.IPUT, type = "F", location = MatchAfterImmediately())
    )
)

/**
 * Constructor of the layout info of a list, which gives the layouts of the sections of the list
 * to the layout manager of the list. The first match sets the sections of the list.
 * Not available on 21.13 and older.
 */
internal object ListLayoutInfoConstructorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    parameters = listOf("L", "Lcom/google/android/libraries/elements/converters/layout/FlowLayoutManager;"),
    filters = listOf(
        fieldAccess(opcode = Opcode.IPUT_OBJECT)
    )
)

/**
 * Returns a value of the layout of the section of a list item, or of the list without a position.
 *
 * @param listLayoutInfoType Class of [ListLayoutInfoConstructorFingerprint].
 * @param field              Field of the value in the class of [SectionLayoutDescriptorToStringFingerprint].
 * @param parameters         The position of the item, or none for the value of the list.
 */
internal class ListLayoutInfoValueFingerprint(
    listLayoutInfoType: String,
    field: FieldReference,
    parameters: List<String>
) : Fingerprint(
    definingClass = listLayoutInfoType,
    returnType = "I",
    parameters = parameters,
    filters = listOf(
        fieldAccess(reference = field, opcode = Opcode.IGET)
    )
)

/**
 * Makes the layout of the rows of the section of a list item, with the row gap of the section
 * and the row gap of the list.
 *
 * @param rowGapMethodName     Name of the method of [ListLayoutInfoValueFingerprint] that returns the row gap
 *                             of a section.
 * @param listRowGapMethodName Name of the method of [ListLayoutInfoValueFingerprint] that returns the row gap
 *                             of the list.
 */
internal class ListSectionRowLayoutFingerprint(
    rowGapMethodName: String,
    listRowGapMethodName: String
) : Fingerprint(
    returnType = "L",
    parameters = listOf("I", "L", "I", "I", "I", "Landroid/util/DisplayMetrics;"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            name = rowGapMethodName,
            returnType = "I",
            parameters = listOf("I")
        ),
        opcode(Opcode.MOVE_RESULT, MatchAfterImmediately()),
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            name = listRowGapMethodName,
            returnType = "I",
            parameters = listOf()
        ),
        opcode(Opcode.MOVE_RESULT, MatchAfterImmediately())
    )
)

/**
 * Returns the item of a list section at a position.
 *
 * @param sectionType Type of the sections of [ListLayoutInfoConstructorFingerprint].
 */
internal class ListSectionItemFingerprint(sectionType: String) : Fingerprint(
    definingClass = sectionType,
    returnType = "Ljava/lang/Object;",
    parameters = listOf("I")
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
 * ID of the panel that the engagement panel controller shows, or null.
 */
internal object EngagementPanelControllerShownPanelIdFingerprint : Fingerprint(
    classFingerprint = EngagementPanelControllerFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = listOf()
)
