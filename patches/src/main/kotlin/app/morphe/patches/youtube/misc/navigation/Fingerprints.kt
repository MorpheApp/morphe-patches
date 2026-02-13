package app.morphe.patches.youtube.misc.navigation

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.checkCast
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral
import app.morphe.patches.youtube.layout.buttons.navigation.navigationBarPatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object ActionBarSearchResultsFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/view/View;",
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "action_bar_search_results_view_mic"),
        methodCall(name = "setLayoutDirection")
    )
)

internal object ToolbarLayoutFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "toolbar_container"),
        checkCast("Lcom/google/android/apps/youtube/app/ui/actionbar/MainCollapsingToolbarLayout;")
    )
)

/**
 * Matches to https://android.googlesource.com/platform/frameworks/support/+/9eee6ba/v7/appcompat/src/android/support/v7/widget/Toolbar.java#963
 */
internal object AppCompatToolbarBackButtonFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/graphics/drawable/Drawable;",
    parameters = listOf(),
    custom = { _, classDef ->
        classDef.type == "Landroid/support/v7/widget/Toolbar;"
    }
)

/**
 * Matches to the class found in [PivotBarConstructorFingerprint].
 */
internal object InitializeButtonsFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        string("FEvideo_picker")
    )
)

/**
 * Extension method, used for callback into to other patches.
 * Specifically, [navigationBarPatch].
 */
internal object NavigationBarHookCallbackFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "V",
    parameters = listOf(EXTENSION_NAVIGATION_BUTTON_DESCRIPTOR, "Landroid/view/View;"),
    custom = { method, _ ->
        method.name == "navigationTabCreatedCallback" &&
            method.definingClass == EXTENSION_CLASS_DESCRIPTOR
    }
)

/**
 * Matches to the Enum class that looks up ordinal -> instance.
 */
internal object NavigationEnumFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.STATIC, AccessFlags.CONSTRUCTOR),
    strings = listOf(
        "PIVOT_HOME",
        "TAB_SHORTS",
        "CREATION_TAB_LARGE",
        "PIVOT_SUBSCRIPTIONS",
        "TAB_ACTIVITY",
        "VIDEO_LIBRARY_WHITE",
        "INCOGNITO_CIRCLE",
    ),
    custom = { _, classDef ->
        // Don't match our own code.
        !classDef.type.startsWith("Lapp/morphe")
    }
)

internal object PivotBarButtonsCreateDrawableViewFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/view/View;",
    custom = { method, _ ->
        method.definingClass == "Lcom/google/android/libraries/youtube/rendering/ui/pivotbar/PivotBar;" &&
            // Only one view creation method has a Drawable parameter.
            method.parameterTypes.firstOrNull() == "Landroid/graphics/drawable/Drawable;"
    }
)

internal object PivotBarButtonsCreateResourceStyledViewFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/view/View;",
    parameters = listOf("L", "Z", "I", "L"),
    custom = { method, _ ->
        method.definingClass == "Lcom/google/android/libraries/youtube/rendering/ui/pivotbar/PivotBar;"
    }
)

/**
 * 20.21+
 */
internal object PivotBarButtonsCreateResourceIntViewFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/view/View;",
    custom = { method, _ ->
        method.definingClass == "Lcom/google/android/libraries/youtube/rendering/ui/pivotbar/PivotBar;" &&
            // Only one view creation method has an int first parameter.
            method.parameterTypes.firstOrNull() == "I"
    }
)

internal object PivotBarButtonsViewSetSelectedFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("I", "Z"),
    filters = listOf(
        methodCall(name = "setSelected")
    ),
    custom = { method, _ ->
        method.definingClass == "Lcom/google/android/libraries/youtube/rendering/ui/pivotbar/PivotBar;"
    }
)

internal object PivotBarConstructorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        string("com.google.android.apps.youtube.app.endpoint.flags"),
    )
)

internal object ImageEnumConstructorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.STATIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        string("TAB_ACTIVITY_CAIRO"),
        opcode(Opcode.SPUT_OBJECT)
    ),
    custom = { _, classDef ->
        // Don't match our extension code.
        !classDef.type.startsWith("Lapp/morphe/")
    }
)

internal object SetEnumMapFingerprint : Fingerprint(
    filters = listOf(
        resourceLiteral(ResourceType.DRAWABLE, "yt_fill_bell_black_24"),
        methodCall(smali = "Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;", location = MatchAfterWithin(10)),
        methodCall(smali = "Ljava/util/EnumMap;->put(Ljava/lang/Enum;Ljava/lang/Object;)Ljava/lang/Object;", location = MatchAfterWithin(10))
    )
)
