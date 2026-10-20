/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.video.quality

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import app.morphe.patches.shared.CurrentAudioVideoFormatToStringFingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal const val FIXED_RESOLUTION_STRING = ", initialPlaybackVideoQualityFixedResolution="

internal object NewFlyoutMenuFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45712556)
    )
)

/**
 * If this flag is off, the initial video quality of the playback start parameters is ignored
 * and YouTube automatically chooses the quality when the video starts.
 */
internal object InitialVideoQualityFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45408049L)
    )
)

/**
 * If this flag is off, the minimum and maximum quality allowed by the player
 * are not sent with the SABR requests. Not present in 20.21.
 */
internal object SabrQualityConstraintFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45738253L)
    )
)

/**
 * 'Video quality preferences' setting of YouTube settings
 * (Auto, Higher picture quality, Data saver or Advanced).
 */
internal object VideoQualitySettingEnumFingerprint : Fingerprint(
    name = "<clinit>",
    filters = listOf(
        string("VIDEO_QUALITY_SETTING_ADVANCED_MENU"),
        fieldAccess( // VIDEO_QUALITY_SETTING_ADVANCED_MENU enum value.
            opcode = Opcode.SPUT_OBJECT,
            definingClass = "this",
            location = MatchAfterWithin(5)
        )
    )
)

/**
 * Video quality setting used for a video ID, sent with the SABR requests.
 */
internal fun getVideoQualitySettingFingerprint(
    videoQualitySettingType: String
) = object : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = videoQualitySettingType,
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            definingClass = "Ljava/util/Map;",
            name = "get"
        ),
        checkCast(videoQualitySettingType)
    )
) {}

/**
 * Chooses the qualities allowed by the player, when the video starts and again while it plays:
 * the quality last selected in the quality menu, or an automatic quality.
 */
internal fun getVideoQualityConstraintFingerprint(
    videoQualitySettingMethod: String
) = object : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Z", "L", "Ljava/lang/String;", "L"),
    filters = listOf(
        methodCall(smali = videoQualitySettingMethod)
    )
) {}

internal object ShortsQualityChangeObserverPrimaryFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45387052)
    )
)

internal object ShortsQualityChangeObserverSecondaryFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45399743)
    )
)

internal fun getCurrentVideoFormatConstructorFingerprint(
    videoQualityArray: String
) = object : Fingerprint(
    classFingerprint = CurrentAudioVideoFormatToStringFingerprint,
    name = "<init>",
    returnType = "V",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = videoQualityArray
        )
    )
) {}

internal object DefaultOverflowOverlayOnClickFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/libraries/youtube/player/features/overlay/overflow/ui/DefaultOverflowOverlay;",
    name = "onClick",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    filters = listOf(
        opcode(Opcode.IF_NE),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            location = MatchAfterWithin(2)
        ),
    )
)

internal object HidePremiumVideoQualityGetArrayFingerprint : Fingerprint(
    // Cannot use patch declaration of class because this is starts_with matching of the synthetic method.
    definingClass = "Lapp/morphe/extension/youtube/patches/playback/quality/HidePremiumVideoQualityPatch",
    name = "apply",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/Object;",
    parameters = listOf("I"),
    custom = { _, classDef ->
        AccessFlags.SYNTHETIC.isSet(classDef.accessFlags)
    }
)

internal object PlaybackStartParametersToStringFingerprint : Fingerprint(
    name = "toString",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        string(FIXED_RESOLUTION_STRING)
    )
)

internal fun getPlaybackStartParametersConstructorFingerprint(
    initialResolutionField: FieldReference
) = object : Fingerprint(
    classFingerprint = PlaybackStartParametersToStringFingerprint,
    name = "<init>",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            reference = initialResolutionField
        )
    )
) {}

private object VideoQualityItemOnClickParentFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("VIDEO_QUALITIES_MENU_BOTTOM_SHEET_FRAGMENT")
    )
)

internal object VideoQualityItemOnClickFingerprint : Fingerprint(
    classFingerprint = VideoQualityItemOnClickParentFingerprint,
    name = "onItemClick",
    returnType = "V",
    parameters = listOf(
        "Landroid/widget/AdapterView;",
        "Landroid/view/View;",
        "I",
        "J"
    )
)

internal object ShowVideoQualityQuickMenuFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    strings = listOf("VIDEO_QUALITIES_QUICK_MENU_BOTTOM_SHEET_FRAGMENT"),
    filters = listOf(
        opcode(Opcode.MOVE_RESULT),
        opcode(
            opcode = Opcode.IF_NEZ,
            location = MatchAfterWithin(3)
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "getSupportFragmentManager",
            location = MatchAfterWithin(3)
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf("L", "Ljava/lang/String;"),
            returnType = "V",
            location = MatchAfterWithin(5)
        )
    )
)

internal object ShortsQualityMenuFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Z"),
    returnType = "V",
    filters = listOf(
        resourceLiteral(
            type = ResourceType.STRING,
            name = "video_quality_unavailable_announcement"
        )
    )
)

internal object ShortsQualityConstructorFingerprint : Fingerprint(
    classFingerprint = ShortsQualityMenuFingerprint,
    name = "<init>",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this"
        )
    )
)
