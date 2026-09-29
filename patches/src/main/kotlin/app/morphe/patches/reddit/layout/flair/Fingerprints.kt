/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3260
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.flair

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.Opcode

internal object FeedElementProcessorFingerprint : Fingerprint(
    returnType = "Lkotlin/Pair;",
    parameters = listOf(
        "Lcom/reddit/feeds/data/FeedType;",
        "Ljava/util/List;",
        "Ljava/util/List;"
    ),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.SGET_OBJECT,
            definingClass = "Lcom/reddit/feeds/data/FeedType;",
            type = "Lcom/reddit/feeds/data/FeedType;",
        ),
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

internal object FeedPostSectionToStringFingerprint : Fingerprint(
    name = "toString",
    parameters = listOf(),
    returnType = "Ljava/lang/String;",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;"
        )
    ),
    strings = listOf(
        "FeedPostSection(linkId=",
        ", sections="
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
