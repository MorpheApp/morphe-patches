package app.morphe.patches.shared.misc.spoof

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val STREAMING_DATA_OUTER_CLASS =
    $$"Lcom/google/protos/youtube/api/innertube/StreamingDataOuterClass$StreamingData;"

internal object BuildInitPlaybackRequestFingerprint : Fingerprint(
    returnType = $$"Lorg/chromium/net/UrlRequest$Builder;",
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.IGET_OBJECT, // Moves the request URI string to a register to build the request with.
    ),
    strings = listOf(
        "Content-Type",
        "Range",
    )
)

// 21.21+
internal object BuildPlayerRequestURIBuilderFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = $$"Landroid/net/Uri$Builder;",
    parameters = listOf(),
    filters = listOf(
        string("key"),
        string("asig"),
        methodCall($$"Landroid/net/Uri$Builder;->appendQueryParameter(Ljava/lang/String;Ljava/lang/String;)Landroid/net/Uri$Builder;"),
        opcode(Opcode.RETURN_OBJECT)
    )
)

// 21.20 and lower
internal object BuildPlayerRequestURIFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.INVOKE_VIRTUAL, // Register holds player request URI.
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.IPUT_OBJECT,
        Opcode.IGET_OBJECT,
        Opcode.MONITOR_EXIT,
        Opcode.RETURN_OBJECT,
    ),
    strings = listOf(
        "key",
        "asig",
    )
)

internal object BuildRequestFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Lorg/chromium/net/UrlRequest", // UrlRequest; or UrlRequest$Builder;
    filters = listOf(
        methodCall(name = "newUrlRequestBuilder")
    ), // UrlRequest; or UrlRequest$Builder;
    custom = { methodDef, _ ->
        // Different targets have slightly different parameters

        // Earlier targets have parameters = listOf(:),
        // L
        // Ljava/util/Map;
        // [B
        // L
        // L
        // L
        // Lorg/chromium/net/UrlRequest$Callback;

        // Later targets have parameters = listOf(:),
        // L
        // Ljava/util/Map;
        // [B
        // L
        // L
        // L
        // Lorg/chromium/net/UrlRequest\$Callback;
        // L

        // 20.16+ uses a refactored and extracted method:
        // L
        // Ljava/util/Map;
        // [B
        // L
        // Lorg/chromium/net/UrlRequest$Callback;
        // L

        val parameterTypes = methodDef.parameterTypes
        val parameterTypesSize = parameterTypes.size
        (parameterTypesSize == 6 || parameterTypesSize == 7 || parameterTypesSize == 8) &&
                parameterTypes[1] == "Ljava/util/Map;" // URL headers.
                && indexOfNewUrlRequestBuilderInstruction(methodDef) >= 0
    }
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

// Feature flag that causes Shorts content to freeze and fail to load when scrolling.
// Flag does not seem to affect Shorts if spoofing is off.
internal object ReelItemWatchResponseFeatureFlagFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        literal(45638126L)
    )
)

internal fun indexOfNewUrlRequestBuilderInstruction(method: Method) = method.indexOfFirstInstruction {
    val reference = getReference<MethodReference>()
    opcode == Opcode.INVOKE_VIRTUAL && reference?.definingClass == "Lorg/chromium/net/CronetEngine;"
            && reference.name == "newUrlRequestBuilder"
            && reference.parameterTypes.size == 3
            && reference.parameterTypes[0] == "Ljava/lang/String;"
            && reference.parameterTypes[1] == $$"Lorg/chromium/net/UrlRequest$Callback;"
            && reference.parameterTypes[2] == "Ljava/util/concurrent/Executor;"
}
