/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3714
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.redgifs

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal const val DOMAIN_VIDEO_URLS_CLASS = "Lcom/reddit/domain/model/VideoUrls;"

/**
 * Main Link constructor, the only one that sets the url.
 */
internal object LinkConstructorFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/domain/model/Link;",
    name = "<init>",
    returnType = "V",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "Lcom/reddit/domain/model/Link;",
            name = "url",
            type = "Ljava/lang/String;"
        )
    )
)

/**
 * Feed post header.
 */
internal object MetadataCellToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        // Media path, which is nullable.
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;"
        ),
        opcode(Opcode.IF_NEZ, location = MatchAfterImmediately()),
        string("MetadataCellFragment(id="),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;",
            location = MatchAfterWithin(2)
        ),
        string(", mediaPath=")
    )
)

internal object MetadataCellConstructorFingerprint : Fingerprint(
    classFingerprint = MetadataCellToStringFingerprint,
    name = "<init>",
    returnType = "V"
)

internal object VideoElementToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        string("VideoElement(linkId="),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;",
            location = MatchAfterWithin(3)
        ),
        string(", defaultUrl="),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;",
            location = MatchAfterWithin(8)
        )
    )
)

/**
 * Feed video.
 */
internal fun videoElementConstructorFingerprint(urlField: FieldReference) = Fingerprint(
    definingClass = urlField.definingClass,
    name = "<init>",
    returnType = "V",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            reference = urlField
        )
    )
)

private object VideoUrlsToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        string("VideoUrls(defaultUrl=")
    )
)

/**
 * Video player urls. The first parameter is the default url.
 */
internal object VideoUrlsConstructorFingerprint : Fingerprint(
    classFingerprint = VideoUrlsToStringFingerprint,
    name = "<init>",
    returnType = "V"
)

/**
 * Video urls of feed videos.
 */
internal object DomainVideoUrlsConstructorFingerprint : Fingerprint(
    definingClass = DOMAIN_VIDEO_URLS_CLASS,
    name = "<init>",
    returnType = "V",
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Lcom/reddit/domain/model/PlaybackMp4s;",
        "Ljava/lang/String;"
    )
)

private object DataSpecToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("DataSpec[")
    )
)

/**
 * Media3 DataSpec, created for every url the video player loads.
 */
internal object DataSpecConstructorFingerprint : Fingerprint(
    classFingerprint = DataSpecToStringFingerprint,
    name = "<init>",
    returnType = "V",
    parameters = listOf(
        "Landroid/net/Uri;",
        "J",
        "I",
        "[B",
        "Ljava/util/Map;",
        "J",
        "J",
        "Ljava/lang/String;",
        "I"
    )
)
