/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared.misc.audio.tracks

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.resourceLiteral
import app.morphe.patches.shared.CurrentAudioVideoFormatToStringFingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * YT 21.26+
 * YTM 9.26+
 */
internal object AudioTrackRecordToStringFingerprint : Fingerprint(
    name = "toString",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    filters = listOf(
        // id
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;"
        ),
        // displayName
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;",
            location = MatchAfterWithin(5)
        ),
        // isAutoDubbed
        fieldAccess(
            opcode = Opcode.IGET_BOOLEAN,
            definingClass = "this",
            type = "Z",
            location = MatchAfterWithin(5)
        ),
        // isDefault
        fieldAccess(
            opcode = Opcode.IGET_BOOLEAN,
            definingClass = "this",
            type = "Z",
            location = MatchAfterWithin(5)
        ),
        string("id;displayName;isAutoDubbed;isDefault")
    )
)

/**
 * YT 21.26+
 * YTM 9.26+
 */
private object AudioTrackItemOnClickParentFingerprint : Fingerprint(
    parameters = listOf(),
    returnType = "Ljava/lang/String;",
    filters = listOf(
        resourceLiteral(ResourceType.STRING, "audio_tracks_title"),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = "Landroid/content/res/Resources;->getString(I)Ljava/lang/String;",
            location = MatchAfterWithin(3)
        )
    )
)

/**
 * YT 21.26+
 * YTM 9.26+
 */
internal fun getAudioTrackItemOnClickFingerprint(
    audioTrackRecordClass: String
) = object : Fingerprint(
    classFingerprint = AudioTrackItemOnClickParentFingerprint,
    name = "onItemClick",
    returnType = "V",
    filters = listOf(
        // id
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = audioTrackRecordClass,
            type = "Ljava/lang/String;"
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf("Ljava/lang/String;"),
            location = MatchAfterWithin(3)
        )
    )
) {}

/**
 * YT 21.26+
 * YTM 9.26+
 */
internal fun getCurrentAudioFormatConstructorFingerprint(
    audioTrackRecordClass: String
) = object : Fingerprint(
    classFingerprint = CurrentAudioVideoFormatToStringFingerprint,
    name = "<init>",
    returnType = "V",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "[$audioTrackRecordClass"
        )
    )
) {}

/**
 * YT 21.26+
 * YTM 9.26+
 */
internal fun getSetVideoQualityListFingerprint(
    audioVideoFormatClass: String,
    playerControllerClass: String
) = object : Fingerprint(
    parameters = listOf(audioVideoFormatClass),
    returnType = "V",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = audioVideoFormatClass,
            type = "[L"
        ),
        resourceLiteral(ResourceType.STRING, "quality_auto"),
    ),
    custom = { _, classDef ->
        classDef.fields.find { it.type == playerControllerClass } != null
    }
) {}

internal object FormatStreamModelToStringFingerprint : Fingerprint(
    name = "toString",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    strings = listOf(
        // Strings are partial matches.
        "isDefaultAudioTrack=",
        "audioTrackId="
    )
)

/**
 * ~ YT 21.25
 * ~ YTM 9.25
 */
internal object SelectAudioStreamFingerprint : Fingerprint(
    filters = listOf(
        // Replaced with 45673827L?
        literal(45666189L)
    )
)
