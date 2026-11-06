package app.morphe.patches.youtube.misc.settings

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags

internal object SetThemeFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf(),
    filters = listOf(
        resourceLiteral(ResourceType.STRING, "app_theme_appearance_dark"),
    )
)

internal object CairoFragmentConfigFingerprint : Fingerprint(
    filters = listOf(
        literal(45532100L)
    )
)

/**
 * Lays out the snackbars and the other bottom messages, then keeps the top of the shown one.
 */
internal object BottomUiContainerOnLayoutFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/app/common/ui/bottomui/BottomUiContainer;",
    name = "onLayout",
    filters = listOf(
        methodCall(smali = "Landroid/widget/FrameLayout;->onLayout(ZIIII)V")
    )
)
