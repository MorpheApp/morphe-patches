/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3260
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.flair

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object FeedElementProcessorFingerprint : Fingerprint(
    returnType = "Lkotlin/Pair;",
    parameters = listOf(
        "Lcom/reddit/feeds/data/FeedType;",
        "Ljava/util/List;",
        "Ljava/util/List;"
    ),
    filters = listOf(
        methodCall(
            smali = "Lkotlin/Pair;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V"
        )
    )
)

internal object LinkConstructorFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/domain/model/Link;",
    name = "<init>",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_BOOLEAN,
            definingClass = "this",
            name = "isBlankAd"
        )
    )
)

internal object PostFlairFeedPresentationModelToStringFingerprint : Fingerprint(
    name = "toString",
    parameters = listOf(),
    returnType = "Ljava/lang/String;",
    strings = listOf(
        "PostFlairFeedPresentationModel(text=",
        ", richText="
    )
)

internal object PostFlairsElementToStringFingerprint : Fingerprint(
    name = "toString",
    parameters = listOf(),
    returnType = "Ljava/lang/String;",
    strings = listOf(
        "PostFlairsElement(linkId=",
        ", uniqueId="
    )
)

internal object PostFlairsSectionToStringFingerprint : Fingerprint(
    name = "toString",
    parameters = listOf(),
    returnType = "Ljava/lang/String;",
    strings = listOf(
        "PostFlairsSection(feedElement="
    )
)

internal object FeedPostTitleSectionToStringFingerprint : Fingerprint(
    name = "toString",
    parameters = listOf(),
    returnType = "Ljava/lang/String;",
    strings = listOf(
        "FeedPostTitleSection(data="
    )
)

/**
 * Reddit's mapper that creates a flair element with a single flair.
 */
internal fun postFlairsElementMapperFingerprint(
    presentationModelType: String,
    flairsElementType: String
) = Fingerprint(
    returnType = flairsElementType,
    filters = listOf(
        methodCall(
            definingClass = presentationModelType,
            name = "<init>"
        ),
        // Creates a persistent list from an array.
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("[Ljava/lang/Object;")
        ),
        newInstance(flairsElementType),
        methodCall(
            definingClass = flairsElementType,
            name = "<init>"
        )
    )
)

internal object PostPreviewFeedElementToStringFingerprint : Fingerprint(
    name = "toString",
    parameters = listOf(),
    returnType = "Ljava/lang/String;",
    strings = listOf(
        "PostPreviewFeedElement(linkId=",
        ", uniqueId="
    )
)

internal object PostPreviewFeedElementConstructorFingerprint : Fingerprint(
    classFingerprint = PostPreviewFeedElementToStringFingerprint,
    name = "<init>",
    filters = listOf(
        // Link id.
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;"
        ),
        // Unique id.
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;",
            location = MatchAfterImmediately()
        ),
        // Promoted.
        fieldAccess(
            opcode = Opcode.IPUT_BOOLEAN,
            definingClass = "this",
            location = MatchAfterImmediately()
        ),
        // Identifier.
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            location = MatchAfterImmediately()
        ),
        // Child elements, converted to an immutable list.
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("Ljava/lang/Iterable;")
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            location = MatchAfterImmediately()
        )
    )
)

/**
 * Kotlin data class copy method with a default value bit mask.
 */
internal fun postPreviewFeedElementCopyFingerprint(
    postPreviewFeedElementType: String,
    flairsElementType: String
) = Fingerprint(
    definingClass = postPreviewFeedElementType,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = postPreviewFeedElementType,
    filters = listOf(
        // Flairs element default value bit.
        opcode(Opcode.AND_INT_LIT16),
        opcode(Opcode.IF_EQZ, MatchAfterImmediately()),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = postPreviewFeedElementType,
            type = flairsElementType,
            location = MatchAfterImmediately()
        )
    )
)

internal object FeedPostSectionToStringFingerprint : Fingerprint(
    name = "toString",
    parameters = listOf(),
    returnType = "Ljava/lang/String;",
    strings = listOf(
        "FeedPostSection(linkId=",
        ", sections="
    )
)

internal object FeedPostSectionConstructorFingerprint : Fingerprint(
    classFingerprint = FeedPostSectionToStringFingerprint,
    name = "<init>",
    filters = listOf(
        // Link id.
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;"
        ),
        // Sections.
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            location = MatchAfterImmediately()
        ),
        // Unique id.
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;",
            location = MatchAfterImmediately()
        ),
        // Identifier.
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            location = MatchAfterImmediately()
        )
    )
)

internal fun feedPostSectionMapperFingerprint(feedPostSectionType: String) = Fingerprint(
    filters = listOf(
        newInstance(feedPostSectionType),
        methodCall(
            definingClass = feedPostSectionType,
            name = "<init>"
        ),
        opcode(Opcode.RETURN_OBJECT, MatchAfterImmediately())
    )
)
