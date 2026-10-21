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
