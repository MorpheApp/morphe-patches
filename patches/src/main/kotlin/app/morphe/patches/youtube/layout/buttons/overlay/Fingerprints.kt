package app.morphe.patches.youtube.layout.buttons.overlay

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags

internal object MediaRouteButtonFingerprint : Fingerprint(
    definingClass = "/MediaRouteButton;",
    name = "setVisibility",
    parameters = listOf("I")
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
