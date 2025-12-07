package app.morphe.patches.youtube.layout.buttons.overlay

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags

internal val mediaRouteButtonFingerprint = Fingerprint(
    parameters = listOf("I"),
    custom = { methodDef, _ ->
        methodDef.definingClass.endsWith("/MediaRouteButton;") && methodDef.name == "setVisibility"
    }
)

internal val castButtonPlayerFeatureFlagFingerprint = Fingerprint(
    returnType = "Z",
    filters = listOf(
        literal(45690091)
    )
)

internal val castButtonActionFeatureFlagFingerprint = Fingerprint(
    returnType = "Z",
    filters = listOf(
        literal(45690090)
    )
)

internal val inflateControlsGroupLayoutStubFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.ID, "youtube_controls_button_group_layout_stub"),
        methodCall(name = "inflate")
    )
)
