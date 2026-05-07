@file:Suppress("SpellCheckingInspection")

package app.morphe.patches.youtube.video.information

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.patches.youtube.shared.PlaybackSpeedOnItemClickParentFingerprint
import app.morphe.patches.youtube.shared.VideoQualityChangedFingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object PlaybackSpeedOnItemClickFingerprint : Fingerprint(
    classFingerprint = PlaybackSpeedOnItemClickParentFingerprint,
    name = "onItemClick",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "L", "I", "J")
)

internal object PlayerControllerSetTimeReferenceFingerprint : Fingerprint(
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.INVOKE_DIRECT_RANGE, Opcode.IGET_OBJECT
    ),
    strings = listOf("Media progress reported outside media playback: ")
)

internal object PlayerInitFingerprint : Fingerprint(
    filters = listOf(
        string("playVideo called on player response with no videoStreamingData."),
    )
)

internal object ChannelInformationFingerprint : Fingerprint(
    classFingerprint = PlayerInitFingerprint,
    filters = listOf(
        string("loadVideo() called on LocalDirector in wrong state"),
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

internal object SeekFingerprint : Fingerprint(
    classFingerprint = PlayerInitFingerprint,
    filters = listOf(
        anyInstruction(
            // 20.xx
            string("Attempting to seek during an ad"),
            // 21.02+
            string("currentPositionMs.")
        )
    )
)

private object CreateVideoPlayerSeekbarFingerprint : Fingerprint(
    name = "onDraw",
    returnType = "V",
    filters = listOf(
        string("timed_markers_width")
    )
)

internal object VideoLengthFingerprint : Fingerprint(
    classFingerprint = CreateVideoPlayerSeekbarFingerprint,
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall("Landroid/graphics/Rect;->set(Landroid/graphics/Rect;)V"),

        methodCall(returnType = "J"),
        methodCall(returnType = "J", location = MatchAfterWithin(5)),
        methodCall(returnType = "J", location = MatchAfterWithin(10)),
        methodCall(returnType = "J", location = MatchAfterWithin(10)),

        methodCall(returnType = "Z", parameters = listOf()),
        opcode(Opcode.CMP_LONG, location = MatchAfterWithin(8))
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

internal object SeekRelativeFingerprint : Fingerprint(
    classFingerprint = PlayerInitFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    // Return type is boolean up to 19.39, and void with 19.39+.
    parameters = listOf("J", "L"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.ADD_LONG_2ADDR,
        Opcode.INVOKE_VIRTUAL,
    )
)

internal object GetVideoTimeFingerprint : Fingerprint(
    classFingerprint = PlayerInitFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    returnType = "V",
    filters = listOf(
        methodCall(  // getVideoTime()
            definingClass = "this",
            returnType = "J",
            parameters = listOf(),
        ),
        literal(69, location = MatchAfterWithin(5))
    )
)

internal object PlaybackSpeedMenuSpeedChangedFingerprint : Fingerprint(
    classFingerprint = VideoQualityChangedFingerprint,
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

internal object PlaybackStartDescriptorToStringFingerprint : Fingerprint(
    name = "toString",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    filters = listOf(
        methodCall(smali = "Ljava/util/Locale;->getDefault()Ljava/util/Locale;"),
        // First method call after Locale is the video ID.
        methodCall(returnType = "Ljava/lang/String;", parameters = listOf()),
        string("PlaybackStartDescriptor:", comparison = StringComparisonType.STARTS_WITH)
    )
)

// Class name is un-obfuscated in targets before 21.01
internal object VideoQualityFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(
        "I", // Resolution.
        "L",
        "Ljava/lang/String;", // Human readable resolution: "480p", "1080p Premium", etc
        "Z",
        "L"
    )
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
    classFingerprint = VideoQualitySetterFingerprint,
    returnType = "V",
    parameters = listOf("L"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.IGET_OBJECT,
        Opcode.IPUT_OBJECT,
        Opcode.IGET_OBJECT,
    )
)
