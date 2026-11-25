/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5272
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.glassnav

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

private const val BOTTOM_NAV_SCREEN_CLASS = "Lcom/reddit/launch/bottomnav/BottomNavScreen;"

internal object BottomNavBarFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        // Modifier.drawWithContent
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("L", "Lkotlin/jvm/functions/Function1;"),
            returnType = "L"
        ),
        string("bottom_nav", location = MatchAfterWithin(40)),
        // Surface
        methodCall(
            parameters = listOf("L", "L", "F", "J", "L", "Lkotlin/jvm/functions/Function2;", "L", "I", "I"),
            returnType = "V",
            location = MatchAfterWithin(40)
        )
    )
)

/**
 * Content of the side rail. The buttons are spaced evenly if the rail is not tall enough.
 */
internal object SideNavRailContentFingerprint : Fingerprint(
    filters = listOf(
        literal(500f),
        // Dp.compareTo
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("F", "F"),
            returnType = "I",
            location = MatchAfterWithin(5)
        ),
        // Arrangement.spacedBy
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            name = "<init>",
            parameters = listOf("F", "Z", "L")
        ),
        // Modifier of the column with the buttons.
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            definingClass = "Landroidx/compose/ui/b;",
            parameters = listOf("L", "L"),
            returnType = "L"
        ),
        literal(64f),
        literal(48f, location = MatchAfterWithin(10))
    )
)

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
 * A color of the theme, used as the background of a modifier.
 */
private val themeBackgroundFilters = listOf(
    checkCast("L"),
    opcode(Opcode.IGET_OBJECT, location = MatchAfterImmediately()),
    methodCall(
        opcode = Opcode.INVOKE_VIRTUAL,
        parameters = listOf(),
        returnType = "J",
        location = MatchAfterWithin(5)
    ),
    opcode(Opcode.MOVE_RESULT_WIDE, location = MatchAfterImmediately()),
    // Modifier.background
    methodCall(
        opcode = Opcode.INVOKE_STATIC,
        parameters = listOf("L", "J", "L"),
        returnType = "L",
        location = MatchAfterWithin(5)
    )
)

private val bottomNavBackdropFilters = themeBackgroundFilters + methodCall(
    // Modifier.graphicsLayer
    definingClass = "Landroidx/compose/ui/graphics/d;",
    parameters = listOf("L", "Lkotlin/jvm/functions/Function1;"),
    location = MatchAfterWithin(40)
)

/**
 * 2026.47.0+
 */
internal object BottomNavBackdropFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(
        "Lkotlin/jvm/functions/Function0;",
        "Z",
        "L",
        "L",
        "Landroidx/compose/runtime/internal/a;",
        "L",
        "I"
    ),
    filters = bottomNavBackdropFilters
)

/**
 * 2026.46.0 and lower.
 */
internal object BottomNavBackdropLegacyFingerprint : Fingerprint(
    definingClass = BOTTOM_NAV_SCREEN_CLASS,
    returnType = "V",
    parameters = listOf("L", "L", "I"),
    filters = bottomNavBackdropFilters
)

internal object RailBackgroundFingerprint : Fingerprint(
    definingClass = BOTTOM_NAV_SCREEN_CLASS,
    returnType = "V",
    filters = listOf(
        newInstance($$"Lcom/reddit/launch/bottomnav/BottomNavScreen$NavStackHostContent$2$1;")
    ) + themeBackgroundFilters
)

/**
 * Host of all screens that are behind the bottom navigation bar.
 * Matches 2 methods with 2026.46.0 and lower, because the layouts with and without
 * the side rail each have their own copy.
 */
internal object BottomNavStackHostFingerprint : Fingerprint(
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = BOTTOM_NAV_SCREEN_CLASS,
            type = "Lcom/reddit/navstack/"
        ),
        methodCall(
            opcode = Opcode.INVOKE_STATIC_RANGE,
            definingClass = "Lcom/reddit/navstack/",
            parameters = listOf("L", "L", "Z", "L", "L", "I", "I"),
            returnType = "V"
        )
    )
)

private object NavScreenContentStateToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string(
            "NavScreenContentState(showBottomNav=",
            comparison = StringComparisonType.STARTS_WITH
        ),
    )
)

/**
 * 2026.47.0+
 */
internal object NavScreenContentStateConstructorFingerprint : Fingerprint(
    classFingerprint = NavScreenContentStateToStringFingerprint,
    name = "<init>",
    parameters = listOf("Z", "Z", "Z", "F", "Z")
)

/**
 * Vertical offset of the bottom navigation bar, which slides it down when it hides.
 */
internal object BottomNavBarOffsetFingerprint : Fingerprint(
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        methodCall(definingClass = "Ljava/lang/Number;", name = "floatValue"),
        opcode(Opcode.INT_TO_FLOAT),
        methodCall(
            definingClass = "Ljava/lang/Math;",
            name = "max",
            parameters = listOf("I", "I"),
            returnType = "I"
        ),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        opcode(Opcode.NEG_INT, location = MatchAfterImmediately()),
        literal(0xffffffffL)
    ),
    custom = { _, classDef ->
        classDef.fields.any { it.type == BOTTOM_NAV_SCREEN_CLASS }
    }
)
