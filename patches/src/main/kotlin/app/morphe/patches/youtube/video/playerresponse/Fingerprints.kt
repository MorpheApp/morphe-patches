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
        "Z", // Appears to indicate if the video id is being opened or is currently playing.
        "Z",
        "Z",
        "Lj\$/time/Duration;"
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
        "Z", // Appears to indicate if the video id is being opened or is currently playing.
        "Z",
        "Z",
        "Lj\$/time/Duration;"
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
        "Z", // Appears to indicate if the video id is being opened or is currently playing.
        "Z",
        "Z",
    ),
    filters = listOf(
        string("psps")
    )
)

/**
 * For targets 20.10 to 20.14.
 */
internal object PlayerParameterBuilder2010Fingerprint : Fingerprint(
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
        "Z", // Appears to indicate if the video id is being opened or is currently playing.
        "Z",
        "Z",
        "Z"
    ),
    filters = listOf(
        string("psps")
    )
)

/**
 * For targets 20.02 to 20.09.
 */
internal object PlayerParameterBuilder2002Fingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf(
        "Ljava/lang/String;", // VideoId.
        "[B",
        "Ljava/lang/String;", // Player parameters = listOf( proto buffer.),
        "Ljava/lang/String;",
        "I",
        "I",
        "L", // 19.25+ parameter
        "Ljava/util/Set;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "L",
        "Z", // Appears to indicate if the video id is being opened or is currently playing.
        "Z",
        "Z",
        "Z",
    ),
    filters = listOf(
        string("psps"),
    )
)

/**
 * For targets 19.25 to 19.50.
 */
internal object PlayerParameterBuilder1925Fingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf(
        "Ljava/lang/String;", // VideoId.
        "[B",
        "Ljava/lang/String;", // Player parameters = listOf( proto buffer.),
        "Ljava/lang/String;",
        "I",
        "I",
        "L", // 19.25+ parameter
        "Ljava/util/Set;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "L",
        "Z", // Appears to indicate if the video id is being opened or is currently playing.
        "Z",
        "Z",
    ),
    filters = listOf(
        string("psps")
    )
)

/**
 * For targets 19.01 to 19.24.
 */
internal object PlayerParameterBuilderLegacyFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf(
        "Ljava/lang/String;", // VideoId.
        "[B",
        "Ljava/lang/String;", // Player parameters = listOf( proto buffer.),
        "Ljava/lang/String;",
        "I",
        "I",
        "Ljava/util/Set;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "L",
        "Z", // Appears to indicate if the video id is being opened or is currently playing.
        "Z",
        "Z",
    )
)
