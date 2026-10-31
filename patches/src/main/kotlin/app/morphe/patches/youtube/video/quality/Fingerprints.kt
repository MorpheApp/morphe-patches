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
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import app.morphe.patcher.string
import app.morphe.patches.shared.CurrentAudioVideoFormatToStringFingerprint
import app.morphe.patches.youtube.video.information.VideoQualitySetterFingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

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

internal const val CLIENT_ABR_STATE_CLASS =
    "Lcom/google/android/apps/youtube/proto/ClientAbrStateOuterClass\$ClientAbrState;"

/**
 * Client ABR state of the SABR requests sent while the video plays.
 */
internal object ClientAbrStateFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = CLIENT_ABR_STATE_CLASS,
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = CLIENT_ABR_STATE_CLASS,
            type = "Lcom/google/android/apps/youtube/proto/streaming/MediaCapabilitiesOuterClass\$MediaCapabilities;"
        )
    )
)

/**
 * Quality last selected in the quality menu, kept by YouTube for the next videos.
 * The player uses it as a fixed quality, and it is sent with the SABR requests.
 * The direction is how the selected quality compares to the quality playing when it was selected.
 */
internal object LastSelectedVideoQualityConstructorFingerprint : Fingerprint(
    name = "<init>",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf("L", "I", "L"),
    filters = listOf(
        fieldAccess( // Time the quality was selected.
            opcode = Opcode.IPUT_WIDE,
            definingClass = "this",
            type = "J"
        ),
        fieldAccess( // Video ID the quality was selected for.
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;"
        ),
        string("last_playback_start_timestamp"),
        fieldAccess( // Time the last playback started.
            opcode = Opcode.IPUT_WIDE,
            definingClass = "this",
            type = "J"
        ),
        fieldAccess( // Resolution of the quality.
            opcode = Opcode.IPUT,
            definingClass = "this",
            type = "I"
        ),
        fieldAccess( // Direction of the quality change.
            opcode = Opcode.IPUT,
            definingClass = "this",
            type = "I"
        )
    )
)

/**
 * Chooses the qualities allowed by the player, when the video starts and again while it plays:
 * the quality last selected in the quality menu, or an automatic quality.
 */
internal fun getVideoQualityConstraintFingerprint(
    lastSelectedVideoQualityType: String,
    videoQualitySettingMethod: String
) = object : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Z", "L", "Ljava/lang/String;", "L"),
    filters = listOf(
        methodCall(returnType = lastSelectedVideoQualityType),
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

/**
 * If this flag is off, the qualities of the quality menu have no formats, and the player ignores
 * the formats of the quality last selected and finds the quality only by its resolution.
 */
internal object QualityFormatsFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45400072)
    )
)

/**
 * The method of the player that sets the video formats to play, and clears the buffered video
 * if the parameter is true.
 */
internal object PlaybackControllerSetVideoFormatsFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/libraries/youtube/media/player/exo2/platypus/PlaybackControllerWrapper;",
    accessFlags = listOf(AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf("Z"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Lcom/google/android/libraries/youtube/media/interfaces/PlaybackController;"
        ),
        opcode(Opcode.IF_NEZ, location = MatchAfterImmediately()),
        // The parameter, if the buffered video is cleared.
        opcode(Opcode.IF_EQZ, location = MatchAfterWithin(3)),
        opcode(Opcode.SGET_OBJECT, location = MatchAfterImmediately()) // Video track type.
    )
)

/**
 * The method of the async media fetch controller that sets the video formats to play.
 * If the flag is on, the method sets the formats itself instead of calling
 * [PlaybackControllerSetVideoFormatsFingerprint], and clears the buffered video
 * if the boolean parameter is true.
 */
internal object AsyncMediaFetchControllerSetVideoFormatsFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/libraries/youtube/media/player/exo2/platypus/AsyncMediaFetchControllerWrapper;",
    returnType = "V",
    parameters = listOf("L", "Z"),
    filters = listOf(
        literal(45779716)
    )
)

internal const val BUFFER_MANAGER_CLASS =
    "Lcom/google/android/libraries/youtube/media/player/exo2/platypus/mediapush/BufferManagerImpl;"

/**
 * The method of the player that clears the buffered media of a track, and buffers it again
 * from the time the video started or was last seeked to.
 */
internal object PlaybackControllerClearBufferFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/libraries/youtube/media/player/exo2/platypus/PlaybackControllerWrapper;",
    returnType = "V",
    filters = listOf(
        // Stops the media requests.
        methodCall(definingClass = "this", returnType = "V", parameters = listOf()),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = BUFFER_MANAGER_CLASS
        ),
        string("setStartTimeUs with disposed BufferManager")
    )
)

internal const val MEDIA_SEGMENT_HOLDER_CLASS =
    "Lcom/google/android/libraries/youtube/media/player/exo2/platypus/mediapush/MediaSegmentHolder;"

/**
 * The call to the method of the buffered media of a track that clears it after the segment
 * that contains a time. Before 21.44 it is called from a method of the buffer manager,
 * and 21.44+ inlines that method into the player.
 */
internal object MediaSegmentHolderDiscardBufferAfterSegmentFingerprint : Fingerprint(
    filters = listOf(
        string("discardBufferAfterCurrentSegment with disposed BufferManager"),
        methodCall(
            definingClass = MEDIA_SEGMENT_HOLDER_CLASS,
            returnType = "V",
            parameters = listOf("J")
        )
    )
)

internal const val SAMPLE_HOLDER_CLASS =
    "Lcom/google/android/libraries/youtube/media/player/exo2/platypus/mediapush/SampleHolder;"

/**
 * The method of the buffer manager that returns the buffered media of a track.
 */
internal object BufferManagerGetMediaSegmentHolderFingerprint : Fingerprint(
    definingClass = BUFFER_MANAGER_CLASS,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = MEDIA_SEGMENT_HOLDER_CLASS,
    parameters = listOf("L")
)

/**
 * 21.17 - 21.42
 *
 * The method of the buffered media of an audio or video track that clears
 * the media before the segment that contains a time.
 */
internal object SampleHolderDiscardBufferBeforeSegmentLegacyFingerprint : Fingerprint(
    definingClass = SAMPLE_HOLDER_CLASS,
    returnType = "V",
    parameters = listOf("J"),
    filters = listOf(
        methodCall(smali = "Ljava/lang/Math;->min(JJ)J"),
        methodCall(smali = "Ljava/util/List;->subList(II)Ljava/util/List;"),
        methodCall(smali = "Ljava/util/List;->clear()V")
    )
)

/**
 * 21.43+
 *
 * Same as [SampleHolderDiscardBufferBeforeSegmentLegacyFingerprint],
 * with the parameters: time, playback time and size of the media kept before the playback time.
 */
internal object SampleHolderDiscardBufferBeforeSegmentFingerprint : Fingerprint(
    definingClass = SAMPLE_HOLDER_CLASS,
    returnType = "V",
    parameters = listOf("J", "J", "J"),
    filters = listOf(
        methodCall(definingClass = "this", returnType = "Z", parameters = listOf("J", "J", "J")),
        methodCall(definingClass = "this", returnType = "V", parameters = listOf("J", "J", "J"))
    )
)

/**
 * The method of the buffered media of a track that saves the end time of its first segment.
 * The media before a time is cleared only if the time is after it.
 */
internal object MediaSegmentHolderSetFirstSegmentEndTimeFingerprint : Fingerprint(
    definingClass = MEDIA_SEGMENT_HOLDER_CLASS,
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(smali = "Ljava/util/List;->isEmpty()Z"),
        methodCall(
            definingClass = "Lcom/google/android/libraries/youtube/media/player/exo2/platypus/mediapush/BufferedMediaSegment;",
            returnType = "J",
            parameters = listOf()
        ),
        fieldAccess(opcode = Opcode.IPUT_WIDE, definingClass = "this", type = "J")
    )
)

/**
 * The method of the media source that clears the media buffered before the playback time,
 * called by the player while the video is loaded.
 */
internal object MediaPushMediaSourceDiscardBufferFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/libraries/youtube/media/player/exo2/platypus/mediapush/MediaPushMediaSource;",
    returnType = "V",
    parameters = listOf("J"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.SGET_WIDE,
            definingClass = "Lcom/google/android/libraries/youtube/media/player/exo2/platypus/live/LiveTimeline;"
        ),
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "this", type = BUFFER_MANAGER_CLASS),
        methodCall(smali = "Ljava/lang/Math;->max(JJ)J")
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

/**
 * The method that returns the initial video quality of the playback start parameters,
 * used by the player each time it loads the video, such as when the app returns from the background.
 */
internal fun getPlaybackStartParametersInitialResolutionFingerprint(
    initialResolutionField: FieldReference
) = object : Fingerprint(
    returnType = "Ljava/lang/Integer;",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            reference = initialResolutionField
        ),
        methodCall(smali = "Lj\$/util/Optional;->orElse(Ljava/lang/Object;)Ljava/lang/Object;"),
        checkCast("Ljava/lang/Integer;")
    )
) {}

/**
 * A method of the class of [getPlaybackStartParametersInitialResolutionFingerprint] that gets
 * the playback start descriptor of its player, which has the video the player loads.
 */
internal fun getPlayerPlaybackStartDescriptorFingerprint(
    playbackStartParametersGetterClass: String,
    playbackStartDescriptorClass: String
) = Fingerprint(
    definingClass = playbackStartParametersGetterClass,
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this"
        ),
        methodCall(
            returnType = playbackStartDescriptorClass,
            parameters = listOf(),
            location = MatchAfterImmediately()
        )
    )
)

/**
 * The method of [VideoQualitySetterFingerprint] that sets the qualities the quality menu shows.
 */
internal fun getVideoQualityMenuQualitiesFingerprint(
    videoQualityMenuClass: String,
    videoQualityArrayType: String
) = Fingerprint(
    classFingerprint = VideoQualitySetterFingerprint,
    returnType = "V",
    parameters = listOf("[L", "I", "Z"),
    strings = listOf("menu_item_video_quality"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = videoQualityMenuClass,
            type = videoQualityArrayType
        )
    )
)

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

/**
 * The method of the quality menu that sets the quality by its resolution.
 */
internal fun getSetVideoQualityResolutionFingerprint(videoQualityMenuType: String) = Fingerprint(
    definingClass = videoQualityMenuType,
    returnType = "V",
    parameters = listOf("I")
)

/**
 * The item click listener of the Shorts quality menu, which sets the selected quality by its resolution.
 * The listener is either the menu of [VideoQualityItemOnClickFingerprint] or a class it calls.
 */
internal fun getVideoQualityItemSetResolutionFingerprint(setResolutionMethod: MethodReference) = Fingerprint(
    name = "onItemClick",
    returnType = "V",
    parameters = listOf(
        "Landroid/widget/AdapterView;",
        "Landroid/view/View;",
        "I",
        "J"
    ),
    filters = listOf(
        methodCall(reference = setResolutionMethod)
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
