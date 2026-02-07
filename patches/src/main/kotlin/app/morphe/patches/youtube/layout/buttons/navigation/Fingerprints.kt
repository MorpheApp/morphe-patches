package app.morphe.patches.youtube.layout.buttons.navigation

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral
import app.morphe.patches.youtube.layout.hide.general.YouTubeDoodlesImageViewFingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

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

internal object AnimatedNavigationTabsFeatureFlagFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    filters = listOf(
        literal(45680008L)
    )
)

internal object PivotBarStyleFingerprint : Fingerprint(
    definingClass = "/PivotBar;",
    returnType = "V",
    parameters = listOf("L"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.INVOKE_STATIC,
        Opcode.MOVE_RESULT,
        Opcode.XOR_INT_2ADDR
    )
)

internal object PivotBarChangedFingerprint : Fingerprint(
    definingClass = "/PivotBar;",
    name = "onConfigurationChanged",
    returnType = "V",
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.INVOKE_STATIC,
        Opcode.MOVE_RESULT
    )
)

internal object TranslucentNavigationStatusBarFeatureFlagFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    filters = listOf(
        literal(45400535L) // Translucent status bar feature flag.
    )
)

/**
 * YouTube nav buttons.
 */
internal object TranslucentNavigationButtonsFeatureFlagFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        literal(45630927L) // Translucent navigation bar buttons feature flag.
    )
)

/**
 * Device on screen back/home/recent buttons.
 */
internal object TranslucentNavigationButtonsSystemFeatureFlagFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    filters = listOf(
        literal(45632194L) // Translucent system buttons feature flag.
    )
)

internal object SetWordmarkHeaderFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/widget/ImageView;"),
    filters = listOf(
        resourceLiteral(ResourceType.ATTR, "ytPremiumWordmarkHeader"),
        resourceLiteral(ResourceType.ATTR, "ytWordmarkHeader")
    )
)

/**
 * Matches the same method as [YouTubeDoodlesImageViewFingerprint].
 */
internal object WideSearchbarLayoutFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/view/View;",
    parameters = listOf("L", "L"),
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "action_bar_ringo"),
    )
)

