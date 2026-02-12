package app.morphe.patches.youtube.layout.buttons.overlay

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object MediaRouteButtonFingerprint : Fingerprint(
    parameters = listOf("I"),
    custom = { methodDef, _ ->
        methodDef.definingClass.endsWith("/MediaRouteButton;") && methodDef.name == "setVisibility"
    }
)

internal object CastButtonPlayerFeatureFlagFingerprint : Fingerprint(
    returnType = "Z",
    filters = listOf(
        literal(45690091)
    )
)

internal object CastButtonActionFeatureFlagFingerprint : Fingerprint(
    returnType = "Z",
    filters = listOf(
        literal(45690090)
    )
)

internal object InflateControlsGroupLayoutStubFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.ID, "youtube_controls_button_group_layout_stub"),
        methodCall(name = "inflate")
    )
)

internal object FullscreenButtonFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Landroid/view/View;"),
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.ID, "fullscreen_button"),
        opcode(Opcode.CHECK_CAST)
    )
)

internal object TitleAnchorFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.ID, "player_collapse_button"),
        opcode(Opcode.CHECK_CAST),

        resourceLiteral(ResourceType.ID, "title_anchor"),
        opcode(Opcode.MOVE_RESULT_OBJECT)
    )
)