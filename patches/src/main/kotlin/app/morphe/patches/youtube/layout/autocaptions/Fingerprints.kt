package app.morphe.patches.youtube.layout.autocaptions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal val startVideoInformerFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.INVOKE_INTERFACE,
        Opcode.RETURN_VOID,
    ),
    strings = listOf("pc")
)

internal val storyboardRendererDecoderRecommendedLevelFingerprint = Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("L"),
    strings = listOf("#-1#")
)

internal val subtitleTrackFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.CONST_STRING,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT,
        Opcode.RETURN,
    ),
    strings = listOf("DISABLE_CAPTIONS_OPTION"),
    custom = { _, classDef ->
        classDef.endsWith("/SubtitleTrack;")
    }
)
