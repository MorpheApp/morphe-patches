package app.morphe.patches.shared.misc.spoof

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal object BuildInitPlaybackRequestFingerprint : Fingerprint(
    returnType = "Lorg/chromium/net/UrlRequest\$Builder;",
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.IGET_OBJECT, // Moves the request URI string to a register to build the request with.
    ),
    strings = listOf(
        "Content-Type",
        "Range",
    )
)

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

internal object ProtobufClassParseByteBufferFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.STATIC),
    returnType = "L",
    parameters = listOf("L", "Ljava/nio/ByteBuffer;"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.SGET_OBJECT,
        Opcode.INVOKE_STATIC,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.RETURN_OBJECT,
    ),
    custom = { method, _ -> method.name == "parseFrom" }
)

internal object CreateStreamingDataFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf("L"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.IPUT_OBJECT,
        Opcode.IGET_OBJECT,
        Opcode.IF_NEZ,
        Opcode.SGET_OBJECT,
        Opcode.IPUT_OBJECT,
    ),
    custom = { method, classDef ->
        classDef.fields.any { field ->
            field.name == "a" && field.type.endsWith("/StreamingDataOuterClass\$StreamingData;")
        }
    }
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

internal const val DISABLED_BY_SABR_STREAMING_URI_STRING = "DISABLED_BY_SABR_STREAMING_URI"

internal object MediaFetchEnumConstructorFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf(
        "ENABLED",
        "DISABLED_FOR_PLAYBACK",
        DISABLED_BY_SABR_STREAMING_URI_STRING
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

internal object PatchIncludedExtensionMethodFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(),
    custom = { method, classDef ->
        method.name == "isPatchIncluded" && classDef.type == EXTENSION_CLASS_DESCRIPTOR
    }
)

// Feature flag that turns on Platypus programming language code compiled to native C++.
// This code appears to replace the player config after the streams are loaded.
// Flag is present in YouTube 19.34, but is missing Platypus stream replacement code until 19.43.
// Flag and Platypus code is also present in newer versions of YouTube Music.
internal object MediaFetchHotConfigFingerprint : Fingerprint(
    filters = listOf(
        literal(45645570L)
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
// but it's exact purpose is not known. If this flag is enabled while stream spoofing
// then videos will never start playback and load forever.
// Flag does not seem to affect playback if spoofing is off.
internal object PlaybackStartDescriptorFeatureFlagFingerprint : Fingerprint(
    parameters = listOf(),
    returnType = "Z",
    filters = listOf(
        literal(45665455L)
    )
)

internal object MediaSessionFeatureFlagFingerprint : Fingerprint(
    parameters = listOf(),
    returnType = "Z",
    filters = listOf(
        literal(45640404L)
    )
)

internal fun indexOfNewUrlRequestBuilderInstruction(method: Method) = method.indexOfFirstInstruction {
    val reference = getReference<MethodReference>()
    opcode == Opcode.INVOKE_VIRTUAL && reference?.definingClass == "Lorg/chromium/net/CronetEngine;"
            && reference.name == "newUrlRequestBuilder"
            && reference.parameterTypes.size == 3
            && reference.parameterTypes[0] == "Ljava/lang/String;"
            && reference.parameterTypes[1] == "Lorg/chromium/net/UrlRequest\$Callback;"
            && reference.parameterTypes[2] == "Ljava/util/concurrent/Executor;"
}
