package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object StartVideoInformerFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.INVOKE_INTERFACE,
        Opcode.RETURN_VOID,
    ),
    strings = listOf("pc")
)

internal object StoryboardRendererDecoderRecommendedLevelFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    strings = listOf("#-1#")
)

internal object SubtitleManagerFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        string(""),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf(),
            returnType = "Z"
        ),
        opcode(opcode = Opcode.IF_EQZ, location = MatchAfterWithin(3))
    )
)

internal object SubtitleManagerConstructorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.STATIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        string("subtitles")
    )
)

/**
 * YouTube 20.26+
 */
internal object NoVolumeCaptionsFeatureFlagFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    filters = listOf(
        literal(45692436L)
    )
)

internal object TimedTextUrlFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = "Lorg/chromium/net/CronetEngine;->newUrlRequestBuilder(Ljava/lang/String;Lorg/chromium/net/UrlRequest\$Callback;Ljava/util/concurrent/Executor;)Lorg/chromium/net/UrlRequest\$Builder;",
        ),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            smali = "Lorg/chromium/net/UploadDataProviders;->create(Ljava/nio/ByteBuffer;)Lorg/chromium/net/UploadDataProvider;",
        )
    )
)