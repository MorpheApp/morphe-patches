package app.morphe.patches.youtube.video.playerresponse

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * For targets 20.46 and later.
 */
internal object PlayerParameterBuilderFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf(
        "Ljava/lang/String;",  // VideoId.
        "[B",
        "Ljava/lang/String;",  // Player parameters = listOf( proto buffer.),
        "Ljava/lang/String;",
        "I",
        "Z",
        "I",
        "L",
        "Ljava/util/Set;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "L",
        "Z", // Appears to indicate if the video ID is being opened or is currently playing.
        "Z",
        "Z",
        "Lj$/time/Duration;"
    )
)

/**
 * For targets 20.26 and later.
 */
internal object PlayerParameterBuilder2026Fingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf(
        "Ljava/lang/String;",  // VideoId.
        "[B",
        "Ljava/lang/String;",  // Player parameters = listOf( proto buffer.),
        "Ljava/lang/String;",
        "I",
        "Z",
        "I",
        "L",
        "Ljava/util/Set;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "L",
        "Z", // Appears to indicate if the video ID is being opened or is currently playing.
        "Z",
        "Z",
        "Lj$/time/Duration;"
    ),
    filters = listOf(
        string("psps")
    )
)

/**
 * For targets 20.15 to 20.25
 */
internal object PlayerParameterBuilder2015Fingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf(
        "Ljava/lang/String;",  // VideoId.
        "[B",
        "Ljava/lang/String;",  // Player parameters = listOf( proto buffer.),
        "Ljava/lang/String;",
        "I",
        "Z",
        "I",
        "L",
        "Ljava/util/Set;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "L",
        "Z", // Appears to indicate if the video ID is being opened or is currently playing.
        "Z",
        "Z",
    ),
    filters = listOf(
        string("psps")
    )
)