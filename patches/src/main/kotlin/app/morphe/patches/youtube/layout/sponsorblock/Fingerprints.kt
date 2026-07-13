package app.morphe.patches.youtube.layout.sponsorblock

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.checkCast
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.resourceLiteral
import app.morphe.patches.youtube.layout.player.overlay.CreatePlayerOverviewFingerprint
import app.morphe.patches.youtube.misc.playercontrols.PlayerBottomGradientScrimFingerprint
import app.morphe.patches.youtube.shared.LayoutConstructorFingerprint
import app.morphe.patches.youtube.shared.SeekbarFingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object AppendTimeFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Ljava/lang/CharSequence;", "Ljava/lang/CharSequence;", "Ljava/lang/CharSequence;"),
    filters = listOf(
        resourceLiteral(ResourceType.STRING, "total_time"),

        methodCall(smali = "Landroid/content/res/Resources;->getString(I[Ljava/lang/Object;)Ljava/lang/String;"),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately())
    )
)

/**
 * Matches same method as [CreatePlayerOverviewFingerprint] and [PlayerBottomGradientScrimFingerprint].
 */
internal object ControlsOverlayFingerprint : Fingerprint(
    classFingerprint = LayoutConstructorFingerprint,
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "inset_overlay_view_layout"),
        checkCast("Landroid/widget/FrameLayout;", MatchAfterWithin(20))
    )
)

internal object RectangleFieldInvalidatorFingerprint : Fingerprint(
    classFingerprint = SeekbarFingerprint,
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(name = "invalidate")
    )
)

internal object AdProgressTextViewVisibilityFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Z"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/google/android/libraries/youtube/ads/player/ui/AdProgressTextView;",
            name = "setVisibility"
        )
    )
)
