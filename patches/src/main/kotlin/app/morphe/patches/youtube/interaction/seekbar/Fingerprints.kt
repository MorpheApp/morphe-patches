package app.morphe.patches.youtube.interaction.seekbar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.patches.youtube.video.quality.VideoStreamingDataToStringFingerprint
import app.morphe.util.customLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode


private object SwipingUpGestureParentFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        literal(45379021) // Swipe up fullscreen feature flag
    )
)

internal object ShowSwipingUpGuideFingerprint : Fingerprint(
    classFingerprint = SwipingUpGestureParentFingerprint,
    accessFlags = listOf(AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        literal(1)
    )
)

internal object AllowSwipingUpGestureFingerprint : Fingerprint(
    classFingerprint = SwipingUpGestureParentFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L")
)

internal object DisableFastForwardGestureFingerprint : Fingerprint(
    definingClass = "/NextGenWatchLayout;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.IF_EQZ,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT,
    ),
    custom = { methodDef, _ ->
        methodDef.implementation!!.instructions.count() > 30
    }
)

internal object OnTouchEventHandlerFingerprint : Fingerprint(
    name = "onTouchEvent",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.PUBLIC),
    returnType = "Z",
    parameters = listOf("L"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.INVOKE_VIRTUAL, // nMethodReference
        Opcode.RETURN,
        Opcode.IGET_OBJECT,
        Opcode.IGET_BOOLEAN,
        Opcode.IF_EQZ,
        Opcode.INVOKE_VIRTUAL,
        Opcode.RETURN,
        Opcode.INT_TO_FLOAT,
        Opcode.INT_TO_FLOAT,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT,
        Opcode.IF_EQZ,
        Opcode.INVOKE_VIRTUAL,
        Opcode.INVOKE_VIRTUAL, // oMethodReference
    )
)

internal object TapToSeekFingerprint : Fingerprint(
    name = "onTouchEvent",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf("Landroid/view/MotionEvent;"),
    filters = listOf(
        literal(Int.MAX_VALUE),

        newInstance("Landroid/graphics/Point;"),
        methodCall(
            smali = "Landroid/graphics/Point;-><init>(II)V",
            location = MatchAfterImmediately()
        ),
        methodCall(
            smali = "Lj\$/util/Optional;->of(Ljava/lang/Object;)Lj\$/util/Optional;",
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            type = "Lj\$/util/Optional;",
            location = MatchAfterImmediately()
        ),

        opcode(Opcode.INVOKE_VIRTUAL, location = MatchAfterWithin(10))
    )
)

internal object SlideToSeekFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/view/View;", "F"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT,
        Opcode.IF_EQZ,
        Opcode.GOTO_16,
    ),
    custom = customLiteral { 67108864 } // TODO: Convert this to an instruction filter
)

internal object FullscreenLargeSeekbarFeatureFlagFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        literal(45691569)
    )
)

internal object VideoStreamingDataAllowSeekingFingerprint : Fingerprint(
    classFingerprint = VideoStreamingDataToStringFingerprint,
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        literal(8),
        opcode(Opcode.IF_EQ, location = MatchAfterImmediately()),
        // Another method in the same class almost matches this fingerprint but uses literal(0) here.
        literal(1, location = MatchAfterImmediately()),
    )
)

private object FormatStreamModelClassFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("FormatStream(itag=")
    )
)

// DVR window duration in seconds; 0 for non-DVR streams.
// Caller multiplies result by 1e6 with 4-hour fallback when <= 0, logs "windowMaxMediaTimeUs".
internal object FormatStreamModelMaxDvrDurationFingerprint : Fingerprint(
    classFingerprint = FormatStreamModelClassFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "D",
    parameters = listOf(),
    filters = listOf(
        opcode(Opcode.IGET_OBJECT),
        fieldAccess(opcode = Opcode.IGET_WIDE, type = "D", location = MatchAfterImmediately()),
        opcode(Opcode.RETURN_WIDE, location = MatchAfterImmediately()),
    )
)

