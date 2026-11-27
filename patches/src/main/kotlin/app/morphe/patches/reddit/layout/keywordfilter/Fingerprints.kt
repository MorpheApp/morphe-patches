/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4867
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.keywordfilter

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

private fun feedElementToStringFingerprint(name: String) = Fingerprint(
    name = "toString",
    parameters = listOf(),
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("$name(linkId=")
    )
)

/**
 * Fields that the constructor of every feed element sets first:
 * link id, unique id, promoted and identifier.
 */
private fun feedElementFieldFilters() = listOf(
    fieldAccess(
        opcode = Opcode.IPUT_OBJECT,
        definingClass = "this",
        type = "Ljava/lang/String;"
    ),
    fieldAccess(
        opcode = Opcode.IPUT_OBJECT,
        definingClass = "this",
        type = "Ljava/lang/String;",
        location = MatchAfterImmediately()
    ),
    fieldAccess(
        opcode = Opcode.IPUT_BOOLEAN,
        definingClass = "this",
        location = MatchAfterImmediately()
    ),
    fieldAccess(
        opcode = Opcode.IPUT_OBJECT,
        definingClass = "this",
        location = MatchAfterImmediately()
    )
)

internal object PostTitleElementToStringFingerprint : Fingerprint(
    name = "toString",
    parameters = listOf(),
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("PostTitleElement(linkId=")
    )
)

/**
 * The public link id method of the base class of all feed elements,
 * which is the superclass of [PostTitleElementToStringFingerprint].
 */
internal object FeedElementGetLinkIdFingerprint : Fingerprint(
    definingClass = { PostTitleElementToStringFingerprint.originalClassDef.superclass!! },
    name = "getLinkId",
    returnType = { "Ljava/lang/String;" },
    parameters = { emptyList() }
)

internal object PostTitleElementConstructorFingerprint : Fingerprint(
    classFingerprint = PostTitleElementToStringFingerprint,
    name = "<init>",
    filters = feedElementFieldFilters() + listOf(
        // Title.
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;",
            location = MatchAfterImmediately()
        ),
        // Translated title.
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;",
            location = MatchAfterImmediately()
        )
    )
)

internal object PreviewTextElementConstructorFingerprint : Fingerprint(
    classFingerprint = feedElementToStringFingerprint("PreviewTextElement"),
    name = "<init>",
    filters = feedElementFieldFilters() + listOf(
        // Text.
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;",
            location = MatchAfterImmediately()
        ),
        // Number of lines.
        fieldAccess(
            opcode = Opcode.IPUT,
            definingClass = "this",
            location = MatchAfterImmediately()
        ),
        // Is read.
        fieldAccess(
            opcode = Opcode.IPUT_BOOLEAN,
            definingClass = "this",
            location = MatchAfterImmediately()
        ),
        // Translated text.
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;",
            location = MatchAfterImmediately()
        )
    )
)

internal object RichTextElementConstructorFingerprint : Fingerprint(
    classFingerprint = feedElementToStringFingerprint("RichTextElement"),
    name = "<init>",
    filters = feedElementFieldFilters() + listOf(
        // Rich text JSON.
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;",
            location = MatchAfterImmediately()
        )
    )
)

/**
 * Post of the classic feed layout, which has its own title fields.
 */
internal object ClassicPostElementConstructorFingerprint : Fingerprint(
    classFingerprint = feedElementToStringFingerprint("ClassicPostElement"),
    name = "<init>",
    filters = listOf(
        // Title.
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;"
        ),
        // Is read. Some versions move a register in between. A larger distance
        // would match the String fields a few fields before the title.
        fieldAccess(
            opcode = Opcode.IPUT_BOOLEAN,
            definingClass = "this",
            location = MatchAfterWithin(1)
        ),
        // Thumbnail.
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            location = MatchAfterWithin(3)
        ),
        // Flairs.
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            location = MatchAfterWithin(3)
        ),
        // Mod role indicators.
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Ljava/util/List;",
            location = MatchAfterWithin(3)
        ),
        // Mod action indicators.
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Ljava/util/List;",
            location = MatchAfterWithin(3)
        ),
        // Mod note label.
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Lcom/reddit/mod/notes/domain/model/NoteLabel;",
            location = MatchAfterWithin(3)
        ),
        // Translated title.
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;",
            location = MatchAfterWithin(3)
        )
    )
)
