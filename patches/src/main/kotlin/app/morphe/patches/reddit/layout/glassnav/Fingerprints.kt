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
        literal(48f, location = MatchAfterWithin(10)),
        // Label of a button and if it is shown. Also used by the bottom navigation bar.
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            name = "<init>",
            parameters = listOf("Ljava/lang/String;", "Z")
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
 * Places the bottom navigation bar of screens where it hides while scrolling.
 */
internal object BottomNavBarPlacementFingerprint : Fingerprint(
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        // (1 - shownFraction) * height
        methodCall(definingClass = "Ljava/lang/Number;", name = "floatValue"),
        opcode(Opcode.SUB_FLOAT_2ADDR, location = MatchAfterWithin(5)),
        // Must be immediately, because a similar lambda negates this to slide the top bar up.
        fieldAccess(opcode = Opcode.IGET, type = "I", location = MatchAfterImmediately()),
        opcode(Opcode.INT_TO_FLOAT, location = MatchAfterWithin(5)),
        opcode(Opcode.MUL_FLOAT_2ADDR, location = MatchAfterWithin(5)),
        // Float.roundToInt
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("F"),
            returnType = "I",
            location = MatchAfterWithin(5)
        ),
        // PlacementScope.placeRelative
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("L", "L", "I", "I"),
            returnType = "V",
            location = MatchAfterWithin(5)
        )
    )
)

/**
 * Bottom navigation bar of screens where it does not hide while scrolling.
 */
internal object StaticBottomNavBarFingerprint : Fingerprint(
    definingClass = BOTTOM_NAV_SCREEN_CLASS,
    returnType = "V",
    parameters = listOf("L", "L", "I"),
    filters = listOf(
        // Modifier of the bar.
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            definingClass = "Landroidx/compose/ui/b;",
            parameters = listOf("L", "L"),
            returnType = "L"
        ),
        methodCall(
            definingClass = "Ljava/lang/Integer;",
            name = "valueOf",
            location = MatchAfterWithin(35)
        ),
        // Content of the bar.
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = BOTTOM_NAV_SCREEN_CLASS,
            type = "Landroidx/compose/runtime/internal/a;",
            location = MatchAfterWithin(25)
        )
    )
)

/**
 * Offset of the create post button that floats above the bottom navigation bar, 2026.45.0+
 */
internal object CreateButtonOffsetFingerprint : Fingerprint(
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = BOTTOM_NAV_SCREEN_CLASS
        ),
        methodCall(definingClass = "Ljava/lang/Number;", name = "floatValue"),
        // Height of the bar.
        opcode(Opcode.INT_TO_FLOAT),
        // Not lower than the system navigation bar.
        methodCall(
            definingClass = "Ljava/lang/Math;",
            name = "max",
            parameters = listOf("I", "I"),
            returnType = "I"
        ),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        opcode(Opcode.NEG_INT, location = MatchAfterWithin(5))
    )
)
