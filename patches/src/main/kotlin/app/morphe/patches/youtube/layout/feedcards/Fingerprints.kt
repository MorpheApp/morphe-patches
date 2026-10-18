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
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Runs the command of the watch next response for when the watch page is ready.
 *
 * @param uiReadyCommandMethod Method that runs the command, with the parameters (command, Map, watch next response).
 */
internal class WatchNextUiReadyFingerprint(uiReadyCommandMethod: MethodReference) : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = uiReadyCommandMethod.parameterTypes[2].toString()
        ),
        string("com.google.android.libraries.youtube.innertube.endpoint.tag"),
        string("sectionListController"),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = uiReadyCommandMethod.definingClass
        ),
        methodCall(
            reference = uiReadyCommandMethod,
            location = MatchAfterWithin(2)
        )
    )
)

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

