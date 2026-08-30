/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared.misc.spoof

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.OpcodesFilter.Companion.opcodesToFilters
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

private const val STREAMING_DATA_OUTER_CLASS =
    $$"Lcom/google/protos/youtube/api/innertube/StreamingDataOuterClass$StreamingData;"

internal object CuepointListFingerprint : Fingerprint(
    definingClass = $$"Lcom/google/android/apps/youtube/proto/streaming/CuepointListOuterClass$CuepointList;",
    name = "<clinit>",
    filters = listOf(
        methodCall(name = "registerDefaultInstance")
    )
)

internal object BuildInitPlaybackRequestFingerprint : Fingerprint(
    returnType = $$"Lorg/chromium/net/UrlRequest$Builder;",
    filters = opcodesToFilters(
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.IGET_OBJECT, // Moves the request URI string to a register to build the request with.
    ),
    strings = listOf(
        "Content-Type",
        "Range",
    )
)

private object CreateStreamingDataParentFingerprint : Fingerprint(
    strings = listOf("Invalid playback type; streaming data is not playable")
)

internal object CreateStreamingDataFingerprint : Fingerprint(
    classFingerprint = CreateStreamingDataParentFingerprint,
    name = "<init>",
    parameters = listOf("L"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = STREAMING_DATA_OUTER_CLASS
        ),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = STREAMING_DATA_OUTER_CLASS,
            location = MatchAfterWithin(7)
        ),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            location = MatchAfterImmediately()
        ),
        opcode(
            opcode = Opcode.IF_NEZ,
            location = MatchAfterImmediately()
        ),
        fieldAccess(
            opcode = Opcode.SGET_OBJECT,
            location = MatchAfterImmediately()
        ),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.AND_INT_LIT8),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            location = MatchAfterWithin(5)
        )
    )
)

internal fun abrStateDataFingerprint(playerConfigClass: String) = object : Fingerprint(
    returnType = "J",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = playerConfigClass
        ),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = playerConfigClass,
            location = MatchAfterImmediately()
        ),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            location = MatchAfterWithin(5)
        ),
        string("/videoplayback"),
        string("AbrStateDataSpec: Unexpected http body.")
    )
) {}


internal object PlayerConfigBuilderFingerprint : Fingerprint(
    returnType = "Lcom/google/protobuf/MessageLite;",
    filters = listOf(
        string("com.google.android.libraries.youtube.innertube.pref.player_config_supplier"),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            name = "decode",
            returnType = "[B"
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "createBuilder",
            parameters = listOf(),
            location = MatchAfterWithin(5)
        ),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            smali = "Lcom/google/protobuf/ExtensionRegistryLite;->getGeneratedRegistry()Lcom/google/protobuf/ExtensionRegistryLite;",
            location = MatchAfterWithin(5)
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "mergeFrom",
            parameters = listOf("[B", "Lcom/google/protobuf/ExtensionRegistryLite;"),
            location = MatchAfterWithin(5)
        ),
        opcode(
            opcode = Opcode.CHECK_CAST,
            location = MatchAfterWithin(3)
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "build",
            parameters = listOf(),
            location = MatchAfterWithin(3)
        )
    )
)

internal object BuildMediaDataSourceFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
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
        "I",
        "Ljava/lang/Object;",
    )
)

internal object HlsCurrentTimeFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Z", "L"),
    filters = listOf(
        literal(45355374L) // HLS current time feature flag.
    )
)

internal object MediaFetchEnumConstructorFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf(
        "ENABLED",
        "DISABLED_FOR_PLAYBACK",
        "DISABLED_BY_SABR_STREAMING_URI"
    )
)

internal object NerdsStatsVideoFormatBuilderFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    parameters = listOf("L"),
    filters = listOf(
        string("codecs=\"")
    )
)

// Feature flag that turns on Platypus programming language code compiled to native C++.
// This code appears to replace the player config after the streams are loaded.
// Flag is present in YouTube 19.34, but is missing Platypus stream replacement code until 19.43.
// Flag and Platypus code is also present in newer versions of YouTube Music.
internal object MediaFetchHotConfigFingerprint : Fingerprint(
    filters = listOf(
        literal(45645570L),
        opcode(
            opcode = Opcode.MOVE_RESULT,
            location = MatchAfterWithin(3)
        ),
        anyInstruction(
            opcode(
                opcode = Opcode.IF_EQZ,
                location = MatchAfterWithin(5)
            ),
            // Only for YouTube Music 7.29.52
            fieldAccess(
                opcode = Opcode.IPUT_BOOLEAN,
                definingClass = "this",
                location = MatchAfterWithin(5)
            )
        )
    )
)

// YT 20.10+, YT Music 8.11 - 8.14.
// Flag is missing in YT Music 8.15+, and it is not known if a replacement flag/feature exists.
internal object MediaFetchHotConfigAlternativeFingerprint : Fingerprint(
    filters = listOf(
        literal(45683169L)
    )
)

// Feature flag that enables different code for parsing and starting video playback,
// but its exact purpose is not known. If this flag is enabled while stream spoofing
// then videos will never start playback and load forever.
// Flag does not seem to affect playback if spoofing is off.
internal object PlaybackStartDescriptorFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45665455L)
    )
)

internal object MediaSessionFeatureFlagFingerprint : Fingerprint(
    parameters = listOf(),
    returnType = "Z",
    filters = listOf(
        literal(45640404L),
        opcode(
            opcode = Opcode.MOVE_RESULT,
            location = MatchAfterWithin(3)
        ),
        opcode(
            opcode = Opcode.RETURN,
            location = MatchAfterWithin(5)
        )
    )
)

// Feature flag that causes Shorts content to freeze and fail to load when scrolling.
// Flag does not seem to affect Shorts if spoofing is off.
internal object ReelItemWatchResponseFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45638126L)
    )
)
