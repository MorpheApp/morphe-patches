package app.morphe.patches.youtube.layout.player.overlay

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.checkCast
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral
import app.morphe.patches.youtube.layout.sponsorblock.ControlsOverlayFingerprint
import app.morphe.patches.youtube.misc.playercontrols.PlayerBottomGradientScrimFingerprint

/**
 * Matches same method as [ControlsOverlayFingerprint] and [PlayerBottomGradientScrimFingerprint].
 */
internal object CreatePlayerOverviewFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.ID, "scrim_overlay"),
        checkCast("Landroid/widget/ImageView;", location = MatchAfterWithin(10))
    )
)
