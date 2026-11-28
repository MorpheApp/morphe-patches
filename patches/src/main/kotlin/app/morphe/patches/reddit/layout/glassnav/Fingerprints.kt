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
import app.morphe.patcher.anyInstruction
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
private const val BOTTOM_NAV_WIDGET_CLASS = "Lcom/reddit/widget/bottomnav/a;"

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
 * Row of buttons in the bottom navigation bar, with a height of 50dp.
 */
internal object BottomNavRowHeightFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
    filters = listOf(
        literal(28f),
        literal(50f),
        // Modifier.height
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("L", "F"),
            returnType = "L",
            location = MatchAfterWithin(5)
        )
    )
)

/**
 * Height of the bottom navigation bar, which is the row of buttons (50dp)
 * plus the system navigation bar below it.
 */
internal object BottomNavBarHeightFingerprint : Fingerprint(
    filters = listOf(
        fieldAccess(
            opcode = Opcode.SGET,
            definingClass = BOTTOM_NAV_WIDGET_CLASS,
            type = "I"
        ),
        literal(50f, location = MatchAfterWithin(15)),
        opcode(Opcode.ADD_FLOAT_2ADDR, location = MatchAfterWithin(5))
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

private object ModifierCompanionToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        string("Modifier")
    )
)

/**
 * Creates the empty Modifier.
 */
internal object ModifierCompanionFingerprint : Fingerprint(
    classFingerprint = ModifierCompanionToStringFingerprint,
    name = "<clinit>",
    filters = listOf(
        fieldAccess(opcode = Opcode.SPUT_OBJECT, definingClass = "this")
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

internal object IsSearchButtonSupportedFingerprint : Fingerprint(
    definingClass = "Lapp/morphe/extension/reddit/patches/GlassNavigationBarPatch;",
    name = "isSearchButtonSupported",
    returnType = "Z",
    parameters = listOf()
)

/**
 * Top of Home with the menu button, the search field and the avatar.
 */
internal object HomeTopBarFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        string("home_revamp_m1_app_bar"),
        // Menu button.
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            name = "invoke",
            parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;", "Ljava/lang/Object;"),
            returnType = "Ljava/lang/Object;"
        ),
        string("main_top_app_bar_search"),
        // Search field. The order of its parameters differs between app versions.
        methodCall(
            opcode = Opcode.INVOKE_STATIC_RANGE,
            returnType = "V"
        ),
        // Avatar.
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("I", "I", "L", "L", "Landroidx/compose/runtime/internal/a;"),
            returnType = "V"
        )
    )
)

/**
 * Home feed tab row (For You, Following, Popular), 2026.45.0+
 */
internal object HomeTabBarFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(
        "L",
        "L",
        "Lkotlin/jvm/functions/Function1;",
        "Z",
        "Z",
        "Lcom/reddit/listing/model/sort/SortType;",
        "Lkotlin/jvm/functions/Function2;",
        "F",
        "L",
        "I"
    ),
    filters = listOf(
        string("home_tab_bar"),
        methodCall(
            opcode = Opcode.INVOKE_STATIC_RANGE,
            parameters = listOf(
                "Ljava/util/List;",
                "L",
                "L",
                "Z",
                "Lcom/reddit/ui/compose/ds/TabsArrangement;",
                "L",
                "Lcom/reddit/ui/compose/ds/TabSize;",
                "L",
                "Lkotlin/jvm/functions/Function1;",
                "L",
                "Landroidx/compose/runtime/internal/a;",
                "L",
                "I",
                "I"
            ),
            returnType = "V"
        )
    )
)

/**
 * Wrapper around the home tab bar with its modifier and top padding, 2026.45.0+
 */
internal object HomeTabBarWrapperFingerprint : Fingerprint(
    classFingerprint = HomeTabBarFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(
        "L",
        "L",
        "Lkotlin/jvm/functions/Function1;",
        "L",
        "Z",
        "Lcom/reddit/listing/model/sort/SortType;",
        "Lkotlin/jvm/functions/Function2;",
        "F",
        "L",
        "I"
    )
)

/**
 * Height of the home feed tab row header, 2026.45.0+
 */
internal object HomeTabBarHeightFingerprint : Fingerprint(
    classFingerprint = HomeTabBarFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "F",
    parameters = listOf("Z", "L")
)

/**
 * Item of the home feed tab row, 2026.47.0+
 */
internal object HomeTabItemFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;", "Ljava/lang/Object;"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_STATIC_RANGE,
            parameters = listOf("L", "F", "F", "F", "F", "I"),
            returnType = "L"
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        string("home_tab_", location = MatchAfterWithin(3)),
        methodCall(
            opcode = Opcode.INVOKE_STATIC_RANGE,
            parameters = listOf(
                "Landroidx/compose/runtime/internal/a;",
                "Lkotlin/jvm/functions/Function0;",
                "L",
                "Z",
                "Z",
                "Lcom/reddit/ui/compose/ds/TabSize;",
                "Lkotlin/jvm/functions/Function2;",
                "Lkotlin/jvm/functions/Function2;",
                "L",
                "Ljava/lang/String;",
                "L",
                "L",
                "I",
                "I"
            ),
            returnType = "V"
        )
    )
)

/**
 * Label of an item in the home feed tab row, 2026.47.0+
 */
internal object HomeTabLabelFingerprint : Fingerprint(
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "Lcom/reddit/feedslegacy/switcher/homepager/model/HomePagerScreenTabUiModel;",
            type = "Ljava/lang/String;"
        ),
        methodCall(
            opcode = Opcode.INVOKE_STATIC_RANGE,
            parameters = listOf(
                "Ljava/lang/String;", "L", "J", "J", "L", "L", "L", "J", "L", "I", "J",
                "I", "Z", "I", "I", "Lkotlin/jvm/functions/Function1;", "L", "L", "I", "I", "I"
            ),
            returnType = "V",
            location = MatchAfterWithin(40)
        )
    )
)

/**
 * Indicator of a tab row. Uses a RoundedCornerShape, 2026.47.0+
 * The tab row is the class of the method that [HomeTabBarFingerprint] calls to draw it.
 */
internal object TabRowIndicatorFingerprint : Fingerprint(
    definingClass = { HomeTabBarFingerprint.instructionMatches[1].methodReference.definingClass },
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("I", "L", "L", "Z"),
    filters = listOf(
        literal(2.0f),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("F"),
            returnType = "L",
            location = MatchAfterWithin(6)
        )
    )
)

/**
 * The pager state of the tab row, the second parameter of the tab row that [HomeTabBarFingerprint] calls.
 */
private val tabRowPagerType
    get() = HomeTabBarFingerprint.instructionMatches[1].methodReference.parameterTypes[1].toString()

/**
 * Calls the indicator of a tab row ([TabRowIndicatorFingerprint]) with the pager state of the tab row, 2026.47.0+
 * Some 2026.47.0 builds read the pager state from a field of the indicator lambda,
 * and newer builds cast it.
 */
internal object TabRowIndicatorCallerFingerprint : Fingerprint(
    filters = listOf(
        anyInstruction(
            checkCast({ tabRowPagerType }),
            fieldAccess(opcode = Opcode.IGET_OBJECT, type = { tabRowPagerType })
        ),
        methodCall(reference = { TabRowIndicatorFingerprint.originalMethod })
    )
)

/**
 * Uses the height of the home feed tab row header ([HomeTabBarHeightFingerprint]).
 * The tab bar ([HomeTabBarFingerprint]) uses the height too, so its class is excluded.
 */
internal object HomeTabBarHeightCallerFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        methodCall(reference = { HomeTabBarHeightFingerprint.originalMethod }),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately())
    ),
    custom = { _, classDef -> classDef.type != HomeTabBarFingerprint.originalClassDef.type }
)

internal object NewRoundedCornerShapeFingerprint : Fingerprint(
    definingClass = "Lapp/morphe/extension/reddit/patches/GlassNavigationBarTopTabs;",
    name = "newRoundedCornerShape",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("F")
)

/**
 * Button in the bottom navigation bar. Its parameters start with
 * (L, Function0, String, Z selected, Modifier) in all versions, and the later ones differ.
 */
internal object BottomNavButtonFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        string("bottom_nav_button"),
        // Space above the icon.
        literal(8f),
        // Modifier.height
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("L", "F"),
            returnType = "L",
            location = MatchAfterWithin(5)
        )
    )
)

/**
 * Tracks if a nested list feed is scrolled, with a shared lambda class that checks the list state.
 */
internal object NestedListTrackerFingerprint : Fingerprint(
    definingClass = $$"Lcom/reddit/feeds/ui/composables/NestedListTrackerKt$NestedListTracker$1$1;",
    name = "invokeSuspend",
    filters = listOf(
        // Case number of the shared lambda class.
        opcode(Opcode.CONST_4),
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            name = "<init>",
            parameters = listOf("Landroidx/compose/foundation/lazy/", "I"),
            location = MatchAfterWithin(3)
        ),
        // derivedStateOf
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("Lkotlin/jvm/functions/Function0;"),
            location = MatchAfterWithin(3)
        )
    )
)
