/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3566
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.InstructionLocation.MatchFirst
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private object SubtitleManagerFingerprintClassFingerprint : Fingerprint(
    isStatic = true,
    returnType = "Z",
    parameters = listOf("L", "Landroid/view/accessibility/CaptioningManager;"),
    filters = listOf(
        fieldAccess("Ljava/util/concurrent/TimeUnit;->SECONDS:Ljava/util/concurrent/TimeUnit;"),
        methodCall("Landroid/view/accessibility/CaptioningManager;->isEnabled()Z"),
    )
)

internal object SubtitleManagerFingerprint : Fingerprint(
    classFingerprint = SubtitleManagerFingerprintClassFingerprint,
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

/**
 * YouTube 20.26+
 */
internal object NoVolumeCaptionsFeatureFlagFingerprint : Fingerprint(
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
            smali = $$"Lorg/chromium/net/CronetEngine;->newUrlRequestBuilder(Ljava/lang/String;Lorg/chromium/net/UrlRequest$Callback;Ljava/util/concurrent/Executor;)Lorg/chromium/net/UrlRequest$Builder;"
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        methodCall(
            smali = "Lorg/chromium/net/UploadDataProviders;->create(Ljava/nio/ByteBuffer;)Lorg/chromium/net/UploadDataProvider;"
        )
    )
)

/**
 * Timed text (srv3) `<s>` segment element handler. Resolves to the pen lookup method.
 */
internal object TimedTextSegmentElementFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/util/Deque;", "Lorg/xml/sax/Attributes;", "Ljava/lang/String;"),
    filters = listOf(
        string("p"),
        methodCall(
            smali = "Lorg/xml/sax/Attributes;->getValue(Ljava/lang/String;)Ljava/lang/String;",
            location = MatchAfterImmediately()
        ),
        // Pen lookup by id.
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf("I"),
            returnType = "L",
            location = MatchAfterWithin(5)
        ),
        string("t"),
        string("ac"),
        string("<br/>")
    )
)

/**
 * Parses a cached caption track file.
 */
internal object CaptionFileParserFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;", "L"),
    filters = listOf(
        // Caption request.
        opcode(Opcode.CHECK_CAST, location = MatchFirst()),
        // Caption track of the request.
        fieldAccess(opcode = Opcode.IGET_OBJECT, location = MatchAfterWithin(2)),
        methodCall(smali = "Ljava/io/File;->exists()Z"),
        methodCall(smali = "Ljava/io/FileInputStream;-><init>(Ljava/io/File;)V"),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf("Ljava/io/InputStream;", "L"),
            returnType = "Ljava/lang/Object;"
        )
    )
)

/**
 * Parses the response of a request. Used for caption tracks and other requests.
 */
internal object RequestResponseParserFingerprint : Fingerprint(
    name = "run",
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        // Response parser.
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = listOf("Ljava/lang/Object;"),
            returnType = "Ljava/lang/Object;"
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        // Request.
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/Object;",
            location = MatchAfterWithin(1)
        ),
        // Response callback.
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
            returnType = "V",
            location = MatchAfterImmediately()
        )
    )
)

/**
 * Resolves to the caption track class. 21.02+
 */
internal object CaptionTrackIsDisableOptionFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        string("DISABLE_CAPTIONS_OPTION"),
        // Language code.
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;",
            location = MatchAfterImmediately()
        ),
        methodCall(
            smali = "Ljava/lang/String;->equals(Ljava/lang/Object;)Z",
            location = MatchAfterImmediately()
        )
    )
)

/**
 * Resolves to the caption track class. Before 21.02 the class is abstract and uses getters.
 */
internal object CaptionTrackIsDisableOptionLegacyFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        string("DISABLE_CAPTIONS_OPTION"),
        // Language code.
        methodCall(
            definingClass = "this",
            parameters = listOf(),
            returnType = "Ljava/lang/String;",
            location = MatchAfterImmediately()
        ),
        methodCall(
            smali = "Ljava/lang/String;->equals(Ljava/lang/Object;)Z",
            location = MatchAfterWithin(1)
        )
    )
)

/**
 * 21.02+
 */
internal object CaptionTrackIsAutoTranslatedFingerprint : Fingerprint(
    classFingerprint = CaptionTrackIsDisableOptionFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        // Vss id.
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;",
            location = InstructionLocation.MatchAfterAnywhere()
        ),
        string("t", location = MatchAfterImmediately()),
        methodCall(
            smali = "Ljava/lang/String;->startsWith(Ljava/lang/String;)Z",
            location = MatchAfterImmediately()
        )
    )
)

/**
 * Before 21.02.
 */
internal object CaptionTrackIsAutoTranslatedLegacyFingerprint : Fingerprint(
    classFingerprint = CaptionTrackIsDisableOptionLegacyFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        // Vss id.
        methodCall(
            definingClass = "this",
            parameters = listOf(),
            returnType = "Ljava/lang/String;",
            location = InstructionLocation.MatchAfterAnywhere()
        ),
        string("t", location = MatchAfterWithin(1)),
        methodCall(
            smali = "Ljava/lang/String;->startsWith(Ljava/lang/String;)Z",
            location = MatchAfterImmediately()
        )
    )
)

/**
 * Resolves to the caption tracks manager class.
 */
internal object CaptionTracksManagerDirectTracksFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/util/List;",
    parameters = listOf(),
    filters = listOf(
        string("AUTO_TRANSLATE_CAPTIONS_OPTION")
    )
)

internal object CaptionTracksManagerAutoTranslateTracksFingerprint : Fingerprint(
    classFingerprint = CaptionTracksManagerDirectTracksFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/util/List;",
    parameters = listOf(),
    filters = listOf(
        // Url of the source caption track.
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Ljava/lang/String;"
        ),
        methodCall(
            smali = "Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;",
            location = MatchAfterImmediately()
        ),
        string("&tlang=", location = MatchAfterImmediately())
    )
)

/** The track manager's default source, without subtitle preference or CC state overrides. */
internal class CaptionTracksManagerDefaultSourceFingerprint(captionTrackType: String) : Fingerprint(
    classFingerprint = CaptionTracksManagerDirectTracksFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = captionTrackType,
    parameters = listOf(),
)

/**
 * Auto-translate tracks method, matched at the general translation source fallback that is used
 * if a translation language has no source of its own.
 */
internal object CaptionTracksManagerTranslationSourceFingerprint : Fingerprint(
    classFingerprint = CaptionTracksManagerDirectTracksFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/util/List;",
    parameters = listOf(),
    filters = listOf(
        // Caption metadata.
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "this"),
        // Current audio track.
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "this"),
        opcode(Opcode.IF_EQZ, location = MatchAfterImmediately()),
        // Source of the translation language.
        methodCall(opcode = Opcode.INVOKE_DIRECT, definingClass = "this", parameters = listOf("L")),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        // Indices of the general translation sources.
        fieldAccess(opcode = Opcode.IGET_OBJECT, location = MatchAfterWithin(1)),
        methodCall(smali = "Ljava/util/List;->iterator()Ljava/util/Iterator;", location = MatchAfterImmediately()),
        methodCall(smali = "Ljava/lang/Integer;->intValue()I", location = MatchAfterWithin(7)),
        // Caption track indices of the current audio track.
        fieldAccess(opcode = Opcode.IGET_OBJECT, location = MatchAfterWithin(1)),
        methodCall(smali = "Ljava/util/List;->contains(Ljava/lang/Object;)Z", location = MatchAfterImmediately()),
        // Caption tracks.
        fieldAccess(opcode = Opcode.IGET_OBJECT, location = MatchAfterWithin(2)),
        methodCall(name = "get", parameters = listOf("I"), returnType = "Ljava/lang/Object;",
            location = MatchAfterImmediately()),
        // Converts the caption track metadata to a caption track.
        methodCall(opcode = Opcode.INVOKE_DIRECT, definingClass = "this", parameters = listOf("L"),
            location = MatchAfterWithin(2))
    )
)

/**
 * Calls of the caption tracks manager auto-translate getter inside the subtitle manager.
 */
internal class AutoTranslateTracksCallFingerprint(autoTranslateTracksMethod: MethodReference) : Fingerprint(
    classFingerprint = DefaultCaptionTrackFingerprint,
    filters = listOf(
        methodCall(reference = autoTranslateTracksMethod)
    )
)

/**
 * Resolves to the caption track builder class.
 */
internal object CaptionTrackBuilderBuildFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf(),
    filters = listOf(
        // Display name.
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/CharSequence;"
        ),
        string(" languageCode"),
        string(" vssId"),
        string(" url")
    )
)

internal object CaptionTrackBuilderCopyConstructorFingerprint : Fingerprint(
    classFingerprint = CaptionTrackBuilderBuildFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf("L"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/CharSequence;"
        )
    )
)

/**
 * @param nullValueMessage Exception message of the setter, such as "Null url".
 */
internal class CaptionTrackBuilderSetterFingerprint(nullValueMessage: String) : Fingerprint(
    classFingerprint = CaptionTrackBuilderBuildFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        string(nullValueMessage)
    )
)

/**
 * Subtitle manager method that returns the direct caption tracks.
 *
 * @param directTracksMethod Caption tracks manager method that returns the direct tracks.
 */
internal class SubtitleManagerDirectTracksFingerprint(directTracksMethod: MethodReference) : Fingerprint(
    classFingerprint = SubtitleManagerFingerprintClassFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/util/List;",
    parameters = listOf(),
    filters = listOf(
        // Caption tracks manager.
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = directTracksMethod.definingClass
        ),
        opcode(Opcode.IF_EQZ, location = MatchAfterImmediately()),
        methodCall(reference = directTracksMethod, location = MatchAfterImmediately())
    )
)

internal object DefaultCaptionTrackFingerprint : Fingerprint(
    classFingerprint = SubtitleManagerFingerprintClassFingerprint,
    returnType = "L",
    parameters = listOf(),
    filters = listOf(
        methodCall("Landroid/view/accessibility/CaptioningManager;->isEnabled()Z"),
    )
)

context(patchContext: BytecodePatchContext)
internal fun preferredCaptionsTrackFingerprint() = Fingerprint(
    classFingerprint = DefaultCaptionTrackFingerprint,
    // 21.23+ also has a method without parameters that calls the default track method.
    parameters = listOf("L", "L"),
    filters = listOf(
        methodCall(reference = DefaultCaptionTrackFingerprint.method),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
    )
)

internal object SetSubtitleTrackFingerprint : Fingerprint(
    classFingerprint = SubtitleManagerFingerprintClassFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        // 21.40+ removed the "setSubtitleTrack name:%s languageCode:%s ..." log string,
        // but the Throwable used for the log statement remains.
        newInstance("Ljava/lang/Throwable;")
    )
)

/** The final translation-count check before YouTube builds its own Auto-translate row. */
internal object CaptionTracksManagerTranslationCountFingerprint : Fingerprint(
    classFingerprint = CaptionTracksManagerDirectTracksFingerprint,
    parameters = listOf(),
    returnType = "Ljava/util/List;",
    filters = listOf(
        methodCall(name = "size", parameters = listOf(), returnType = "I"),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        opcode(Opcode.IF_LEZ, location = MatchAfterImmediately()),
        fieldAccess(opcode = Opcode.IGET_BOOLEAN, definingClass = "this", type = "Z",
            location = MatchAfterImmediately()),
        opcode(Opcode.IF_NEZ, location = MatchAfterImmediately()),
        string("AUTO_TRANSLATE_CAPTIONS_OPTION", location = InstructionLocation.MatchAfterAnywhere()),
        // Register holding the returned tracks list.
        opcode(Opcode.RETURN_OBJECT, location = InstructionLocation.MatchAfterAnywhere())
    )
)

/**
 * Player state given to each player overlay to decide if it is shown.
 * Its fields are listed in toString(), such as "watchDynamicLayoutState;isVrActive;isInlineActive;isPipActive".
 */
internal object PlayerOverlayStateToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("isPipActive", StringComparisonType.CONTAINS)
    )
)

/**
 * Creates the player state and asks an overlay if it is shown. More than one overlay layout does this.
 */
internal fun getPlayerOverlayVisibilityFingerprint(stateType: String) = object : Fingerprint(
    filters = listOf(
        methodCall(definingClass = stateType, name = "<init>"),
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = listOf(stateType),
            returnType = "Z",
            location = MatchAfterImmediately()
        )
    )
) {}

/**
 * Asks an overlay if it is shown for the player type, which can be picture-in-picture.
 * Used instead of the player state by older versions.
 */
internal fun getPlayerTypeOverlayVisibilityFingerprint(playerType: String) = object : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/app/common/player/overlay/YouTubePlayerOverlaysLayout;",
    returnType = "Z",
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = listOf(playerType),
            returnType = "Z"
        ),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately())
    )
) {}

/**
 * Name of the captions overlay.
 */
internal object CaptionsOverlayNameFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        string("player_overlay_caption")
    )
)

/**
 * Sets the caption track of a caption track event.
 */
internal fun getCaptionTrackEventFingerprint(trackType: String) = object : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = trackType
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            definingClass = "this",
            parameters = listOf(trackType),
            returnType = "V",
            location = MatchAfterImmediately()
        )
    )
) {}

/**
 * Cue range manager seek. Its class also adds and removes cue ranges.
 */
internal object CueRangeManagerSeekFingerprint : Fingerprint(
    returnType = "J",
    parameters = listOf("J", "J", "Z"),
    filters = listOf(
        string("CueRangeManager", StringComparisonType.CONTAINS)
    )
)

/**
 * Server flag to start cue ranges that are added while the video time is inside them.
 */
internal object EnterCueRangeOnAddFeatureFlagFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        literal(45415907L)
    )
)

/**
 * Adds a list of cue ranges. Matches at the cast of each cue range and the flag check.
 */
internal fun getAddCueRangesFingerprint(enterCueRangeOnAddFlagMethod: MethodReference) = object : Fingerprint(
    classFingerprint = CueRangeManagerSeekFingerprint,
    returnType = "V",
    parameters = listOf("Ljava/util/List;"),
    filters = listOf(
        methodCall(smali = "Ljava/util/Iterator;->next()Ljava/lang/Object;"),
        opcode(Opcode.CHECK_CAST, MatchAfterWithin(2)),
        methodCall(reference = enterCueRangeOnAddFlagMethod),
        opcode(Opcode.MOVE_RESULT, MatchAfterImmediately())
    )
) {}

/**
 * Caption cue, a cue range with the caption windows it shows when it starts.
 */
internal fun getCaptionCueConstructorFingerprint(cueRangeType: String) = object : Fingerprint(
    superclass = cueRangeType,
    name = "<init>",
    returnType = "V",
    parameters = listOf("J", "J", "Ljava/util/List;", "L"),
    filters = listOf(
        methodCall(
            definingClass = cueRangeType,
            name = "<init>"
        )
    )
) {}

/**
 * Captions of a fully loaded caption track.
 * Matches at the field of the sorted times the captions change at.
 */
internal object CaptionWindowsConstructorFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    parameters = listOf("Ljava/util/List;"),
    filters = listOf(
        newInstance("Ljava/util/TreeSet;"),
        methodCall(smali = "Ljava/util/TreeSet;->addAll(Ljava/util/Collection;)Z"),
        newInstance("Ljava/util/ArrayList;"),
        methodCall(smali = "Lj$/util/DesugarCollections;->unmodifiableList(Ljava/util/List;)Ljava/util/List;"),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Ljava/util/List;",
            location = MatchAfterImmediately()
        )
    )
)

/**
 * Caption chunk loader. Matches at the field of the captions it loads from.
 */
internal fun getCaptionChunkLoaderConstructorFingerprint(captionWindowsType: String) = object : Fingerprint(
    name = "<init>",
    returnType = "V",
    filters = listOf(
        newInstance("Ljava/util/TreeMap;"),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = captionWindowsType
        )
    )
) {}

/**
 * Loads the caption cues that start in the chunk of a video time.
 */
internal fun getCaptionChunkLoaderFingerprint(loaderType: String) = object : Fingerprint(
    definingClass = loaderType,
    returnType = "V",
    parameters = listOf("J"),
    filters = listOf(
        methodCall(smali = "Ljava/util/concurrent/Executor;->execute(Ljava/lang/Runnable;)V")
    )
) {}

internal const val SUBTITLE_WINDOW_VIEW_CLASS =
    "Lcom/google/android/libraries/youtube/player/subtitles/ui/SubtitleWindowView;"

/**
 * Caption cue of a subtitle window. Matches the id, time, text, full text and settings fields.
 */
internal object CaptionCueToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET, definingClass = "this", type = "I"),
        fieldAccess(opcode = Opcode.IGET_WIDE, definingClass = "this", type = "J"),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/CharSequence;"
        ),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/CharSequence;",
            location = MatchAfterImmediately()
        ),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            location = MatchAfterImmediately()
        ),
        string("id: %d time: %d text: %s fullText: %s settings: %s")
    )
)

internal object CaptionCueConstructorFingerprint : Fingerprint(
    classFingerprint = CaptionCueToStringFingerprint,
    name = "<init>",
    returnType = "V",
    parameters = listOf("I", "J", "Ljava/lang/CharSequence;", "Ljava/lang/CharSequence;", "L")
)

/**
 * Matches at the default caption window settings.
 */
internal fun getCaptionDefaultSettingsFingerprint(settingsType: String) = object : Fingerprint(
    definingClass = settingsType,
    name = "<clinit>",
    returnType = "V",
    filters = listOf(
        fieldAccess(opcode = Opcode.SPUT_OBJECT, definingClass = "this", type = settingsType)
    )
) {}

/**
 * Returns the caption cues shown at a video time.
 */
internal fun getCaptionSamplerFingerprint(cueType: String) = object : Fingerprint(
    classFingerprint = CaptionWindowsConstructorFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/util/List;",
    parameters = listOf("J"),
    filters = listOf(
        newInstance(cueType),
        methodCall(definingClass = cueType, name = "<init>")
    )
) {}

/**
 * Sets the cue of a subtitle window and splits its text into lines.
 */
internal fun getCaptionWindowSetterFingerprint(cueType: String) = object : Fingerprint(
    definingClass = SUBTITLE_WINDOW_VIEW_CLASS,
    returnType = "V",
    parameters = listOf(cueType),
    filters = listOf(
        fieldAccess(opcode = Opcode.IPUT_OBJECT, definingClass = "this", type = cueType),
        // Visible text lines.
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/util/List;",
            location = MatchAfterImmediately()
        ),
        methodCall(smali = "Ljava/util/List;->clear()V", location = MatchAfterImmediately()),
        // Full text lines.
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/util/List;",
            location = MatchAfterImmediately()
        ),
        methodCall(smali = "Ljava/util/List;->clear()V", location = MatchAfterImmediately()),
        // Split the visible text, then the full text.
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("Ljava/util/List;", "Ljava/lang/CharSequence;"),
            returnType = "V"
        ),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("Ljava/util/List;", "Ljava/lang/CharSequence;"),
            returnType = "V",
            location = MatchAfterWithin(2)
        )
    )
) {}

/**
 * Subtitle window constructor. Matches at the row used to measure caption text.
 */
internal fun getCaptionWindowConstructorFingerprint(rowType: String) = object : Fingerprint(
    definingClass = SUBTITLE_WINDOW_VIEW_CLASS,
    name = "<init>",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(
        fieldAccess(opcode = Opcode.IPUT_OBJECT, definingClass = "this", type = rowType)
    )
) {}

/**
 * The height measure spec branch incorrectly checks the width measure mode.
 */
internal object CaptionWindowMeasureFingerprint : Fingerprint(
    definingClass = SUBTITLE_WINDOW_VIEW_CLASS,
    name = "onMeasure",
    returnType = "V",
    parameters = listOf("I", "I"),
    filters = listOf(
        methodCall(smali = $$"Landroid/view/View$MeasureSpec;->getMode(I)I"),
        opcode(Opcode.MOVE_RESULT, MatchAfterImmediately()),
        literal(0x40000000, location = MatchAfterImmediately()),
        methodCall(smali = $$"Landroid/view/View$MeasureSpec;->getMode(I)I"),
        opcode(Opcode.MOVE_RESULT, MatchAfterImmediately()),
        // Height mode is AT_MOST.
        opcode(Opcode.IF_NE, MatchAfterImmediately()),
        // Width mode is EXACTLY.
        anyInstruction(
            opcode(Opcode.IF_EQ),
            opcode(Opcode.IF_NE),
            location = MatchAfterWithin(4)
        )
    )
)

/**
 * Text layout of a caption row.
 */
internal object CaptionRowLayoutFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/text/StaticLayout;",
    parameters = listOf(),
    superclass = "Landroid/view/View;",
    filters = listOf(
        string("Edge type %d is not supported.")
    )
)

internal object CaptionRowSetTextFingerprint : Fingerprint(
    classFingerprint = CaptionRowLayoutFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Ljava/lang/CharSequence;")
)

internal const val CAPTION_STATIC_LAYOUT_PARAMETERS =
    $$"Ljava/lang/CharSequence;Landroid/text/TextPaint;ILandroid/text/Layout$Alignment;FFZ"

/**
 * Creates the fill and the outline text layouts of a caption row.
 */
internal object CaptionRowLayoutFactoryFingerprint : Fingerprint(
    classFingerprint = CaptionRowLayoutFingerprint,
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "Landroid/text/StaticLayout;",
    parameters = listOf("I"),
    filters = listOf(
        newInstance("Landroid/text/StaticLayout;"),
        methodCall(
            smali = "Landroid/text/StaticLayout;-><init>(${CAPTION_STATIC_LAYOUT_PARAMETERS})V"
        )
    )
)

/**
 * Shows the caption cues of the subtitle overlay.
 */
internal fun getCaptionOverlayPresenterFingerprint(cueType: String) = object : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf("Ljava/util/List;"),
    filters = listOf(
        methodCall(
            definingClass = SUBTITLE_WINDOW_VIEW_CLASS,
            parameters = listOf(cueType),
            returnType = "V"
        )
    )
) {}

/**
 * Caption font size, calculated from the size of the subtitle overlay.
 */
internal object CaptionFontSizeFingerprint : Fingerprint(
    returnType = "F",
    parameters = listOf("F", "I", "I"),
    filters = listOf(
        methodCall(smali = "Ljava/lang/Math;->min(II)I"),
        fieldAccess(smali = "Landroid/util/DisplayMetrics;->scaledDensity:F"),
        literal(0.0625f),
        literal(13.0f)
    )
)
