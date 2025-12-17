package app.morphe.patches.youtube.video.information

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import app.morphe.patches.youtube.shared.VideoQualityChangedFingerprint
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal object CreateVideoPlayerSeekbarFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("timed_markers_width"),
    )
)

internal object OnPlaybackSpeedItemClickFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "L", "I", "J"),
    custom = { method, _ ->
        method.name == "onItemClick" &&
            method.implementation?.instructions?.find {
                it.opcode == Opcode.IGET_OBJECT &&
                    it.getReference<FieldReference>()!!.type == "Lcom/google/android/libraries/youtube/innertube/model/player/PlayerResponseModel;"
            } != null
    }
)

internal object PlayerControllerSetTimeReferenceFingerprint : Fingerprint(
    filters = OpcodesFilter.opcodesToFilters(
Opcode.INVOKE_DIRECT_RANGE, Opcode.IGET_OBJECT),
    strings = listOf("Media progress reported outside media playback: ")
)

internal object PlayerInitFingerprint : Fingerprint(
    filters = listOf(
        string("playVideo called on player response with no videoStreamingData."),
    )
)

internal object PlayerStatusEnumFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.STATIC, AccessFlags.CONSTRUCTOR),
    strings = listOf(
        "NEW",
        "PLAYBACK_PENDING",
        "PLAYBACK_LOADED",
        "PLAYBACK_INTERRUPTED",
        "INTERSTITIAL_REQUESTED",
        "INTERSTITIAL_PLAYING",
        "VIDEO_PLAYING",
        "ENDED",
    )
)

/**
 * Matched using class found in [PlayerInitFingerprint].
 */
internal object SeekFingerprint : Fingerprint(
    filters = listOf(
        string("Attempting to seek during an ad"),
    )
)

internal object VideoLengthFingerprint : Fingerprint(
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.MOVE_RESULT_WIDE,
        Opcode.CMP_LONG,
        Opcode.IF_LEZ,
        Opcode.IGET_OBJECT,
        Opcode.CHECK_CAST,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT_WIDE,
        Opcode.GOTO,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT_WIDE,
        Opcode.CONST_4,
        Opcode.INVOKE_VIRTUAL,
    )
)

/**
 * Matches using class found in [MdxPlayerDirectorSetVideoStageFingerprint].
 */
internal object MdxSeekFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf("J", "L"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT,
        Opcode.RETURN,
    ),
    custom = { methodDef, _ ->
        // The instruction count is necessary here to avoid matching the relative version
        // of the seek method we're after, which has the same function signature as the
        // regular one, is in the same class, and even has the exact same 3 opcodes pattern.
        methodDef.implementation!!.instructions.count() == 3
    }
)

internal object MdxPlayerDirectorSetVideoStageFingerprint : Fingerprint(
    filters = listOf(
        string("MdxDirector setVideoStage ad should be null when videoStage is not an Ad state "),
    )
)

/**
 * Matches using class found in [MdxPlayerDirectorSetVideoStageFingerprint].
 */
internal object MdxSeekRelativeFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    // Return type is boolean up to 19.39, and void with 19.39+.
    parameters = listOf("J", "L"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.IGET_OBJECT,
        Opcode.INVOKE_INTERFACE,
    )
)

/**
 * Matches using class found in [PlayerInitFingerprint].
 */
internal object SeekRelativeFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    // Return type is boolean up to 19.39, and void with 19.39+.
    parameters = listOf("J", "L"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.ADD_LONG_2ADDR,
        Opcode.INVOKE_VIRTUAL,
    )
)

/**
 * Resolves with the class found in [VideoQualityChangedFingerprint].
 */
internal object PlaybackSpeedMenuSpeedChangedFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf("L"),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET, type = "F")
    )
)

internal object PlaybackSpeedClassFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "L",
    parameters = listOf("L"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.RETURN_OBJECT
    ),
    strings = listOf("PLAYBACK_RATE_MENU_BOTTOM_SHEET_FRAGMENT")
)


internal const val YOUTUBE_VIDEO_QUALITY_CLASS_TYPE = "Lcom/google/android/libraries/youtube/innertube/model/media/VideoQuality;"

/**
 * YouTube 20.19 and lower.
 */
internal object VideoQualityLegacyFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(
        "I", // Resolution.
        "Ljava/lang/String;", // Human readable resolution: "480p", "1080p Premium", etc
        "Z",
        "L"
    ),
    custom = { _, classDef ->
        classDef.type == YOUTUBE_VIDEO_QUALITY_CLASS_TYPE
    }
)

internal object VideoQualityFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(
        "I", // Resolution.
        "L",
        "Ljava/lang/String;", // Human readable resolution: "480p", "1080p Premium", etc
        "Z",
        "L"
    ),
    custom = { _, classDef ->
        classDef.type == YOUTUBE_VIDEO_QUALITY_CLASS_TYPE
    }
)

internal object VideoQualitySetterFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("[L", "I", "Z"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.IF_EQZ,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.INVOKE_VIRTUAL,
        Opcode.IPUT_BOOLEAN,
    ),
    strings = listOf("menu_item_video_quality")
)

/**
 * Matches with the class found in [VideoQualitySetterFingerprint].
 */
internal object SetVideoQualityFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.IGET_OBJECT,
        Opcode.IPUT_OBJECT,
        Opcode.IGET_OBJECT,
    )
)
