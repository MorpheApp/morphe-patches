package app.morphe.patches.youtube.layout.buttons.navigation

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral
import app.morphe.patches.youtube.layout.hide.general.YouTubeDoodlesImageViewFingerprint
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

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

internal object CollapsingToolbarLayoutFeatureFlag : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        literal(45736608L)
    )
)

internal object PivotBarStyleFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.INVOKE_STATIC,
        Opcode.MOVE_RESULT,
        Opcode.XOR_INT_2ADDR
    ),
    custom = { method, _ ->
        method.definingClass.endsWith("/PivotBar;")
    }
)

internal object PivotBarChangedFingerprint : Fingerprint(
    returnType = "V",
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.INVOKE_STATIC,
        Opcode.MOVE_RESULT
    ),
    custom = { method, _ ->
        method.definingClass.endsWith("/PivotBar;")
                && method.name == "onConfigurationChanged"
    }
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

internal object SearchBarFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.IGET_OBJECT,
        Opcode.IF_EQZ,
        Opcode.IGET_BOOLEAN,
        Opcode.IF_EQZ
    ),
    custom = { method, _ ->
        method.instructions.any {
            it.opcode == Opcode.INVOKE_VIRTUAL &&
                    it.getReference<MethodReference>()?.name == "isEmpty"
        }
    }
)

internal object SearchBarParentFingerprint : Fingerprint(
    returnType = "Landroid/view/View;",
    filters = listOf(
        resourceLiteral(ResourceType.ID, "voice_search"),
        string("voz-target-id")
    )
)

internal object SearchResultFingerprint : Fingerprint(
    returnType = "Landroid/view/View;",
    filters = listOf(
        resourceLiteral(ResourceType.ID, "voice_search"),
        string("search_filter_chip_applied"),
        string("search_original_chip_query")
    )
)

internal object VoiceInputControllerParentFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("[B", "Z"),
    filters = listOf(
        string("VoiceInputController")
    )
)

internal object VoiceInputControllerFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = emptyList(),
    custom = { method, _ ->
        method.instructions.any {
            it.opcode == Opcode.INVOKE_VIRTUAL &&
                    it.getReference<MethodReference>()?.name == "resolveActivity"
        }
    }
)

