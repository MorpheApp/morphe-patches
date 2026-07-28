/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

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
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.resourceLiteral
import app.morphe.patches.youtube.shared.SeekbarFingerprint
import app.morphe.patches.youtube.shared.VideoStreamingDataToStringFingerprint
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
            smali = "Lj$/util/Optional;->of(Ljava/lang/Object;)Lj$/util/Optional;",
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            type = "Lj$/util/Optional;",
            location = MatchAfterImmediately()
        ),

        opcode(Opcode.INVOKE_VIRTUAL, location = MatchAfterWithin(10))
    )
)

internal object SlideToSeekFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/view/View;", "F"),
    filters = listOf(
        opcode(Opcode.INVOKE_VIRTUAL),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        opcode(Opcode.IF_EQZ, location = MatchAfterImmediately()),
        opcode(Opcode.GOTO_16, location = MatchAfterImmediately()),

        literal(67108864)
    )
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
internal object FormatStreamModelMaxDVRDurationFingerprint : Fingerprint(
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

internal object SeekbarHandlerOnTouchFingerprint : Fingerprint (
    classFingerprint = SeekbarFingerprint,
    name = "onTouchEvent"
)

internal object SeekbarUpdatePointFingerprint : Fingerprint (
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    returnType = "V",
    filters = listOf(
        fieldAccess(
            definingClass = "this",
            type = "Landroid/graphics/Point;"
        ),
        methodCall( // Get seekbar point.
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = listOf("Landroid/graphics/Point;"),
            returnType = "V",
            location = MatchAfterWithin(5)
        ),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Landroid/graphics/Rect;",
            location = MatchAfterWithin(10)
        ),
        fieldAccess(
            opcode = Opcode.IGET,
            smali = "Landroid/graphics/Rect;->left:I",
            location = MatchAfterWithin(5)
        )
    )
)

internal object SlideSeekbarHandlerOnTouchFingerprint : Fingerprint (
    classFingerprint = Fingerprint (
        accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
        filters = listOf(
            resourceLiteral(ResourceType.DIMEN, "seek_easy_horizontal_touch_offset_to_start_scrubbing")
        )
    ),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf("Landroid/view/View;", "Landroid/view/MotionEvent;")
)

internal object SlideSeekbarGetViewControllerFingerprint : Fingerprint (
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/view/View;", "F"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            location = MatchAfterWithin(10) // Match close to start of method.
        ),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            location = MatchAfterWithin(10)
        ),
        literal(124587, location = MatchAfterWithin(20)),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            location = MatchAfterWithin(10)
        ),
        literal(67108864)
    )
)

internal object SeekbarFineScrubbingBitmapFingerprint : Fingerprint (
    classFingerprint = Fingerprint (
        returnType = "Landroid/graphics/Bitmap;",
        parameters = listOf("L", "I", "Landroid/graphics/Bitmap;"),
        filters = listOf(
            string("Storyboard regionDecoder.decodeRegion exception - ")
        )
    ),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL, AccessFlags.DECLARED_SYNCHRONIZED),
    returnType = "V",
    parameters = listOf("Landroid/graphics/Bitmap;")
)

internal object SeekbarBigBoardsUpdateFingerprint : Fingerprint (
    classFingerprint = Fingerprint(
        accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
        returnType = "Ljava/lang/String;",
        parameters = listOf(),
        filters = listOf(
            string("player_overlay_big_boards")
        )
    ),
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        literal(1),
        opcode(opcode = Opcode.IF_NEZ, location = MatchAfterImmediately()),
        opcode(opcode = Opcode.RETURN, location = MatchAfterImmediately())
    )
)

internal object SeekbarBigBoardsUpdateLegacyFingerprint : Fingerprint (
    classFingerprint = Fingerprint(
        accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
        returnType = "V",
        parameters = listOf("Z"),
        filters = listOf(
            fieldAccess(
                opcode = Opcode.SGET_OBJECT,
                smali = $$"Landroid/widget/ImageView$ScaleType;->CENTER_CROP:Landroid/widget/ImageView$ScaleType;"
            ),
            fieldAccess(
                opcode = Opcode.SGET_OBJECT,
                smali = $$"Landroid/widget/ImageView$ScaleType;->FIT_CENTER:Landroid/widget/ImageView$ScaleType;"
            )
        )
    ),
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        literal(1),
        opcode(opcode = Opcode.IF_NEZ, location = MatchAfterImmediately()),
        opcode(opcode = Opcode.RETURN, location = MatchAfterImmediately())
    )
)
