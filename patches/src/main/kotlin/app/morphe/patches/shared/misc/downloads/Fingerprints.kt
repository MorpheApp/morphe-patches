/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared.misc.downloads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import app.morphe.patches.shared.VideoStreamingDataToStringFingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * The offline settings keep only the download qualities up to the height of the screen,
 * which the download quality settings and the quality picker of YouTube show.
 */
internal object OfflineSettingsConstructorFingerprint : Fingerprint(
    name = "<init>",
    parameters = listOf(
        "Landroid/content/SharedPreferences;",
        "L",
        "I", // Height of the highest download quality.
        "L",
        "L"
    ),
    filters = listOf(
        // Format types of the heights map.
        methodCall(smali = "Ljava/util/Map;->keySet()Ljava/util/Set;"),
        // Copies the format types into an immutable list.
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("Ljava/util/Collection;")
        )
    )
)

/**
 * Maps the format types to their heights and to the labels of the quality settings.
 */
internal object FormatTypeMapsFingerprint : Fingerprint(
    name = "<clinit>",
    filters = listOf(
        resourceLiteral(ResourceType.STRING, "offline_video_quality_1080p")
    )
)

/**
 * Label of a format type in the quality settings, or -1 if it has none.
 */
internal object FormatTypeLabelFingerprint : Fingerprint(
    classFingerprint = FormatTypeMapsFingerprint,
    returnType = "I",
    parameters = listOf("L")
)

/**
 * Constructor of the streaming data, which first sets the list of every stream of the video.
 */
internal object StreamingDataFormatStreamsFingerprint : Fingerprint(
    classFingerprint = VideoStreamingDataToStringFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Ljava/util/List;"
        )
    )
)
