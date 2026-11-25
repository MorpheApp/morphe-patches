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
