package app.morphe.patches.youtube.layout.seekbar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object PlayerSeekbarColorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        resourceLiteral(ResourceType.COLOR, "inline_time_bar_played_not_highlighted_color"),
        resourceLiteral(ResourceType.COLOR, "inline_time_bar_colorized_bar_played_color_dark")
    )
)

// class is ControlsOverlayStyle in 20.32 and lower, and obfuscated in 20.33+
internal object SetSeekbarClickedColorFingerprint : Fingerprint(
    filters = OpcodesFilter.opcodesToFilters(Opcode.CONST_HIGH16),
    strings = listOf("YOUTUBE", "PREROLL", "POSTROLL", "REMOTE_LIVE", "AD_LARGE_CONTROLS")
)

internal object ShortsSeekbarColorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        resourceLiteral(ResourceType.COLOR, "reel_time_bar_played_color")
    )
)

internal object PlayerSeekbarHandle1ColorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        resourceLiteral(ResourceType.COLOR, "inline_time_bar_live_seekable_range"),
        resourceLiteral(ResourceType.ATTR, "ytStaticBrandRed"),
    )
)

internal object PlayerSeekbarHandle2ColorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(
        resourceLiteral(ResourceType.ATTR, "ytTextSecondary"),
        resourceLiteral(ResourceType.ATTR, "ytStaticBrandRed"),
    )
)

internal object WatchHistoryMenuUseProgressDrawableFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(definingClass = "Landroid/widget/ProgressBar;", name = "setMax"),
        opcode(Opcode.MOVE_RESULT),
        literal(-1712394514)
    )
)

internal object LithoLinearGradientFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.STATIC),
    returnType = "Landroid/graphics/LinearGradient;",
    parameters = listOf("F", "F", "F", "F", "[I", "[F"),
)

/**
 * 19.49+
 */
internal object PlayerLinearGradientFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("I", "I", "I", "I", "Landroid/content/Context;", "I"),
    returnType = "Landroid/graphics/LinearGradient;",
    filters = listOf(
        resourceLiteral(ResourceType.COLOR, "yt_youtube_magenta"),

        opcode(Opcode.FILLED_NEW_ARRAY, location = InstructionLocation.MatchAfterWithin(5)),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
    )
)

/**
 * 19.25 - 19.47
 */
internal object PlayerLinearGradientLegacyFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.COLOR, "yt_youtube_magenta"),

        opcode(Opcode.FILLED_NEW_ARRAY),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),
    )
)

internal const val LOTTIE_ANIMATION_VIEW_CLASS_TYPE = "Lcom/airbnb/lottie/LottieAnimationView;"

internal object LottieAnimationViewSetAnimationIntFingerprint : Fingerprint(
    definingClass = LOTTIE_ANIMATION_VIEW_CLASS_TYPE,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("I"),
    returnType = "V",
    filters = listOf(
        methodCall(definingClass = "this", name = "isInEditMode")
    )
)

internal object LottieCompositionFactoryZipFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/content/Context;", "Ljava/util/zip/ZipInputStream;", "Ljava/lang/String;"),
    returnType = "L",
    filters = listOf(
        string("Unable to parse composition"),
        string(" however it was not found in the animation.")
    )
)

/**
 * Resolves using class found in [lottieCompositionFactoryZipFingerprint].
 *
 * [Original method](https://github.com/airbnb/lottie-android/blob/26ad8bab274eac3f93dccccfa0cafc39f7408d13/lottie/src/main/java/com/airbnb/lottie/LottieCompositionFactory.java#L386)
 */
internal object LottieCompositionFactoryFromJsonInputStreamFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Ljava/io/InputStream;", "Ljava/lang/String;"),
    returnType = "L",
    filters = listOf(
        anyInstruction(literal(2), literal(3))
    )
)

