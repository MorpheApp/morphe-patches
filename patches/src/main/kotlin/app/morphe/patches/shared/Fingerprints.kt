/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object CastContextFetchFingerprint : Fingerprint(
    filters = listOf(
        string("Error fetching CastContext.")
    )
)

internal object PrimeMethodFingerprint : Fingerprint(
    filters = listOf(
        string("com.android.vending"),
        string("com.google.android.GoogleCamera")
    )
)

//
// YouTube / YT Music fingerprints
//

internal object GoogleApiActivityOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/gms/common/api/GoogleApiActivity;",
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;")
)

// Flag is present in YT 20.23, but bold icons are missing and forcing them crashes the app.
// 20.31 is the first target with all the bold icons present.
internal object BoldIconsFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45685201L)
    )
)

internal object BuildInnerTubeProtoRequestUriFingerprint : Fingerprint(
    parameters = listOf(),
    filters = listOf(
        string("key"),
        string("asig"),
        checkCast("Ljava/lang/String;"),
        methodCall($$"Landroid/net/Uri$Builder;->appendQueryParameter(Ljava/lang/String;Ljava/lang/String;)Landroid/net/Uri$Builder;"),
        anyInstruction(
            // YT 21.20, YTM 9.18
            methodCall($$"Landroid/net/Uri$Builder;->build()Landroid/net/Uri;"),
            // YT 21.21+, YTM 9.19+
            opcode(
                opcode = Opcode.RETURN_OBJECT,
                location = MatchAfterWithin(5)
            )
        )
    )
)

internal object CurrentAudioVideoFormatToStringFingerprint : Fingerprint(
    name = "toString",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    strings = listOf("currentVideoFormat=")
)

internal object FormatStreamModelToStringFingerprint : Fingerprint(
    name = "toString",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string(" isDefaultAudioTrack="),
        string(" audioTrackId="),
        string(" audioTrackDisplayName="),
        string(" width="),
        string(" height="),
        // formatStreamModelFormatField.
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "L"
        ),
        string("FormatStream(itag="),
        string(" mimeType=")
    )
)

internal object MediaSessionSetPlaybackStateFingerprint : Fingerprint(
    filters = listOf(
        methodCall(
            definingClass = "Landroid/media/session/MediaSession;",
            name = "setPlaybackState",
            parameters = listOf("Landroid/media/session/PlaybackState;")
        )
    )
)

internal object SpannableStringBuilderFingerprint : Fingerprint(
    returnType = "Ljava/lang/CharSequence;",
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            smali = "Landroid/text/SpannableString;->valueOf(Ljava/lang/CharSequence;)Landroid/text/SpannableString;"
        ),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            returnType = "V",
            parameters = listOf(
                "Landroid/text/SpannableString;",
                "Ljava/lang/Object;",
                "I",
                "Z",
                "I"
            )
        ),
        string(
            "Failed to set PB Style Run Extension in TextComponentSpec.",
            comparison = StringComparisonType.STARTS_WITH
        )
    )
)
