/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.hide.relatedvideos

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.string
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal object RelatedItemSectionFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Z",
    parameters = listOf("L"),
    filters = listOf(
        opcode(Opcode.AND_INT_LIT8),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Ljava/lang/String;"
        ),
        string(
            string = "related-items",
            location = MatchAfterWithin(3)
        ),
    )
)

internal object WatchNextResponseModelClassResolverFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        string("Request being made from non-critical thread"),
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            smali = "Lcom/google/common/util/concurrent/ListenableFuture;->get()Ljava/lang/Object;"
        ),
        opcode(
            opcode = Opcode.CHECK_CAST,
            location = MatchAfterWithin(3)
        )
    )
)

/**
 * The method of the watch next response model that reads the sections of the watch page.
 * The first instruction match is the field of the sections, and the last one is the
 * item section renderer field of the section class.
 */
internal fun BytecodePatchContext.watchNextResultsFingerprint(
    sectionsType: String,
    resultsClass: String,
) = Fingerprint(
    definingClass = WatchNextResponseModelClassResolverFingerprint
        .instructionMatches.last().instruction.getReference<TypeReference>()!!.type,
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(resultsClass),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = resultsClass,
            type = sectionsType
        ),
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            name = "iterator",
            location = MatchAfterImmediately()
        ),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = RelatedItemSectionFingerprint.instructionMatches[1].fieldReference.definingClass
        )
    )
)
