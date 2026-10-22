/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.buttons.navigation

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.parametersMatch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

private val SET_VISIBILITY_METHOD_CALL = methodCall(
    opcode = Opcode.INVOKE_VIRTUAL,
    smali = "Landroid/view/View;->setVisibility(I)V"
)

internal object CreatePivotBarFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(
        "Lcom/google/android/libraries/youtube/rendering/ui/pivotbar/PivotBar;",
        "Landroid/widget/TextView;",
        "Ljava/lang/CharSequence;",
    ),
    filters = listOf(
        methodCall(definingClass = "Landroid/widget/TextView;", name = "setText"),
        opcode(Opcode.RETURN_VOID)
    )
)

internal object CastMenuItemInitializeFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "castmediaroutebutton"),
        methodCall(name = "setShowAsAction")
    )
)

internal object CastMenuItemVisibilityFingerprint : Fingerprint(
    classFingerprint = CastMenuItemInitializeFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        methodCall(name = "setVisible")
    )
)

internal object AnimatedNavigationTabsFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45680008L)
    )
)

internal object AutoHideNavigationBarOnFeedScrollingFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/support/v7/widget/RecyclerView;", "I", "I"),
    filters = listOf(
        methodCall("Landroid/view/ViewConfiguration;->get(Landroid/content/Context;)Landroid/view/ViewConfiguration;"),
        methodCall(
            smali = "Landroid/view/ViewConfiguration;->getScaledTouchSlop()I",
            location = MatchAfterWithin(5)
        )
    )
)

internal object AutoHideNavigationBarOnDismissMiniplayerFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("F"),
    filters = listOf(
        literal(2),
        methodCall(
            smali = "Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;",
            location = MatchAfterWithin(5)
        ),
        methodCall(
            smali = "Ljava/util/Set;->iterator()Ljava/util/Iterator;",
            location = MatchAfterWithin(5)
        ),
        methodCall(
            smali = "Ljava/util/Iterator;->hasNext()Z",
            location = MatchAfterWithin(5)
        ),
        methodCall(
            smali = "Ljava/util/Iterator;->next()Ljava/lang/Object;",
            location = MatchAfterWithin(5)
        )
    )
)

internal object PivotBarStyleFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/libraries/youtube/rendering/ui/pivotbar/PivotBar;",
    returnType = "V",
    parameters = listOf("L"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.INVOKE_STATIC,
        Opcode.MOVE_RESULT,
        Opcode.XOR_INT_2ADDR
    )
)

internal object PivotBarChangedFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/libraries/youtube/rendering/ui/pivotbar/PivotBar;",
    name = "onConfigurationChanged",
    returnType = "V",
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.INVOKE_STATIC,
        Opcode.MOVE_RESULT
    )
)

/**
 * Feature flags of the translucent navigation bar. 21.29 and older read each of them with a single
 * accessor method, newer targets read them inline in many classes, sometimes more than once in
 * the same method.
 */
internal val TRANSLUCENT_NAVIGATION_BAR_FEATURE_FLAGS = listOf(
    45630927L, // Blurred, translucent background of the navigation bar.
    45632194L, // Navigation bar also covers the system navigation buttons.
    45631257L  // Feed laid out behind the navigation bar instead of above it.
)

internal object TranslucentNavigationBarFeatureFlagsFingerprint : Fingerprint(
    filters = listOf(
        anyInstruction(*TRANSLUCENT_NAVIGATION_BAR_FEATURE_FLAGS.map { literal(it) }.toTypedArray())
    )
)

/**
 * Sets the bottom padding of the navigation bar to the bottom window inset, the room for
 * the system navigation buttons when the bar is drawn behind them. The bar height also grows
 * by the same value, which is first stored in a field of the bar.
 */
internal object NavigationBarBottomPaddingFingerprint : Fingerprint(
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT,
            definingClass = "Lcom/google/android/libraries/youtube/rendering/ui/pivotbar/PivotBar;",
            type = "I"
        ),
        methodCall(
            definingClass = "Lcom/google/android/libraries/youtube/rendering/ui/pivotbar/PivotBar;",
            name = "setPadding",
            location = MatchAfterWithin(10)
        )
    )
)

/**
 * Sets the bottom margin of the layout that holds the page and the navigation bar.
 * With the keyboard open and a text field focused, the margin is the keyboard height,
 * otherwise the bottom of the window insets.
 */
internal object PageBottomMarginFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/graphics/Rect;"),
    filters = listOf(
        methodCall(smali = "Landroid/view/View;->findFocus()Landroid/view/View;"),
        methodCall(
            smali = "Landroid/view/View;->onCheckIsTextEditor()Z",
            location = MatchAfterWithin(5)
        ),
        fieldAccess(
            opcode = Opcode.IGET,
            definingClass = "Landroid/graphics/Rect;",
            name = "bottom"
        )
    )
)

/**
 * Sets the padding of a feed list. When the feed is laid out behind the navigation bar,
 * the bottom padding holds the navigation bar height and the bottom window inset.
 */
internal object FeedListPaddingFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/support/v7/widget/RecyclerView;",
            name = "setClipToPadding"
        ),
        methodCall(
            definingClass = "Landroid/support/v7/widget/RecyclerView;",
            name = "setPadding",
            location = MatchAfterImmediately()
        )
    )
)

/**
 * Draws the blurred background of the translucent navigation bar. If the canvas is hardware
 * accelerated, a copy of the watch layout, with the player, is drawn into a blurred render node,
 * then the bar color is drawn over it.
 */
internal object NavigationBarBlurDrawFingerprint : Fingerprint(
    classFingerprint = Fingerprint(
        filters = listOf(
            string("frostedGlassRenderNode")
        )
    ),
    name = "draw",
    returnType = "V",
    parameters = listOf("Landroid/graphics/Canvas;"),
    filters = listOf(
        methodCall(smali = "Landroid/graphics/Canvas;->isHardwareAccelerated()Z"),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        methodCall(smali = "Landroid/view/View;->draw(Landroid/graphics/Canvas;)V")
    )
)

/**
 * Applies the system navigation bar color of the app theme to the window.
 */
internal object SystemNavigationBarColorFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/content/res/TypedArray;"),
    filters = listOf(
        methodCall(smali = "Landroid/content/res/TypedArray;->getColor(II)I"),
        opcode(Opcode.MOVE_RESULT, MatchAfterImmediately()),
        methodCall(smali = "Landroid/view/Window;->getNavigationBarColor()I"),
        methodCall(smali = "Landroid/view/Window;->setNavigationBarColor(I)V")
    )
)

/*
 * YouTube nav buttons.
 */

private object OldSearchButtonAccessibilityLabelFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/CharSequence;",
    parameters = listOf(),
    filters = listOf(
        resourceLiteral(ResourceType.STRING, "menu_search")
    )
)

internal object OldSearchButtonVisibilityFingerprint : Fingerprint(
    classFingerprint = OldSearchButtonAccessibilityLabelFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            smali = "Landroid/view/MenuItem;->setShowAsAction(I)V"
        )
    )
)

internal object SearchResultButtonVisibilityFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/view/View;",
    filters = listOf(
        literal(45423782L), // lens search button feature flags.
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = $$"Landroid/view/View;->setOnClickListener(Landroid/view/View$OnClickListener;)V"
        ),
    )
)

private object SearchFragmentFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/view/View;",
    filters = listOf(
        string("search-lens-button")
    )
)

internal object SearchButtonsVisibilityFingerprint : Fingerprint(
    classFingerprint = SearchFragmentFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            smali = "Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z"
        ),
        SET_VISIBILITY_METHOD_CALL, // clear button.
        SET_VISIBILITY_METHOD_CALL, // microphone button.
        SET_VISIBILITY_METHOD_CALL  // lens button.
    )
)

internal object PivotBarRendererFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("L"),
    returnType = "Lj$/util/Optional;",
    filters = listOf(
        literal(117501096L),
        opcode(Opcode.IF_NE),
        opcode(Opcode.CHECK_CAST),
        methodCall(
            opcode = Opcode.INVOKE_DIRECT_RANGE,
            definingClass = "this",
            name = "<init>",
            returnType = "V"
        ),
        opcode(Opcode.RETURN_OBJECT)
    )
)

internal object PivotBarRendererListFingerprint : Fingerprint(
    parameters = listOf("L"),
    returnType = "V",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "L"
        ),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("L"),
            returnType = "L"
        ),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "L"
        ),
        literal(45633821L),
    )
)

/**
 * Tabs of the browse response (singleColumnBrowseResultsRenderer).
 * The tab protos are converted to tab items, that are cached in a field of the class.
 */
internal object BrowseResponseTabsFingerprint : Fingerprint(
    interfaces = listOf("Landroid/os/Parcelable;"),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL, AccessFlags.DECLARED_SYNCHRONIZED),
    returnType = "L",
    parameters = listOf(),
    filters = listOf(
        literal(58173949L),
        methodCall(name = "stream"),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this"
        )
    )
)

/**
 * Creates a navigation bar item proto with a browse endpoint.
 * Parameters: browse id, label, icon type, icon only.
 */
internal object PivotBarItemProtoFactoryFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "L",
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;", "L", "Z"),
    filters = listOf(
        literal(117501096L)
    )
)

/**
 * Navigation bar used before the guide response is loaded.
 * The Subscriptions button is not added if the flag is enabled.
 */
internal object OfflineNavigationBarSubscriptionsFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45780222L),
        string("FEsubscriptions")
    )
)

internal object TopBarRendererPrimaryFilterFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        fieldAccess(opcode = Opcode.SGET_OBJECT),
        checkCast(
            type = "Ljava/util/List;",
            location = MatchAfterWithin(5)
        ),
        opcode(
            opcode = Opcode.CHECK_CAST,
            location = MatchAfterWithin(3)
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            returnType = "L",
            location = MatchAfterWithin(3)
        ),
        opcode(
            opcode = Opcode.CHECK_CAST,
            location = MatchAfterWithin(5)
        ),
        literal(120823052L),
    )
)

internal object TopBarRendererSecondaryFilterFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            smali = "Ljava/util/List;->iterator()Ljava/util/Iterator;"
        ),
        literal(120823052L),
    )
)

internal object SettingIntentFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
        ),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            location = MatchAfterImmediately()
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf(),
            returnType = "Landroid/content/Intent;",
            location = MatchAfterImmediately()
        ),
        opcode(
            opcode = Opcode.MOVE_RESULT_OBJECT,
            location = MatchAfterImmediately()
        ),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            location = MatchAfterImmediately()
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf("L"),
            returnType = "Lcom/google/common/util/concurrent/ListenableFuture;",
            location = MatchAfterWithin(5)
        ),
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            name = "<init>",
            parameters = listOf("I"),
            location = MatchAfterWithin(5)
        )
    ),
    custom = { method, _ ->
        parametersMatch( // 21.25+
            method.parameters,
            listOf("Landroid/view/MenuItem;")
        ) || parametersMatch( // 21.24 and older
            method.parameters,
            listOf()
        )
    }
)

internal object GetGeneratedRegistryFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS,
    name = "getGeneratedRegistry",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Lcom/google/protobuf/ExtensionRegistryLite;",
    parameters = listOf()
)

internal object ParseWithRegistryFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS,
    name = "parseWithRegistry",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Lcom/google/protobuf/MessageLite;",
    parameters = listOf("Lcom/google/protobuf/MessageLite;", "[B", "Lcom/google/protobuf/ExtensionRegistryLite;")
)
