package app.morphe.patches.youtube.layout.player.overlay

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.checkCast
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral

internal object CreatePlayerOverviewFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.ID, "scrim_overlay"),
        checkCast("Landroid/widget/ImageView;", location = MatchAfterWithin(10))
    )
)
