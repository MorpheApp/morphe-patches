package app.morphe.patches.youtube.misc.playercontrols

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.checkCast
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patches.shared.layout.branding.NotificationFingerprint.classDef
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal val playerControlsVisibilityEntityModelFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "L",
    parameters = listOf(),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.IGET,
        Opcode.INVOKE_STATIC
    ),
    custom = { method, _ ->
        method.name == "getPlayerControlsVisibility"
    }
)

internal val youtubeControlsOverlayFingerprint = Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(name = "setFocusableInTouchMode"),
        resourceLiteral(ResourceType.ID, "inset_overlay_view_layout"),
        resourceLiteral(ResourceType.ID, "scrim_overlay"),
    )
)

internal val motionEventFingerprint = Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/view/MotionEvent;"),
    filters = listOf(
        methodCall(name = "setTranslationY")
    )
)

internal val playerControlsExtensionHookListenersExistFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Z",
    parameters = listOf(),
    custom = { methodDef, classDef ->
        methodDef.name == "fullscreenButtonVisibilityCallbacksExist" &&
                classDef.type == EXTENSION_CLASS_DESCRIPTOR
    }
)

internal val playerControlsExtensionHookFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "V",
    parameters = listOf("Z"),
    custom = { methodDef, classDef ->
        methodDef.name == "fullscreenButtonVisibilityChanged" &&
            classDef.type == EXTENSION_CLASS_DESCRIPTOR
    }
)

internal val playerTopControlsInflateFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "controls_layout_stub"),
        methodCall("Landroid/view/ViewStub;", "inflate"),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately())
    )
)

internal val playerBottomControlsInflateFingerprint = Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf(),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "bottom_ui_container_stub"),
        methodCall("Landroid/view/ViewStub;", "inflate"),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately())
    )
)

internal val overlayViewInflateFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "heatseeker_viewstub"),
        resourceLiteral(ResourceType.ID, "fullscreen_button"),
        checkCast("Landroid/widget/ImageView;")
    )
)

/**
 * Resolves to the class found in [playerTopControlsInflateFingerprint].
 */
internal val controlsOverlayVisibilityFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Z", "Z"),
)

internal val playerBottomControlsExploderFeatureFlagFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        literal(45643739L)
    )
)

internal val playerTopControlsExperimentalLayoutFeatureFlagFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "I",
    parameters = listOf(),
    filters = listOf(
        literal(45629424L)
    )
)

internal val playerControlsLargeOverlayButtonsFeatureFlagFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        literal(45709810L)
    )
)

internal val playerControlsFullscreenLargeButtonsFeatureFlagFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        literal(45686474L)
    )
)

internal val playerControlsButtonStrokeFeatureFlagFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        literal(45713296)
    )
)