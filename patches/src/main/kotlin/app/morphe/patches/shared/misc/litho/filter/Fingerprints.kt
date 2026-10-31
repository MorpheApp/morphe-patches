/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared.misc.litho.filter

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal object AccessibilityIdFingerprint : Fingerprint(
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = listOf(),
            returnType = "Ljava/lang/String;"
        ),
        string("primary_image", location = MatchAfterWithin(5)),
    )
)

private object EmptyComponentParentFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.CONSTRUCTOR),
    parameters = listOf(),
    filters = listOf(
        string("EmptyComponent")
    )
)

internal object EmptyComponentFingerprint : Fingerprint(
    classFingerprint = EmptyComponentParentFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "L",
    parameters = listOf("L")
)

internal object LithoFilterFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS,
    accessFlags = listOf(AccessFlags.STATIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.SPUT_OBJECT,
            definingClass = "this",
            type = EXTENSION_FILTER
        )
    )
)

internal object ProtobufBufferEncodeFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "[B",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Lcom/google/android/libraries/elements/adl/UpbMessage;"
        ),
        methodCall(
            definingClass = "Lcom/google/android/libraries/elements/adl/UpbMessage;",
            name = "jniEncode"
        )
    )
)

internal object LithoThreadExecutorFingerprint : Fingerprint(
    superclass = "Ljava/util/concurrent/ThreadPoolExecutor;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf("I", "I", "I"),
    filters = listOf(
        literal(1L) // 1L = default thread timeout.
    )
)

internal object LithoConverterBufferUpbFeatureFlagFingerprint : Fingerprint(
    returnType = "L",
    filters = listOf(
        literal(45419603L)
    )
)

/**
 * Creates the decorator of the command event data of an item of a list, such as the feed.
 * The decorator holds the data, and the item is the first parameter of the data.
 */
internal object CommandEventDataDecoratorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "L",
    parameters = listOf("Ljava/lang/Object;", "L", "Ljava/util/Map;"),
    filters = listOf(
        methodCall(
            name = "<init>",
            parameters = listOf("Ljava/lang/Object;", "L", "L", "L", "Ljava/util/List;")
        ),
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            name = "<init>",
            parameters = listOf("Ljava/util/Map;", "L"),
            location = MatchAfterWithin(2)
        )
    )
)

/**
 * Gets the decorators of the command event data of the conversion context.
 * They are in the elements config if there is one, otherwise in the field.
 */
internal fun commandEventDataDecoratorsFingerprint(decoratorsField: FieldReference) = Fingerprint(
    definingClass = decoratorsField.definingClass,
    returnType = decoratorsField.type,
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            reference = decoratorsField
        )
    )
)

/**
 * Constructor of the observable list that holds the items of the lists, such as the feed.
 */
internal object ObservableListConstructorFingerprint : Fingerprint(
    superclass = "Ljava/util/AbstractList;",
    name = "<init>",
    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.CONSTRUCTOR),
    parameters = listOf("Ljava/util/List;", "L")
)

/**
 * Removes an item from a section of a list, such as the feed or the related videos, and lays the
 * section out again without it. The grids of the tablet layout that group the items of a section
 * into rows, such as the related videos, are laid out again too.
 */
internal object ListSectionRemoveItemFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        // The header of the section, which hides the whole section instead.
        fieldAccess(opcode = Opcode.IGET_OBJECT),
        opcode(Opcode.IF_NE, location = MatchAfterWithin(2)),
        // The items of the section.
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = "Ljava/util/List;", location = MatchAfterWithin(4)),
        methodCall(
            definingClass = "Ljava/util/List;",
            name = "remove",
            parameters = listOf("Ljava/lang/Object;"),
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        opcode(Opcode.IF_EQZ, location = MatchAfterImmediately())
    )
)

/**
 * Releases a section of a list, such as when the list is created again.
 * The section stops listening to the events, such as the removal of its items.
 */
internal object ListSectionReleaseFingerprint : Fingerprint(
    classFingerprint = ListSectionRemoveItemFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "this"),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf("Ljava/lang/Object;"),
            returnType = "V",
            location = MatchAfterImmediately()
        )
    )
)

/**
 * Updates a section of a list after its items are removed. A grid lays out its rows again,
 * otherwise the items are removed one by one.
 */
internal object ListSectionUpdateRowsFingerprint : Fingerprint(
    classFingerprint = ListSectionRemoveItemFingerprint,
    returnType = "V",
    filters = listOf(
        // If the section is a grid.
        methodCall(opcode = Opcode.INVOKE_DIRECT, definingClass = "this", returnType = "Z", parameters = listOf()),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        opcode(Opcode.IF_EQZ, location = MatchAfterImmediately()),
        // Lays out the rows again.
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            definingClass = "this",
            returnType = "V",
            parameters = listOf(),
            location = MatchAfterImmediately()
        ),
        // Updates the rest of the section, such as the show more button. It is right after
        // laying out the rows on some versions, and after removing the items on others.
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            definingClass = "this",
            returnType = "V",
            parameters = listOf()
        )
    )
)
