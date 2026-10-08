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
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

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
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = $$"Lorg/chromium/net/CronetEngine;->newUrlRequestBuilder(Ljava/lang/String;Lorg/chromium/net/UrlRequest$Callback;Ljava/util/concurrent/Executor;)Lorg/chromium/net/UrlRequest$Builder;",
        ),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            smali = "Lorg/chromium/net/UploadDataProviders;->create(Ljava/nio/ByteBuffer;)Lorg/chromium/net/UploadDataProvider;",
        )
    )
)

/**
 * String field of the enclosing class, or (older app targets) a call to an abstract String getter.
 */
private fun stringFieldOrGetter(
    location: InstructionLocation = InstructionLocation.MatchAfterAnywhere()
) = anyInstruction(
    fieldAccess(
        opcode = Opcode.IGET_OBJECT,
        definingClass = "this",
        type = "Ljava/lang/String;"
    ),
    methodCall(
        definingClass = "this",
        parameters = listOf(),
        returnType = "Ljava/lang/String;"
    ),
    location = location
)

/**
 * Resolves to the caption track class.
 */
internal object CaptionTrackIsDisableOptionFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        string("DISABLE_CAPTIONS_OPTION"),
        // Language code.
        stringFieldOrGetter(location = MatchAfterImmediately()),
        methodCall(
            smali = "Ljava/lang/String;->equals(Ljava/lang/Object;)Z",
            location = MatchAfterWithin(1)
        )
    )
)

internal object CaptionTrackIsAutoTranslatedFingerprint : Fingerprint(
    classFingerprint = CaptionTrackIsDisableOptionFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        // Vss id.
        stringFieldOrGetter(),
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
        string("&tlang=")
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

