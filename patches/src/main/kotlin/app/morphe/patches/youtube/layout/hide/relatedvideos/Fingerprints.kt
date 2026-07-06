/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.hide.relatedvideos

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

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
 * Matches the constructor that initializes the empty protobuf list.
 * Caller must supply the dynamically resolved results class.
 */
internal fun emptyProtobufListFingerprint(resultsClass: String) = Fingerprint(
    definingClass = resultsClass,
    name = "<init>",
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            name = "emptyProtobufList"
        )
    )
)

/**
 * Matches the method parsing watch next results.
 * Caller must supply dynamic classes and return types resolved from earlier fingerprints.
 */
internal fun watchNextResultsFingerprint(
    watchNextResponseModelClass: String,
    resultsClass: String,
    emptyProtobufListReturnType: String,
    sectionIdentifierDefiningClass: String) = Fingerprint(
    definingClass = watchNextResponseModelClass,
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(resultsClass),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = resultsClass,
            type = emptyProtobufListReturnType
        ),
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            name = "iterator",
            location = MatchAfterImmediately()
        ),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = sectionIdentifierDefiningClass
        )
    )
)

/**
 * Matches the thumbnail crawler.
 * Caller must supply the dynamically resolved item section renderer field and class.
 */
internal fun firstHomeThumbnailCrawlerFingerprint(
    itemSectionRendererDefiningClass: String,
    itemSectionRendererField: FieldReference) = Fingerprint(
    returnType = "Ljava/util/List;",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        string("hint=%s,(%s=%s,cheatsheet=%b,key1=%s,w=%d,h=%d)"),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = itemSectionRendererDefiningClass
        ),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            reference = itemSectionRendererField
        )
    )
)
