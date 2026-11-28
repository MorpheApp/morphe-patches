/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5272
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.compose

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * DrawScope.drawRoundRect with the default arguments mask.
 */
internal object DrawRoundRectFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.SYNTHETIC),
    returnType = "V",
    parameters = listOf("L", "J", "J", "J", "J", "L", "F", "I"),
    filters = listOf(
        // DrawScope.size
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = listOf(),
            returnType = "J"
        ),
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE_RANGE,
            parameters = listOf("J", "J", "J", "J", "L", "F"),
            returnType = "V"
        )
    )
)

internal object DrawContentFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        string(
            "Attempting to drawContent for a `null` node",
            comparison = StringComparisonType.STARTS_WITH
        )
    )
)

/**
 * Draws a Lottie animation using the native Canvas.
 */
internal object LottieAnimationDrawFingerprint : Fingerprint(
    definingClass = $$"Lcom/airbnb/lottie/compose/LottieAnimationKt$LottieAnimation$2;",
    name = "invoke",
    returnType = "V",
    filters = listOf(
        // DrawScope.drawContext
        methodCall(parameters = listOf(), returnType = "L"),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        // DrawContext.canvas
        methodCall(parameters = listOf(), returnType = "L", location = MatchAfterWithin(5)),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        // AndroidCanvas.internalCanvas
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = "Landroid/graphics/Canvas;")
    )
)

/**
 * Modifier.offset
 */
internal object OffsetFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf("L", "F", "F"),
    filters = listOf(
        methodCall(
            name = "<init>",
            parameters = listOf("F", "F", "L")
        ),
        // Modifier.then
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = listOf("L"),
            returnType = "L",
            location = MatchAfterWithin(5)
        )
    )
)

/**
 * Modifier.padding
 */
internal object PaddingFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf("L", "F", "F", "F", "F"),
    filters = listOf(
        methodCall(
            name = "<init>",
            parameters = listOf("F", "F", "F", "F", "Lkotlin/jvm/functions/Function1;")
        ),
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = listOf("L"),
            returnType = "L",
            location = MatchAfterWithin(5)
        )
    )
)

/**
 * WindowInsets of only some sides.
 */
private object LimitInsetsToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("WindowInsetsSides(")
    )
)

internal object LimitInsetsGetTopFingerprint : Fingerprint(
    classFingerprint = LimitInsetsToStringFingerprint,
    returnType = "I",
    parameters = listOf("L"),
    filters = listOf(
        // WindowInsetsSides.Top
        literal(0x10),
        // WindowInsets.getTop
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = listOf("L"),
            returnType = "I"
        )
    )
)

/**
 * RowScope.weight / ColumnScope.weight, 2026.45.0+
 */
internal object WeightElementFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf("F", "L", "Z"),
    filters = listOf(
        string("invalid weight; must be greater than zero"),
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            name = "<init>",
            parameters = listOf("F", "Z"),
            returnType = "V",
            location = MatchAfterWithin(10)
        )
    )
)

internal object NewWeightModifierFingerprint : Fingerprint(
    definingClass = "Lapp/morphe/extension/reddit/utils/ComposeUtils;",
    name = "newWeightModifier",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("F", "Z")
)

/**
 * Modifier.graphicsLayer, 2026.45.0+
 */
internal object GraphicsLayerFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "L",
    definingClass = "Landroidx/compose/ui/graphics/",
    parameters = listOf("L", "F", "F", "F", "F", "F", "L", "Z", "I", "I")
)

/**
 * mutableStateOf(value), 2026.47.0+
 */
internal object MutableStateOfFingerprint : Fingerprint(
    definingClass = "Landroidx/compose/runtime/",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "L",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        newInstance("Landroidx/compose/runtime/ParcelableSnapshotMutableState;")
    )
)

internal object NewMutableStateFingerprint : Fingerprint(
    definingClass = "Lapp/morphe/extension/reddit/utils/ComposeUtils;",
    name = "newMutableState",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;")
)
