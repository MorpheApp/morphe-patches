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
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private object SubtitleManagerFingerprintClassFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("L", "Landroid/view/accessibility/CaptioningManager;"),
    filters = listOf(
        fieldAccess("Ljava/util/concurrent/TimeUnit;->SECONDS:Ljava/util/concurrent/TimeUnit;"),
        methodCall("Landroid/view/accessibility/CaptioningManager;->isEnabled()Z"),
    ),
    custom = { method, _ ->
        AccessFlags.STATIC.isSet(method.accessFlags)
    }
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
